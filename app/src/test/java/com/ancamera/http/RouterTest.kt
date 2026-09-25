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
        assertEquals(200, status(router.route(req("POST", "/api/settings", body = """{"fps":15}"""))))
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
}
