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
 * Caches Sportsnet highlight feeds and refreshes them every few minutes.
 * The channel feed is required. The playlist feed is merged when it returns
 * a body and ignored when it fails. A failure of every source keeps the last
 * good list, or marks the guide unavailable when there is nothing to show.
 * The 48-hour window is applied when the screen reads [visible], so a cached
 * highlight drops off without another download.
 */
internal class SportsnetGuide(
    private val directory: File,
    private val channel: GuideFetch,
    private val playlist: GuideFetch,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS
) {
    private val _state = MutableStateFlow(HighlightState())
    val state: StateFlow<HighlightState> = _state.asStateFlow()

    private var etag: String? = null
    private var job: Job? = null
    private var owner: CoroutineScope? = null
    private val gate = Any()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        owner = scope
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

    /** Ask both feeds again. A total failure with nothing cached stays unavailable. */
    fun retry() {
        val scope = owner ?: return
        if (_state.value.videos.isEmpty()) {
            _state.value = _state.value.copy(status = GuideStatus.Loading)
        }
        scope.launch(Dispatchers.IO) { refreshOnce() }
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
        synchronized(gate) {
            val channelVideos = readSource(channel)
            val playlistVideos = readSource(playlist)
            if (channelVideos == null && playlistVideos == null) {
                markFailure()
                return
            }
            val merged = SportsnetFeed.merge(channelVideos.orEmpty(), playlistVideos.orEmpty())
            etag = null
            writeCache(null, merged)
            _state.value = HighlightState(merged, GuideStatus.Ready)
        }
    }

    /**
     * A body is a successful source, including a feed with no matching titles.
     * Non-200 and network errors throw from [GuideFetch] and count as a miss.
     * A miss is null so an empty successful feed is not confused with a failure.
     */
    private fun readSource(source: GuideFetch): List<HighlightVideo>? {
        return try {
            val download = source.fetch(null) ?: return null
            download.use { body -> SportsnetFeed.parse(body.stream) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
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
