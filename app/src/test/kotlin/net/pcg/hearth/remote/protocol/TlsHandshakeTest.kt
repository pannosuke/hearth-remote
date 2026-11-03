package net.pcg.hearth.remote.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

/**
 * JVM unit tests for TLS handshake with software-backed RSA keys.
 * 
 * These tests verify that the certificate generation approach works correctly
 * for TLS client authentication, which is critical for the Android TV Remote
 * protocol. The tests use a local TLS server to simulate the TV's behavior.
 */
class TlsHandshakeTest {
    
    companion object {
        private const val KEY_ALIAS = "test"
        private const val KEYSTORE_PASSWORD = "test-password"
        
        private val bcProvider = BouncyCastleProvider()
    }
    
    private lateinit var clientKeyPair: KeyPair
    private lateinit var clientCert: X509Certificate
    private lateinit var serverKeyPair: KeyPair
    private lateinit var serverCert: X509Certificate
    
    @Before
    fun setUp() {
        clientKeyPair = generateKeyPair()
        clientCert = generateSelfSignedCertificate(clientKeyPair, "CN=TestClient,O=Test,C=JP")
        
        serverKeyPair = generateKeyPair()
        serverCert = generateSelfSignedCertificate(serverKeyPair, "CN=TestServer,O=Test,C=JP")
    }
    
