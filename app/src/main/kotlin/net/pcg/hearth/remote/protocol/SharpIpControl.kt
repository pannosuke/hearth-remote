package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sharp AQUOS IP control on TCP 10002.
 *
 * There is no login and no greeting. Each command is exactly 8 ASCII
 * characters plus CR: a 4-character command and a 4-character parameter
 * padded with spaces. The TV answers OK, ERR, or a value. It allows one
 * session at a time and drops a second connection with
 * "Service Busy. Connection Closed." The set closes an idle session after
 * 60 seconds. That teardown has been aborting the Android TV remote socket
 * on port 6466, so this client closes its own socket with a FIN after
 * [LOCAL_IDLE_MS] instead of waiting for the set.
 *
 * Power: `POWR????` answers `1` when the set is on and `0` in standby.
 * `POWR0` is standby. `POWR1` wakes the set, but only after power-on over
 * IP is enabled. `RSPW2` (parameter `2`) enables power-on over IP from any
 * address. It has to be sent while the set is on. The phone sends it once
 * and remembers that the set accepted it.
 *
 * This client keeps that one socket, queues every command on it, and opens
 * a new socket only after the idle window or a dead connection. A busy
 * reply is retried with backoff and is never returned to the caller.
 */
internal object SharpIpCommands {
    const val PORT = 10002
    const val IDLE_WINDOW_MS = 60_000L
    const val LOCAL_IDLE_MS = 15_000L

    const val VOLUME_UP = "0114"
    const val VOLUME_DOWN = "0115"
    const val MUTE_KEY = "0117"
    const val SUBTITLE = "0267"
    const val ENTER = "0152"
    const val UP = "0157"
    const val DOWN = "0120"
    const val BACK = "01E4"
    const val HOME = "02BB"
    const val CHANNEL_UP = "0111"
    const val CHANNEL_DOWN = "0112"

    fun encode(command: String, param: String): ByteArray {
        require(command.length == 4) { "Sharp command must be 4 characters" }
        val padded = param.padEnd(4, ' ').take(4)
        return (command + padded + "\r").toByteArray(Charsets.US_ASCII)
    }

    fun parseReply(raw: String): String = raw.trim()

    fun isServiceBusy(reply: String): Boolean {
        return reply.contains("service busy", ignoreCase = true)
    }
}

internal class ServiceBusyException(message: String) : IOException(message)

internal class SharpIpControl(
    private val hostProvider: () -> String?,
    private val port: Int = SharpIpCommands.PORT,
    private val connectTimeoutMs: Int = 2000,
    private val readTimeoutMs: Int = 2000,
    private val idleWindowMs: Long = SharpIpCommands.LOCAL_IDLE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val backoffMs: LongArray = longArrayOf(300, 700, 1500),
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var lastUsedMs = 0L

    suspend fun command(command: String, param: String): String = mutex.withLock {
        sendWithRetry(command, param)
    }

    /**
     * Run several commands on the same session. The block must send through
     * the lambda. [command] takes the same lock and would wait forever.
     */
    suspend fun <T> exclusive(block: suspend (suspend (String, String) -> String) -> T): T {
        return mutex.withLock { block { command, param -> sendWithRetry(command, param) } }
    }

    suspend fun releaseIfIdle() {
        mutex.withLock {
            if (socket == null) return
            if (clock() - lastUsedMs >= idleWindowMs) closeSocket()
        }
    }

    suspend fun tryCommand(command: String, param: String): Boolean {
        return try {
            val reply = command(command, param)
            reply.isNotEmpty() && reply != "ERR" && !SharpIpCommands.isServiceBusy(reply)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun sendWithRetry(command: String, param: String): String {
        var last: Exception? = null
        val attempts = backoffMs.size + 1
        for (attempt in 0 until attempts) {
            try {
                return withContext(Dispatchers.IO) { exchangeOnce(command, param) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: NoTvAddressException) {
                throw e
            } catch (e: Exception) {
                closeSocket()
                last = e
                if (attempt == backoffMs.size) break
                sleeper(backoffMs[attempt])
            }
        }
        throw last ?: IOException("Sharp IP failed")
    }

    private fun exchangeOnce(command: String, param: String): String {
        val current = openIfNeeded()
        val payload = SharpIpCommands.encode(command, param)
        val output = current.getOutputStream()
        output.write(payload)
        output.flush()
        val reply = SharpIpCommands.parseReply(readReply(current.getInputStream()))
        lastUsedMs = clock()
        if (SharpIpCommands.isServiceBusy(reply)) {
            throw ServiceBusyException(reply)
        }
        return reply
    }

    private fun openIfNeeded(): Socket {
        val now = clock()
        val current = socket
        if (current != null && !current.isClosed && current.isConnected &&
            now - lastUsedMs < idleWindowMs
        ) {
            return current
        }
        closeSocket()
        val host = hostProvider() ?: throw NoTvAddressException()
        val opened = Socket()
        opened.connect(InetSocketAddress(host, port), connectTimeoutMs)
        opened.tcpNoDelay = true
        opened.soTimeout = readTimeoutMs
        socket = opened
        lastUsedMs = now
        return opened
    }

    private fun closeSocket() {
        val closing = socket
        socket = null
        try {
            closing?.shutdownOutput()
        } catch (_: Exception) {
        }
        try {
            closing?.close()
        } catch (_: Exception) {
        }
    }

    private fun readReply(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) throw IOException("TV closed the session")
                break
            }
            if (b == '\r'.code || b == '\n'.code) {
                if (out.size() > 0) break
            } else {
                out.write(b)
            }
        }
        return out.toString(Charsets.US_ASCII)
    }
}

internal class NoTvAddressException : IOException("No TV address")

internal object SharpIpPower {
    fun parse(reply: String): Boolean? = when (reply.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
