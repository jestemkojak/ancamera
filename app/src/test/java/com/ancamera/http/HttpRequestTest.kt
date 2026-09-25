package com.ancamera.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpRequestTest {
    private fun parse(raw: String) = HttpRequest.parse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    @Test fun parsesGetWithQueryAndHeaders() {
        val r = parse("GET /mjpeg?fps=10&x=a%20b HTTP/1.1\r\nHost: phone\r\nAuthorization: Basic abc\r\n\r\n")!!
        assertEquals("GET", r.method)
        assertEquals("/mjpeg", r.path)
        assertEquals("10", r.query["fps"])
        assertEquals("a b", r.query["x"])
        assertEquals("Basic abc", r.header("authorization"))
        assertEquals("phone", r.header("HOST"))
        assertEquals(0, r.body.size)
    }

    @Test fun parsesPostBody() {
        val r = parse("POST /api/settings HTTP/1.1\r\nContent-Length: 11\r\n\r\n{\"fps\":15}X")!!
        assertEquals("POST", r.method)
        assertArrayEquals("{\"fps\":15}X".toByteArray(), r.body)
    }

    @Test fun acceptsBareLf() {
        assertEquals("/", parse("GET / HTTP/1.0\n\n")!!.path)
    }

    @Test fun emptyStreamGivesNull() {
        assertNull(parse(""))
    }

    @Test(expected = BadRequestException::class) fun rejectsBadRequestLine() { parse("GARBAGE\r\n\r\n") }

    @Test(expected = BadRequestException::class) fun rejectsTruncatedHeaders() { parse("GET / HTTP/1.1\r\nHost: x\r\n") }

    @Test(expected = BadRequestException::class) fun rejectsHugeBody() {
        parse("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n")
    }

    @Test(expected = BadRequestException::class) fun rejectsShortBody() {
        parse("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")
    }

    @Test(expected = BadRequestException::class) fun rejectsLongLine() {
        parse("GET /" + "a".repeat(HttpRequest.MAX_LINE_BYTES) + " HTTP/1.1\r\n\r\n")
    }
}
