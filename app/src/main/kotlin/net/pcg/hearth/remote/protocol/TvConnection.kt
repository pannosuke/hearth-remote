package net.pcg.hearth.remote.protocol

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.pcg.hearth.remote.R
import kotlin.coroutines.cancellation.CancellationException
import net.pcg.hearth.remote.data.SettingsRepository
import net.pcg.hearth.remote.proto.RemoteProto.RemoteKeyCode
import javax.net.ssl.SSLSocket

class TvConnection(private val context: Context) {

    companion object {
        private const val TAG = "TvConnection"
    }
    
    private val certificateManager = CertificateManager(context)
    private val pairingProtocol = PairingProtocol(certificateManager, context)
    private val remoteProtocol = RemoteProtocol(certificateManager, context)
    private val settingsRepository = SettingsRepository(context)
    private val sharpIp = SharpIpControl(hostProvider = { settingsRepository.getTvHost() })
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val connectionState = remoteProtocol.connectionState
    val lastError = remoteProtocol.lastError
    val closeReason = remoteProtocol.closeReason
    val sessionLog = remoteProtocol.sessionLog
    val volumeState = remoteProtocol.volumeState
    val currentAppPackage = remoteProtocol.currentAppPackage

    private val _tunerStatus = MutableStateFlow(TunerStatus())
    val tunerStatus: StateFlow<TunerStatus> = _tunerStatus.asStateFlow()

    private val _powerOn = MutableStateFlow<Boolean?>(null)
    val powerOn: StateFlow<Boolean?> = _powerOn.asStateFlow()

    private val subtitleBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _subtitleMacroRunning = MutableStateFlow(false)
    val subtitleMacroRunning: StateFlow<Boolean> = _subtitleMacroRunning.asStateFlow()

    init {
        ioScope.launch {
            var nextPowerAt = 0L
            while (isActive) {
                delay(5_000)
                val now = System.currentTimeMillis()
                if (now >= nextPowerAt) {
                    nextPowerAt = now + 20_000
                    refreshPower()
                }
                sharpIp.releaseIfIdle()
            }
        }
    }

    private var pairingSocket: SSLSocket? = null
    
    sealed class PairingState {
        data object Idle : PairingState()
        data object Connecting : PairingState()
        data object WaitingForCode : PairingState()
        data object Verifying : PairingState()
        data class Success(val host: String) : PairingState()
        data class Error(val message: String) : PairingState()
    }
    
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()
    
    fun isPaired(): Boolean {
        return certificateManager.hasClientCertificate() && 
               certificateManager.getServerCertificate() != null &&
               settingsRepository.getTvHost() != null
    }
    
    suspend fun startPairing(host: String) {
        _pairingState.value = PairingState.Connecting
        
        withContext(Dispatchers.IO) {
            try {
                val sslContext = certificateManager.createSSLContext()
                val factory = sslContext.socketFactory
                val socket = factory.createSocket(host, PairingProtocol.PAIRING_PORT) as SSLSocket
                socket.soTimeout = 30000
                socket.startHandshake()
                
                val result = pairingProtocol.startPairing(socket)
                
                when (result) {
                    is PairingProtocol.PairingResult.NeedCode -> {
                        pairingSocket = socket
                        settingsRepository.setTvHost(host)
                        _pairingState.value = PairingState.WaitingForCode
                    }
                    is PairingProtocol.PairingResult.Error -> {
                        socket.close()
                        _pairingState.value = PairingState.Error(result.message)
                    }
                    is PairingProtocol.PairingResult.Success -> {
                        socket.close()
                        settingsRepository.setTvHost(host)
                        _pairingState.value = PairingState.Success(host)
                    }
                }
            } catch (e: Exception) {
                _pairingState.value = PairingState.Error(e.message ?: context.getString(R.string.setup_error))
            }
        }
    }
    
