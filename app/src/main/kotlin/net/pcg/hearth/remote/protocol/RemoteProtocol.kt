package net.pcg.hearth.remote.protocol

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.pcg.hearth.remote.proto.RemoteProto.*
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket
import kotlin.coroutines.cancellation.CancellationException

class RemoteProtocol(
    private val certificateManager: CertificateManager
) {
    companion object {
        const val REMOTE_PORT = 6466
        private const val TAG = "RemoteProtocol"
        private const val PING_INTERVAL_MS = 5000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val READ_TIMEOUT_MS = 20000
        private const val MAX_PENDING = 32
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var outputSessionId = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _volumeState = MutableStateFlow<VolumeState?>(null)
    val volumeState: StateFlow<VolumeState?> = _volumeState.asStateFlow()

    private val commandChannel = Channel<RemoteMessage>(Channel.BUFFERED)
    private val pendingCommands = ArrayDeque<RemoteMessage>()
    private var sessionReady = false

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private var sessionId = 0
    private var activeHost: String? = null

    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null

    data class VolumeState(
        val level: Int,
        val max: Int,
        val muted: Boolean
    )

    init {
        startWriteLoop()
    }

    suspend fun connect(host: String) {
        val id = synchronized(sessionLock) { ++sessionId }
        activeHost = host
        synchronized(pendingCommands) { sessionReady = false }
        stopSocket()
        _connectionState.value = ConnectionState.Connecting

        connectionJob = io.launch {
            try {
                openSocket(host, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onSocketLost(id, e.message ?: "Connection failed")
            }
        }
    }

    suspend fun disconnect() {
        synchronized(sessionLock) { sessionId += 1 }
        activeHost = null
        stopSocket()
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

    private suspend fun openSocket(host: String, id: Int) {
        val sslContext = certificateManager.createSSLContext()
        val factory = sslContext.socketFactory
        val newSocket = factory.createSocket(host, REMOTE_PORT) as SSLSocket
        newSocket.tcpNoDelay = true
        newSocket.keepAlive = true
        newSocket.soTimeout = READ_TIMEOUT_MS
        newSocket.startHandshake()

        val attached = synchronized(sessionLock) {
            if (id != sessionId) {
                false
            } else {
                socket = newSocket
                output = newSocket.getOutputStream()
                input = newSocket.getInputStream()
                outputSessionId = id
                true
            }
        }
        if (!attached) {
            newSocket.close()
            return
        }
        Log.i(TAG, "Connected to $host:$REMOTE_PORT with the pairing certificate, waiting for RemoteConfigure")
        startReadLoop(id)
    }

    private fun stopSocket() {
        connectionJob?.cancel()
        readJob?.cancel()
        pingJob?.cancel()
        connectionJob = null
        readJob = null
        pingJob = null
        val closing = synchronized(sessionLock) {
            val current = socket
            socket = null
            output = null
            input = null
            current
        }
        try {
            closing?.close()
        } catch (_: Exception) {
        }
    }

    private fun onSocketLost(id: Int, message: String) {
        val host = synchronized(sessionLock) {
            if (id != sessionId || activeHost == null) return
            sessionId += 1
            activeHost
        } ?: return
        Log.w(TAG, "Remote session lost: $message")
        _lastError.value = message
        synchronized(pendingCommands) { sessionReady = false }
        _connectionState.value = ConnectionState.Error(message)
        stopSocket()
        io.launch {
            delay(RECONNECT_DELAY_MS)
            if (activeHost == host) {
                connect(host)
            }
        }
    }

    private fun startReadLoop(id: Int) {
        readJob = io.launch {
            try {
                while (isActive && id == sessionId) {
                    val message = readMessage()
                    handleMessage(message, id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onSocketLost(id, e.message ?: "Connection lost")
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

    private fun startPingLoop(id: Int) {
        pingJob?.cancel()
        pingJob = io.launch {
            while (isActive && id == sessionId && _connectionState.value == ConnectionState.Connected) {
                delay(PING_INTERVAL_MS)
                if (id != sessionId) break
                sendPing()
            }
        }
    }

    private fun handleMessage(message: RemoteMessage, id: Int) {
        when {
            message.hasRemoteConfigure() -> {
                val deviceCode = message.remoteConfigure.code1
                if (deviceCode and RemoteMessages.FEATURE_APP_LINK == 0) {
                    Log.w(TAG, "TV did not offer app-link in features $deviceCode")
                }
                Log.i(
                    TAG,
                    "RemoteConfigure device=$deviceCode reply=${RemoteMessages.CLIENT_CODE} " +
                        "model=${RemoteMessages.CLIENT_MODEL}"
                )
                sendMessageSync(RemoteMessages.configureResponse())
            }
            message.hasRemoteSetActive() -> {
                Log.i(TAG, "RemoteSetActive reply=${RemoteMessages.CLIENT_CODE}")
                sendMessageSync(RemoteMessages.setActiveResponse())
            }
            message.hasRemoteStart() -> {
                Log.i(TAG, "RemoteStart")
                onSessionReady(id)
            }
            message.hasRemotePingRequest() -> {
                sendMessageSync(RemoteMessages.pingResponse(message.remotePingRequest.val1))
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
                val text = "TV rejected a command"
                Log.w(TAG, "$text: ${message.remoteError}")
                _lastError.value = text
            }
            else -> {
                Log.d(TAG, "Unhandled remote message")
            }
        }
    }

    private fun onSessionReady(id: Int) {
        if (id != sessionId) return
        val queued = synchronized(pendingCommands) {
            if (sessionReady) return
            sessionReady = true
            pendingCommands.toList().also { pendingCommands.clear() }
        }
        _connectionState.value = ConnectionState.Connected
        _lastError.value = null
        queued.forEach { commandChannel.trySend(it) }
        startPingLoop(id)
    }

    private fun sendPing() {
        val ping = RemotePingRequest.newBuilder()
            .setVal1((System.currentTimeMillis() / 1000).toInt())
            .setVal2(0)
            .build()
        val message = RemoteMessage.newBuilder()
            .setRemotePingRequest(ping)
            .build()
        sendMessageSync(message)
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
                io.launch { connect(host) }
            }
        }
    }

    private fun sendMessageSync(message: RemoteMessage): Boolean {
        val (out, id) = synchronized(sessionLock) { output to outputSessionId }
        if (out == null) return false
        return try {
            val bytes = message.toByteArray()
            synchronized(out) {
                val framed = ByteArrayOutputStream(bytes.size + 5)
                writeVarint(framed, bytes.size)
                framed.write(bytes)
                out.write(framed.toByteArray())
                out.flush()
            }
            true
        } catch (e: Exception) {
            onSocketLost(id, e.message ?: "Send failed")
            false
        }
    }

    private fun readMessage(): RemoteMessage {
        val inp = input ?: throw EOFException("Remote session closed")
        val length = readVarint(inp)
        if (length <= 0 || length > 1_000_000) throw IOException("Bad message length $length")
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = inp.read(bytes, read, length - read)
            if (n < 0) throw EOFException("Remote session closed")
            read += n
        }
        return RemoteMessage.parseFrom(bytes)
    }

    private fun writeVarint(output: OutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
    }

    private fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Remote session closed")
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 35) throw IOException("Varint too long")
        }
        return result
    }
}
