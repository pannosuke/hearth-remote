package net.pcg.hearth.remote.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * HTTP/1.1 over a plain socket.
 *
 * Android's network security config can refuse cleartext from
 * [java.net.HttpURLConnection] even when the user typed a LAN address.
 * Jellyfin on this network is `http://` to that address. The TV remote
 * stays on TLS and does not use this client.
 */
internal object CleartextHttp {
    private const val MAX_BODY = 8_000_000

    fun exchange(
        request: JellyfinCall,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 20_000
    ): JellyfinResult {
        val uri = URI(request.url)
        if (!uri.scheme.equals("http", ignoreCase = true)) {
            throw IOException("Cleartext client only speaks http")
        }
        val host = uri.host ?: throw IOException("Missing host")
        val port = if (uri.port == -1) 80 else uri.port
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
            if (!uri.rawQuery.isNullOrEmpty()) {
                append('?')
                append(uri.rawQuery)
            }
        }
        val body = request.body?.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val header = StringBuilder()
            header.append(request.method).append(' ').append(path).append(" HTTP/1.1\r\n")
            header.append("Host: ").append(host)
            if (port != 80) header.append(':').append(port)
            header.append("\r\n")
            header.append("Connection: close\r\n")
            header.append("Accept-Encoding: identity\r\n")
            request.headers.forEach { (name, value) ->
                if (name.equals("Host", true) || name.equals("Content-Length", true) || name.equals("Connection", true)) {
                    return@forEach
                }
                header.append(name.replace("\r", "").replace("\n", ""))
                header.append(": ")
                header.append(value.replace("\r", "").replace("\n", ""))
                header.append("\r\n")
            }
            if (body != null) header.append("Content-Length: ").append(body.size).append("\r\n")
            header.append("\r\n")
            val output = socket.getOutputStream()
            output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) output.write(body)
            output.flush()
            return readResponse(socket.getInputStream())
        }
    }

    internal fun readResponse(input: InputStream): JellyfinResult {
        val status = readLine(input)
        if (status.isEmpty()) throw IOException("Empty HTTP response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Bad HTTP status")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val length = headers["content-length"]!!.toIntOrNull() ?: throw IOException("Bad content length")
                readExact(input, length)
            }
            else -> input.readBytes().also { if (it.size > MAX_BODY) throw IOException("HTTP body too large") }
        }
        return JellyfinResult(code, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input).substringBefore(';').trim()
            val size = sizeLine.toIntOrNull(16) ?: throw IOException("Bad chunk size")
            if (size == 0) {
                while (readLine(input).isNotEmpty()) {
                }
                break
            }
            if (out.size() + size > MAX_BODY) throw IOException("HTTP body too large")
            out.write(readExact(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        if (length < 0 || length > MAX_BODY) throw IOException("HTTP body too large")
        if (length == 0) return ByteArray(0)
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) throw IOException("HTTP body ended early")
            read += n
        }
        return buf
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1)
    }
}
