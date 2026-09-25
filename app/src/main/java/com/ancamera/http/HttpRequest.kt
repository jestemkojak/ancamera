package com.ancamera.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

class BadRequestException(message: String) : IOException(message)

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower case. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADERS = 64
        const val MAX_BODY_BYTES = 64 * 1024

        /** Reads one request. Returns null if the stream ends before the first byte. */
        fun parse(input: InputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw BadRequestException("bad request line")
            val method = parts[0].uppercase()
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw BadRequestException("headers not complete")
                if (line.isEmpty()) break
                if (headers.size >= MAX_HEADERS) throw BadRequestException("too many headers")
                val colon = line.indexOf(':')
                if (colon <= 0) throw BadRequestException("bad header")
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            val length = headers["content-length"]?.let {
                it.toIntOrNull() ?: throw BadRequestException("bad content-length")
            } ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) throw BadRequestException("body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw BadRequestException("body not complete")
                read += n
            }
            return HttpRequest(method, path, query, headers, body)
        }

        /** Reads up to CRLF or LF. Returns null on end of stream with no bytes. */
        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (buf.size() == 0) return null
                    throw BadRequestException("line not complete")
                }
                if (b == '\n'.code) break
                if (buf.size() >= MAX_LINE_BYTES) throw BadRequestException("line too long")
                buf.write(b)
            }
            val bytes = buf.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.ISO_8859_1)
        }

        private fun parseQuery(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                try {
                    out[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
                } catch (e: IllegalArgumentException) {
                    throw BadRequestException("bad query")
                }
            }
            return out
        }
    }
}
