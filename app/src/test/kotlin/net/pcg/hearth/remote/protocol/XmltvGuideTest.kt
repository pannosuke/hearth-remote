package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class XmltvGuideTest {

    @Test
    fun `osaka listings keep the offset and ignore other channels`() {
        val parsed = XmltvGuide.parse(FIXTURE.byteInputStream(), OsakaChannels.epgIds)
        assertEquals(setOf(OsakaChannels.find(10)?.epgId, OsakaChannels.find(4)?.epgId), parsed.keys)
        val now = XmltvGuide.parseTime("20261103091900 +0000")!!
        val ytv = XmltvGuide.nowAndNext(parsed.getValue("jcom_2_2088_32725"), now)
        assertEquals("ten.", ytv.current?.title)
        assertEquals("news zero", ytv.next?.title)
        assertEquals("18:15–19:00", XmltvGuide.formatRange(ytv.current!!.startMs, ytv.current.endMs))
        val progress = ytv.progress(now)!!
        assertTrue(progress > 0.08f && progress < 0.10f)
        val mbs = XmltvGuide.nowAndNext(parsed.getValue("jcom_2_2064_32722"), now)
        assertEquals("よんチャンＴＶ", mbs.current?.title)
        assertNull(parsed["jcom_2_1040_32738"])
    }

    @Test
    fun `entities and a japan offset parse`() {
        val xml = """
            <tv>
              <programme channel="jcom_2_2088_32725" start="20261103180000 +0900" stop="20261103190000 +0900">
                <title>A &amp; B</title>
              </programme>
            </tv>
        """.trimIndent()
        val parsed = XmltvGuide.parse(xml.byteInputStream(), OsakaChannels.epgIds)
        val show = parsed.getValue("jcom_2_2088_32725").single()
        assertEquals("A & B", show.title)
        assertEquals(XmltvGuide.parseTime("20261103090000 +0000"), show.startMs)
        assertEquals("18:00–19:00", XmltvGuide.formatRange(show.startMs, show.endMs))
    }

    @Test
    fun `a gap shows the next programme and not a made-up current one`() {
        val parsed = XmltvGuide.parse(FIXTURE.byteInputStream(), OsakaChannels.epgIds)
        val before = XmltvGuide.parseTime("20261103080000 +0000")!!
        val slot = XmltvGuide.nowAndNext(parsed.getValue("jcom_2_2088_32725"), before)
        assertNull(slot.current)
        assertEquals("ten.", slot.next?.title)
        assertNull(slot.progress(before))
    }

    @Test
    fun `button 1 is NHK Osaka and button 4 is MBS`() {
        assertEquals("jcom_2_40960_32112", OsakaChannels.find(1)?.epgId)
        assertEquals("jcom_2_2064_32722", OsakaChannels.find(4)?.epgId)
        assertTrue(OsakaChannels.find(1)?.epgId != OsakaChannels.find(4)?.epgId)
    }

    @Test
    fun `refresh caches the eight channels and a failure keeps them`() {
        val dir = Files.createTempDirectory("osaka-epg").toFile()
        var calls = 0
        val guide = OsakaGuide(dir, GuideFetch { etag ->
            calls += 1
            if (calls == 1) {
                assertNull(etag)
                GuideDownload("\"v1\"", FIXTURE.byteInputStream())
            } else {
                assertEquals("\"v1\"", etag)
                null
            }
        })
        guide.refreshOnce()
        val now = XmltvGuide.parseTime("20261103091900 +0000")!!
        assertEquals(GuideStatus.Ready, guide.state.value.status)
        assertEquals("ten.", guide.nowAndNext(10, now).current?.title)
        assertEquals("よんチャンＴＶ", guide.nowAndNext(4, now).current?.title)
        assertNull(guide.nowAndNext(12, now).current)
        guide.refreshOnce()
        assertEquals(2, calls)

        val restored = OsakaGuide(dir, GuideFetch { throw IOException("offline") })
        restored.restore()
        assertEquals("ten.", restored.nowAndNext(10, now).current?.title)
        restored.refreshOnce()
        assertEquals(GuideStatus.Ready, restored.state.value.status)
        assertEquals("ten.", restored.nowAndNext(10, now).current?.title)
        dir.deleteRecursively()
    }

    @Test
    fun `a failed fetch with an empty cache stays unavailable`() {
        val dir = Files.createTempDirectory("osaka-epg-empty").toFile()
        val guide = OsakaGuide(dir, GuideFetch { throw IOException("offline") })
        guide.refreshOnce()
        assertEquals(GuideStatus.Unavailable, guide.state.value.status)
        assertTrue(guide.state.value.byButton.isEmpty())
        dir.deleteRecursively()
    }

    private companion object {
        val FIXTURE = """
            <tv generator-info-name="https://github.com/dbghelp">
              <programme channel="jcom_2_2088_32725" start="20261103091500 +0000" stop="20261103100000 +0000">
                <title>ten.</title>
                <desc>ignored</desc>
              </programme>
              <programme channel="jcom_2_2088_32725" start="20261103100000 +0000" stop="20261103110000 +0000">
                <title>news zero</title>
              </programme>
              <programme channel="jcom_2_2064_32722" start="20261103084500 +0000" stop="20261103093000 +0000">
                <title>よんチャンＴＶ</title>
              </programme>
              <programme channel="jcom_2_1040_32738" start="20261103091500 +0000" stop="20261103100000 +0000">
                <title>Tokyo only</title>
              </programme>
            </tv>
        """.trimIndent()
    }
}
