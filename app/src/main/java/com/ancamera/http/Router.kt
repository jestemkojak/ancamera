package com.ancamera.http

/** What the HTTP layer needs from the rest of the app. The service implements it. */
interface HttpBackend {
    /** Current username and password. Both empty = auth off. */
    fun credentials(): Pair<String, String>
    fun statusJson(): String
    fun settingsJson(): String
    /** Validates and applies a JSON settings patch. Returns 200 with the new settings, or 400. */
    fun updateSettings(body: String): HttpResponse
    /** A current JPEG frame, or null when no frame comes within [timeoutMs]. */
    fun jpegFrame(timeoutMs: Long): ByteArray?
}

sealed class Routed {
    class Response(val response: HttpResponse) : Routed()
    class Mjpeg(val fps: Int) : Routed()
}

/** Maps a request to a response. No sockets here, so JVM tests cover it. */
class Router(private val backend: HttpBackend, private val indexHtml: ByteArray) {
    fun route(req: HttpRequest): Routed {
        val (user, pass) = backend.credentials()
        if (!BasicAuth.isAuthorized(req.header("Authorization"), user, pass)) {
            return Routed.Response(HttpResponse.unauthorized())
        }
        val get = req.method == "GET"
        return when (req.path) {
            "/", "/index.html" ->
                if (get) respond(HttpResponse(200, HttpResponse.HTML, indexHtml)) else notAllowed()
            "/mjpeg" ->
                if (get) Routed.Mjpeg(clampFps(req.query["fps"])) else notAllowed()
            "/snapshot.jpg" ->
                if (get) respond(snapshot()) else notAllowed()
            "/api/status" ->
                if (get) respond(HttpResponse.json(200, backend.statusJson())) else notAllowed()
            "/api/settings" -> when {
                get -> respond(HttpResponse.json(200, backend.settingsJson()))
                req.method == "POST" -> respond(backend.updateSettings(req.body.toString(Charsets.UTF_8)))
                else -> notAllowed()
            }
            else -> respond(HttpResponse.text(404, "not found"))
        }
    }

    private fun snapshot(): HttpResponse {
        val jpeg = backend.jpegFrame(SNAPSHOT_TIMEOUT_MS)
            ?: return HttpResponse.text(503, "no frame available")
        return HttpResponse(200, HttpResponse.JPEG, jpeg)
    }

    private fun respond(r: HttpResponse) = Routed.Response(r)
    private fun notAllowed() = Routed.Response(HttpResponse.text(405, "method not allowed"))

    companion object {
        const val DEFAULT_MJPEG_FPS = 5
        const val MAX_MJPEG_FPS = 15
        const val SNAPSHOT_TIMEOUT_MS = 3000L

        fun clampFps(raw: String?): Int {
            val fps = raw?.toIntOrNull() ?: DEFAULT_MJPEG_FPS
            return fps.coerceIn(1, MAX_MJPEG_FPS)
        }
    }
}
