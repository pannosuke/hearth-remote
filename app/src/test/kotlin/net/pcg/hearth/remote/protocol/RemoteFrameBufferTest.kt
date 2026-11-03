package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.RemoteProto.RemoteMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class RemoteFrameBufferTest {

    @Test
    fun `zero length append is not end of stream and yields nothing`() {
        val frame = RemoteFrameBuffer()
        assertFalse(frame.append(ByteArray(0), 0))
        assertFalse(frame.append(byteArrayOf(0x01), 0))
        assertNull(frame.poll())
    }

    @Test
    fun `negative length is end of stream`() {
        val frame = RemoteFrameBuffer()
        assertTrue(frame.append(ByteArray(0), -1))
    }

    @Test
    fun `a varint and payload split across appends reassemble`() {
        val payload = RemoteMessages.pingResponse(5).toByteArray()
        val framed = frame(payload)
        val buffer = RemoteFrameBuffer()
        var found: ByteArray? = null
        for (index in framed.indices) {
            assertFalse(buffer.append(byteArrayOf(framed[index]), 1))
            buffer.append(ByteArray(0), 0)
            found = buffer.poll() ?: found
        }
        assertArrayEquals(payload, found)
        assertNull(buffer.poll())
    }

    @Test
    fun `one chunk can hold two messages`() {
        val first = RemoteMessages.pingResponse(5).toByteArray()
        val second = RemoteMessages.setActiveResponse(615).toByteArray()
        val chunk = frame(first) + frame(second)
        val buffer = RemoteFrameBuffer()
        assertFalse(buffer.append(chunk, chunk.size))
        assertArrayEquals(first, buffer.poll())
        assertArrayEquals(second, buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun `an unfinished varint waits for the next chunk`() {
        val payload = ByteArray(128) { 0x11 }
        val framed = frame(payload)
        assertTrue(framed[0] == 0x80.toByte())
        val buffer = RemoteFrameBuffer()
        buffer.append(byteArrayOf(framed[0]), 1)
        assertNull(buffer.poll())
        buffer.append(framed.copyOfRange(1, framed.size), framed.size - 1)
        assertArrayEquals(payload, buffer.poll())
    }

    @Test
    fun `a varint longer than ten bytes closes the stream`() {
        val buffer = RemoteFrameBuffer()
        val junk = ByteArray(11) { 0x80.toByte() }
        buffer.append(junk, junk.size)
        try {
            buffer.poll()
            throw AssertionError("expected a corrupt length to fail")
        } catch (e: IOException) {
            assertEquals("Varint too long", e.message)
        }
    }

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var value = payload.size
        while (value >= 0x80) {
            out.write((value and 0x7F) or 0x80)
            value = value ushr 7
        }
        out.write(value)
        out.write(payload)
        return out.toByteArray()
    }
}
