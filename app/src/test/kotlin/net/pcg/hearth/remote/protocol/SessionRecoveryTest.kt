package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {

    @Test
    fun `an established drop reconnects quickly without raising an error`() {
        val first = planReconnect(wasUp = true, quietLeft = 0)
        assertTrue(first.quiet)
        assertEquals(400L, first.delayMs)
        assertEquals(3, first.quietLeft)

        val retry = planReconnect(wasUp = false, quietLeft = first.quietLeft)
        assertTrue(retry.quiet)
        assertEquals(400L, retry.delayMs)
        assertEquals(2, retry.quietLeft)

        val exhausted = planReconnect(wasUp = false, quietLeft = 0)
        assertFalse(exhausted.quiet)
        assertEquals(2_000L, exhausted.delayMs)
        assertEquals(0, exhausted.quietLeft)
    }
}
