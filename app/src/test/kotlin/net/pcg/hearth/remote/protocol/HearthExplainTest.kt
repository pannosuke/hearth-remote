package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class HearthExplainTest {

    @Test
    fun `the url uses the paired tv host`() {
        assertEquals("http://192.168.10.105:8765/api/explain", explainUrl("192.168.10.105"))
        assertEquals("http://192.168.10.105:8765/api/explain", explainUrl("http://192.168.10.105/"))
        assertThrows(OverlayException::class.java) { explainUrl("  ") }
        assertThrows(OverlayException::class.java) { explainUrl(null) }
    }

    @Test
    fun `post is enough when hearth accepts it`() {
        val calls = mutableListOf<String>()
        val client = client(calls) { 200 }
        assertEquals(ExplainOutcome.Sent, client.toggle())
        assertEquals(listOf("POST"), calls)
    }

    @Test
    fun `a 405 falls back to get`() {
        val calls = mutableListOf<String>()
        val client = client(calls) { method -> if (method == "POST") 405 else 200 }
        assertEquals(ExplainOutcome.Sent, client.toggle())
        assertEquals(listOf("POST", "GET"), calls)
    }

    @Test
    fun `a 404 means the tv hearth is too old and get is not tried`() {
        val calls = mutableListOf<String>()
        val client = client(calls) { 404 }
        assertEquals(ExplainOutcome.NeedsUpdate, client.toggle())
        assertEquals(listOf("POST"), calls)
    }

    @Test
    fun `get 404 after a 405 still asks for an update`() {
        val calls = mutableListOf<String>()
        val client = client(calls) { method -> if (method == "POST") 405 else 404 }
        assertEquals(ExplainOutcome.NeedsUpdate, client.toggle())
        assertEquals(listOf("POST", "GET"), calls)
    }

    @Test
    fun `any other status is an error and does not fall back`() {
        val calls = mutableListOf<String>()
        val client = client(calls) { 500 }
        assertEquals(ExplainOutcome.Failed, client.toggle())
        assertEquals(listOf("POST"), calls)
    }

    @Test
    fun `a dropped connection is an error`() {
        val client = HearthExplain(
            hostProvider = { "192.168.10.105" },
            transport = ExplainTransport { throw java.io.IOException("down") }
        )
        assertThrows(OverlayException::class.java) { client.toggle() }
    }

    @Test
    fun `the button reports sent, then an update, and a second press waits`() = runBlocking {
        var code = 200
        val calls = AtomicInteger()
        val client = HearthExplain(
            hostProvider = { "192.168.10.105" },
            transport = ExplainTransport {
                calls.incrementAndGet()
                ExplainResult(code, "{}")
            }
        )
        val controller = ExplainController(client, holdMs = 0L)
        assertEquals(ExplainPhase.Sent, controller.pressNow())
        assertEquals(ExplainPhase.Idle, controller.state.value)
        assertEquals(1, calls.get())

        code = 404
        assertEquals(ExplainPhase.NeedsUpdate, controller.pressNow())
        assertEquals(ExplainPhase.NeedsUpdate, controller.state.value)

        code = 200
        controller.pressNow()
        assertEquals(3, calls.get())
    }

    @Test
    fun `the real client posts and then gets when the set says 405`() {
        ServerSocket(0).use { server ->
            val seen = mutableListOf<String>()
            val thread = Thread {
                repeat(2) {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                        val request = reader.readLine().orEmpty()
                        val method = request.substringBefore(' ')
                        synchronized(seen) { seen += method }
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val code = if (method == "POST") 405 else 200
                        val body = "{}"
                        val head = "HTTP/1.1 $code OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
                        val out = socket.getOutputStream()
                        out.write(head.toByteArray(Charsets.US_ASCII))
                        out.write(body.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
            thread.isDaemon = true
            thread.start()
            val client = HearthExplain(
                hostProvider = { "127.0.0.1" },
                port = server.localPort
            )
            assertEquals(ExplainOutcome.Sent, client.toggle())
            thread.join(2_000)
            assertEquals(listOf("POST", "GET"), seen)
        }
    }

    private fun client(calls: MutableList<String>, codeFor: (String) -> Int): HearthExplain {
        return HearthExplain(
            hostProvider = { "192.168.10.105" },
            transport = ExplainTransport { call ->
                calls += call.method
                assertTrue(call.url == "http://192.168.10.105:8765/api/explain")
                ExplainResult(codeFor(call.method), "{}")
            }
        )
    }
}
