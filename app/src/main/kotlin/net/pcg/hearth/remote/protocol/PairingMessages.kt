package net.pcg.hearth.remote.protocol

import com.google.protobuf.ByteString
import net.pcg.hearth.remote.proto.PoloProto.Configuration
import net.pcg.hearth.remote.proto.PoloProto.Options
import net.pcg.hearth.remote.proto.PoloProto.OuterMessage
import net.pcg.hearth.remote.proto.PoloProto.PairingRequest
import net.pcg.hearth.remote.proto.PoloProto.Secret
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Builds Android TV Remote v2 pairing messages.
 *
 * Wire format matches tronikos/androidtvremote2: protocol_version 2, status
 * STATUS_OK (200), and the inner message on its own field (pairing_request is
 * field 10, not a type enum plus a bytes payload).
 */
internal object PairingMessages {
    const val SERVICE_NAME = "atvremote"
    const val CLIENT_NAME = "Hearth Remote"
    const val PROTOCOL_VERSION = 2
    const val SYMBOL_LENGTH = 6

    fun pairingRequest(clientName: String = CLIENT_NAME): OuterMessage {
        val request = PairingRequest.newBuilder()
            .setServiceName(SERVICE_NAME)
            .setClientName(clientName)
            .build()
        return newOuter().setPairingRequest(request).build()
    }

    fun options(): OuterMessage {
        val encoding = hexadecimalEncoding()
        val options = Options.newBuilder()
            .addInputEncodings(encoding)
            .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
            .build()
        return newOuter().setOptions(options).build()
    }

    fun configuration(): OuterMessage {
        val config = Configuration.newBuilder()
            .setEncoding(hexadecimalEncoding())
            .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
            .build()
        return newOuter().setConfiguration(config).build()
    }

    fun secret(secretBytes: ByteArray): OuterMessage {
        val secret = Secret.newBuilder()
            .setSecret(ByteString.copyFrom(secretBytes))
            .build()
        return newOuter().setSecret(secret).build()
    }

    /**
     * Length-delimited frame. The varint and the message are one buffer so a
     * single TLS write carries both. Android's SSLSocket sends each write()
     * as its own record.
     */
    fun frame(message: OuterMessage): ByteArray {
        val payload = message.toByteArray()
        val framed = ByteArrayOutputStream(payload.size + 5)
        writeVarint(framed, payload.size)
        framed.write(payload)
        return framed.toByteArray()
    }

    private fun newOuter(): OuterMessage.Builder {
        return OuterMessage.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setStatus(OuterMessage.Status.STATUS_OK)
    }

    private fun hexadecimalEncoding(): Options.Encoding {
        return Options.Encoding.newBuilder()
            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(SYMBOL_LENGTH)
            .build()
    }

    private fun writeVarint(output: OutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
    }
}
