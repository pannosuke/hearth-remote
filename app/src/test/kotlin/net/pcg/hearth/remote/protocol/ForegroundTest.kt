package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTest {

    @Test
    fun `sharp tuner package is terrestrial even without a TB channel`() {
        assertTrue(Foreground.isTerrestrial(Foreground.LIVE_TV_PACKAGE, null))
        assertTrue(Foreground.isTerrestrial(Foreground.LIVE_TV_PACKAGE, "BS1"))
    }

    @Test
    fun `jellyfin package selects the jellyfin remote`() {
        assertTrue(Foreground.isJellyfin(Foreground.JELLYFIN_PACKAGE))
        assertTrue(Foreground.isJellyfin("org.jellyfin.androidtv"))
        assertFalse(Foreground.isJellyfin("com.google.android.youtube.tv"))
        assertFalse(Foreground.isJellyfin(null))
    }

    @Test
    fun `a known app stays generic even when the tuner still reports TB`() {
        assertFalse(Foreground.isTerrestrial("com.google.android.youtube.tv", "TB10"))
        assertFalse(Foreground.isTerrestrial("org.jellyfin.androidtv", "TB10"))
    }

    @Test
    fun `TB prefix is the fallback only when the package is missing`() {
        assertTrue(Foreground.isTerrestrial(null, "TB10"))
        assertTrue(Foreground.isTerrestrial("", "TB8"))
        assertFalse(Foreground.isTerrestrial(null, "BS141"))
        assertFalse(Foreground.isTerrestrial(null, null))
    }

    @Test
    fun `osaka cards use the requested order and CHRC command`() {
        assertEquals(
            listOf(10, 8, 7, 6, 1, 2, 4, 12, 3),
            OsakaChannels.buttons.map { it.button }
        )
        assertEquals(
            listOf("YTV", "KTV", "TVO", "ABC", "NHK", "NHK E", "MBS", "12", "SUN"),
            OsakaChannels.buttons.map { it.name }
        )
        assertEquals("10", OsakaChannels.chrcCommand(10))
        assertEquals("8", OsakaChannels.chrcCommand(8))
        assertEquals("12", OsakaChannels.chrcCommand(12))
        assertEquals("CHRC10  \r", ascii(SharpIpCommands.encode("CHRC", OsakaChannels.chrcCommand(10))))
        assertEquals("CHRC8   \r", ascii(SharpIpCommands.encode("CHRC", OsakaChannels.chrcCommand(8))))
        assertEquals("IRCO0267\r", ascii(SharpIpCommands.encode("IRCO", SharpIpCommands.SUBTITLE)))
    }

    @Test
    fun `TB10 is button 10 and the busy sentence is not a title`() {
        assertEquals(10, OsakaChannels.buttonNumber("TB10"))
        assertEquals("YTV", OsakaChannels.stationName("TB10"))
        assertEquals("TB10", OsakaChannels.channelReading("TB10"))
        assertEquals(8, OsakaChannels.buttonNumber("TB8"))
        assertNull(OsakaChannels.buttonNumber("BS1"))
        assertEquals("BS1", OsakaChannels.channelReading("BS1"))
        assertEquals("12", OsakaChannels.find(12)?.name)
        assertNull(OsakaChannels.find(12)?.epgId)
        assertEquals("jcom_2_2088_32725", OsakaChannels.find(10)?.epgId)
        assertEquals("jcom_2_40960_32112", OsakaChannels.find(1)?.epgId)
        assertEquals("jcom_2_2064_32722", OsakaChannels.find(4)?.epgId)
        assertEquals(8, OsakaChannels.epgIds.size)
        assertNull(OsakaChannels.channelReading("Service Busy. Connection Closed."))
        assertNull(OsakaChannels.stationName("Service Busy. Connection Closed."))
        assertNull(OsakaChannels.channelReading("ERR"))
    }

    @Test
    fun `subtitle on and off walk the display style list`() {
        val on = listOf(
            MacroStep("IRCO", "0267"),
            MacroStep("WAIT", "900"),
            MacroStep("IRCO", "0152"),
            MacroStep("WAIT", "600"),
            MacroStep("IRCO", "0157"),
            MacroStep("WAIT", "250"),
            MacroStep("IRCO", "0157"),
            MacroStep("WAIT", "250"),
            MacroStep("IRCO", "0157"),
            MacroStep("WAIT", "250"),
            MacroStep("IRCO", "0152"),
            MacroStep("WAIT", "500"),
            MacroStep("IRCO", "01E4")
        )
        val off = on.map { step ->
            if (step.command == "IRCO" && step.param == "0157") MacroStep("IRCO", "0120") else step
        }
        assertEquals(on, SubtitleMacro.parse(null))
        assertEquals(on, SubtitleMacro.parse(SubtitleMacro.ON))
        assertEquals(off, SubtitleMacro.parse(SubtitleMacro.OFF, SubtitleMacro.OFF))
        assertEquals(3, on.count { it.param == "0157" })
        assertEquals(3, off.count { it.param == "0120" })
        assertEquals(
            listOf(
                MacroStep("IRCO", "0267"),
                MacroStep("WAIT", "400"),
                MacroStep("IRCO", "01E4")
            ),
            SubtitleMacro.parse("IRCO 0267\nWAIT 400\nIRCO 01E4")
        )
        assertEquals(on, SubtitleMacro.parse("not a macro"))
        val v028On = "IRCO 0267\nWAIT 900\nIRCO 0152\nWAIT 600\nIRCO 0120\nIRCO 0152\nWAIT 500\nIRCO 01E4"
        val v028Off = "IRCO 0267\nWAIT 900\nIRCO 0152\nWAIT 600\nIRCO 0157\nIRCO 0152\nWAIT 500\nIRCO 01E4"
        assertEquals(SubtitleMacro.ON, SubtitleMacro.replacementFor(v028On, on = true))
        assertEquals(SubtitleMacro.OFF, SubtitleMacro.replacementFor("IRCO0120\n".let { v028Off.replace(" ", "") }, on = false))
        assertEquals(SubtitleMacro.ON, SubtitleMacro.replacementFor("IRCO 0267", on = true))
        assertEquals(null, SubtitleMacro.replacementFor("IRCO 0267\nWAIT 400\nIRCO 01E4", on = true))
        assertEquals(null, SubtitleMacro.replacementFor(SubtitleMacro.ON, on = true))
        assertEquals(null, SubtitleMacro.replacementFor(SubtitleMacro.OFF, on = false))
    }

    @Test
    fun `mute 1 is on and 2 is off`() {
        assertEquals(true, TunerStatus(mute = 1).muted)
        assertEquals(false, TunerStatus(mute = 2).muted)
        assertEquals(null, TunerStatus(mute = null).muted)
    }

    private fun ascii(bytes: ByteArray) = bytes.toString(Charsets.US_ASCII)
}