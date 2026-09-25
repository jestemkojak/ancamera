package com.ancamera.http

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Real sockets on localhost with a fake backend. */
class HttpServerTest {
    private val backend = FakeBackend()
    private var port = 0
    private lateinit var server: HttpServer

    @Before fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = HttpServer(port, Router(backend, "<html>".toByteArray()), backend)
        server.start()
    }

    @After fun tearDown() = server.stop()

    private fun open(request: String): Socket {
        val s = Socket("127.0.0.1", port)
        s.soTimeout = 5000
        s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        s.getOutputStream().flush()
        return s
    }

    private fun exchange(request: String): String = open(request).use { s ->
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    /** Reads until [count] MJPEG parts arrived. */
    private fun readParts(input: InputStream, count: Int): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (Regex("--ancameraframe").findAll(buf.toString("ISO-8859-1")).count() < count) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toString("ISO-8859-1")
    }

    @Test fun servesStatus() {
        val text = exchange("GET /api/status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertTrue(text, text.startsWith("HTTP/1.0 200 OK"))
        assertTrue(text.endsWith("""{"state":"streaming"}"""))
    }

    @Test fun badRequestGets400() {
        assertTrue(exchange("NONSENSE\r\n\r\n").startsWith("HTTP/1.0 400"))
    }

    @Test fun authRequiredWhenSet() {
        backend.user = "user"; backend.pass = "pa:ss"
        assertTrue(exchange("GET /api/status HTTP/1.1\r\n\r\n").startsWith("HTTP/1.0 401"))
        assertTrue(exchange("GET /api/status HTTP/1.1\r\nAuthorization: Basic dXNlcjpwYTpzcw==\r\n\r\n").startsWith("HTTP/1.0 200"))
    }

    @Test fun mjpegStreamsPartsAndCountsClients() {
        open("GET /mjpeg?fps=15 HTTP/1.1\r\n\r\n").use { s ->
            val text = readParts(s.getInputStream(), 3)
            assertTrue(text.contains("multipart/x-mixed-replace; boundary=ancameraframe"))
            assertTrue(Regex("--ancameraframe").findAll(text).count() >= 3)
            assertEquals(1, server.mjpegClients)
        }
        Thread.sleep(500) // the server sees the closed socket on its next write
        assertEquals(0, server.mjpegClients)
    }

    @Test fun ninthConnectionGets503() {
        val streams = (1..HttpServer.MAX_CONNECTIONS).map { open("GET /mjpeg HTTP/1.1\r\n\r\n") }
        try {
            streams.forEach { readParts(it.getInputStream(), 1) }
            assertTrue(exchange("GET /api/status HTTP/1.1\r\n\r\n").startsWith("HTTP/1.0 503"))
        } finally {
            streams.forEach { it.close() }
        }
    }

    @Test(expected = java.io.IOException::class) fun portInUseThrows() {
        HttpServer(port, Router(backend, ByteArray(0)), backend).start()
    }

    @Test fun stalledMjpegClientIsDropped() {
        val big = FakeBackend(frame = ByteArray(1 shl 20))
        val p = ServerSocket(0).use { it.localPort }
        val s2 = HttpServer(p, Router(big, ByteArray(0)), big, writeTimeoutMs = 500)
        s2.start()
        val client = Socket()
        try {
            client.receiveBufferSize = 4096
            client.connect(InetSocketAddress("127.0.0.1", p))
            client.getOutputStream().write("GET /mjpeg?fps=15 HTTP/1.1\r\n\r\n".toByteArray())
            // The client never reads, so the server's send buffer fills and its write blocks.
            var seen = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (s2.mjpegClients == 1) seen = true
                if (seen && s2.mjpegClients == 0) break
                Thread.sleep(50)
            }
            assertTrue("stream never started", seen)
            assertEquals(0, s2.mjpegClients)
        } finally {
            client.close()
            s2.stop()
        }
    }

    @Test fun slowHeadersAreClosedAtTheDeadline() {
        val p = ServerSocket(0).use { it.localPort }
        val s2 = HttpServer(p, Router(backend, ByteArray(0)), backend, headerDeadlineMs = 500)
        s2.start()
        try {
            Socket("127.0.0.1", p).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().write("GET /api/sta".toByteArray())
                client.getOutputStream().flush()
                val started = System.currentTimeMillis()
                // Read returns -1 (or throws a reset) when the server closes the socket.
                val n = try {
                    client.getInputStream().read()
                } catch (_: java.net.SocketTimeoutException) {
                    -2 // the server did not close the socket
                } catch (_: java.io.IOException) {
                    -1
                }
                val took = System.currentTimeMillis() - started
                assertEquals(-1, n)
                assertTrue("closed after $took ms", took < 2000)
            }
        } finally {
            s2.stop()
        }
    }
}
