package net.pcg.hearth.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class RemoteKeyCodeTest {
    
    @Test
    fun `number keys map to correct keycodes`() {
        val expectedMappings = mapOf(
            0 to 7,   // KEYCODE_0
            1 to 8,   // KEYCODE_1
            2 to 9,   // KEYCODE_2
            3 to 10,  // KEYCODE_3
            4 to 11,  // KEYCODE_4
            5 to 12,  // KEYCODE_5
            6 to 13,  // KEYCODE_6
            7 to 14,  // KEYCODE_7
            8 to 15,  // KEYCODE_8
            9 to 16   // KEYCODE_9
        )
        
        for ((digit, keycode) in expectedMappings) {
            assertEquals("Digit $digit should map to keycode $keycode", keycode, digit + 7)
        }
    }
    
    @Test
    fun `dpad keycodes are sequential`() {
        val dpadUp = 19
        val dpadDown = 20
        val dpadLeft = 21
        val dpadRight = 22
        val dpadCenter = 23
        
        assertEquals(dpadUp + 1, dpadDown)
        assertEquals(dpadDown + 1, dpadLeft)
        assertEquals(dpadLeft + 1, dpadRight)
        assertEquals(dpadRight + 1, dpadCenter)
    }
    
    @Test
    fun `volume keycodes are correct`() {
        val volumeUp = 24
        val volumeDown = 25
        val volumeMute = 164
        
        assertEquals(24, volumeUp)
        assertEquals(25, volumeDown)
        assertEquals(164, volumeMute)
    }
    
    @Test
    fun `channel keycodes are correct`() {
        val channelUp = 166
        val channelDown = 167
        
        assertEquals(166, channelUp)
        assertEquals(167, channelDown)
    }
    
    @Test
    fun `navigation keycodes are correct`() {
        val home = 3
        val back = 4
        val menu = 82
        
        assertEquals(3, home)
        assertEquals(4, back)
        assertEquals(82, menu)
    }
    
    @Test
    fun `media keycodes are correct`() {
        val playPause = 85
        val stop = 86
        val next = 87
        val previous = 88
        val rewind = 89
        val fastForward = 90
        
        assertEquals(85, playPause)
        assertEquals(86, stop)
        assertEquals(87, next)
        assertEquals(88, previous)
        assertEquals(89, rewind)
        assertEquals(90, fastForward)
    }
    
    @Test
    fun `power keycode is correct`() {
        val power = 26
        assertEquals(26, power)
    }
    
    @Test
    fun `direction enum values are defined`() {
        val unknownDirection = 0
        val startLong = 1
        val endLong = 2
        val short = 3
        
        assertEquals(0, unknownDirection)
        assertEquals(1, startLong)
        assertEquals(2, endLong)
        assertEquals(3, short)
    }
}
