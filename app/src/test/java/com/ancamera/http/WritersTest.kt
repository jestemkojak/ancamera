package com.ancamera.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WritersTest {
    @Test fun responseHasStatusLengthAndBody() {
        val out = ByteArrayOutputStream()
        HttpResponse.json(200, """{"a":1}""").writeTo(out)
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("HTTP/1.0 200 OK\r\n"))
        assertTrue(text.contains("Content-Type: application/json; charset=utf-8\r\n"))
        assertTrue(text.contains("Content-Length: 7\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"a\":1}"))
    }

    @Test fun unauthorizedAsksForBasicAuth() {
        val out = ByteArrayOutputStream()
        HttpResponse.unauthorized().writeTo(out)
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("HTTP/1.0 401 Unauthorized\r\n"))
        assertTrue(text.contains("WWW-Authenticate: Basic realm=\"ancamera\"\r\n"))
    }

    @Test fun mjpegWritesHeaderAndParts() {
        val out = ByteArrayOutputStream()
        val w = MjpegWriter(out)
        w.writeHeader()
        w.writeFrame(byteArrayOf(1, 2, 3))
        w.writeFrame(byteArrayOf(4))
        val text = out.toString("ISO-8859-1")
        assertTrue(text.contains("Content-Type: multipart/x-mixed-replace; boundary=ancameraframe\r\n"))
        assertEquals(2, Regex("--ancameraframe\r\n").findAll(text).count())
        assertTrue(text.contains("Content-Length: 3\r\n\r\n\u0001\u0002\u0003\r\n"))
    }
}
