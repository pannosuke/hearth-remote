package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class SportsnetFeedTest {

    private val now = Instant.parse("2026-05-21T12:00:00Z").toEpochMilli()

    @Test
    fun `game highlights in the last 36 hours become watch urls`() {
        val parsed = SportsnetFeed.parse(fixture().byteInputStream())
        val shown = SportsnetFeed.select(parsed, now)
        assertEquals(listOf("game1aaaaaa", "hlitebbbbbb"), shown.map { it.videoId })
        assertEquals("https://www.youtube.com/watch?v=game1aaaaaa", shown[0].watchUrl)
        assertEquals(
            "NHL Game 1 Highlights | Canadiens vs. Hurricanes - May 20, 2026",
            shown[0].title
        )
        assertEquals("5/21 13:00", SportsnetFeed.formatPublished(shown[0].publishedMs))
        assertTrue(shown.all { it.watchUrl.startsWith("https://www.youtube.com/watch?v=") })
        assertTrue(shown.none { it.watchUrl.contains("shorts") })
    }

    @Test
    fun `a cached feed is filtered again when the window moves`() {
        val directory = Files.createTempDirectory("sportsnet").toFile()
        val guide = SportsnetGuide(directory, GuideFetch { etag ->
            GuideDownload("etag-1", fixture().byteInputStream())
        })
        guide.refreshOnce()
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        assertEquals(2, guide.visible(now).size)
        val later = now + SportsnetFeed.WINDOW_MS + 60_000
        assertTrue(guide.visible(later).isEmpty())
        val restored = SportsnetGuide(directory, GuideFetch { null })
        restored.restore()
        assertEquals(2, restored.visible(now).size)
        directory.deleteRecursively()
    }

    @Test
    fun `a failed first fetch is unavailable and a later failure keeps the list`() {
        val directory = Files.createTempDirectory("sportsnet-fail").toFile()
        val failing = SportsnetGuide(directory, GuideFetch { throw java.io.IOException("down") })
        failing.refreshOnce()
        assertEquals(GuideStatus.Unavailable, failing.state.value.status)
        val loaded = SportsnetGuide(directory, GuideFetch {
            GuideDownload(null, fixture().byteInputStream())
        })
        loaded.refreshOnce()
        val again = SportsnetGuide(directory, GuideFetch { throw java.io.IOException("down") })
        again.restore()
        again.refreshOnce()
        assertEquals(GuideStatus.Ready, again.state.value.status)
        assertEquals(2, again.visible(now).size)
        directory.deleteRecursively()
    }

    private fun fixture(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <yt:videoId>game1aaaaaa</yt:videoId>
            <title>NHL Game 1 Highlights | Canadiens vs. Hurricanes - May 20, 2026</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=game1aaaaaa"/>
            <published>2026-05-21T04:00:00+00:00</published>
          </entry>
          <entry>
            <yt:videoId>hlitebbbbbb</yt:videoId>
            <title>NHL Highlights | Kraken vs. Blues - February 26, 2026</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=hlitebbbbbb"/>
            <published>2026-05-20T20:00:00Z</published>
          </entry>
          <entry>
            <yt:videoId>shortccccccc</yt:videoId>
            <title>NHL Game 2 Highlights | a short that must be dropped</title>
            <link rel="alternate" href="https://www.youtube.com/shorts/shortccccccc"/>
            <published>2026-05-21T05:00:00Z</published>
          </entry>
          <entry>
            <yt:videoId>bestsddddddd</yt:videoId>
            <title>Nikita Kucherov's BEST Plays From The 2025-26 NHL Season</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=bestsddddddd"/>
            <published>2026-05-21T06:00:00Z</published>
          </entry>
          <entry>
            <yt:videoId>oldeeeeeeeee</yt:videoId>
            <title>NHL Game 9 Highlights | Old game</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=oldeeeeeeeee"/>
            <published>2026-05-19T00:00:00Z</published>
          </entry>
        </feed>
    """.trimIndent()
}
