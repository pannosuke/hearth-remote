package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.PoloProto.OuterMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden bytes produced by protoc --encode of the androidtvremote2 polo.proto
 * (protocol_version 2, status STATUS_OK, inner message on its own field).
 */
class PairingMessageEncodingTest {

    @Test
    fun `pairing request matches androidtvremote2 bytes`() {
        val message = PairingMessages.pairingRequest("Hearth Remote")
        assertArrayEquals(PAIRING_REQUEST, message.toByteArray())
        assertArrayEquals(frame(PAIRING_REQUEST), PairingMessages.frame(message))

        val parsed = OuterMessage.parseFrom(PAIRING_REQUEST)
        assertEquals(2, parsed.protocolVersion)
        assertEquals(OuterMessage.Status.STATUS_OK, parsed.status)
        assertTrue(parsed.hasPairingRequest())
        assertEquals("atvremote", parsed.pairingRequest.serviceName)
        assertEquals("Hearth Remote", parsed.pairingRequest.clientName)
        assertFalse(parsed.hasOptions())
        assertFalse(parsed.hasSecret())
    }

    @Test
    fun `options message matches androidtvremote2 bytes`() {
        val message = PairingMessages.options()
        assertArrayEquals(OPTIONS, message.toByteArray())
        assertArrayEquals(frame(OPTIONS), PairingMessages.frame(message))

        val parsed = OuterMessage.parseFrom(OPTIONS)
        assertEquals(2, parsed.protocolVersion)
        assertEquals(OuterMessage.Status.STATUS_OK, parsed.status)
        assertEquals(1, parsed.options.inputEncodingsCount)
        assertEquals(3, parsed.options.getInputEncodings(0).type.number)
        assertEquals(6, parsed.options.getInputEncodings(0).symbolLength)
        assertEquals(1, parsed.options.preferredRole.number)
    }

    @Test
    fun `configuration message matches androidtvremote2 bytes`() {
        val message = PairingMessages.configuration()
        assertArrayEquals(CONFIGURATION, message.toByteArray())
        assertArrayEquals(frame(CONFIGURATION), PairingMessages.frame(message))

        val parsed = OuterMessage.parseFrom(CONFIGURATION)
        assertEquals(3, parsed.configuration.encoding.type.number)
        assertEquals(6, parsed.configuration.encoding.symbolLength)
        assertEquals(1, parsed.configuration.clientRole.number)
    }

    @Test
    fun `secret message matches androidtvremote2 bytes`() {
        val secret = ByteArray(32) { 0x11 }
        val message = PairingMessages.secret(secret)
        assertArrayEquals(SECRET, message.toByteArray())
        assertArrayEquals(frame(SECRET), PairingMessages.frame(message))
        assertArrayEquals(secret, OuterMessage.parseFrom(SECRET).secret.secret.toByteArray())
    }

    @Test
    fun `framed pairing request is one buffer with the varint prefix`() {
        val framed = PairingMessages.frame(PairingMessages.pairingRequest())
        assertEquals(PAIRING_REQUEST.size, framed[0].toInt() and 0xFF)
        assertArrayEquals(PAIRING_REQUEST, framed.copyOfRange(1, framed.size))
    }

    private fun frame(payload: ByteArray): ByteArray {
        return byteArrayOf(payload.size.toByte()) + payload
    }

    companion object {
        // protocol_version: 2 status: STATUS_OK pairing_request { service_name: "atvremote" client_name: "Hearth Remote" }
        private val PAIRING_REQUEST = byteArrayOf(
            0x08, 0x02, 0x10, 0xC8.toByte(), 0x01, 0x52, 0x1A,
            0x0A, 0x09, 0x61, 0x74, 0x76, 0x72, 0x65, 0x6D, 0x6F, 0x74, 0x65,
            0x12, 0x0D, 0x48, 0x65, 0x61, 0x72, 0x74, 0x68, 0x20, 0x52, 0x65, 0x6D, 0x6F, 0x74, 0x65
        )

        // options { input_encodings { type: ENCODING_TYPE_HEXADECIMAL symbol_length: 6 } preferred_role: ROLE_TYPE_INPUT }
        private val OPTIONS = byteArrayOf(
            0x08, 0x02, 0x10, 0xC8.toByte(), 0x01, 0xA2.toByte(), 0x01, 0x08,
            0x0A, 0x04, 0x08, 0x03, 0x10, 0x06, 0x18, 0x01
        )

        // configuration { encoding { type: ENCODING_TYPE_HEXADECIMAL symbol_length: 6 } client_role: ROLE_TYPE_INPUT }
        private val CONFIGURATION = byteArrayOf(
            0x08, 0x02, 0x10, 0xC8.toByte(), 0x01, 0xF2.toByte(), 0x01, 0x08,
            0x0A, 0x04, 0x08, 0x03, 0x10, 0x06, 0x10, 0x01
        )

        // secret { secret: 32 bytes of 0x11 }
        private val SECRET = byteArrayOf(
            0x08, 0x02, 0x10, 0xC8.toByte(), 0x01, 0xC2.toByte(), 0x02, 0x22, 0x0A, 0x20,
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11
        )
    }
}
