package net.pcg.hearth.remote.protocol

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.pcg.hearth.remote.proto.RemoteProto.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket

class RemoteProtocol(
    private val certificateManager: CertificateManager
) {
    companion object {
        const val REMOTE_PORT = 6466
        private const val TAG = "RemoteProtocol"
        private const val PING_INTERVAL_MS = 5000L
        private const val RECONNECT_DELAY_MS = 3000L
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
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _volumeState = MutableStateFlow<VolumeState?>(null)
    val volumeState: StateFlow<VolumeState?> = _volumeState.asStateFlow()
    
    private val commandChannel = Channel<RemoteMessage>(Channel.BUFFERED)
    private val pendingCommands = ArrayDeque<RemoteMessage>()
    private var sessionReady = false
    private var activeFeatures = 0
    
    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var pingJob: Job? = null
    
    data class VolumeState(
        val level: Int,
        val max: Int,
        val muted: Boolean
    )
    
    suspend fun connect(host: String) {
        disconnect()
        
        _connectionState.value = ConnectionState.Connecting
        
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val sslContext = certificateManager.createSSLContext()
                val factory = sslContext.socketFactory
                
                val newSocket = factory.createSocket(host, REMOTE_PORT) as SSLSocket
                newSocket.soTimeout = 30000
                newSocket.startHandshake()
                
                socket = newSocket
                output = newSocket.getOutputStream()
                input = newSocket.getInputStream()
                
                // The TV sends RemoteConfigure first. Replying before that
                // message arrives leaves app-link launches ignored.
                startReadLoop()
                startWriteLoop()
                
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                scheduleReconnect(host)
            }
        }
    }
    
    suspend fun disconnect() {
        connectionJob?.cancel()
        readJob?.cancel()
        writeJob?.cancel()
        pingJob?.cancel()
        
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        
        socket = null
        output = null
        input = null
        activeFeatures = 0
        synchronized(pendingCommands) {
            sessionReady = false
            pendingCommands.clear()
        }
        
        _connectionState.value = ConnectionState.Disconnected
    }
    
    private suspend fun scheduleReconnect(host: String) {
        delay(RECONNECT_DELAY_MS)
        if (_connectionState.value !is ConnectionState.Connected) {
            connect(host)
        }
    }
    
    private fun startReadLoop() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive && socket?.isConnected == true) {
                    val message = readMessage() ?: break
                    handleMessage(message)
                }
            } catch (e: IOException) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error("Connection lost")
                }
            }
        }
    }
    
    private fun startWriteLoop() {
        writeJob = CoroutineScope(Dispatchers.IO).launch {
            for (message in commandChannel) {
                if (!isActive) break
                sendMessageSync(message)
            }
        }
    }
    
    private fun startPingLoop() {
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                delay(PING_INTERVAL_MS)
                sendPing()
            }
        }
    }
    
    private fun handleMessage(message: RemoteMessage) {
        when {
            message.hasRemoteConfigure() -> {
                val deviceCode = message.remoteConfigure.code1
                activeFeatures = RemoteMessages.activeFeatures(deviceCode)
                if (activeFeatures and RemoteMessages.FEATURE_APP_LINK == 0) {
                    Log.w(TAG, "TV did not offer app-link in features $deviceCode")
                }
                Log.i(TAG, "RemoteConfigure features device=$deviceCode active=$activeFeatures")
                sendMessageSync(RemoteMessages.configureResponse(deviceCode))
            }
            message.hasRemoteSetActive() -> {
                Log.i(TAG, "RemoteSetActive")
                sendMessageSync(RemoteMessages.setActiveResponse(activeFeatures))
            }
            message.hasRemoteStart() -> {
                Log.i(TAG, "RemoteStart")
                onSessionReady()
            }
            message.hasRemotePingRequest() -> {
                val response = RemotePingResponse.newBuilder()
                    .setVal1(message.remotePingRequest.val1)
                    .build()
                val responseMsg = RemoteMessage.newBuilder()
                    .setRemotePingResponse(response)
                    .build()
                sendMessageSync(responseMsg)
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
                Log.w(TAG, "TV remote error: ${message.remoteError}")
            }
        }
    }

    private fun onSessionReady() {
        val queued = synchronized(pendingCommands) {
            if (sessionReady) return
            sessionReady = true
            pendingCommands.toList().also { pendingCommands.clear() }
        }
        _connectionState.value = ConnectionState.Connected
        queued.forEach { commandChannel.trySend(it) }
        startPingLoop()
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
        val keyInject = RemoteKeyInject.newBuilder()
            .setKeyCode(keyCode)
            .setDirection(direction)
            .build()
        val message = RemoteMessage.newBuilder()
            .setRemoteKeyInject(keyInject)
            .build()
        enqueue(message)
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
        Log.i(TAG, "Queue app link ${message.remoteAppLinkLaunchRequest.appLink}")
        enqueue(message)
    }

    private fun enqueue(message: RemoteMessage) {
        val sendNow = synchronized(pendingCommands) {
            if (sessionReady) {
                true
            } else {
                pendingCommands.addLast(message)
                false
            }
        }
        if (sendNow) {
            commandChannel.trySend(message)
        }
    }
    
    private fun sendMessageSync(message: RemoteMessage) {
        try {
            val bytes = message.toByteArray()
            output?.let { out ->
                synchronized(out) {
                    val framed = ByteArrayOutputStream(bytes.size + 5)
                    writeVarint(framed, bytes.size)
                    framed.write(bytes)
                    out.write(framed.toByteArray())
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // Connection error, will be handled by read loop
        }
    }
    
    private fun readMessage(): RemoteMessage? {
        return try {
            input?.let { inp ->
                val length = readVarint(inp)
                if (length <= 0) return null
                
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = inp.read(bytes, read, length - read)
                    if (n < 0) return null
                    read += n
                }
                RemoteMessage.parseFrom(bytes)
            }
        } catch (e: Exception) {
            null
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
    
    private fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) return -1
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 35) throw IOException("Varint too long")
        }
        return result
    }
}