    private fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }
    
    /**
     * Generate a self-signed certificate using provider instance directly.
     * This mirrors the production code approach that works on Android.
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair, dnString: String): X509Certificate {
        val dn = X500Name(dnString)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply {
            add(Calendar.YEAR, 1)
        }.time
        
        val certBuilder = JcaX509v3CertificateBuilder(
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
    
    private fun createKeyStore(cert: X509Certificate, key: PrivateKey): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEYSTORE_PASSWORD.toCharArray())
        ks.setKeyEntry(KEY_ALIAS, key, KEYSTORE_PASSWORD.toCharArray(), arrayOf<Certificate>(cert))
        return ks
    }
    
    private fun createKeyManagerFactory(ks: KeyStore): KeyManagerFactory {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())
        return kmf
    }
    
    private fun createTrustAllManager(): TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
    
    @Test
    fun `certificate generation produces valid X509 certificate`() {
        assertNotNull(clientCert)
        assertTrue(clientCert.subjectX500Principal.name.contains("CN=TestClient"))
        assertTrue(clientCert.subjectX500Principal.name.contains("O=Test"))
        assertTrue(clientCert.subjectX500Principal.name.contains("C=JP"))
        assertEquals("SHA256WITHRSA", clientCert.sigAlgName.uppercase())
        
        clientCert.verify(clientKeyPair.public)
    }
    
    @Test
    fun `certificate can be stored in PKCS12 keystore`() {
        val ks = createKeyStore(clientCert, clientKeyPair.private)
        
        assertTrue(ks.containsAlias(KEY_ALIAS))
        assertTrue(ks.isKeyEntry(KEY_ALIAS))
        
        val storedCert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        assertEquals(clientCert.serialNumber, storedCert.serialNumber)
        
        val storedKey = ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        assertEquals(clientKeyPair.private.algorithm, storedKey.algorithm)
    }
    
    @Test
    fun `TLS 1_2 handshake succeeds with client authentication`() {
        val serverReady = CountDownLatch(1)
        val handshakeComplete = CountDownLatch(1)
        val serverError = AtomicReference<Throwable?>()
        val clientError = AtomicReference<Throwable?>()
        val serverPort = AtomicReference<Int>()
        
        val serverThread = Thread {
            try {
                val serverKs = createKeyStore(serverCert, serverKeyPair.private)
                val serverKmf = createKeyManagerFactory(serverKs)
                
                val sslContext = SSLContext.getInstance("TLSv1.2")
                sslContext.init(serverKmf.keyManagers, arrayOf(createTrustAllManager()), SecureRandom())
                
                val serverSocketFactory = sslContext.serverSocketFactory
                val serverSocket = serverSocketFactory.createServerSocket(0) as SSLServerSocket
                serverSocket.needClientAuth = true
                serverSocket.enabledProtocols = arrayOf("TLSv1.2")
                serverSocket.soTimeout = 5000
                
                serverPort.set(serverSocket.localPort)
                serverReady.countDown()
                
                val socket = serverSocket.accept() as SSLSocket
                socket.soTimeout = 5000
                socket.startHandshake()
                
                val peerCerts = socket.session.peerCertificates
                assertTrue("Client should present certificate", peerCerts.isNotEmpty())
                
                socket.outputStream.write("OK".toByteArray())
                socket.outputStream.flush()
                
                socket.close()
                serverSocket.close()
            } catch (e: Throwable) {
                serverError.set(e)
            }
        }
        
        val clientThread = Thread {
            try {
                serverReady.await(5, TimeUnit.SECONDS)
                
                val clientKs = createKeyStore(clientCert, clientKeyPair.private)
                val clientKmf = createKeyManagerFactory(clientKs)
                
                val sslContext = SSLContext.getInstance("TLSv1.2")
                sslContext.init(clientKmf.keyManagers, arrayOf(createTrustAllManager()), SecureRandom())
                
                val socketFactory = sslContext.socketFactory
                val socket = socketFactory.createSocket("localhost", serverPort.get()) as SSLSocket
                socket.soTimeout = 5000
                socket.enabledProtocols = arrayOf("TLSv1.2")
                socket.startHandshake()
                
                val response = ByteArray(2)
                socket.inputStream.read(response)
                assertEquals("OK", String(response))
                
                socket.close()
                handshakeComplete.countDown()
            } catch (e: Throwable) {
                clientError.set(e)
            }
        }
        
        serverThread.start()
        clientThread.start()
        
        serverThread.join(10000)
        clientThread.join(10000)
        
        serverError.get()?.let { throw AssertionError("Server error", it) }
        clientError.get()?.let { throw AssertionError("Client error", it) }
        
        assertTrue("Handshake should complete", handshakeComplete.await(1, TimeUnit.SECONDS))
    }
    
    @Test
    fun `KeyManagerFactory properly initializes with PKCS12 keystore`() {
        val ks = createKeyStore(clientCert, clientKeyPair.private)
        val kmf = createKeyManagerFactory(ks)
        
        val keyManagers = kmf.keyManagers
        assertNotNull(keyManagers)
        assertTrue(keyManagers.isNotEmpty())
        
        val x509km = keyManagers[0] as X509KeyManager
        assertNotNull(x509km.getPrivateKey(KEY_ALIAS))
        assertNotNull(x509km.getCertificateChain(KEY_ALIAS))
    }
    
    @Test
    fun `SSLContext can be created with TLSv1_2`() {
        val clientKs = createKeyStore(clientCert, clientKeyPair.private)
        val clientKmf = createKeyManagerFactory(clientKs)
        
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(clientKmf.keyManagers, arrayOf(createTrustAllManager()), SecureRandom())
        
        assertNotNull(sslContext)
        assertEquals("TLSv1.2", sslContext.protocol)
        
        val socketFactory = sslContext.socketFactory
        assertNotNull(socketFactory)
    }
    
    @Test
    fun `RSA key operations work correctly`() {
        val message = "Test message for signing".toByteArray()
        
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(clientKeyPair.private)
        signature.update(message)
        val signed = signature.sign()
        
        signature.initVerify(clientKeyPair.public)
        signature.update(message)
        assertTrue(signature.verify(signed))
    }
    
    @Test
    fun `certificate serial number is unique per generation`() {
        val cert1 = generateSelfSignedCertificate(clientKeyPair, "CN=Test1")
        Thread.sleep(2)
        val cert2 = generateSelfSignedCertificate(clientKeyPair, "CN=Test2")
        
        assertNotEquals(cert1.serialNumber, cert2.serialNumber)
    }
    
    /**
     * Simulates the Android environment where the "BC" provider name points to
     * the platform's stripped-down BouncyCastle, not our bundled full version.
     * 
     * This test removes/replaces the BC provider by name and verifies that
     * certificate generation still works when using provider instances directly.
     */
    @Test
    fun `certificate generation works without BC provider registered by name`() {
        val originalProvider = Security.getProvider("BC")
        
        try {
            Security.removeProvider("BC")
            assertNull("BC provider should be removed", Security.getProvider("BC"))
            
            val keyPair = generateKeyPair()
            val cert = generateSelfSignedCertificate(keyPair, "CN=TestNoBC,O=Test,C=JP")
            
            assertNotNull(cert)
            assertTrue(cert.subjectX500Principal.name.contains("CN=TestNoBC"))
            assertEquals("SHA256WITHRSA", cert.sigAlgName.uppercase())
            cert.verify(keyPair.public)
        } finally {
            if (originalProvider != null) {
                Security.addProvider(originalProvider)
            }
        }
    }
    
    /**
     * Verifies that using provider name "BC" would fail if a different provider
     * is registered under that name (simulating Android's stripped-down BC).
     * 
     * This test demonstrates why we must use provider instances directly.
     */
    @Test
    fun `certificate generation with provider name fails when BC is shadowed`() {
        val originalProvider = Security.getProvider("BC")
        
        try {
            Security.removeProvider("BC")
            
            @Suppress("DEPRECATION")
            val mockProvider = object : Provider("BC", 1.0, "Mock stripped BC") {
                init {
                    // No SHA256withRSA signer - like Android's stripped BC
                }
            }
            Security.addProvider(mockProvider)
            
            val keyPair = generateKeyPair()
            val dn = X500Name("CN=TestShadowed,O=Test,C=JP")
            val certBuilder = JcaX509v3CertificateBuilder(
                dn,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date(),
                Calendar.getInstance().apply { add(Calendar.YEAR, 1) }.time,
                dn,
                keyPair.public
            )
            
            var exceptionThrown = false
            try {
                JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(keyPair.private)
            } catch (e: Exception) {
                exceptionThrown = true
                assertTrue(
                    "Exception should mention algorithm not found",
                    e.message?.contains("no such algorithm", ignoreCase = true) == true ||
                    e.message?.contains("cannot create", ignoreCase = true) == true ||
                    e.cause?.message?.contains("no such algorithm", ignoreCase = true) == true
                )
            }
            
            assertTrue("Using provider name 'BC' should fail with shadowed provider", exceptionThrown)
            
            val cert = generateSelfSignedCertificate(keyPair, "CN=TestWithInstance,O=Test,C=JP")
            assertNotNull("Using provider instance should still work", cert)
            
        } finally {
            Security.removeProvider("BC")
            if (originalProvider != null) {
                Security.addProvider(originalProvider)
            }
        }
    }
}
