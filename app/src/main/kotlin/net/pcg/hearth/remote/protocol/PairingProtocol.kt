package net.pcg.hearth.remote.protocol

import android.content.Context
import android.util.Log
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.proto.PoloProto.OuterMessage
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingProtocol(
    private val certificateManager: CertificateManager,
    private val context: Context
) {
    companion object {
        const val PAIRING_PORT = 6467
        private const val TAG = "PairingProtocol"
    }
    
    sealed class PairingResult {
        data class NeedCode(val socket: SSLSocket) : PairingResult()
        data class Success(val serverCert: X509Certificate) : PairingResult()
        data class Error(val message: String) : PairingResult()
    }
    
    fun startPairing(socket: SSLSocket): PairingResult {
        return try {
            val input = socket.inputStream
            val output = socket.outputStream
            
            send(output, PairingMessages.pairingRequest())
            val ackResult = receivePairingRequestAck(input)
            if (ackResult != null) return ackResult
            
            send(output, PairingMessages.options())
            val optionsResult = receiveOptions(input)
            if (optionsResult != null) return optionsResult
            
            send(output, PairingMessages.configuration())
            val configResult = receiveConfigurationAck(input)
            if (configResult != null) return configResult
            
            PairingResult.NeedCode(socket)
        } catch (e: Exception) {
            PairingResult.Error(context.getString(R.string.pairing_failed_detail, e.message.orEmpty()))
        }
    }
    
    fun completePairing(socket: SSLSocket, code: String): PairingResult {
        return try {
            val input = socket.inputStream
            val output = socket.outputStream
            
            val serverCert = socket.session.peerCertificates[0] as X509Certificate
            val secret = computeSecret(serverCert, code)
            
            send(output, PairingMessages.secret(secret))
            val secretResult = receiveSecretAck(input, serverCert)
            
            if (secretResult is PairingResult.Success) {
                certificateManager.saveServerCertificate(serverCert)
            }
            
            secretResult
        } catch (e: Exception) {
            PairingResult.Error(context.getString(R.string.pairing_completion_failed, e.message.orEmpty()))
        }
    }
    
    private fun receivePairingRequestAck(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return rejected(outer.status)
        }
        if (!outer.hasPairingRequestAck()) {
            return unexpected(outer)
        }
        return null
    }
    
    private fun receiveOptions(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return rejected(outer.status)
        }
        if (!outer.hasOptions()) {
            return unexpected(outer)
        }
        return null
    }
    
    private fun receiveConfigurationAck(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return rejected(outer.status)
        }
        if (!outer.hasConfigurationAck()) {
            return unexpected(outer)
        }
        return null
    }
    
    private fun receiveSecretAck(input: InputStream, serverCert: X509Certificate): PairingResult {
        val outer = receiveMessage(input)
        if (outer.status == OuterMessage.Status.STATUS_BAD_SECRET) {
            return PairingResult.Error(context.getString(R.string.pairing_error))
        }
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return rejected(outer.status)
        }
        if (!outer.hasSecretAck()) {
            return unexpected(outer)
        }
        
        return PairingResult.Success(serverCert)
    }
    
    private fun rejected(status: OuterMessage.Status): PairingResult.Error {
        return PairingResult.Error(context.getString(R.string.pairing_rejected, status.toString()))
    }

    private fun unexpected(outer: OuterMessage): PairingResult.Error {
        return PairingResult.Error(context.getString(R.string.pairing_unexpected, describe(outer)))
    }

    private fun computeSecret(serverCert: X509Certificate, code: String): ByteArray {
        val clientKey = certificateManager.getOrCreateClientCertificate().first.publicKey as RSAPublicKey
        val serverKey = serverCert.publicKey as RSAPublicKey
        val hash = PairingSecret.compute(
            clientKey.modulus,
            clientKey.publicExponent,
            serverKey.modulus,
            serverKey.publicExponent,
            code,
        )
        val passed = PairingSecret.firstByteMatches(hash, code)
        Log.i(TAG, "Pairing secret first-byte check ${if (passed) "passed" else "failed"}")
        if (!passed) {
            throw IllegalArgumentException("Secret validation failed - code mismatch")
        }
        return hash
    }
    
    private fun send(output: OutputStream, message: OuterMessage) {
        output.write(PairingMessages.frame(message))
        output.flush()
    }
    
    private fun receiveMessage(input: InputStream): OuterMessage {
        val length = readVarint(input)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) throw java.io.IOException("Unexpected end of stream")
            read += n
        }
        return OuterMessage.parseFrom(bytes)
    }
    
    private fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.IOException("Unexpected end of stream")
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    private fun describe(message: OuterMessage): String {
        return buildString {
            append("status=${message.status}")
            if (message.hasPairingRequest()) append(" pairing_request")
            if (message.hasPairingRequestAck()) append(" pairing_request_ack")
            if (message.hasOptions()) append(" options")
            if (message.hasConfiguration()) append(" configuration")
            if (message.hasConfigurationAck()) append(" configuration_ack")
            if (message.hasSecret()) append(" secret")
            if (message.hasSecretAck()) append(" secret_ack")
        }
    }
}
