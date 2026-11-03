package net.pcg.hearth.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket

class CleartextHttpTest {

    @Test
    fun `sign in request keeps a spaced username and a trailing-slash-free path`() {
        ServerSocket(0).use { server ->
            val seen = arrayOf("")
            val thread = Thread {
                val client = server.accept()
                client.soTimeout = 3000
                val bytes = readHttpRequest(client.getInputStream())
                seen[0] = bytes.toString(Charsets.UTF_8)
                val body = """{"User":{"Name":"Queen Bee","Id":"u"},"AccessToken":"t"}"""
                val payload = body.toByteArray(Charsets.UTF_8)
                val response = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                client.getOutputStream().write(payload)
                client.close()
            }
            thread.start()
            val result = HttpJellyfinTransport().exchange(
                JellyfinCall(
                    method = "POST",
                    url = "http://127.0.0.1:${server.localPort}/Users/AuthenticateByName",
                    headers = mapOf(
                        "Authorization" to "MediaBrowser Client=\"Hearth Remote\", Device=\"Phone\", DeviceId=\"phone\", Version=\"0.2.4\"",
                        "Content-Type" to "application/json"
                    ),
                    body = """{"Username":"Queen Bee","Pw":"s3cret-pw"}"""
                )
            )
            thread.join(3000)
            assertEquals(200, result.code)
            assertTrue(result.text().contains("Queen Bee"))
            assertTrue(seen[0].contains("POST /Users/AuthenticateByName HTTP/1.1"))
            assertTrue(seen[0].contains("Authorization: MediaBrowser Client=\"Hearth Remote\""))
            assertTrue(seen[0].contains("\"Username\":\"Queen Bee\""))
            assertFalse(seen[0].contains("X-Emby-Authorization"))
        }
    }

    @Test
    fun `chunked error bodies are read`() {
        ServerSocket(0).use { server ->
            val thread = Thread {
                val client = server.accept()
                client.soTimeout = 3000
                readHttpRequest(client.getInputStream())
                val response = "HTTP/1.1 401 Unauthorized\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                    "5\r\nhello\r\n0\r\n\r\n"
                client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                client.close()
            }
            thread.start()
            val result = CleartextHttp.exchange(
                JellyfinCall("GET", "http://127.0.0.1:${server.localPort}/Sessions", emptyMap(), null)
            )
            thread.join(3000)
            assertEquals(401, result.code)
            assertEquals("hello", result.text())
        }
    }

    private fun readHttpRequest(input: InputStream): ByteArray {
        val header = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            header.write(b)
            val text = header.toString(Charsets.ISO_8859_1)
            if (text.contains("\r\n\r\n")) {
                val length = Regex("(?i)Content-Length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                header.write(body)
                return header.toByteArray()
            }
        }
        return header.toByteArray()
    }
}
