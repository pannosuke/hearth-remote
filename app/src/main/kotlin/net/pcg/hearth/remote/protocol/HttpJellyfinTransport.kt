package net.pcg.hearth.remote.protocol

import java.net.HttpURLConnection
import java.net.URL

internal class HttpJellyfinTransport : JellyfinTransport {
    override fun exchange(request: JellyfinCall): JellyfinResult {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { stream ->
                    stream.write(request.body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return JellyfinResult(code, bytes)
        } finally {
            connection.disconnect()
        }
    }
}
