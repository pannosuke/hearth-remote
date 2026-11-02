package net.pcg.hearth.remote.protocol

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.pcg.hearth.remote.data.SettingsRepository
import net.pcg.hearth.remote.proto.RemoteProto.RemoteKeyCode
import javax.net.ssl.SSLSocket

class TvConnection(context: Context) {
    
    private val certificateManager = CertificateManager(context)
    private val pairingProtocol = PairingProtocol(certificateManager)
    private val remoteProtocol = RemoteProtocol(certificateManager)
    private val settingsRepository = SettingsRepository(context)
    
    val connectionState = remoteProtocol.connectionState
    val volumeState = remoteProtocol.volumeState
    
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
                _pairingState.value = PairingState.Error(e.message ?: "Connection failed")
            }
        }
    }
    
    suspend fun submitPairingCode(code: String) {
        val socket = pairingSocket ?: run {
            _pairingState.value = PairingState.Error("No pairing session")
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
                _pairingState.value = PairingState.Error(e.message ?: "Pairing failed")
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
    fun sendHome() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_HOME)
    fun sendMenu() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MENU)
    
    fun sendVolumeUp() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_VOLUME_UP)
    fun sendVolumeDown() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_VOLUME_DOWN)
    fun sendVolumeMute() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_VOLUME_MUTE)
    
    fun sendChannelUp() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_CHANNEL_UP)
    fun sendChannelDown() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_CHANNEL_DOWN)
    
    fun sendPower() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_POWER)
    fun sendPlayPause() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE)
    fun sendPlay() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_PLAY)
    fun sendPause() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_PAUSE)
    fun sendStop() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_STOP)
    fun sendRewind() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_REWIND)
    fun sendFastForward() = remoteProtocol.sendKeyPress(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD)
    
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
    
    fun launchYouTube() = launchApp("https://www.youtube.com")
    fun launchNetflix() = launchApp("https://www.netflix.com")
    fun launchDisneyPlus() = launchApp("https://www.disneyplus.com")
    fun launchAmazonVideo() = launchApp("https://www.amazon.com/gp/video")
}
