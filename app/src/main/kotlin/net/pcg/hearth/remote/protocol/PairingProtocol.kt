package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.PoloProto.OuterMessage
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class PairingProtocol(
    private val certificateManager: CertificateManager
) {
    companion object {
        const val PAIRING_PORT = 6467
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
            PairingResult.Error("Pairing failed: ${e.message}")
        }
    }
    
    fun completePairing(socket: SSLSocket, code: String): PairingResult {
        return try {
            val input = socket.inputStream
            val output = socket.outputStream
            
            val serverCert = socket.session.peerCertificates[0] as X509Certificate
            val secret = computeSecret(serverCert, code)
            
            send(output, PairingMessages.secret(secret))
            val secretResult = receiveSecretAck(input)
            
            if (secretResult is PairingResult.Success) {
                certificateManager.saveServerCertificate(serverCert)
            }
            
            secretResult
        } catch (e: Exception) {
            PairingResult.Error("Pairing completion failed: ${e.message}")
        }
    }
    
    private fun receivePairingRequestAck(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected pairing request: ${outer.status}")
        }
        if (!outer.hasPairingRequestAck()) {
            return PairingResult.Error("Unexpected pairing response: ${describe(outer)}")
        }
        return null
    }
    
    private fun receiveOptions(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected options: ${outer.status}")
        }
        if (!outer.hasOptions()) {
            return PairingResult.Error("Unexpected options response: ${describe(outer)}")
        }
        return null
    }
    
    private fun receiveConfigurationAck(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected configuration: ${outer.status}")
        }
        if (!outer.hasConfigurationAck()) {
            return PairingResult.Error("Unexpected configuration response: ${describe(outer)}")
        }
        return null
    }
    
    private fun receiveSecretAck(input: InputStream): PairingResult {
        val outer = receiveMessage(input)
        if (outer.status == OuterMessage.Status.STATUS_BAD_SECRET) {
            return PairingResult.Error("Invalid pairing code")
        }
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected secret: ${outer.status}")
        }
        if (!outer.hasSecretAck()) {
            return PairingResult.Error("Unexpected secret response: ${describe(outer)}")
        }
        
        val serverCert = certificateManager.getServerCertificate()
            ?: return PairingResult.Error("No server certificate")
        
        return PairingResult.Success(serverCert)
    }
    
    private fun computeSecret(serverCert: X509Certificate, code: String): ByteArray {
        val (clientModulus, clientExponent) = certificateManager.getClientCertModulusAndExponent()
        
        val serverKey = serverCert.publicKey as java.security.interfaces.RSAPublicKey
        var serverModulus = serverKey.modulus.toByteArray()
        if (serverModulus[0] == 0.toByte()) {
            serverModulus = serverModulus.copyOfRange(1, serverModulus.size)
        }
        var serverExponent = serverKey.publicExponent.toByteArray()
        while (serverExponent.size < 3) {
            serverExponent = byteArrayOf(0) + serverExponent
        }
        
        val codeLastTwo = code.takeLast(2)
        val codeBytes = codeLastTwo.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(clientModulus)
        digest.update(clientExponent)
        digest.update(serverModulus)
        digest.update(serverExponent)
        digest.update(codeBytes)
        
        val hash = digest.digest()
        
        val codeFirstTwo = code.take(2).toInt(16)
        if (hash[0].toInt() and 0xFF != codeFirstTwo) {
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
