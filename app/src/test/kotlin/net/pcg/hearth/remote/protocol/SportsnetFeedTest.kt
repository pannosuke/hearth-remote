package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

class SportsnetFeedTest {

    private val now = Instant.parse("2026-05-21T12:00:00Z").toEpochMilli()

    @Test
    fun `game highlights in the last 48 hours become watch urls`() {
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
    fun `a highlight inside 48 hours and outside 36 hours is kept`() {
        val parsed = SportsnetFeed.parse(windowFixture().byteInputStream())
        val shown = SportsnetFeed.select(parsed, now).map { it.videoId }
        assertEquals(listOf("inside48hhhh", "exact48hhhhh"), shown)
    }

    @Test
    fun `channel and playlist feeds merge and the channel copy wins`() {
        val directory = Files.createTempDirectory("sportsnet-merge").toFile()
        val guide = SportsnetGuide(
            directory,
            channel = GuideFetch { GuideDownload(null, fixture().byteInputStream()) },
            playlist = GuideFetch { GuideDownload(null, playlistFixture().byteInputStream()) }
        )
        guide.refreshOnce()
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        val shown = guide.visible(now)
        assertEquals(listOf("game1aaaaaa", "extraffffff", "hlitebbbbbb"), shown.map { it.videoId })
        assertEquals(
            "NHL Game 1 Highlights | Canadiens vs. Hurricanes - May 20, 2026",
            shown[0].title
        )
        directory.deleteRecursively()
    }

    @Test
    fun `a failed playlist is ignored when the channel feed succeeds`() {
        val directory = Files.createTempDirectory("sportsnet-playlist-down").toFile()
        val guide = SportsnetGuide(
            directory,
            channel = GuideFetch { GuideDownload(null, fixture().byteInputStream()) },
            playlist = GuideFetch { throw IOException("HTTP 404") }
        )
        guide.refreshOnce()
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        assertEquals(listOf("game1aaaaaa", "hlitebbbbbb"), guide.visible(now).map { it.videoId })
        directory.deleteRecursively()
    }

    @Test
    fun `a failed channel still uses a playlist that returned a body`() {
        val directory = Files.createTempDirectory("sportsnet-channel-down").toFile()
        val guide = SportsnetGuide(
            directory,
            channel = GuideFetch { throw IOException("HTTP 404") },
            playlist = GuideFetch { GuideDownload(null, fixture().byteInputStream()) }
        )
        guide.refreshOnce()
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        assertEquals(2, guide.visible(now).size)
        directory.deleteRecursively()
    }

    @Test
    fun `an empty channel feed stays the empty state when the playlist fails`() {
        val directory = Files.createTempDirectory("sportsnet-empty").toFile()
        val guide = SportsnetGuide(
            directory,
            channel = GuideFetch { GuideDownload(null, "<feed></feed>".byteInputStream()) },
            playlist = GuideFetch { throw IOException("HTTP 404") }
        )
        guide.refreshOnce()
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        assertTrue(guide.state.value.videos.isEmpty())
        assertTrue(guide.visible(now).isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun `a cached feed is filtered again when the window moves`() {
        val directory = Files.createTempDirectory("sportsnet").toFile()
        val guide = SportsnetGuide(
            directory,
            channel = GuideFetch { GuideDownload("etag-1", fixture().byteInputStream()) },
            playlist = GuideFetch { throw IOException("HTTP 404") }
        )
        guide.refreshOnce()
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        assertEquals(2, guide.visible(now).size)
        val later = now + SportsnetFeed.WINDOW_MS + 60_000
        assertTrue(guide.visible(later).isEmpty())
        val restored = SportsnetGuide(
            directory,
            channel = GuideFetch { null },
            playlist = GuideFetch { throw IOException("HTTP 404") }
        )
        restored.restore()
        assertEquals(2, restored.visible(now).size)
        directory.deleteRecursively()
    }

    @Test
    fun `a failed first fetch is unavailable and a later failure keeps the list`() {
        val directory = Files.createTempDirectory("sportsnet-fail").toFile()
        val failing = SportsnetGuide(
            directory,
            channel = GuideFetch { throw IOException("HTTP 404") },
            playlist = GuideFetch { throw IOException("HTTP 404") }
        )
        failing.refreshOnce()
        assertEquals(GuideStatus.Unavailable, failing.state.value.status)
        assertTrue(failing.state.value.videos.isEmpty())
        val loaded = SportsnetGuide(
            directory,
            channel = GuideFetch { GuideDownload(null, fixture().byteInputStream()) },
            playlist = GuideFetch { throw IOException("HTTP 404") }
        )
        loaded.refreshOnce()
        val again = SportsnetGuide(
            directory,
            channel = GuideFetch { throw IOException("down") },
            playlist = GuideFetch { throw IOException("down") }
        )
        again.restore()
        again.refreshOnce()
        assertEquals(GuideStatus.Ready, again.state.value.status)
        assertEquals(2, again.visible(now).size)
        directory.deleteRecursively()
    }

    private fun windowFixture(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed>
          <entry>
            <yt:videoId>inside48hhhh</yt:videoId>
            <title>NHL Game 3 Highlights | Inside 48 hours</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=inside48hhhh"/>
            <published>2026-05-19T13:00:00Z</published>
          </entry>
          <entry>
            <yt:videoId>exact48hhhhh</yt:videoId>
            <title>NHL Highlights | Exactly 48 hours</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=exact48hhhhh"/>
            <published>2026-05-19T12:00:00Z</published>
          </entry>
          <entry>
            <yt:videoId>outside48hhh</yt:videoId>
            <title>NHL Game 8 Highlights | Outside 48 hours</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=outside48hhh"/>
            <published>2026-05-19T11:59:59Z</published>
          </entry>
        </feed>
    """.trimIndent()

    private fun playlistFixture(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed>
          <entry>
            <yt:videoId>game1aaaaaa</yt:videoId>
            <title>NHL Game 1 Highlights | playlist copy</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=game1aaaaaa"/>
            <published>2026-05-21T04:00:00Z</published>
          </entry>
          <entry>
            <yt:videoId>extraffffff</yt:videoId>
            <title>NHL Game 4 Highlights | Playlist only</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=extraffffff"/>
            <published>2026-05-21T01:00:00Z</published>
          </entry>
        </feed>
    """.trimIndent()

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
