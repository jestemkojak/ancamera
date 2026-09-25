package com.ancamera.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    private val backend = FakeBackend()
    private val router = Router(backend, "<html>".toByteArray())

    private fun req(method: String, path: String, query: Map<String, String> = emptyMap(),
                    headers: Map<String, String> = emptyMap(), body: String = "") =
        HttpRequest(method, path, query, headers, body.toByteArray())

    private fun status(r: Routed) = (r as Routed.Response).response.status

    @Test fun servesIndex() {
        val r = router.route(req("GET", "/")) as Routed.Response
        assertEquals(200, r.response.status)
        assertEquals(HttpResponse.HTML, r.response.contentType)
    }

    @Test fun statusAndSettingsAreJson() {
        assertEquals(HttpResponse.JSON, (router.route(req("GET", "/api/status")) as Routed.Response).response.contentType)
        assertEquals(200, status(router.route(req("GET", "/api/settings"))))
    }

    @Test fun postSettingsPassesBody() {
        val json = mapOf("content-type" to "application/json")
        assertEquals(200, status(router.route(req("POST", "/api/settings", headers = json, body = """{"fps":15}"""))))
        assertEquals("""{"fps":15}""", backend.lastPatch)
    }

    @Test fun wrongMethodIs405() {
        assertEquals(405, status(router.route(req("POST", "/api/status"))))
        assertEquals(405, status(router.route(req("DELETE", "/api/settings"))))
        assertEquals(405, status(router.route(req("HEAD", "/"))))
    }

    @Test fun unknownPathIs404() = assertEquals(404, status(router.route(req("GET", "/nope"))))

    @Test fun snapshotGivesJpegOr503() {
        val ok = router.route(req("GET", "/snapshot.jpg")) as Routed.Response
        assertEquals(HttpResponse.JPEG, ok.response.contentType)
        backend.frame = null
        assertEquals(503, status(router.route(req("GET", "/snapshot.jpg"))))
    }

    @Test fun mjpegFpsIsClamped() {
        assertEquals(5, (router.route(req("GET", "/mjpeg")) as Routed.Mjpeg).fps)
        assertEquals(15, (router.route(req("GET", "/mjpeg", mapOf("fps" to "99"))) as Routed.Mjpeg).fps)
        assertEquals(1, (router.route(req("GET", "/mjpeg", mapOf("fps" to "0"))) as Routed.Mjpeg).fps)
        assertEquals(5, (router.route(req("GET", "/mjpeg", mapOf("fps" to "x"))) as Routed.Mjpeg).fps)
    }

    @Test fun authProtectsEveryPath() {
        backend.user = "user"; backend.pass = "pa:ss"
        for (path in listOf("/", "/mjpeg", "/snapshot.jpg", "/api/status", "/api/settings", "/nope")) {
            assertEquals(path, 401, status(router.route(req("GET", path))))
        }
        val authed = router.route(req("GET", "/api/status", headers = mapOf("authorization" to "Basic dXNlcjpwYTpzcw==")))
        assertTrue(authed is Routed.Response && authed.response.status == 200)
    }

    // ---- F1: DNS rebinding and CSRF ----

    @Test fun localHostNamesAreAllowed() {
        for (host in listOf("127.0.0.1", "127.0.0.1:18080", "192.168.1.5:8080", "[::1]:8080", "[fe80::1]",
            "::1", "localhost", "LOCALHOST:8080", "cam.local", "cam.LAN:8080", "cam.home.arpa", "cam.internal:80")) {
            val r = router.route(req("GET", "/api/status", headers = mapOf("host" to host)))
            assertEquals(host, 200, status(r))
        }
    }

    @Test fun missingHostIsAllowed() = assertEquals(200, status(router.route(req("GET", "/api/status"))))

    @Test fun otherHostNamesGet403() {
        for (host in listOf("evil.example.com", "evil.example.com:8080", "x", "local", "evil.local.example.com",
            "1.2.3", "300.1.1.1", "")) {
            val r = router.route(req("GET", "/api/status", headers = mapOf("host" to host))) as Routed.Response
            assertEquals(host, 403, r.response.status)
            assertEquals("host not allowed", r.response.body.toString(Charsets.UTF_8))
        }
    }

    @Test fun hostCheckComesBeforeAuth() {
        backend.user = "user"; backend.pass = "pa:ss"
        assertEquals(403, status(router.route(req("GET", "/", headers = mapOf("host" to "evil.example.com")))))
    }

    @Test fun postSettingsNeedsJsonContentType() {
        for (type in listOf(null, "text/plain", "application/x-www-form-urlencoded", "multipart/form-data")) {
            val headers = if (type == null) emptyMap() else mapOf("content-type" to type)
            val r = router.route(req("POST", "/api/settings", headers = headers, body = "{}")) as Routed.Response
            assertEquals("$type", 415, r.response.status)
            assertEquals("use Content-Type: application/json", r.response.body.toString(Charsets.UTF_8))
        }
        assertEquals(null, backend.lastPatch)
        for (type in listOf("application/json", "Application/JSON; charset=utf-8")) {
            assertEquals(type, 200, status(router.route(req("POST", "/api/settings", headers = mapOf("content-type" to type), body = "{}"))))
        }
    }

    @Test fun postWithSameOriginIsAllowed() {
        val headers = mapOf("host" to "192.168.1.5:8080", "origin" to "http://192.168.1.5:8080", "content-type" to "application/json")
        assertEquals(200, status(router.route(req("POST", "/api/settings", headers = headers, body = "{}"))))
        val upper = mapOf("host" to "cam.local:8080", "origin" to "HTTP://CAM.LOCAL:8080", "content-type" to "application/json")
        assertEquals(200, status(router.route(req("POST", "/api/settings", headers = upper, body = "{}"))))
    }

    @Test fun postWithOtherOriginGets403() {
        for (origin in listOf("http://evil.example.com", "http://192.168.1.5:9999", "null", "https://192.168.1.5:8080x")) {
            val headers = mapOf("host" to "192.168.1.5:8080", "origin" to origin, "content-type" to "application/json")
            val r = router.route(req("POST", "/api/settings", headers = headers, body = "{}")) as Routed.Response
            assertEquals(origin, 403, r.response.status)
            assertEquals("origin not allowed", r.response.body.toString(Charsets.UTF_8))
        }
        assertEquals(null, backend.lastPatch)
    }

    @Test fun newStatusCodesHaveReasons() {
        assertEquals("Forbidden", HttpResponse.reason(403))
        assertEquals("Unsupported Media Type", HttpResponse.reason(415))
    }
}
