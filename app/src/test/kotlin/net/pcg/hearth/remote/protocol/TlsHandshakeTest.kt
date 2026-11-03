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
import java.net.ServerSocket
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
        
        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
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
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        
        val certHolder = certBuilder.build(signer)
        
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
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
}
