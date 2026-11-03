package net.pcg.hearth.remote.protocol

internal enum class SubtitleTrack {
    Japanese,
    English,
    Off
}

internal enum class JellyfinProblem {
    None,
    SignInFailed,
    Unauthorized,
    RequestFailed,
    NoSession
}

internal data class JellyfinStream(
    val index: Int,
    val type: String,
    val language: String?,
    val title: String?,
    val displayTitle: String?
)

internal data class JellyfinNowPlaying(
    val itemId: String,
    val title: String,
    val seriesName: String?,
    val episodeName: String?,
    val season: Int?,
    val episode: Int?,
    val positionTicks: Long?,
    val runtimeTicks: Long?,
    val paused: Boolean,
    val subtitleIndex: Int?,
    val streams: List<JellyfinStream>,
    val imageItemId: String,
    val imageTag: String?
) {
    fun headline(): String = seriesName?.takeIf { it.isNotBlank() } ?: title

    fun detail(): String {
        val code = if (season != null && episode != null) "S$season E$episode" else null
        val name = episodeName?.takeIf { it.isNotBlank() && it != headline() }
        return when {
            code != null && name != null -> "$code · $name"
            code != null -> code
            name != null -> name
            else -> ""
        }
    }
}

internal data class JellyfinSession(
    val id: String,
    val client: String,
    val deviceName: String,
    val deviceId: String,
    val supportsRemoteControl: Boolean,
    val nowPlaying: JellyfinNowPlaying?
) {
    fun isTelevision(): Boolean {
        if (client.equals(TV_CLIENT, ignoreCase = true)) return true
        if (deviceName.contains("AQUOS", ignoreCase = true)) return true
        if (deviceName.contains("SHARP", ignoreCase = true)) return true
        return false
    }

    companion object {
        const val TV_CLIENT = "Jellyfin Android TV"
    }
}

internal data class JellyfinUpNext(
    val itemId: String,
    val title: String,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val imageTag: String?
) {
    fun headline(): String = seriesName?.takeIf { it.isNotBlank() } ?: title

    fun detail(): String {
        val code = if (season != null && episode != null) "S$season E$episode" else null
        val name = if (seriesName.isNullOrBlank()) "" else title
        return when {
            code != null && name.isNotBlank() -> "$code · $name"
            code != null -> code
            else -> name
        }
    }
}

internal data class JellyfinLogin(
    val serverUrl: String,
    val userId: String,
    val userName: String,
    val accessToken: String
)

internal data class SessionPick(
    val choices: List<JellyfinSession>,
    val selected: JellyfinSession?
)

internal data class JellyfinUiState(
    val serverUrl: String = "",
    val signedIn: Boolean = false,
    val userName: String? = null,
    val problem: JellyfinProblem = JellyfinProblem.None,
    val loaded: Boolean = false,
    val sessions: List<JellyfinSession> = emptyList(),
    val selectedSessionId: String? = null,
    val nowPlaying: JellyfinNowPlaying? = null,
    val upNext: List<JellyfinUpNext> = emptyList(),
    val observedAtMs: Long = 0L
)

internal interface JellyfinCredentialStore {
    fun read(): JellyfinLogin?
    fun write(login: JellyfinLogin)
    fun clearLogin()
    fun serverUrl(): String
    fun deviceId(): String
    fun selectedDeviceId(): String?
    fun setSelectedDeviceId(id: String?)
}

internal data class JellyfinCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?
)

internal data class JellyfinResult(
    val code: Int,
    val bytes: ByteArray
) {
    fun text(): String = bytes.toString(Charsets.UTF_8)
}

internal interface JellyfinTransport {
    fun exchange(request: JellyfinCall): JellyfinResult
}

internal class JellyfinHttpException(val code: Int) : Exception("Jellyfin HTTP $code")

internal object JellyfinApi {
    const val TICKS_PER_SECOND = 10_000_000L
    private const val TICKS_PER_MILLI = 10_000L

