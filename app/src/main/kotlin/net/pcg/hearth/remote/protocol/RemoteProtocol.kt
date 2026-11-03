package net.pcg.hearth.remote.protocol

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.pcg.hearth.remote.proto.RemoteProto.*
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import kotlin.coroutines.cancellation.CancellationException

class RemoteProtocol(
    private val certificateManager: CertificateManager
) {
    companion object {
        const val REMOTE_PORT = 6466
        private const val TAG = "RemoteProtocol"
        private const val HANDSHAKE_TIMEOUT_MS = 20000
        private const val MAX_PENDING = 32
        private const val MAX_LOG = 50
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    data class SessionLogEntry(
        val timeMs: Long,
        val direction: String,
        val text: String
    )

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var outputSessionId = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _closeReason = MutableStateFlow<String?>(null)
    val closeReason: StateFlow<String?> = _closeReason.asStateFlow()

    private val _sessionLog = MutableStateFlow<List<SessionLogEntry>>(emptyList())
    val sessionLog: StateFlow<List<SessionLogEntry>> = _sessionLog.asStateFlow()

    private val _volumeState = MutableStateFlow<VolumeState?>(null)
    val volumeState: StateFlow<VolumeState?> = _volumeState.asStateFlow()

    private val _currentAppPackage = MutableStateFlow<String?>(null)
    val currentAppPackage: StateFlow<String?> = _currentAppPackage.asStateFlow()

    private val commandChannel = Channel<RemoteMessage>(Channel.BUFFERED)
    private val pendingCommands = ArrayDeque<RemoteMessage>()
    private var sessionReady = false

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private var sessionId = 0
    private var generation = 0

    @Volatile private var activeHost: String? = null
    @Volatile private var opening = false
    @Volatile private var activeFeatures = RemoteMessages.REQUESTED_FEATURES

    @Volatile private var connectionJob: Job? = null
    @Volatile private var readJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    private val quietLeft = java.util.concurrent.atomic.AtomicInteger(0)

    data class VolumeState(
        val level: Int,
        val max: Int,
        val muted: Boolean
    )

    init {
        startWriteLoop()
    }

    fun connect(host: String, expectedGeneration: Int? = null, quiet: Boolean = false) {
        val id = synchronized(sessionLock) {
            if (expectedGeneration != null && generation != expectedGeneration) return
            val live = socket != null || opening
            if (activeHost == host && live) return
            if (expectedGeneration == null) generation += 1
            opening = true
            activeHost = host
            ++sessionId
        }
        reconnectJob?.cancel()
        reconnectJob = null
        synchronized(pendingCommands) { sessionReady = false }
        closeSocket()
        connectionJob?.cancel()
        readJob?.cancel()
        if (!quiet) _connectionState.value = ConnectionState.Connecting
        record("sys", "connecting $host:$REMOTE_PORT")

        connectionJob = io.launch {
            try {
                openSocket(host, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onSocketLost(id, socketFailure(e, "Connection failed"))
            }
        }
    }

    fun disconnect() {
        quietLeft.set(0)
        reconnectJob?.cancel()
        reconnectJob = null
        synchronized(sessionLock) {
            generation += 1
            sessionId += 1
            activeHost = null
            opening = false
        }
        closeSocket()
        connectionJob?.cancel()
        readJob?.cancel()
        synchronized(pendingCommands) {
            sessionReady = false
            pendingCommands.clear()
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    fun reportError(message: String) {
        Log.w(TAG, message)
        _lastError.value = message
    }

    fun note(text: String) {
        val clean = text.replace("\n", " ").replace("\r", " ").take(240)
        if (clean.isNotBlank()) record("http", clean)
    }

    private suspend fun openSocket(host: String, id: Int) {
        val sslContext = certificateManager.createSSLContext()
        val factory = sslContext.socketFactory
        val newSocket = factory.createSocket(host, REMOTE_PORT) as SSLSocket
        newSocket.tcpNoDelay = true
        newSocket.keepAlive = true
        newSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
        newSocket.startHandshake()
        newSocket.soTimeout = 0

        val attached = synchronized(sessionLock) {
            if (id != sessionId) {
                false
            } else {
                socket = newSocket
                output = newSocket.getOutputStream()
                outputSessionId = id
                opening = false
                activeFeatures = RemoteMessages.REQUESTED_FEATURES
                true
            }
        }
        if (!attached) {
            newSocket.close()
            return
        }
        Log.i(TAG, "Connected to $host:$REMOTE_PORT with the pairing certificate, waiting for RemoteConfigure")
        record("sys", "socket open")
        startReadLoop(id, newSocket)
    }

    private fun closeSocket() {
        val closing = synchronized(sessionLock) {
            val current = socket
            socket = null
            output = null
            current
        }
        try {
            closing?.close()
        } catch (_: Exception) {
        }
    }

    private fun onSocketLost(id: Int, message: String) {
        val (host, gen) = synchronized(sessionLock) {
            if (id != sessionId || activeHost == null) return
            opening = false
            sessionId += 1
            generation += 1
            activeHost!! to generation
        }
        val wasUp = synchronized(pendingCommands) { sessionReady }
        val plan = planReconnect(wasUp, quietLeft.get())
        quietLeft.set(plan.quietLeft)
        Log.w(TAG, "Remote session lost: $message")
        _closeReason.value = message
        record("close", message)
        synchronized(pendingCommands) { sessionReady = false }
        if (plan.quiet) {
            if (_connectionState.value !is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Connecting
            }
        } else {
            _lastError.value = message
            _connectionState.value = ConnectionState.Error(message)
        }
        closeSocket()
        readJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = io.launch {
            delay(plan.delayMs)
            reconnectJob = null
            connect(host, gen, quiet = plan.quiet)
        }
    }

    private fun startReadLoop(id: Int, socket: SSLSocket) {
        readJob = io.launch {
            val frame = RemoteFrameBuffer()
            val buf = ByteArray(4096)
            try {
                val input = socket.inputStream
                while (isActive && id == sessionId) {
                    val n = try {
                        input.read(buf)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (n < 0) throw EOFException("EOF")
                    if (frame.append(buf, n)) throw EOFException("EOF")
                    while (true) {
                        val payload = frame.poll() ?: break
                        if (payload.isEmpty()) {
                            record("in", "empty")
                            continue
                        }
                        val message = try {
                            RemoteMessage.parseFrom(payload)
                        } catch (e: Exception) {
                            record("in", "parse error ${e.message ?: "unknown"}")
                            continue
                        }
                        record("in", describeRemoteMessage(message))
                        handleMessage(message, id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onSocketLost(id, socketFailure(e, "Connection lost"))
            }
        }
    }

    private fun startWriteLoop() {
        io.launch {
            for (message in commandChannel) {
                if (!sendMessageSync(message)) {
                    synchronized(pendingCommands) {
                        if (pendingCommands.size >= MAX_PENDING) pendingCommands.removeLast()
                        pendingCommands.addFirst(message)
                    }
                }
            }
        }
    }

    private fun handleMessage(message: RemoteMessage, id: Int) {
        when {
            message.hasRemoteConfigure() -> {
                val deviceCode = message.remoteConfigure.code1
                val replyCode = RemoteMessages.activeFeatures(deviceCode)
                activeFeatures = replyCode
                if (deviceCode and RemoteMessages.FEATURE_APP_LINK == 0) {
                    Log.w(TAG, "TV did not offer app-link in features $deviceCode")
                }
                Log.i(TAG, "RemoteConfigure device=$deviceCode reply=$replyCode")
                sendMessageSync(RemoteMessages.configureResponse(deviceCode))
            }
            message.hasRemoteSetActive() -> {
                val active = activeFeatures
                Log.i(TAG, "RemoteSetActive reply=$active")
                sendMessageSync(RemoteMessages.setActiveResponse(active))
            }
            message.hasRemoteStart() -> {
                Log.i(TAG, "RemoteStart")
                onSessionReady(id)
            }
            message.hasRemotePingRequest() -> {
                sendMessageSync(RemoteMessages.pingResponse(message.remotePingRequest.val1))
            }
            message.hasRemoteImeKeyInject() -> {
                val pkg = message.remoteImeKeyInject.appInfo.appPackage
                if (pkg.isNotBlank()) {
                    Log.i(TAG, "Foreground app $pkg")
                    _currentAppPackage.value = pkg
                }
            }
            message.hasRemoteSetVolumeLevel() -> {
                val volume = message.remoteSetVolumeLevel
                _volumeState.value = VolumeState(
                    level = volume.volumeLevel.toInt(),
                    max = volume.volumeMax.toInt(),
                    muted = volume.volumeMuted
                )
            }
            message.hasRemoteError() -> {
                val text = "TV rejected a command: ${message.remoteError.value}"
                Log.w(TAG, "$text ${message.remoteError}")
                _lastError.value = text
            }
            else -> {
                Log.d(TAG, "Unhandled remote message")
            }
        }
    }

    private fun onSessionReady(id: Int) {
        if (id != sessionId) return
        quietLeft.set(0)
        val queued = synchronized(pendingCommands) {
            if (sessionReady) return
            sessionReady = true
            pendingCommands.toList().also { pendingCommands.clear() }
        }
        _connectionState.value = ConnectionState.Connected
        _lastError.value = null
        queued.forEach { commandChannel.trySend(it) }
    }

    fun sendKey(keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT) {
        Log.i(TAG, "Key ${keyCode.name} direction=${direction.name} sessionReady=$sessionReady")
        enqueue(RemoteMessages.keyInject(keyCode, direction))
    }

    fun sendKeyPress(keyCode: RemoteKeyCode) {
        sendKey(keyCode, RemoteDirection.SHORT)
    }

    fun sendKeyLongPressStart(keyCode: RemoteKeyCode) {
        sendKey(keyCode, RemoteDirection.START_LONG)
    }

    fun sendKeyLongPressEnd(keyCode: RemoteKeyCode) {
        sendKey(keyCode, RemoteDirection.END_LONG)
    }

    fun launchApp(appLink: String) {
        val message = RemoteMessages.appLink(appLink)
        Log.i(TAG, "Queue app link ${message.remoteAppLinkLaunchRequest.appLink} sessionReady=$sessionReady")
        enqueue(message)
    }

    private fun enqueue(message: RemoteMessage) {
        val sendNow = synchronized(pendingCommands) {
            if (sessionReady) {
                true
            } else {
                if (pendingCommands.size >= MAX_PENDING) pendingCommands.removeFirst()
                pendingCommands.addLast(message)
                false
            }
        }
        if (sendNow) {
            val result = commandChannel.trySend(message)
            if (result.isFailure) {
                reportError("Remote command was not sent")
            }
        } else {
            val host = activeHost
            val state = _connectionState.value
            if (host != null && state !is ConnectionState.Connecting && state !is ConnectionState.Connected) {
                connect(host)
            }
        }
    }

    private fun sendMessageSync(message: RemoteMessage): Boolean {
        val (out, id) = synchronized(sessionLock) { output to outputSessionId }
        if (out == null) return false
        return try {
            val bytes = message.toByteArray()
            record("out", describeRemoteMessage(message))
            synchronized(out) {
                val framed = ByteArrayOutputStream(bytes.size + 5)
                writeVarint(framed, bytes.size)
                framed.write(bytes)
                out.write(framed.toByteArray())
                out.flush()
            }
            true
        } catch (e: Exception) {
            onSocketLost(id, socketFailure(e, "Send failed"))
            false
        }
    }

    private fun record(direction: String, text: String) {
        val entry = SessionLogEntry(System.currentTimeMillis(), direction, text)
        _sessionLog.update { current ->
            if (current.size < MAX_LOG) current + entry else current.drop(1) + entry
        }
    }

    private fun writeVarint(output: OutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
    }
}

internal fun describeRemoteMessage(message: RemoteMessage): String {
    return when {
        message.hasRemoteConfigure() -> {
            val info = message.remoteConfigure.deviceInfo
            "configure code1=${message.remoteConfigure.code1} model=${info.model} vendor=${info.vendor}"
        }
        message.hasRemoteSetActive() -> "set_active active=${message.remoteSetActive.active}"
        message.hasRemoteError() -> "error value=${message.remoteError.value}"
        message.hasRemotePingRequest() -> "ping_request val1=${message.remotePingRequest.val1}"
        message.hasRemotePingResponse() -> "ping_response val1=${message.remotePingResponse.val1}"
        message.hasRemoteKeyInject() -> {
            val key = message.remoteKeyInject
            "key_inject ${key.keyCode.name} ${key.direction.name}"
        }
        message.hasRemoteStart() -> "start started=${message.remoteStart.started}"
        message.hasRemoteSetVolumeLevel() -> {
            val volume = message.remoteSetVolumeLevel
            "volume level=${volume.volumeLevel} max=${volume.volumeMax} muted=${volume.volumeMuted}"
        }
        message.hasRemoteImeKeyInject() ->
            "ime ${message.remoteImeKeyInject.appInfo.appPackage}"
        message.hasRemoteAppLinkLaunchRequest() ->
            "app_link ${message.remoteAppLinkLaunchRequest.appLink}"
        message.hasRemoteImeBatchEdit() -> "ime_batch_edit"
        message.hasRemoteImeShowRequest() -> "ime_show"
        message.hasRemoteVoiceBegin() -> "voice_begin"
        message.hasRemoteVoicePayload() -> "voice_payload"
        message.hasRemoteVoiceEnd() -> "voice_end"
        message.hasRemoteAdjustVolumeLevel() -> "adjust_volume"
        message.hasRemoteSetPreferredAudioDevice() -> "set_preferred_audio"
        message.hasRemoteResetPreferredAudioDevice() -> "reset_preferred_audio"
        else -> "unhandled"
    }
}

internal data class ReconnectPlan(
    val quiet: Boolean,
    val quietLeft: Int,
    val delayMs: Long
)

internal fun planReconnect(wasUp: Boolean, quietLeft: Int): ReconnectPlan {
    val quiet = wasUp || quietLeft > 0
    val nextQuiet = when {
        wasUp -> 3
        quiet -> quietLeft - 1
        else -> 0
    }
    return ReconnectPlan(
        quiet = quiet,
        quietLeft = nextQuiet,
        delayMs = if (quiet) 400L else 2_000L
    )
}

private fun socketFailure(error: Exception, fallback: String): String {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: fallback
    return "${error.javaClass.simpleName}: $detail"
}
