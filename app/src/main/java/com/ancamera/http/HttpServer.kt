package com.ancamera.http

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Small blocking HTTP/1.0 server: one thread for each connection, at most [MAX_CONNECTIONS]. */
class HttpServer(
    private val port: Int,
    private val router: Router,
    private val backend: HttpBackend,
) {
    private val active = AtomicInteger(0)
    private val mjpeg = AtomicInteger(0)
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var running = false
    private var server: ServerSocket? = null

    val mjpegClients: Int get() = mjpeg.get()

    /** Binds the port. Throws IOException when the port is in use. */
    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        Thread({ acceptLoop(ss) }, "http-accept").start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        synchronized(sockets) {
            for (s in sockets) try { s.close() } catch (_: IOException) {}
            sockets.clear()
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (e: IOException) { break }
            if (active.incrementAndGet() > MAX_CONNECTIONS) {
                active.decrementAndGet()
                try {
                    HttpResponse.text(503, "too many connections").writeTo(socket.getOutputStream())
                } catch (_: IOException) {
                } finally {
                    try { socket.close() } catch (_: IOException) {}
                }
                continue
            }
            sockets.add(socket)
            Thread({
                try {
                    handle(socket)
                } finally {
                    sockets.remove(socket)
                    try { socket.close() } catch (_: IOException) {}
                    active.decrementAndGet()
                }
            }, "http-conn").start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val req = try {
                HttpRequest.parse(socket.getInputStream()) ?: return
            } catch (e: BadRequestException) {
                HttpResponse.text(400, e.message ?: "bad request").writeTo(socket.getOutputStream())
                return
            }
            when (val routed = router.route(req)) {
                is Routed.Response -> routed.response.writeTo(socket.getOutputStream())
                is Routed.Mjpeg -> streamMjpeg(socket, routed.fps)
            }
        } catch (_: IOException) {
            // Client went away or timed out. Nothing to do.
        } catch (e: RuntimeException) {
            try { HttpResponse.text(500, "internal error").writeTo(socket.getOutputStream()) } catch (_: IOException) {}
        }
    }

    private fun streamMjpeg(socket: Socket, fps: Int) {
        val writer = MjpegWriter(socket.getOutputStream())
        val frameMs = 1000L / fps
        mjpeg.incrementAndGet()
        try {
            writer.writeHeader()
            while (running) {
                val started = System.currentTimeMillis()
                val jpeg = backend.jpegFrame(FRAME_TIMEOUT_MS)
                if (jpeg != null) writer.writeFrame(jpeg)
                val wait = frameMs - (System.currentTimeMillis() - started)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
        } finally {
            mjpeg.decrementAndGet()
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        const val READ_TIMEOUT_MS = 10_000
        const val FRAME_TIMEOUT_MS = 2_000L
    }
}
