package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal enum class GuideStatus {
    Loading,
    Ready,
    Unavailable
}

internal data class GuideState(
    val byButton: Map<Int, List<Programme>> = emptyMap(),
    val status: GuideStatus = GuideStatus.Loading
)

/**
 * Fetches the public J:COM XMLTV, keeps the eight Osaka terrestrial channels,
 * and refreshes every few minutes. A 304 leaves the cache in place. A failed
 * fetch never invents titles: the last good cache stays, or the guide is
 * marked unavailable when there is nothing cached.
 */
internal class OsakaGuide(
    private val directory: File,
    private val fetch: GuideFetch,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS
) {
    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()

    private var etag: String? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            withContext(Dispatchers.IO) { restore() }
            while (isActive) {
                withContext(Dispatchers.IO) { refreshOnce() }
                delay(refreshIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun nowAndNext(button: Int, nowMs: Long): NowAndNext {
        val programmes = _state.value.byButton[button].orEmpty()
        return XmltvGuide.nowAndNext(programmes, nowMs)
    }

    internal fun restore() {
        val file = File(directory, CACHE_NAME)
        if (!file.isFile) return
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                if (reader.readLine() != CACHE_VERSION) return
                val storedEtag = reader.readLine()?.takeIf { it.isNotBlank() && it != "-" }
                val parsed = LinkedHashMap<String, MutableList<Programme>>()
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.split('\t', limit = 4)
                    if (parts.size != 4) continue
                    val start = parts[1].toLongOrNull() ?: continue
                    val end = parts[2].toLongOrNull() ?: continue
                    val title = parts[3].trim()
                    if (title.isEmpty() || end <= start) continue
                    parsed.getOrPut(parts[0]) { mutableListOf() }.add(Programme(title, start, end))
                }
                if (parsed.isEmpty()) return
                etag = storedEtag
                _state.value = GuideState(toButtons(parsed), GuideStatus.Ready)
            }
        } catch (_: Exception) {
            // A broken cache is ignored. The next refresh replaces it.
        }
    }

    internal fun refreshOnce() {
        try {
            val download = fetch.fetch(etag)
            if (download == null) {
                markUnchanged()
                return
            }
            download.use { body ->
                val parsed = XmltvGuide.parse(body.stream, OsakaChannels.epgIds)
                if (parsed.isEmpty()) {
                    markFailure()
                    return
                }
                etag = body.etag
                writeCache(body.etag, parsed)
                _state.value = GuideState(toButtons(parsed), GuideStatus.Ready)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markFailure()
        }
    }

    private fun markUnchanged() {
        _state.value = _state.value.copy(
            status = if (_state.value.byButton.isEmpty()) GuideStatus.Unavailable else GuideStatus.Ready
        )
    }

    private fun markFailure() {
        _state.value = _state.value.copy(
            status = if (_state.value.byButton.isEmpty()) GuideStatus.Unavailable else GuideStatus.Ready
        )
    }

    private fun toButtons(parsed: Map<String, List<Programme>>): Map<Int, List<Programme>> {
        val byButton = LinkedHashMap<Int, List<Programme>>()
        for (channel in OsakaChannels.buttons) {
            val id = channel.epgId ?: continue
            val programmes = parsed[id] ?: continue
            if (programmes.isNotEmpty()) byButton[channel.button] = programmes
        }
        return byButton
    }

    private fun writeCache(newEtag: String?, parsed: Map<String, List<Programme>>) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val target = File(directory, CACHE_NAME)
        val temporary = File(directory, "$CACHE_NAME.tmp")
        temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(CACHE_VERSION).append('\n')
            writer.append(newEtag?.takeIf { it.isNotBlank() } ?: "-").append('\n')
            for ((id, programmes) in parsed) {
                if (id !in OsakaChannels.epgIds) continue
                for (programme in programmes) {
                    val title = programme.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    writer.append(id)
                        .append('\t').append(programme.startMs.toString())
                        .append('\t').append(programme.endMs.toString())
                        .append('\t').append(title)
                        .append('\n')
                }
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        const val SOURCE_URL =
            "https://raw.githubusercontent.com/dbghelp/JCOM-TV-EPG/refs/heads/main/jcom.xml"
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        private const val CACHE_NAME = "osaka-epg.tsv"
        private const val CACHE_VERSION = "v1"
    }
}

internal fun interface GuideFetch {
    /** Null means the cached copy is still current (HTTP 304). */
    fun fetch(etag: String?): GuideDownload?
}

internal class GuideDownload(
    val etag: String?,
    val stream: InputStream,
    private val onClose: () -> Unit = {}
) : AutoCloseable {
    override fun close() {
        try {
            stream.close()
        } finally {
            onClose()
        }
    }
}

internal class HttpGuideFetch(
    private val url: String = OsakaGuide.SOURCE_URL,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000
) : GuideFetch {
    override fun fetch(etag: String?): GuideDownload? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "HearthRemote")
            setRequestProperty("Accept", "application/xml, text/xml, */*")
            if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                connection.disconnect()
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("EPG HTTP $code")
            }
            val body = connection.inputStream
            val newEtag = connection.getHeaderField("ETag")
            return GuideDownload(newEtag, body) { connection.disconnect() }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }
}
