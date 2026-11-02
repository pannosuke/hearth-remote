package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.PoloProto.*
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
        private const val SERVICE_NAME = "androidtvremote2"
        private const val CLIENT_NAME = "HearthRemote"
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
            
            sendPairingRequest(output)
            val ackResult = receivePairingRequestAck(input)
            if (ackResult != null) return ackResult
            
            sendOptions(output)
            val optionsResult = receiveOptions(input)
            if (optionsResult != null) return optionsResult
            
            sendConfiguration(output)
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
            
            sendSecret(output, secret)
            val secretResult = receiveSecretAck(input, secret)
            
            if (secretResult is PairingResult.Success) {
                certificateManager.saveServerCertificate(serverCert)
            }
            
            secretResult
        } catch (e: Exception) {
            PairingResult.Error("Pairing completion failed: ${e.message}")
        }
    }
    
    private fun sendPairingRequest(output: OutputStream) {
        val request = PairingRequest.newBuilder()
            .setServiceName(SERVICE_NAME)
            .setClientName(CLIENT_NAME)
            .build()
        
        val outer = OuterMessage.newBuilder()
            .setProtocolVersion(1)
            .setStatus(OuterMessage.Status.STATUS_OK)
            .setType(OuterMessage.MessageType.MESSAGE_TYPE_PAIRING_REQUEST)
            .setPayload(request.toByteString())
            .build()
        
        sendMessage(output, outer)
    }
    
    private fun receivePairingRequestAck(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected pairing request: ${outer.status}")
        }
        if (outer.type != OuterMessage.MessageType.MESSAGE_TYPE_PAIRING_REQUEST_ACK) {
            return PairingResult.Error("Unexpected message type: ${outer.type}")
        }
        return null
    }
    
    private fun sendOptions(output: OutputStream) {
        val encoding = Options.Encoding.newBuilder()
            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()
        
        val options = Options.newBuilder()
            .addInputEncodings(encoding)
            .build()
        
        val outer = OuterMessage.newBuilder()
            .setProtocolVersion(1)
            .setStatus(OuterMessage.Status.STATUS_OK)
            .setType(OuterMessage.MessageType.MESSAGE_TYPE_OPTIONS)
            .setPayload(options.toByteString())
            .build()
        
        sendMessage(output, outer)
    }
    
    private fun receiveOptions(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected options: ${outer.status}")
        }
        if (outer.type != OuterMessage.MessageType.MESSAGE_TYPE_OPTIONS) {
            return PairingResult.Error("Unexpected message type: ${outer.type}")
        }
        return null
    }
    
    private fun sendConfiguration(output: OutputStream) {
        val encoding = Options.Encoding.newBuilder()
            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()
        
        val config = Configuration.newBuilder()
            .setEncoding(encoding)
            .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
            .build()
        
        val outer = OuterMessage.newBuilder()
            .setProtocolVersion(1)
            .setStatus(OuterMessage.Status.STATUS_OK)
            .setType(OuterMessage.MessageType.MESSAGE_TYPE_CONFIGURATION)
            .setPayload(config.toByteString())
            .build()
        
        sendMessage(output, outer)
    }
    
    private fun receiveConfigurationAck(input: InputStream): PairingResult? {
        val outer = receiveMessage(input)
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected configuration: ${outer.status}")
        }
        if (outer.type != OuterMessage.MessageType.MESSAGE_TYPE_CONFIGURATION_ACK) {
            return PairingResult.Error("Unexpected message type: ${outer.type}")
        }
        return null
    }
    
    private fun sendSecret(output: OutputStream, secret: ByteArray) {
        val secretMsg = Secret.newBuilder()
            .setSecret(com.google.protobuf.ByteString.copyFrom(secret))
            .build()
        
        val outer = OuterMessage.newBuilder()
            .setProtocolVersion(1)
            .setStatus(OuterMessage.Status.STATUS_OK)
            .setType(OuterMessage.MessageType.MESSAGE_TYPE_SECRET)
            .setPayload(secretMsg.toByteString())
            .build()
        
        sendMessage(output, outer)
    }
    
    private fun receiveSecretAck(input: InputStream, expectedSecret: ByteArray): PairingResult {
        val outer = receiveMessage(input)
        if (outer.status == OuterMessage.Status.STATUS_BAD_SECRET) {
            return PairingResult.Error("Invalid pairing code")
        }
        if (outer.status != OuterMessage.Status.STATUS_OK) {
            return PairingResult.Error("Server rejected secret: ${outer.status}")
        }
        if (outer.type != OuterMessage.MessageType.MESSAGE_TYPE_SECRET_ACK) {
            return PairingResult.Error("Unexpected message type: ${outer.type}")
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
    
    private fun sendMessage(output: OutputStream, message: OuterMessage) {
        val bytes = message.toByteArray()
        writeVarint(output, bytes.size)
        output.write(bytes)
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
    
    private fun writeVarint(output: OutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
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
}
