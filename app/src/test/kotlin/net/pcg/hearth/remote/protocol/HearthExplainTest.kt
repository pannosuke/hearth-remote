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

    @Test
    fun `the photo url sits under explain`() {
        assertEquals("http://192.168.10.105:8765/api/explain/photo", explainPhotoUrl("192.168.10.105"))
        assertEquals("http://192.168.10.105:9/api/explain/photo", explainPhotoUrl("http://192.168.10.105/", 9))
    }

    @Test
    fun `multipart keeps the photo field and the jpeg bytes`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val form = multipartPhoto(jpeg, "bound")
        val text = form.body.toString(Charsets.ISO_8859_1)
        assertEquals("multipart/form-data; boundary=bound", form.contentType)
        assertTrue(text.contains("name=\"photo\""))
        assertTrue(text.contains("filename=\"photo.jpg\""))
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue(text.endsWith("\r\n--bound--\r\n"))
        val start = text.indexOf("\r\n\r\n") + 4
        val end = text.indexOf("\r\n--bound--")
        assertTrue(text.substring(start, end).toByteArray(Charsets.ISO_8859_1).contentEquals(jpeg))
    }

    @Test
    fun `a 200 photo upload is not retried`() {
        val types = mutableListOf<String>()
        val client = photoClient(types) { 200 }
        assertEquals(ExplainOutcome.Sent, client.uploadPhoto(byteArrayOf(1, 2)))
        assertEquals(1, types.size)
        assertTrue(types[0].startsWith("multipart/form-data"))
    }

    @Test
    fun `a 415 is retried once as a raw jpeg`() {
        val types = mutableListOf<String>()
        val client = photoClient(types) { type -> if (type.startsWith("multipart/")) 415 else 200 }
        assertEquals(ExplainOutcome.Sent, client.uploadPhoto(byteArrayOf(9, 8, 7)))
        assertEquals(2, types.size)
        assertTrue(types[0].startsWith("multipart/form-data; boundary="))
        assertEquals("image/jpeg", types[1])
    }

    @Test
    fun `a 400 is retried once as a raw jpeg`() {
        val types = mutableListOf<String>()
        val client = photoClient(types) { type -> if (type.startsWith("multipart/")) 400 else 200 }
        assertEquals(ExplainOutcome.Sent, client.uploadPhoto(byteArrayOf(3)))
        assertEquals(2, types.size)
        assertEquals("image/jpeg", types[1])
    }

    @Test
    fun `a 404 photo endpoint asks for an update and is not retried`() {
        val types = mutableListOf<String>()
        val client = photoClient(types) { 404 }
        assertEquals(ExplainOutcome.NeedsUpdate, client.uploadPhoto(byteArrayOf(1)))
        assertEquals(1, types.size)
    }

    @Test
    fun `a 500 photo upload is an error and is not retried`() {
        val types = mutableListOf<String>()
        val client = photoClient(types) { 500 }
        assertEquals(ExplainOutcome.Failed, client.uploadPhoto(byteArrayOf(1)))
        assertEquals(1, types.size)
    }

    @Test
    fun `an empty photo is refused before the network`() {
        val client = HearthExplain(
            hostProvider = { "192.168.10.105" },
            photos = PhotoTransport { throw AssertionError("sent") }
        )
        assertThrows(OverlayException::class.java) { client.uploadPhoto(byteArrayOf()) }
    }

    @Test
    fun `the photo button reports sent and then clears`() = runBlocking {
        val client = HearthExplain(
            hostProvider = { "192.168.10.105" },
            photos = PhotoTransport { ExplainResult(200, "{}") }
        )
        val controller = ExplainController(client, holdMs = 0L)
        assertEquals(ExplainPhase.Sent, controller.uploadNow(byteArrayOf(1)))
        assertEquals(ExplainPhase.Idle, controller.state.value)
    }

    @Test
    fun `the real client posts the jpeg as multipart`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 7)
        ServerSocket(0).use { server ->
            var contentType = ""
            var body = ByteArray(0)
            val thread = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    val window = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        header.append(b.toChar())
                        window.append(b.toChar())
                        if (window.length > 4) window.deleteCharAt(0)
                        if (window.toString() == "\r\n\r\n") break
                    }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    contentType = Regex("Content-Type: ([^\r]+)", RegexOption.IGNORE_CASE)
                        .find(header)?.groupValues?.get(1).orEmpty()
                    body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val reply = "{}"
                    val out = socket.getOutputStream()
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    out.flush()
                }
            }
            thread.isDaemon = true
            thread.start()
            val client = HearthExplain(
                hostProvider = { "127.0.0.1" },
                port = server.localPort
            )
            assertEquals(ExplainOutcome.Sent, client.uploadPhoto(jpeg))
            thread.join(2_000)
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            val text = body.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("name=\"photo\""))
            assertTrue(text.contains(jpeg.toString(Charsets.ISO_8859_1)))
        }
    }

    private fun photoClient(types: MutableList<String>, codeFor: (String) -> Int): HearthExplain {
        return HearthExplain(
            hostProvider = { "192.168.10.105" },
            photos = PhotoTransport { call ->
                types += call.contentType
                assertEquals("http://192.168.10.105:8765/api/explain/photo", call.url)
                if (call.contentType == "image/jpeg") {
                    assertTrue(call.body.contentEquals(byteArrayOf(9, 8, 7)) || call.body.size == 1 || call.body.size == 2)
                }
                ExplainResult(codeFor(call.contentType), "{}")
            }
        )
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
