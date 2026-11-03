package net.pcg.hearth.remote.protocol

import java.io.IOException

/**
 * Length-delimited protobuf framing, matching androidtvremote2's base.py.
 *
 * A TLS record can split a varint or a payload, and one read can contain
 * several messages. A zero-length read is not end of stream. A varint that
 * is longer than a 64-bit length, or a length above [MAX_MESSAGE_BYTES],
 * cannot be resynchronized, so [poll] throws.
 */
internal class RemoteFrameBuffer {
    private var buffer = ByteArray(0)

    /**
     * Append [length] bytes from [chunk]. A length of zero adds nothing.
     * Returns true when [length] is negative, which is end of stream.
     */
    fun append(chunk: ByteArray, length: Int): Boolean {
        if (length < 0) return true
        if (length == 0) return false
        val extra = chunk.copyOf(length)
        if (buffer.isEmpty()) {
            buffer = extra
        } else {
            buffer += extra
        }
        return false
    }

    /** Next complete payload, or null when more bytes are required. */
    fun poll(): ByteArray? {
        if (buffer.isEmpty()) return null
        val (length, header) = decodeLength(buffer) ?: return null
        val end = header + length
        if (buffer.size < end) return null
        val payload = buffer.copyOfRange(header, end)
        buffer = if (end == buffer.size) ByteArray(0) else buffer.copyOfRange(end, buffer.size)
        return payload
    }

    private fun decodeLength(data: ByteArray): Pair<Int, Int>? {
        var result = 0L
        var shift = 0
        var index = 0
        while (index < data.size && index < 10) {
            val byte = data[index].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            index += 1
            if ((byte and 0x80) == 0) {
                if (result < 0L || result > MAX_MESSAGE_BYTES) {
                    buffer = ByteArray(0)
                    throw IOException("Bad message length $result")
                }
                return result.toInt() to index
            }
            shift += 7
        }
        if (index >= 10) {
            buffer = ByteArray(0)
            throw IOException("Varint too long")
        }
        return null
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 1_000_000
    }
}
