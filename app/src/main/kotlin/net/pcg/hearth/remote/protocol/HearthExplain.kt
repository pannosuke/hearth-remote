package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks Hearth 4.24 to toggle the English news summary on the TV.
 * The set shows the overlay for 60 seconds and hides it if asked again.
 * The phone only sends the request. It does not keep the overlay timer.
 */
internal enum class ExplainOutcome {
    Sent,
    NeedsUpdate,
    Failed
}

internal enum class ExplainPhase {
    Idle,
    Requesting,
    Sent,
    NeedsUpdate,
    Failed
}

internal data class ExplainCall(val method: String, val url: String)

internal data class ExplainResult(val code: Int, val body: String)

internal fun interface ExplainTransport {
    fun exchange(call: ExplainCall): ExplainResult
}

internal class HearthExplain(
    private val hostProvider: () -> String?,
    private val transport: ExplainTransport = HttpExplainTransport(),
    private val port: Int = HearthOverlay.PORT
) {
    fun toggle(): ExplainOutcome {
        val url = explainUrl(hostProvider(), port)
        val posted = exchange("POST", url)
        val result = if (posted.code == HttpURLConnection.HTTP_BAD_METHOD) exchange("GET", url) else posted
        return when (result.code) {
            in 200..299 -> ExplainOutcome.Sent
            HttpURLConnection.HTTP_NOT_FOUND -> ExplainOutcome.NeedsUpdate
            else -> ExplainOutcome.Failed
        }
    }

    private fun exchange(method: String, url: String): ExplainResult {
        return try {
            transport.exchange(ExplainCall(method, url))
        } catch (e: OverlayException) {
            throw e
        } catch (e: Exception) {
            throw OverlayException(e.message ?: "explain unreachable")
        }
    }
}

internal fun explainUrl(host: String?, port: Int = HearthOverlay.PORT): String {
    var clean = host?.trim().orEmpty()
    if (clean.startsWith("http://", ignoreCase = true)) clean = clean.substring(7)
    if (clean.startsWith("https://", ignoreCase = true)) clean = clean.substring(8)
    val slash = clean.indexOf('/')
    if (slash >= 0) clean = clean.substring(0, slash)
    clean = clean.trimEnd('/')
    if (clean.isEmpty() || clean.any { it.isWhitespace() }) throw OverlayException("no tv host")
    return "http://$clean:$port/api/explain"
}

internal class HttpExplainTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000
) : ExplainTransport {
    override fun exchange(call: ExplainCall): ExplainResult {
        val connection = (URL(call.url).openConnection() as HttpURLConnection).apply {
            requestMethod = call.method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HearthRemote")
            if (call.method == "POST") {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
        try {
            if (call.method == "POST") connection.outputStream.close()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return ExplainResult(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

internal class ExplainController(
    private val client: HearthExplain,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val holdMs: Long = 4_000L
) {
    private val _state = MutableStateFlow(ExplainPhase.Idle)
    val state: StateFlow<ExplainPhase> = _state.asStateFlow()
    private val generation = AtomicInteger()
    private val busy = AtomicBoolean(false)

    fun press() {
        if (!busy.compareAndSet(false, true)) return
        val ticket = generation.incrementAndGet()
        scope.launch {
            val phase = try {
                finish(ticket)
            } catch (e: CancellationException) {
                busy.set(false)
                throw e
            }
            if (phase == ExplainPhase.Sent || phase == ExplainPhase.Failed) {
                delay(holdMs)
                if (ticket == generation.get()) _state.value = ExplainPhase.Idle
            }
        }
    }

    internal suspend fun pressNow(): ExplainPhase {
        if (!busy.compareAndSet(false, true)) return _state.value
        val ticket = generation.incrementAndGet()
        val phase = try {
            finish(ticket)
        } catch (e: CancellationException) {
            busy.set(false)
            throw e
        }
        if (phase == ExplainPhase.Sent || phase == ExplainPhase.Failed) {
            delay(holdMs)
            if (ticket == generation.get()) _state.value = ExplainPhase.Idle
        }
        return phase
    }

    private fun finish(ticket: Int): ExplainPhase {
        _state.value = ExplainPhase.Requesting
        val phase = try {
            when (client.toggle()) {
                ExplainOutcome.Sent -> ExplainPhase.Sent
                ExplainOutcome.NeedsUpdate -> ExplainPhase.NeedsUpdate
                ExplainOutcome.Failed -> ExplainPhase.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ExplainPhase.Failed
        }
        if (ticket == generation.get()) _state.value = phase
        busy.set(false)
        return phase
    }

    fun close() {
        scope.cancel()
    }
}
