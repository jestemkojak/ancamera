package com.ancamera.http

import java.io.OutputStream

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun writeTo(out: OutputStream) {
        val head = StringBuilder()
            .append("HTTP/1.0 ").append(status).append(' ').append(reason(status)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        for ((k, v) in extraHeaders) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        const val JSON = "application/json; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"
        const val HTML = "text/html; charset=utf-8"
        const val JPEG = "image/jpeg"

        fun json(status: Int, body: String) = HttpResponse(status, JSON, body.toByteArray(Charsets.UTF_8))
        fun text(status: Int, body: String) = HttpResponse(status, TEXT, body.toByteArray(Charsets.UTF_8))
        fun unauthorized() = HttpResponse(
            401, TEXT, "authentication required".toByteArray(),
            mapOf("WWW-Authenticate" to "Basic realm=\"ancamera\""),
        )

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            415 -> "Unsupported Media Type"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
    }
}
