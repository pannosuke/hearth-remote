package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Golden hashes from androidtvremote2's Python:
 *
 *   h.update(bytes.fromhex(f"{modulus:X}"))          # odd length padded with 0
 *   h.update(bytes.fromhex(f"0{exponent:X}"))
 *   h.update(bytes.fromhex(code[2:]))
 *   digest[0] == int(code[0:2], 16)
 */
class SecretComputationTest {

    @Test
    fun `exponent 65537 is encoded as 010001`() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x01),
            PairingSecret.exponentBytes(BigInteger.valueOf(65537))
        )
    }

    @Test
    fun `odd-length modulus hex is padded and has no leading zero byte`() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0xBC.toByte()),
            PairingSecret.modulusBytes(BigInteger("ABC", 16))
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x01),
            PairingSecret.modulusBytes(BigInteger("10001", 16))
        )
    }

    @Test
    fun `high-bit modulus drops the BigInteger sign byte`() {
        val modulus = BigInteger(1, byteArrayOf(0xFF.toByte(), 0x80.toByte()))
        val encoded = PairingSecret.modulusBytes(modulus)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x80.toByte()), encoded)
        assertEquals(3, modulus.toByteArray().size)
    }

    @Test
    fun `code bytes are the last four hex characters`() {
        assertArrayEquals(
            byteArrayOf(0x12, 0xCD.toByte()),
            PairingSecret.codeBytes("ab12cd")
        )
        assertEquals(0xAB, PairingSecret.codePrefix("ab12cd"))
    }

    @Test
    fun `small vector matches androidtvremote2 hash`() {
        val hash = PairingSecret.compute(
            BigInteger("ABC", 16),
            BigInteger.valueOf(65537),
            BigInteger("10001", 16),
            BigInteger.valueOf(65537),
            "0f00ff",
        )
        assertEquals(
            "f13296dc13ae1295d8794b80405c153254b35994d39757ac29088d12098eed99",
            hash.toHex()
        )
        assertFalse(PairingSecret.firstByteMatches(hash, "0f00ff"))
    }

    @Test
    fun `lowercase code matches the uppercase golden hash`() {
        val hash = PairingSecret.compute(
            highBitModulus(0xFF, 7),
            BigInteger.valueOf(65537),
            highBitModulus(0x80, 3),
            BigInteger.valueOf(65537),
            "ab12cd",
        )
        assertEquals(
            "1c30b4bd0e1ed7e6389af9c8d4b4e7acca090d090ed7ea0111e7a6ff9ead228b",
            hash.toHex()
        )
        assertArrayEquals(
            hash,
            PairingSecret.compute(
                highBitModulus(0xFF, 7),
                BigInteger.valueOf(65537),
                highBitModulus(0x80, 3),
                BigInteger.valueOf(65537),
                "AB12CD",
            )
        )
    }

    @Test
    fun `first-byte check passes for a code built the way the TV does`() {
        val hash = PairingSecret.compute(
            BigInteger("ABC", 16),
            BigInteger.valueOf(65537),
            BigInteger("10001", 16),
            BigInteger.valueOf(65537),
            "3612cd",
        )
        assertEquals(
            "36c345c7c15f56b896f88fb28846a75ea060d6f32991382d000ab8390a5760e1",
            hash.toHex()
        )
        assertTrue(PairingSecret.firstByteMatches(hash, "3612CD"))
    }

    private fun highBitModulus(lead: Int, step: Int): BigInteger {
        val bytes = ByteArray(256)
        bytes[0] = lead.toByte()
        for (i in 0 until 255) {
            bytes[i + 1] = ((i * step) % 256).toByte()
        }
        return BigInteger(1, bytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
