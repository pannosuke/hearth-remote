package net.pcg.hearth.remote.protocol

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.*
import javax.net.ssl.*

/**
 * Manages client certificates for TLS authentication with Android TV Remote Service.
 * 
 * Uses software-backed RSA keys stored in a PKCS12 file in app-private storage.
 * This approach is used by all known-good Android TV Remote v2 implementations
 * (tronikos/androidtvremote2, louis49/androidtv-remote) because Android Keystore
 * hardware-backed keys have compatibility issues with TLS client authentication
 * on Android 10+ (see google/conscrypt#718).
 * 
 * Certificate generation uses BouncyCastle since standard JCA X.509 certificate
 * builder classes (sun.security.x509) are not available on Android.
 * 
 * IMPORTANT: On Android, the provider name "BC" refers to the platform's stripped-down
 * BouncyCastle, not our bundled full version. We must pass provider instances directly
 * to JcaContentSignerBuilder and JcaX509CertificateConverter instead of using the name.
 */
class CertificateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CertificateManager"
        private const val PKCS12_FILENAME = "client.p12"
        private const val KEY_ALIAS = "client"
        private const val KEYSTORE_PASSWORD = "hearth-remote"
        private const val PREFS_NAME = "hearth_remote_certs"
        private const val PREF_SERVER_CERT = "server_cert"
        private const val PREF_CERT_VERSION = "cert_version"
        private const val CURRENT_CERT_VERSION = 4
        
        private val bcProvider = BouncyCastleProvider()
    }
    
    private val pkcs12File: File
        get() = File(context.filesDir, PKCS12_FILENAME)
    
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private var cachedKeyStore: KeyStore? = null
    
    /**
     * Check if we have a valid client certificate.
     * Also checks cert version and deletes old incompatible certs.
     */
    fun hasClientCertificate(): Boolean {
        val savedVersion = prefs.getInt(PREF_CERT_VERSION, 0)
        if (savedVersion < CURRENT_CERT_VERSION && pkcs12File.exists()) {
            Log.i(TAG, "Deleting old certificate (version $savedVersion < $CURRENT_CERT_VERSION)")
            deleteClientCertificate()
            return false
        }
        
        if (!pkcs12File.exists()) {
            return false
        }
        
        return try {
            val ks = loadKeyStore()
            ks.containsAlias(KEY_ALIAS) && ks.isKeyEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load keystore", e)
            false
        }
    }
    
    /**
     * Get or create the client certificate and private key.
     */
    fun getOrCreateClientCertificate(): Pair<X509Certificate, PrivateKey> {
        if (hasClientCertificate()) {
            val ks = loadKeyStore()
            val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
            val key = ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
            return cert to key
        }
        
        return generateClientCertificate()
    }
    
    private fun loadKeyStore(): KeyStore {
        cachedKeyStore?.let { return it }
        
        val ks = KeyStore.getInstance("PKCS12")
        pkcs12File.inputStream().use { 
            ks.load(it, KEYSTORE_PASSWORD.toCharArray())
        }
        cachedKeyStore = ks
        return ks
    }
    
    /**
     * Generate a new self-signed certificate with a software RSA key.
     * Uses BouncyCastle for certificate generation.
     */
    private fun generateClientCertificate(): Pair<X509Certificate, PrivateKey> {
        Log.i(TAG, "Generating new client certificate")
        
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        val cert = generateSelfSignedCertificate(keyPair)
        
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEYSTORE_PASSWORD.toCharArray())
        ks.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf<Certificate>(cert)
        )
        
        pkcs12File.outputStream().use {
            ks.store(it, KEYSTORE_PASSWORD.toCharArray())
        }
        
        prefs.edit().putInt(PREF_CERT_VERSION, CURRENT_CERT_VERSION).apply()
        
        cachedKeyStore = ks
        
        Log.i(TAG, "Generated certificate: ${cert.subjectX500Principal}")
        
        return cert to keyPair.private
    }
    
    /**
     * Generate a self-signed X.509 certificate using BouncyCastle.
     * 
     * Uses provider instance directly instead of provider name because on Android
     * the name "BC" refers to the platform's stripped-down BouncyCastle which lacks
     * the SHA256withRSA signer algorithm.
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val dn = X500Name("CN=HearthRemote,O=PCG,C=JP")
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply {
            add(Calendar.YEAR, 10)
        }.time
        
        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            dn,
            serialNumber,
            notBefore,
            notAfter,
            dn,
            keyPair.public
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)
        
        val certHolder = certBuilder.build(signer)
        
        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certHolder)
    }
    
    fun deleteClientCertificate() {
        try {
            pkcs12File.delete()
            cachedKeyStore = null
            prefs.edit().remove(PREF_CERT_VERSION).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete certificate", e)
        }
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
        deleteClientCertificate()
        clearServerCertificate()
    }
    
    /**
     * TLS context for both pairing (6467) and the remote session (6466).
     *
     * Both ports must present the same client certificate. The key lives in
     * [PKCS12_FILENAME] under alias [KEY_ALIAS]. A default X509KeyManager can
     * decline to send that cert when the TV's accepted-issuer list does not
     * match a self-signed client, and the TV then completes TLS and silently
     * ignores every command. [FixedAliasKeyManager] always selects [KEY_ALIAS].
     */
    fun createSSLContext(): SSLContext {
        getOrCreateClientCertificate()
        val ks = loadKeyStore()
        if (!ks.containsAlias(KEY_ALIAS) || !ks.isKeyEntry(KEY_ALIAS)) {
            throw IllegalStateException("Client certificate alias $KEY_ALIAS is missing")
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())
        val delegate = kmf.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
            ?: throw IllegalStateException("No X509 key manager for alias $KEY_ALIAS")
        val keyManager = FixedAliasKeyManager(delegate, KEY_ALIAS)
        val chain = keyManager.getCertificateChain(KEY_ALIAS)
        val subject = chain?.firstOrNull()?.subjectX500Principal?.name ?: "missing"
        Log.i(TAG, "TLS client cert alias=$KEY_ALIAS subject=$subject")

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())

        return sslContext
    }

    /**
     * Always offers the pairing certificate, ignoring the TV's issuer list.
     */
    private class FixedAliasKeyManager(
        private val delegate: X509KeyManager,
        private val alias: String
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String = alias

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String = alias

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            delegate.getCertificateChain(this.alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            delegate.getPrivateKey(this.alias)

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(alias)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            delegate.getServerAliases(keyType, issuers)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = delegate.chooseServerAlias(keyType, issuers, socket)

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String? = when (delegate) {
            is X509ExtendedKeyManager -> delegate.chooseEngineServerAlias(keyType, issuers, engine)
            else -> null
        }
    }
    
    /**
     * Get the RSA modulus and exponent from the client certificate.
     * Used for computing the pairing secret.
     */
    fun getClientCertModulusAndExponent(): Pair<ByteArray, ByteArray> {
        val (cert, _) = getOrCreateClientCertificate()
        val rsaKey = cert.publicKey as RSAPublicKey
        return PairingSecret.modulusBytes(rsaKey.modulus) to
            PairingSecret.exponentBytes(rsaKey.publicExponent)
    }
}
