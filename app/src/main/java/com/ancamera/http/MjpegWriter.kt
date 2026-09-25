package com.ancamera.http

import java.io.OutputStream

/** Writes a multipart/x-mixed-replace MJPEG stream. */
class MjpegWriter(private val out: OutputStream) {
    fun writeHeader() {
        out.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Content-Type: $CONTENT_TYPE\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()
    }

    fun writeFrame(jpeg: ByteArray) {
        out.write(
            ("--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.write(jpeg)
        out.write(CRLF)
        out.flush()
    }

    companion object {
        const val BOUNDARY = "ancameraframe"
        const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
