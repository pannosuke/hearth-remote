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
        assertNull(OsakaChannels.channelReading("Service Busy. Connection Closed."))
        assertNull(OsakaChannels.stationName("Service Busy. Connection Closed."))
        assertNull(OsakaChannels.channelReading("ERR"))
    }

    @Test
    fun `subtitle macro defaults to one key and can be a sequence`() {
        assertEquals(listOf(MacroStep("IRCO", "0267")), SubtitleMacro.parse(null))
        assertEquals(listOf(MacroStep("IRCO", "0267")), SubtitleMacro.parse(""))
        assertEquals(listOf(MacroStep("IRCO", "0267")), SubtitleMacro.parse("IRCO0267"))
        assertEquals(
            listOf(
                MacroStep("IRCO", "0267"),
                MacroStep("WAIT", "400"),
                MacroStep("IRCO", "01E4")
            ),
            SubtitleMacro.parse("IRCO 0267\nWAIT 400\nIRCO 01E4")
        )
        assertEquals(listOf(MacroStep("IRCO", "0267")), SubtitleMacro.parse("not a macro"))
    }

    @Test
    fun `mute 1 is on and 2 is off`() {
        assertEquals(true, TunerStatus(mute = 1).muted)
        assertEquals(false, TunerStatus(mute = 2).muted)
        assertEquals(null, TunerStatus(mute = null).muted)
    }

    private fun ascii(bytes: ByteArray) = bytes.toString(Charsets.US_ASCII)
}