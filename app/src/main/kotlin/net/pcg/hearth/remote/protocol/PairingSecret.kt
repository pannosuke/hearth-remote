package net.pcg.hearth.remote.protocol

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Pairing secret from androidtvremote2:
 *
 * SHA-256(client_modulus || client_exponent || server_modulus || server_exponent || code[2:6])
 *
 * Modulus bytes are uppercase hex of the integer with no leading zeros, padded to an
 * even number of digits. Exponent uses the same conversion after a single leading
 * "0", so 65537 ("10001") becomes 010001. The code is 6 hex characters; only the
 * last four (two bytes) enter the hash. The first byte of the hash must equal code[0:2].
 */
internal object PairingSecret {
    fun compute(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(modulusBytes(clientModulus))
        digest.update(exponentBytes(clientExponent))
        digest.update(modulusBytes(serverModulus))
        digest.update(exponentBytes(serverExponent))
        digest.update(codeBytes(code))
        return digest.digest()
    }

    fun firstByteMatches(hash: ByteArray, code: String): Boolean {
        return (hash[0].toInt() and 0xFF) == codePrefix(code)
    }

    fun modulusBytes(value: BigInteger): ByteArray = decodeHex(value.toString(16))

    /**
     * androidtvremote2 uses bytes.fromhex(f"0{exponent:X}"). For 65537 that is 010001.
     * An odd digit count is padded with one more leading zero so fromhex can decode it.
     */
    fun exponentBytes(value: BigInteger): ByteArray = decodeHex("0" + value.toString(16))

    fun codeBytes(code: String): ByteArray = decodeHex(normalizeCode(code).substring(2))

    fun codePrefix(code: String): Int = normalizeCode(code).substring(0, 2).toInt(16)

    fun normalizeCode(code: String): String {
        val normalized = code.trim().uppercase()
        require(normalized.length == 6 && normalized.all { it.isDigit() || it in 'A'..'F' }) {
            "Pairing code must be 6 hex characters"
        }
        return normalized
    }

    private fun decodeHex(hex: String): ByteArray {
        val padded = if (hex.length % 2 == 1) "0$hex" else hex
        return ByteArray(padded.length / 2) { index ->
            padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
