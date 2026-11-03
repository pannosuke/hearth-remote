package net.pcg.hearth.remote.protocol

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

internal data class Programme(
    val title: String,
    val startMs: Long,
    val endMs: Long
)

internal data class NowAndNext(
    val current: Programme?,
    val next: Programme?
) {
    fun progress(nowMs: Long): Float? {
        val item = current ?: return null
        val span = item.endMs - item.startMs
        if (span <= 0L) return null
        return ((nowMs - item.startMs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * XMLTV subset used by the Osaka terrestrial guide.
 * Times keep the offset in the attribute (`20261103091500 +0000` is UTC).
 * Clocks shown to Eric are Asia/Tokyo.
 */
internal object XmltvGuide {
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(tokyo)
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(input: InputStream, keep: Set<String>): Map<String, List<Programme>> {
        val found = HashMap<String, MutableList<Programme>>()
        val handler = object : DefaultHandler() {
            var channel: String? = null
            var startMs: Long? = null
            var endMs: Long? = null
            var keepThis = false
            var titleOpen = false
            var titleDone = false
            val title = StringBuilder()

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                when (elementName(localName, qName)) {
                    "programme" -> {
                        val id = attributes?.getValue("channel")
                        keepThis = id != null && id in keep
                        channel = id
                        startMs = attributes?.getValue("start")?.let(::parseTime)
                        endMs = attributes?.getValue("stop")?.let(::parseTime)
                        title.setLength(0)
                        titleDone = false
                        titleOpen = false
                    }
                    "title" -> if (keepThis && !titleDone) titleOpen = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (titleOpen) title.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementName(localName, qName)) {
                    "title" -> if (titleOpen) {
                        titleOpen = false
                        titleDone = true
                    }
                    "programme" -> {
                        if (keepThis) {
                            val id = channel
                            val start = startMs
                            val end = endMs
                            val text = title.toString().replace(Regex("\\s+"), " ").trim()
                            if (id != null && start != null && end != null && end > start && text.isNotEmpty()) {
                                found.getOrPut(id) { mutableListOf() }.add(Programme(text, start, end))
                            }
                        }
                        keepThis = false
                        titleOpen = false
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
        factory.newSAXParser().parse(input, handler)
        return found.mapValues { (_, programmes) -> programmes.sortedBy { it.startMs } }
    }

    fun nowAndNext(programmes: List<Programme>, nowMs: Long): NowAndNext {
        val current = programmes
            .filter { nowMs >= it.startMs && nowMs < it.endMs }
            .maxByOrNull { it.startMs }
        val next = if (current != null) {
            programmes.filter { it.startMs >= current.endMs }.minByOrNull { it.startMs }
        } else {
            programmes.filter { it.startMs > nowMs }.minByOrNull { it.startMs }
        }
        return NowAndNext(current, next)
    }

    /**
     * `yyyyMMddHHmmss` plus an optional offset such as `+0000` or `+0900`.
     * A missing offset is UTC, matching XMLTV's rule.
     */
    fun parseTime(raw: String): Long? {
        val text = raw.trim()
        val digits = text.takeWhile { it.isDigit() }
        if (digits.length < 14) return null
        val local = try {
            LocalDateTime.parse(digits.substring(0, 14), stamp)
        } catch (_: Exception) {
            return null
        }
        val zone = offset(text.substring(digits.length).trim()) ?: return null
        return local.atOffset(zone).toInstant().toEpochMilli()
    }

    fun formatRange(startMs: Long, endMs: Long): String {
        return "${clock.format(Instant.ofEpochMilli(startMs))}–${clock.format(Instant.ofEpochMilli(endMs))}"
    }

    private fun offset(raw: String): ZoneOffset? {
        if (raw.isEmpty() || raw == "Z" || raw == "UTC" || raw == "GMT") return ZoneOffset.UTC
        val match = Regex("""([+-])(\d{2}):?(\d{2})""").matchEntire(raw) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        val minutes = match.groupValues[3].toIntOrNull() ?: return null
        if (hours > 18 || minutes > 59) return null
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
    }

    private fun elementName(localName: String?, qName: String?): String {
        if (!localName.isNullOrEmpty()) return localName
        return qName?.substringAfter(':').orEmpty()
    }
}
