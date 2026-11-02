package net.pcg.hearth.remote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VarintTest {
    
    @Test
    fun `writeVarint encodes single byte values correctly`() {
        val output = ByteArrayOutputStream()
        writeVarint(output, 0)
        assertArrayEquals(byteArrayOf(0), output.toByteArray())
        
        output.reset()
        writeVarint(output, 1)
        assertArrayEquals(byteArrayOf(1), output.toByteArray())
        
        output.reset()
        writeVarint(output, 127)
        assertArrayEquals(byteArrayOf(127), output.toByteArray())
    }
    
    @Test
    fun `writeVarint encodes two byte values correctly`() {
        val output = ByteArrayOutputStream()
        writeVarint(output, 128)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), output.toByteArray())
        
        output.reset()
        writeVarint(output, 300)
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), output.toByteArray())
    }
    
    @Test
    fun `writeVarint encodes larger values correctly`() {
        val output = ByteArrayOutputStream()
        writeVarint(output, 16384)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), output.toByteArray())
    }
    
    @Test
    fun `readVarint decodes single byte values correctly`() {
        assertEquals(0, readVarint(ByteArrayInputStream(byteArrayOf(0))))
        assertEquals(1, readVarint(ByteArrayInputStream(byteArrayOf(1))))
        assertEquals(127, readVarint(ByteArrayInputStream(byteArrayOf(127))))
    }
    
    @Test
    fun `readVarint decodes two byte values correctly`() {
        assertEquals(128, readVarint(ByteArrayInputStream(byteArrayOf(0x80.toByte(), 0x01))))
        assertEquals(300, readVarint(ByteArrayInputStream(byteArrayOf(0xAC.toByte(), 0x02))))
    }
    
    @Test
    fun `readVarint decodes larger values correctly`() {
        assertEquals(16384, readVarint(ByteArrayInputStream(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01))))
    }
    
    @Test
    fun `roundtrip encoding and decoding works`() {
        val testValues = listOf(0, 1, 127, 128, 255, 256, 16383, 16384, 2097151, 268435455)
        
        for (value in testValues) {
            val output = ByteArrayOutputStream()
            writeVarint(output, value)
            val decoded = readVarint(ByteArrayInputStream(output.toByteArray()))
            assertEquals("Failed for value $value", value, decoded)
        }
    }
    
    @Test
    fun `readVarint returns -1 on empty stream`() {
        assertEquals(-1, readVarint(ByteArrayInputStream(byteArrayOf())))
    }
    
    private fun writeVarint(output: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
    }
    
    private fun readVarint(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) return -1
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 35) throw RuntimeException("Varint too long")
        }
        return result
    }
}
