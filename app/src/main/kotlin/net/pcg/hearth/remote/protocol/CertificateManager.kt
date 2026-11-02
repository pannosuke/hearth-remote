package net.pcg.hearth.remote.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

class CertificateManager(private val context: Context) {
    
    companion object {
        private const val KEYSTORE_ALIAS = "hearth_remote_client"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_NAME = "hearth_remote_certs"
        private const val PREF_SERVER_CERT = "server_cert"
    }
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun hasClientCertificate(): Boolean {
        return try {
            keyStore.containsAlias(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            false
        }
    }
    
    fun getOrCreateClientCertificate(): Pair<X509Certificate, PrivateKey> {
        if (hasClientCertificate()) {
            val cert = keyStore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
            val key = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
            return cert to key
        }
        
        return generateClientCertificate()
    }
    
    private fun generateClientCertificate(): Pair<X509Certificate, PrivateKey> {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE
        )
        
        val startDate = Date()
        val endDate = Calendar.getInstance().apply {
            add(Calendar.YEAR, 10)
        }.time
        
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=HearthRemote,O=PCG,C=JP"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(startDate)
            .setCertificateNotAfter(endDate)
            .build()
        
        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()
        
        val cert = keyStore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        
        return cert to key
    }
    
    fun saveServerCertificate(cert: X509Certificate) {
        val encoded = android.util.Base64.encodeToString(
            cert.encoded,
            android.util.Base64.NO_WRAP
        )
        prefs.edit().putString(PREF_SERVER_CERT, encoded).apply()
    }
    
    fun getServerCertificate(): X509Certificate? {
        val encoded = prefs.getString(PREF_SERVER_CERT, null) ?: return null
        return try {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }
    
    fun clearServerCertificate() {
        prefs.edit().remove(PREF_SERVER_CERT).apply()
    }
    
    fun clearAll() {
        try {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            // Ignore
        }
        clearServerCertificate()
    }
    
    fun createSSLContext(): SSLContext {
        val (clientCert, clientKey) = getOrCreateClientCertificate()
        
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setKeyEntry("client", clientKey, null, arrayOf<Certificate>(clientCert))
        kmf.init(ks, null)
        
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
        
        return sslContext
    }
    
    fun getClientCertModulusAndExponent(): Pair<ByteArray, ByteArray> {
        val (cert, _) = getOrCreateClientCertificate()
        val rsaKey = cert.publicKey as java.security.interfaces.RSAPublicKey
        
        var modulus = rsaKey.modulus.toByteArray()
        if (modulus[0] == 0.toByte()) {
            modulus = modulus.copyOfRange(1, modulus.size)
        }
        
        var exponent = rsaKey.publicExponent.toByteArray()
        while (exponent.size < 3) {
            exponent = byteArrayOf(0) + exponent
        }
        
        return modulus to exponent
    }
}
