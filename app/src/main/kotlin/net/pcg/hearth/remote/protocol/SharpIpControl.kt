package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sharp AQUOS IP control on TCP 10002.
 *
 * There is no login and no greeting. Each command is exactly 8 ASCII
 * characters plus CR: a 4-character command and a 4-character parameter
 * padded with spaces. The TV answers OK, ERR, or a value, and allows one
 * session at a time. This client opens a short-lived socket per command and
 * serializes them so two commands never share the port.
 */
internal object SharpIpCommands {
    const val PORT = 10002

    const val VOLUME_UP = "0114"
    const val VOLUME_DOWN = "0115"
    const val MUTE_KEY = "0117"
    const val HOME = "02BB"
    const val CHANNEL_UP = "0111"
    const val CHANNEL_DOWN = "0112"

    fun encode(command: String, param: String): ByteArray {
        require(command.length == 4) { "Sharp command must be 4 characters" }
        val padded = param.padEnd(4, ' ').take(4)
        return (command + padded + "\r").toByteArray(Charsets.US_ASCII)
    }

    fun parseReply(raw: String): String = raw.trim()
}

internal class SharpIpControl(
    private val hostProvider: () -> String?,
    private val port: Int = SharpIpCommands.PORT,
    private val connectTimeoutMs: Int = 2000,
    private val readTimeoutMs: Int = 2000
) {
    private val mutex = Mutex()

    suspend fun command(command: String, param: String): String = mutex.withLock {
        val host = hostProvider() ?: throw IOException("No TV address")
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.tcpNoDelay = true
                socket.soTimeout = readTimeoutMs
                val payload = SharpIpCommands.encode(command, param)
                val output = socket.getOutputStream()
                output.write(payload)
                output.flush()
                SharpIpCommands.parseReply(readReply(socket.getInputStream()))
            }
        }
    }

    suspend fun tryCommand(command: String, param: String): Boolean {
        return try {
            val reply = command(command, param)
            reply.isNotEmpty() && reply != "ERR"
        } catch (_: Exception) {
            false
        }
    }

    private fun readReply(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\r'.code || b == '\n'.code) {
                if (out.size() > 0) break
            } else {
                out.write(b)
            }
        }
        return out.toString(Charsets.US_ASCII)
    }
}
