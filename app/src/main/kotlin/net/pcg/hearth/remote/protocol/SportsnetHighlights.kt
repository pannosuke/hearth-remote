package net.pcg.hearth.remote.protocol

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * One Sportsnet NHL game-highlight video from a public YouTube Atom feed.
 * [watchUrl] is the https link the TV's YouTube app already accepts.
 */
internal data class HighlightVideo(
    val videoId: String,
    val title: String,
    val publishedMs: Long
) {
    val watchUrl: String = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Sportsnet game highlights from the public channel Atom feed.
 * The channel feed is the primary source. The old highlights playlist is
 * merged in when it still returns 200, and ignored when it does not.
 * Neither feed needs an API key. Each lists the newest 15 entries, including
 * Shorts. Only ordinary watch URLs whose titles are game highlights are kept.
 * Clocks shown to Eric are Asia/Tokyo. The window is the last 48 hours, so a
 * late game in the east is still listed the next evening in Osaka.
 */
internal object SportsnetFeed {
    const val CHANNEL_ID = "UCVhibwHk4WKw4leUt6JfRLg"
    const val CHANNEL_FEED_URL =
        "https://www.youtube.com/feeds/videos.xml?channel_id=$CHANNEL_ID"
    const val PLAYLIST_ID = "PLo12SYwt93SQP81ntu8rz9QcAHTNQaFI3"
    const val PLAYLIST_FEED_URL =
        "https://www.youtube.com/feeds/videos.xml?playlist_id=$PLAYLIST_ID"
    const val WINDOW_MS = 48L * 60L * 60L * 1000L

    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(tokyo)
    private val gameHighlight = Regex(
        """^NHL\s+(?:Game\s+\d+\s+)?Highlights\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(input: InputStream): List<HighlightVideo> {
        val found = mutableListOf<HighlightVideo>()
        val handler = object : DefaultHandler() {
            var inEntry = false
            var videoId: String? = null
            var publishedMs: Long? = null
            var shortLink = false
            var titleOpen = false
            var titleDone = false
            var publishedOpen = false
            var idOpen = false
            val title = StringBuilder()
            val published = StringBuilder()
            val idText = StringBuilder()

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                when (elementName(localName, qName)) {
                    "entry" -> {
                        inEntry = true
                        videoId = null
                        publishedMs = null
                        shortLink = false
                        titleOpen = false
                        titleDone = false
                        publishedOpen = false
                        idOpen = false
                        title.setLength(0)
                        published.setLength(0)
                        idText.setLength(0)
                    }
                    "title" -> if (inEntry && !titleDone) titleOpen = true
                    "published" -> if (inEntry && publishedMs == null) publishedOpen = true
                    "videoId" -> if (inEntry && videoId == null) idOpen = true
                    "link" -> if (inEntry) {
                        val href = attributes?.getValue("href").orEmpty()
                        if (href.contains("/shorts/")) shortLink = true
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                when {
                    titleOpen -> title.append(ch, start, length)
                    publishedOpen -> published.append(ch, start, length)
                    idOpen -> idText.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementName(localName, qName)) {
                    "title" -> if (titleOpen) {
                        titleOpen = false
                        titleDone = true
                    }
                    "published" -> if (publishedOpen) {
                        publishedOpen = false
                        publishedMs = parsePublished(published.toString())
                    }
                    "videoId" -> if (idOpen) {
                        idOpen = false
                        val text = idText.toString().trim()
                        if (text.isNotEmpty()) videoId = text
                    }
                    "entry" -> {
                        val id = videoId?.trim().orEmpty()
                        val whenMs = publishedMs
                        val text = title.toString().replace(Regex("\\s+"), " ").trim()
                        if (!shortLink && whenMs != null && id.isNotEmpty() && isGameHighlight(text)) {
                            found.add(HighlightVideo(id, text, whenMs))
                        }
                        inEntry = false
                    }
                }
            }
        }
        val factory = SAXParserFactory.newInstance()
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        } catch (_: Exception) {
        }
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
        }
        factory.isNamespaceAware = false
        factory.newSAXParser().parse(input, handler)
        return found
    }

    fun isGameHighlight(title: String): Boolean = gameHighlight.containsMatchIn(title.trim())

    /**
     * Channel entries win when the same video id is also on the playlist.
     * Order is otherwise left as given; [select] sorts by publish time.
     */
    fun merge(primary: List<HighlightVideo>, extra: List<HighlightVideo>): List<HighlightVideo> {
        if (extra.isEmpty()) return primary
        if (primary.isEmpty()) return extra
        val seen = HashSet<String>(primary.size + extra.size)
        val merged = ArrayList<HighlightVideo>(primary.size + extra.size)
        for (video in primary) {
            if (seen.add(video.videoId)) merged.add(video)
        }
        for (video in extra) {
            if (seen.add(video.videoId)) merged.add(video)
        }
        return merged
    }

    fun select(videos: List<HighlightVideo>, nowMs: Long): List<HighlightVideo> {
        val start = nowMs - WINDOW_MS
        return videos
            .filter { it.publishedMs in start..nowMs }
            .sortedByDescending { it.publishedMs }
    }

    fun formatPublished(publishedMs: Long): String = clock.format(Instant.ofEpochMilli(publishedMs))

    fun parsePublished(raw: String): Long? {
        return try {
            Instant.parse(raw.trim()).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun elementName(localName: String?, qName: String?): String {
        if (!localName.isNullOrEmpty()) return localName
        return qName?.substringAfter(':').orEmpty()
    }
}
