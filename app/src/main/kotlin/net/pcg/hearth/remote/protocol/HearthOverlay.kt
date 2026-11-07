package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Hearth 4.14 overlay switches. Values are the strings "on" and "off".
 * [WXPILL] is the live-TV temperature pill.
 */
internal object OverlayKey {
    const val WXPILL = "wxpill"
    const val QUAKE = "quake"
    const val WARN = "warn"
    const val SABRES = "sabres"
    const val WEATHER = "weather"
    val ALL = listOf(WXPILL, QUAKE, WARN, SABRES, WEATHER)
}

internal data class OverlayConfig(
    val wxpill: Boolean,
    val quake: Boolean,
    val warn: Boolean,
    val sabres: Boolean,
    val weather: Boolean
) {
    operator fun get(key: String): Boolean = when (key) {
        OverlayKey.WXPILL -> wxpill
        OverlayKey.QUAKE -> quake
        OverlayKey.WARN -> warn
        OverlayKey.SABRES -> sabres
        OverlayKey.WEATHER -> weather
        else -> false
    }
}

internal class OverlayException(message: String) : Exception(message)

internal data class OverlayCall(val url: String)

internal data class OverlayResult(val code: Int, val body: String)

internal fun interface OverlayTransport {
    fun exchange(call: OverlayCall): OverlayResult
}

/**
 * Reads and sets Hearth's overlay config at http://&lt;tv-host&gt;:8765/overlay.
 * The host is the paired TV address. There is no auth.
 * A set is `GET /overlay?key=on` or `GET /overlay?key=off`.
 */
internal class HearthOverlay(
    private val hostProvider: () -> String?,
    private val transport: OverlayTransport = HttpOverlayTransport(),
    private val port: Int = PORT
) {
    fun read(): OverlayConfig {
        val result = call(emptyMap())
        return try {
            parseOverlay(result.body)
        } catch (e: IllegalArgumentException) {
            throw OverlayException(e.message ?: "bad overlay json")
        }
    }

    fun set(key: String, on: Boolean): OverlayConfig {
        if (key !in OverlayKey.ALL) throw OverlayException("unknown overlay $key")
        val result = call(mapOf(key to if (on) "on" else "off"))
        val parsed = try {
            parseOverlay(result.body)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsed != null && parsed[key] == on) return parsed
        return read()
    }

    private fun call(query: Map<String, String>): OverlayResult {
        val result = try {
            transport.exchange(OverlayCall(overlayUrl(hostProvider(), query, port)))
        } catch (e: OverlayException) {
            throw e
        } catch (e: Exception) {
            throw OverlayException(e.message ?: "overlay unreachable")
        }
        if (result.code != HttpURLConnection.HTTP_OK) {
            throw OverlayException("HTTP ${result.code}")
        }
        return result
    }

    companion object {
        const val PORT = 8765
    }
}

internal fun overlayUrl(host: String?, query: Map<String, String> = emptyMap(), port: Int = HearthOverlay.PORT): String {
    var clean = host?.trim().orEmpty()
    if (clean.startsWith("http://", ignoreCase = true)) clean = clean.substring(7)
    if (clean.startsWith("https://", ignoreCase = true)) clean = clean.substring(8)
    val slash = clean.indexOf('/')
    if (slash >= 0) clean = clean.substring(0, slash)
    clean = clean.trimEnd('/')
    if (clean.isEmpty() || clean.any { it.isWhitespace() }) throw OverlayException("no tv host")
    val base = "http://$clean:$port/overlay"
    if (query.isEmpty()) return base
    val encoded = query.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }
    return "$base?$encoded"
}

internal fun parseOverlay(raw: String): OverlayConfig {
    val fields = parseJson(raw).obj()
    return OverlayConfig(
        wxpill = onOff(fields, OverlayKey.WXPILL),
        quake = onOff(fields, OverlayKey.QUAKE),
        warn = onOff(fields, OverlayKey.WARN),
        sabres = onOff(fields, OverlayKey.SABRES),
        weather = onOff(fields, OverlayKey.WEATHER)
    )
}

private fun onOff(fields: Map<String, Json>, key: String): Boolean {
    val raw = fields[key]?.textOrNull()?.trim()?.lowercase()
        ?: throw IllegalArgumentException("missing $key")
    return when (raw) {
        "on" -> true
        "off" -> false
        else -> throw IllegalArgumentException("bad $key")
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

internal class HttpOverlayTransport(
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000
) : OverlayTransport {
    override fun exchange(call: OverlayCall): OverlayResult {
        val connection = (URL(call.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HearthRemote")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return OverlayResult(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

internal data class OverlayUiState(
    val config: OverlayConfig? = null,
    val available: Boolean = false,
    val loading: Boolean = true
)

/**
 * One shared reader for the terrestrial pill and the settings switches.
 * A failed read or write leaves the controls unavailable. It does not throw.
 */
internal class OverlayController(
    private val client: HearthOverlay,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _state = MutableStateFlow(OverlayUiState())
    val state: StateFlow<OverlayUiState> = _state.asStateFlow()
    private val gate = Mutex()

    fun refresh() {
        scope.launch { refreshNow() }
    }

    fun set(key: String, on: Boolean) {
        scope.launch { setNow(key, on) }
    }

    internal suspend fun refreshNow() {
        gate.withLock {
            try {
                val config = client.read()
                _state.value = OverlayUiState(config = config, available = true, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(available = false, loading = false)
            }
        }
    }

    internal suspend fun setNow(key: String, on: Boolean) {
        gate.withLock {
            if (!_state.value.available) return@withLock
            try {
                val config = client.set(key, on)
                _state.value = OverlayUiState(config = config, available = true, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(available = false, loading = false)
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