    suspend fun submitPairingCode(code: String) {
        val socket = pairingSocket ?: run {
            _pairingState.value = PairingState.Error(context.getString(R.string.pairing_no_session))
            return
        }
        
        _pairingState.value = PairingState.Verifying
        
        withContext(Dispatchers.IO) {
            try {
                val result = pairingProtocol.completePairing(socket, code)
                
                when (result) {
                    is PairingProtocol.PairingResult.Success -> {
                        val host = settingsRepository.getTvHost() ?: "unknown"
                        _pairingState.value = PairingState.Success(host)
                    }
                    is PairingProtocol.PairingResult.Error -> {
                        _pairingState.value = PairingState.Error(result.message)
                    }
                    is PairingProtocol.PairingResult.NeedCode -> {
                        _pairingState.value = PairingState.WaitingForCode
                    }
                }
            } catch (e: Exception) {
                _pairingState.value = PairingState.Error(
                    e.message ?: context.getString(R.string.pairing_failed_detail, "")
                )
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
                pairingSocket = null
            }
        }
    }
    
    fun cancelPairing() {
        try {
            pairingSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        pairingSocket = null
        _pairingState.value = PairingState.Idle
    }
    
    suspend fun connect() {
        val host = settingsRepository.getTvHost() ?: return
        remoteProtocol.connect(host)
    }
    
    suspend fun disconnect() {
        remoteProtocol.disconnect()
    }
    
    fun forgetPairing() {
        certificateManager.clearAll()
        settingsRepository.clearTvHost()
        _pairingState.value = PairingState.Idle
    }
    
    // Remote control methods
    fun sendDpadUp() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_DPAD_UP)
    fun sendDpadDown() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_DPAD_DOWN)
    fun sendDpadLeft() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_DPAD_LEFT)
    fun sendDpadRight() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_DPAD_RIGHT)
    fun sendDpadCenter() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_DPAD_CENTER)
    
    fun sendBack() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_BACK)
    fun sendHome() = sendKeyOrIp(RemoteKeyCode.KEYCODE_HOME, "IRCO", SharpIpCommands.HOME)
    fun sendMenu() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MENU)

    /**
     * Volume, mute, home, and channel go out on Sharp IP control first.
     * That port is confirmed on this TV. The Android TV key is sent only when
     * IP control does not answer, so a working command is not applied twice.
     */
    private fun sendKeyOrIp(key: RemoteKeyCode, command: String, param: String) {
        ioScope.launch {
            val ipOk = sharpIp.tryCommand(command, param)
            if (ipOk) {
                Log.i(TAG, "Sharp IP $command $param")
            } else {
                Log.w(TAG, "Sharp IP $command failed, sending ${key.name}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
                remoteProtocol.sendKeyPress(key)
            }
        }
    }
    
    fun sendVolumeUp() = sendKeyOrIp(RemoteKeyCode.KEYCODE_VOLUME_UP, "IRCO", SharpIpCommands.VOLUME_UP)
    fun sendVolumeDown() = sendKeyOrIp(RemoteKeyCode.KEYCODE_VOLUME_DOWN, "IRCO", SharpIpCommands.VOLUME_DOWN)
    fun sendVolumeMute() = sendKeyOrIp(RemoteKeyCode.KEYCODE_VOLUME_MUTE, "MUTE", "0")
    
    fun sendChannelUp() = sendKeyOrIp(RemoteKeyCode.KEYCODE_CHANNEL_UP, "CHUP", "")
    fun sendChannelDown() = sendKeyOrIp(RemoteKeyCode.KEYCODE_CHANNEL_DOWN, "CHDW", "")
    
    fun note(line: String) = remoteProtocol.note(line)

    fun togglePower() {
        ioScope.launch {
            if (connectionState.value is RemoteProtocol.ConnectionState.Connected) {
                remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_POWER)
                delay(700)
                refreshPower()
                return@launch
            }
            try {
                sharpIp.exclusive { send ->
                    val on = SharpIpPower.parse(send("POWR", "????"))
                    if (on == null) {
                        remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
                        return@exclusive
                    }
                    send("POWR", if (on) "0" else "1")
                    val now = SharpIpPower.parse(send("POWR", "????"))
                    if (now != null) _powerOn.value = now
                    if (now == true) enablePowerOnOverIp(send)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Power toggle failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            }
        }
    }

    private suspend fun refreshPower() {
        try {
            sharpIp.exclusive { send ->
                val on = SharpIpPower.parse(send("POWR", "????"))
                if (on != null) _powerOn.value = on
                if (on == true) enablePowerOnOverIp(send)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoTvAddressException) {
        } catch (e: Exception) {
            Log.w(TAG, "Power query failed: ${e.message}")
        }
    }

    private suspend fun enablePowerOnOverIp(send: suspend (String, String) -> String) {
        if (settingsRepository.hasEnabledPowerOnIp()) return
        val reply = send("RSPW", "2")
        if (reply.isNotEmpty() && reply != "ERR") {
            settingsRepository.setPowerOnIpEnabled()
            Log.i(TAG, "RSPW2 enabled power-on over IP")
        }
    }
    fun sendPlayPause() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE)
    fun sendPlay() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_PLAY)
    fun sendPause() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_PAUSE)
    fun sendStop() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_STOP)
    fun sendRewind() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_REWIND)
    fun sendFastForward() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD)
    fun sendNext() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_NEXT)
    fun sendCaptions() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_CAPTIONS)
    
    fun sendNumber(n: Int) {
        val keyCode = when (n) {
            0 -> RemoteKeyCode.KEYCODE_0
            1 -> RemoteKeyCode.KEYCODE_1
            2 -> RemoteKeyCode.KEYCODE_2
            3 -> RemoteKeyCode.KEYCODE_3
            4 -> RemoteKeyCode.KEYCODE_4
            5 -> RemoteKeyCode.KEYCODE_5
            6 -> RemoteKeyCode.KEYCODE_6
            7 -> RemoteKeyCode.KEYCODE_7
            8 -> RemoteKeyCode.KEYCODE_8
            9 -> RemoteKeyCode.KEYCODE_9
            else -> return
        }
        remoteProtocol.sendKeyPress(keyCode)
    }
    
    fun launchApp(uri: String) = remoteProtocol.launchApp(uri)
    
    fun launchYouTube() = launchApp(TvAppLinks.YOUTUBE)
    fun launchNetflix() = launchApp(TvAppLinks.NETFLIX)
    fun launchDisneyPlus() = launchApp(TvAppLinks.DISNEY_PLUS)
    fun launchAmazonVideo() = launchApp(TvAppLinks.PRIME_VIDEO)
    fun launchJellyfin() {
        val foreground = remoteProtocol.currentAppPackage.value
        val pkg = HearthLaunch.jellyfinPackage(foreground)
        val link = HearthLaunch.scheme(pkg)
        remoteProtocol.logSession(
            "jellyfin launch pkg=$pkg foreground=${foreground ?: "none"} link=$link"
        )
        launchApp(link)
    }
    fun launchTver() = launchApp(TvAppLinks.TVER)
    fun launchNhk() = launchApp(TvAppLinks.NHK)
    fun launchHulu() = launchApp(TvAppLinks.HULU)
    fun launchLiveTv() = launchApp(TvAppLinks.LIVE_TV)

    suspend fun refreshTuner() {
        try {
            sharpIp.exclusive { send ->
                val previous = _tunerStatus.value
                val volume = send("VOLM", "????").toIntOrNull()
                val mute = send("MUTE", "????").toIntOrNull()
                val channel = OsakaChannels.channelReading(send("CHRC", "????"))
                _tunerStatus.value = TunerStatus(
                    volume = volume ?: previous.volume,
                    mute = mute ?: previous.mute,
                    channel = channel ?: previous.channel
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tuner refresh failed: ${e.message}")
        }
    }

    /**
     * Set the tuner volume to an exact level. The wire command is `VOLM`
     * plus the level padded to 4 characters, then CR (`VOLM18  \r` for 18).
     */
    fun setVolume(level: Int) {
        val target = level.coerceIn(0, 100)
        ioScope.launch {
            try {
                sharpIp.exclusive { send ->
                    val reply = send("VOLM", target.toString())
                    if (reply == "ERR") {
                        remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
                        return@exclusive
                    }
                    val shown = send("VOLM", "????").toIntOrNull() ?: target
                    _tunerStatus.value = _tunerStatus.value.copy(volume = shown)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Volume set failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            }
        }
    }

    fun nudgeVolume(delta: Int) {
        ioScope.launch {
            try {
                sharpIp.exclusive { send ->
                    val current = _tunerStatus.value.volume ?: send("VOLM", "????").toIntOrNull()
                    if (current == null) {
                        val key = if (delta > 0) SharpIpCommands.VOLUME_UP else SharpIpCommands.VOLUME_DOWN
                        send("IRCO", key)
                        return@exclusive
                    }
                    val next = (current + delta).coerceIn(0, 100)
                    val reply = send("VOLM", next.toString())
                    if (reply == "ERR") {
                        val key = if (delta > 0) SharpIpCommands.VOLUME_UP else SharpIpCommands.VOLUME_DOWN
                        send("IRCO", key)
                    } else {
                        _tunerStatus.value = _tunerStatus.value.copy(volume = next)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Volume change failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            }
        }
    }

    fun toggleTunerMute() {
        ioScope.launch {
            try {
                sharpIp.exclusive { send ->
                    send("MUTE", "0")
                    val mute = send("MUTE", "????").toIntOrNull()
                    _tunerStatus.value = _tunerStatus.value.copy(mute = mute)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mute failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            }
        }
    }

    fun stepChannel(up: Boolean) {
        ioScope.launch {
            try {
                sharpIp.exclusive { send ->
                    send(if (up) "CHUP" else "CHDW", "")
                    val channel = OsakaChannels.channelReading(send("CHRC", "????"))
                    if (channel != null) {
                        _tunerStatus.value = _tunerStatus.value.copy(channel = channel)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Channel step failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            }
        }
    }

    fun selectChannel(button: Int) {
        ioScope.launch {
            try {
                sharpIp.exclusive { send ->
                    send("CHRC", OsakaChannels.chrcCommand(button))
                    val channel = OsakaChannels.channelReading(send("CHRC", "????"))
                    _tunerStatus.value = _tunerStatus.value.copy(channel = channel ?: "TB$button")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Channel select failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            }
        }
    }

    /**
     * Run the On or Off subtitle macro on the one Sharp IP socket.
     * Waits stay inside the lock so a volume or channel command cannot
     * land in the middle of the drawer. Returns false when a macro is
     * already running.
     */
    fun startSubtitleMacro(on: Boolean): Boolean {
        if (!subtitleBusy.compareAndSet(false, true)) return false
        _subtitleMacroRunning.value = true
        ioScope.launch {
            val text = if (on) {
                settingsRepository.getSubtitleOnMacro()
            } else {
                settingsRepository.getSubtitleOffMacro()
            }
            val fallback = if (on) SubtitleMacro.ON else SubtitleMacro.OFF
            val steps = SubtitleMacro.parse(text, fallback)
            try {
                sharpIp.exclusive { send ->
                    for (step in steps) {
                        if (step.command == "WAIT") {
                            delay(step.param.toLong())
                        } else {
                            val reply = send(step.command, step.param)
                            if (reply == "ERR") throw java.io.IOException("ERR")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Subtitle macro failed: ${e.message}")
                remoteProtocol.reportError(context.getString(R.string.ip_control_failed))
            } finally {
                subtitleBusy.set(false)
                _subtitleMacroRunning.value = false
            }
        }
        return true
    }
}
