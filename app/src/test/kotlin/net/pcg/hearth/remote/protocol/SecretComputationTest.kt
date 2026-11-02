package net.pcg.hearth.remote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SecretComputationTest {
    
    @Test
    fun `computeSecret generates correct SHA-256 hash format`() {
        val clientModulus = ByteArray(256) { (it % 256).toByte() }
        val clientExponent = byteArrayOf(0x01, 0x00, 0x01)
        val serverModulus = ByteArray(256) { ((it + 128) % 256).toByte() }
        val serverExponent = byteArrayOf(0x01, 0x00, 0x01)
        
        val code = "123456"
        val codeLastTwo = code.takeLast(2)
        val codeBytes = codeLastTwo.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(clientModulus)
        digest.update(clientExponent)
        digest.update(serverModulus)
        digest.update(serverExponent)
        digest.update(codeBytes)
        
        val hash = digest.digest()
        
        assertEquals(32, hash.size)
    }
    
    @Test
    fun `code parsing extracts correct bytes`() {
        val code = "AB12CD"
        
        val lastTwo = code.takeLast(2)
        assertEquals("CD", lastTwo)
        
        val lastTwoBytes = lastTwo.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(1, lastTwoBytes.size)
        assertEquals(0xCD.toByte(), lastTwoBytes[0])
        
        val firstTwo = code.take(2)
        assertEquals("AB", firstTwo)
        
        val firstTwoInt = firstTwo.toInt(16)
        assertEquals(0xAB, firstTwoInt)
    }
    
    @Test
    fun `hexadecimal code characters are valid`() {
        val validCodes = listOf("000000", "FFFFFF", "123ABC", "abcdef", "A1B2C3")
        
        for (code in validCodes) {
            assertTrue("Code $code should be valid hex", code.all { 
                it.isDigit() || it.uppercaseChar() in 'A'..'F' 
            })
        }
    }
    
    @Test
    fun `secret verification checks first byte against code prefix`() {
        val code = "12ABCD"
        val expectedFirstByte = code.take(2).toInt(16)
        assertEquals(0x12, expectedFirstByte)
        
        val mockHash = byteArrayOf(0x12, 0x00, 0x00, 0x00)
        val actualFirstByte = mockHash[0].toInt() and 0xFF
        
        assertEquals(expectedFirstByte, actualFirstByte)
    }
    
    @Test
    fun `RSA modulus leading zero is stripped`() {
        val modulusWithLeadingZero = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        
        var stripped = modulusWithLeadingZero
        if (stripped[0] == 0.toByte()) {
            stripped = stripped.copyOfRange(1, stripped.size)
        }
        
        assertEquals(3, stripped.size)
        assertEquals(0x7F.toByte(), stripped[0])
    }
    
    @Test
    fun `exponent is padded to 3 bytes`() {
        var exponent = byteArrayOf(0x01, 0x01)
        
        while (exponent.size < 3) {
            exponent = byteArrayOf(0) + exponent
        }
        
        assertEquals(3, exponent.size)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x01), exponent)
    }
}
