package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoJpegTest {

    @Test
    fun `the long edge is sampled in powers of two`() {
        assertEquals(1, sampleSize(1600, 900))
        assertEquals(1, sampleSize(3199, 2000))
        assertEquals(2, sampleSize(3200, 2000))
        assertEquals(4, sampleSize(6400, 4800))
        assertEquals(1, sampleSize(0, 100))
    }

    @Test
    fun `the fitted size never grows and caps the long edge`() {
        assertEquals(1600 to 900, fittedSize(1600, 900))
        assertEquals(1600 to 900, fittedSize(3200, 1800))
        assertEquals(80 to 1600, fittedSize(100, 2000))
        assertEquals(450 to 1600, fittedSize(900, 3200))
        assertEquals(0 to 0, fittedSize(0, 40))
    }
}
