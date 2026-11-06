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

/**
 * Caches the Sportsnet playlist feed and refreshes it every few minutes.
 * A 304 leaves the cache in place. A failed fetch keeps the last good list.
 * The 36-hour window is applied when the screen reads [visible], so a cached
 * highlight drops off without another download.
 */
internal class SportsnetGuide(
    private val directory: File,
    private val fetch: GuideFetch,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS
) {
    private val _state = MutableStateFlow(HighlightState())
    val state: StateFlow<HighlightState> = _state.asStateFlow()

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

    fun visible(nowMs: Long): List<HighlightVideo> = SportsnetFeed.select(_state.value.videos, nowMs)

    internal fun restore() {
        val file = File(directory, CACHE_NAME)
        if (!file.isFile) return
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                if (reader.readLine() != CACHE_VERSION) return
                val storedEtag = reader.readLine()?.takeIf { it.isNotBlank() && it != "-" }
                val videos = mutableListOf<HighlightVideo>()
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.split('\t', limit = 3)
                    if (parts.size != 3) continue
                    val published = parts[1].toLongOrNull() ?: continue
                    val title = parts[2].trim()
                    val id = parts[0].trim()
                    if (id.isEmpty() || title.isEmpty()) continue
                    if (!SportsnetFeed.isGameHighlight(title)) continue
                    videos.add(HighlightVideo(id, title, published))
                }
                etag = storedEtag
                _state.value = HighlightState(videos, GuideStatus.Ready)
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
                val parsed = SportsnetFeed.parse(body.stream)
                etag = body.etag
                writeCache(body.etag, parsed)
                _state.value = HighlightState(parsed, GuideStatus.Ready)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markFailure()
        }
    }

    private fun markUnchanged() {
        val status = if (_state.value.videos.isEmpty() && _state.value.status == GuideStatus.Loading) {
            GuideStatus.Unavailable
        } else {
            GuideStatus.Ready
        }
        _state.value = _state.value.copy(status = status)
    }

    private fun markFailure() {
        val keep = _state.value.videos.isNotEmpty() || _state.value.status == GuideStatus.Ready
        _state.value = _state.value.copy(
            status = if (keep) GuideStatus.Ready else GuideStatus.Unavailable
        )
    }

    private fun writeCache(newEtag: String?, videos: List<HighlightVideo>) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val target = File(directory, CACHE_NAME)
        val temporary = File(directory, "$CACHE_NAME.tmp")
        temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(CACHE_VERSION).append('\n')
            writer.append(newEtag?.takeIf { it.isNotBlank() } ?: "-").append('\n')
            for (video in videos) {
                val title = video.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                writer.append(video.videoId)
                    .append('\t').append(video.publishedMs.toString())
                    .append('\t').append(title)
                    .append('\n')
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        private const val CACHE_NAME = "sportsnet-highlights.tsv"
        private const val CACHE_VERSION = "v1"
    }
}

internal data class HighlightState(
    val videos: List<HighlightVideo> = emptyList(),
    val status: GuideStatus = GuideStatus.Loading
)