    fun normalizeServerUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }

    fun embyAuthorization(deviceId: String, version: String): String {
        val safeDevice = deviceId.replace("\"", "")
        val safeVersion = version.replace("\"", "").ifBlank { "0.2.3" }
        return "MediaBrowser Client=\"Hearth Remote\", Device=\"Hearth Remote\", DeviceId=\"$safeDevice\", Version=\"$safeVersion\""
    }

    fun authBody(username: String, password: String): String {
        return """{"Username":${jsonString(username)},"Pw":${jsonString(password)}}"""
    }

    fun subtitleBody(index: Int): String {
        return """{"Name":"SetSubtitleStreamIndex","Arguments":{"Index":"$index"}}"""
    }

    fun parseAuth(json: String): Triple<String, String, String> {
        val root = parseJson(json).obj()
        val user = root["User"]?.obj() ?: throw IllegalArgumentException("missing user")
        val userId = user["Id"]?.textOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing user id")
        val name = user["Name"]?.textOrNull().orEmpty()
        val token = root["AccessToken"]?.textOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing token")
        return Triple(userId, name, token)
    }

    fun parseSessions(json: String): List<JellyfinSession> {
        return parseJson(json).arrayOrEmpty().mapNotNull { element ->
            val obj = element as? Json.Obj ?: return@mapNotNull null
            val id = obj.field("Id")?.textOrNull() ?: return@mapNotNull null
            JellyfinSession(
                id = id,
                client = obj.field("Client")?.textOrNull().orEmpty(),
                deviceName = obj.field("DeviceName")?.textOrNull().orEmpty(),
                deviceId = obj.field("DeviceId")?.textOrNull().orEmpty(),
                supportsRemoteControl = obj.field("SupportsRemoteControl")?.boolOrNull() ?: true,
                nowPlaying = parseNowPlaying(obj)
            )
        }
    }

    fun parseNextUp(json: String): List<JellyfinUpNext> {
        val items = parseJson(json).field("Items")?.arrayOrEmpty().orEmpty()
        return items.mapNotNull { element ->
            val obj = element as? Json.Obj ?: return@mapNotNull null
            val id = obj.field("Id")?.textOrNull() ?: return@mapNotNull null
            JellyfinUpNext(
                itemId = id,
                title = obj.field("Name")?.textOrNull().orEmpty(),
                seriesName = obj.field("SeriesName")?.textOrNull(),
                season = obj.field("ParentIndexNumber")?.intOrNull(),
                episode = obj.field("IndexNumber")?.intOrNull(),
                imageTag = obj.field("ImageTags")?.field("Primary")?.textOrNull()
            )
        }
    }

    fun pickSession(
        sessions: List<JellyfinSession>,
        ourDeviceId: String,
        rememberedDeviceId: String?
    ): SessionPick {
        val controllable = sessions.filter { session ->
            session.supportsRemoteControl &&
                session.id.isNotBlank() &&
                !session.client.equals("Hearth Remote", ignoreCase = true) &&
                session.deviceId != ourDeviceId
        }
        val televisions = controllable.filter { it.isTelevision() }
        val choices = if (televisions.isNotEmpty()) televisions else controllable
        val remembered = rememberedDeviceId?.let { id -> choices.find { it.deviceId == id } }
        val selected = remembered ?: choices.singleOrNull()
        return SessionPick(choices, selected)
    }

    fun matchingSubtitle(streams: List<JellyfinStream>, track: SubtitleTrack): Int? {
        if (track == SubtitleTrack.Off) return -1
        return streams
            .filter { it.type.equals("Subtitle", ignoreCase = true) }
            .firstOrNull { streamMatches(it, track) }
            ?.index
    }

    fun seekTicks(positionTicks: Long?, deltaSeconds: Int, runtimeTicks: Long?): Long? {
        val position = positionTicks ?: return null
        val next = position + deltaSeconds.toLong() * TICKS_PER_SECOND
        val runtime = runtimeTicks?.takeIf { it > 0L }
        return if (runtime != null) next.coerceIn(0L, runtime) else next.coerceAtLeast(0L)
    }

    fun shownTicks(item: JellyfinNowPlaying, nowMs: Long, observedAtMs: Long): Long? {
        val position = item.positionTicks ?: return null
        if (item.paused || observedAtMs <= 0L) return position
        val elapsedMs = (nowMs - observedAtMs).coerceAtLeast(0L)
        val advanced = position + elapsedMs * TICKS_PER_MILLI
        val runtime = item.runtimeTicks?.takeIf { it > 0L }
        return if (runtime != null) advanced.coerceAtMost(runtime) else advanced
    }

    fun fraction(positionTicks: Long?, runtimeTicks: Long?): Float? {
        if (positionTicks == null || runtimeTicks == null || runtimeTicks <= 0L) return null
        return (positionTicks.toDouble() / runtimeTicks.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun formatTicks(ticks: Long): String {
        val total = (ticks / TICKS_PER_SECOND).coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "$hours:${minutes.pad()}:${seconds.pad()}"
        } else {
            "$minutes:${seconds.pad()}"
        }
    }

    fun imageUrl(serverUrl: String, itemId: String, tag: String?, maxHeight: Int = 480): String {
        val base = normalizeServerUrl(serverUrl)
        val query = buildString {
            append("maxHeight=").append(maxHeight)
            if (!tag.isNullOrBlank()) {
                append("&tag=").append(encode(tag))
            }
        }
        return "$base/Items/${encode(itemId)}/Images/Primary?$query"
    }

    fun authenticateUrl(serverUrl: String): String =
        "${normalizeServerUrl(serverUrl)}/Users/AuthenticateByName"

    fun sessionsUrl(serverUrl: String): String =
        "${normalizeServerUrl(serverUrl)}/Sessions"

    fun nextUpUrl(serverUrl: String, userId: String): String =
        "${normalizeServerUrl(serverUrl)}/Shows/NextUp?userId=${encode(userId)}&Limit=12"

    fun playPauseUrl(serverUrl: String, sessionId: String): String =
        "${normalizeServerUrl(serverUrl)}/Sessions/${encode(sessionId)}/Playing/PlayPause"

    fun seekUrl(serverUrl: String, sessionId: String, ticks: Long): String =
        "${normalizeServerUrl(serverUrl)}/Sessions/${encode(sessionId)}/Playing/Seek?seekPositionTicks=$ticks"

    fun playNowUrl(serverUrl: String, sessionId: String, itemId: String): String =
        "${normalizeServerUrl(serverUrl)}/Sessions/${encode(sessionId)}/Playing?playCommand=PlayNow&itemIds=${encode(itemId)}&startPositionTicks=0"

    fun commandUrl(serverUrl: String, sessionId: String): String =
        "${normalizeServerUrl(serverUrl)}/Sessions/${encode(sessionId)}/Command"

    private fun parseNowPlaying(session: Json.Obj): JellyfinNowPlaying? {
        val item = session.field("NowPlayingItem") as? Json.Obj ?: return null
        val id = item.field("Id")?.textOrNull() ?: return null
        val play = session.field("PlayState") as? Json.Obj
        val streams = item.field("MediaStreams")?.arrayOrEmpty().orEmpty().mapNotNull { element ->
            val stream = element as? Json.Obj ?: return@mapNotNull null
            val index = stream.field("Index")?.intOrNull() ?: return@mapNotNull null
            JellyfinStream(
                index = index,
                type = stream.field("Type")?.textOrNull().orEmpty(),
                language = stream.field("Language")?.textOrNull(),
                title = stream.field("Title")?.textOrNull(),
                displayTitle = stream.field("DisplayTitle")?.textOrNull()
            )
        }
        val primaryTag = item.field("ImageTags")?.field("Primary")?.textOrNull()
        val seriesId = item.field("SeriesId")?.textOrNull()
        val seriesTag = item.field("SeriesPrimaryImageTag")?.textOrNull()
        val name = item.field("Name")?.textOrNull().orEmpty()
        val seriesName = item.field("SeriesName")?.textOrNull()
        return JellyfinNowPlaying(
            itemId = id,
            title = name,
            seriesName = seriesName,
            episodeName = if (seriesName.isNullOrBlank()) null else name,
            season = item.field("ParentIndexNumber")?.intOrNull(),
            episode = item.field("IndexNumber")?.intOrNull(),
            positionTicks = play?.field("PositionTicks")?.longOrNull(),
            runtimeTicks = item.field("RunTimeTicks")?.longOrNull(),
            paused = play?.field("IsPaused")?.boolOrNull() ?: false,
            subtitleIndex = play?.field("SubtitleStreamIndex")?.intOrNull(),
            streams = streams,
            imageItemId = if (primaryTag != null) id else seriesId ?: id,
            imageTag = primaryTag ?: seriesTag
        )
    }

    private fun streamMatches(stream: JellyfinStream, track: SubtitleTrack): Boolean {
        val language = stream.language?.trim()?.lowercase().orEmpty().replace('_', '-')
        val label = listOfNotNull(stream.displayTitle, stream.title).joinToString(" ").lowercase()
        return when (track) {
            SubtitleTrack.Japanese ->
                language == "jpn" || language == "ja" || language == "jp" ||
                    language.startsWith("ja-") || language.startsWith("jpn") ||
                    label.contains("日本語") || label.contains("japanese")
            SubtitleTrack.English ->
                language == "eng" || language == "en" ||
                    language.startsWith("en-") || language.startsWith("eng") ||
                    label.contains("英語") || label.contains("english")
            SubtitleTrack.Off -> false
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun Long.pad(): String = if (this < 10) "0$this" else toString()
}
