package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SharpIpControlTest {

    @Test
    fun `commands are 8 characters plus cr`() {
        assertEquals("VOLM20  \r", ascii(SharpIpCommands.encode("VOLM", "20")))
        assertEquals("MUTE0   \r", ascii(SharpIpCommands.encode("MUTE", "0")))
        assertEquals("MUTE2   \r", ascii(SharpIpCommands.encode("MUTE", "2")))
        assertEquals("IRCO02BB\r", ascii(SharpIpCommands.encode("IRCO", SharpIpCommands.HOME)))
        assertEquals("IRCO0114\r", ascii(SharpIpCommands.encode("IRCO", SharpIpCommands.VOLUME_UP)))
        assertEquals("IRCO0115\r", ascii(SharpIpCommands.encode("IRCO", SharpIpCommands.VOLUME_DOWN)))
        assertEquals("CHUP    \r", ascii(SharpIpCommands.encode("CHUP", "")))
        assertEquals("CHDW    \r", ascii(SharpIpCommands.encode("CHDW", "")))
        assertEquals("POWR????\r", ascii(SharpIpCommands.encode("POWR", "????")))
        assertEquals(9, SharpIpCommands.encode("IRCO", "0267").size)
    }

    @Test
    fun `replies drop the trailing cr`() {
        assertEquals("OK", SharpIpCommands.parseReply("OK\r"))
        assertEquals("ERR", SharpIpCommands.parseReply("ERR\r\n"))
        assertEquals("TB10", SharpIpCommands.parseReply("TB10\r"))
        assertEquals("20", SharpIpCommands.parseReply("20\r"))
    }

    @Test
    fun `one command uses one socket and reads the reply`() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger(0)
            val thread = Thread {
                val client = server.accept()
                accepted.incrementAndGet()
                val buf = ByteArray(9)
                readFully(client.getInputStream(), buf)
                assertEquals("IRCO02BB\r", ascii(buf))
                client.getOutputStream().write("OK\r".toByteArray())
                client.close()
            }
            thread.start()
            val control = SharpIpControl({ "127.0.0.1" }, server.localPort)
            assertEquals("OK", control.command("IRCO", "02BB"))
            thread.join(3000)
            assertEquals(1, accepted.get())
        }
    }

    @Test
    fun `commands are serialized and an ERR is a failure`() = runBlocking {
        ServerSocket(0).use { server ->
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val thread = Thread {
                repeat(2) {
                    val client = server.accept()
                    val buf = ByteArray(9)
                    readFully(client.getInputStream(), buf)
                    val text = ascii(buf)
                    seen.add(text)
                    val reply = if (text.startsWith("MUTE")) "OK\r" else "ERR\r"
                    client.getOutputStream().write(reply.toByteArray())
                    client.close()
                }
            }
            thread.start()
            val control = SharpIpControl({ "127.0.0.1" }, server.localPort)
            val mute = async { control.tryCommand("MUTE", "0") }
            val volume = async { control.tryCommand("VOLM", "20") }
            assertTrue(mute.await())
            assertFalse(volume.await())
            thread.join(3000)
            assertEquals(setOf("MUTE0   \r", "VOLM20  \r"), seen.toSet())
        }
    }

    private fun ascii(bytes: ByteArray) = bytes.toString(Charsets.US_ASCII)

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
    }
}
