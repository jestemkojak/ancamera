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
        // These checks come before auth. They stop DNS rebinding and cross-site requests.
        val host = req.header("Host")
        if (host != null && !isAllowedHost(host)) return respond(HttpResponse.text(403, "host not allowed"))
        if (req.method == "POST") {
            val origin = req.header("Origin")
            if (origin != null && !isSameOrigin(origin, host)) return respond(HttpResponse.text(403, "origin not allowed"))
            if (req.path == "/api/settings" && !isJson(req.header("Content-Type"))) {
                return respond(HttpResponse.text(415, "use Content-Type: application/json"))
            }
        }
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

        private val IPV4 = Regex("""(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}""")
        private val IPV6 = Regex("""[0-9a-fA-F:.]*:[0-9a-fA-F:.]*(%[0-9a-zA-Z._-]+)?""")
        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

        /**
         * True when the Host header names this phone on the LAN: an IP literal, localhost, or a
         * name with a local suffix. A public name can point to the phone by DNS rebinding.
         */
        fun isAllowedHost(hostHeader: String): Boolean {
            val h = hostHeader.trim()
            val name = when {
                h.startsWith("[") -> {
                    val end = h.indexOf(']')
                    if (end < 0) return false
                    val rest = h.substring(end + 1)
                    if (rest.isNotEmpty() && !isPort(rest)) return false
                    return IPV6.matches(h.substring(1, end))
                }
                h.count { it == ':' } > 1 -> return IPV6.matches(h)
                h.contains(':') -> {
                    val colon = h.indexOf(':')
                    if (!isPort(h.substring(colon))) return false
                    h.substring(0, colon)
                }
                else -> h
            }.lowercase()
            if (name.isEmpty()) return false
            if (IPV4.matches(name) || name == "localhost") return true
            return LOCAL_SUFFIXES.any { name.endsWith(it) && name.length > it.length }
        }

        private fun isPort(s: String) = s.length > 1 && s[0] == ':' && s.substring(1).all { it in '0'..'9' }

        /** True when the Origin header (without "http://") is the same as the Host header. */
        fun isSameOrigin(origin: String, host: String?): Boolean {
            if (host == null) return false
            val o = origin.trim()
            if (!o.startsWith("http://", ignoreCase = true)) return false
            return o.substring("http://".length).equals(host.trim(), ignoreCase = true)
        }

        private fun isJson(contentType: String?): Boolean =
            contentType != null && contentType.trim().lowercase().let {
                it == "application/json" || it.startsWith("application/json;") || it.startsWith("application/json ")
            }

        fun clampFps(raw: String?): Int {
            val fps = raw?.toIntOrNull() ?: DEFAULT_MJPEG_FPS
            return fps.coerceIn(1, MAX_MJPEG_FPS)
        }
    }
}
