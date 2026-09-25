package com.ancamera.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {
    private val caps = Capabilities(
        sizes = mapOf(
            Facing.BACK to listOf(Size(1920, 1080), Size(1280, 720), Size(640, 480)),
            Facing.FRONT to listOf(Size(1280, 960), Size(640, 480)),
        ),
        maxFps = 30,
    )
    private val base = Settings()

    private fun ok(patch: Map<String, Any?>, current: Settings = base, fromWeb: Boolean = true): PatchResult.Ok {
        val r = SettingsRules.applyPatch(current, patch, caps, fromWeb)
        assertTrue("expected Ok, got $r", r is PatchResult.Ok)
        return r as PatchResult.Ok
    }

    private fun invalidField(patch: Map<String, Any?>, current: Settings = base, fromWeb: Boolean = true): String {
        val r = SettingsRules.applyPatch(current, patch, caps, fromWeb)
        assertTrue("expected Invalid, got $r", r is PatchResult.Invalid)
        return (r as PatchResult.Invalid).field
    }

    @Test fun defaultsMatchSpec() {
        assertEquals(Facing.BACK, base.facing)
        assertEquals(Size(1280, 720), base.size)
        assertEquals(20, base.fps)
        assertEquals(2_500_000, base.bitrate)
        assertEquals(70, base.mjpegQuality)
        assertEquals(8554, base.rtspPort)
        assertEquals(8080, base.httpPort)
        assertEquals(false, base.authEnabled)
    }

    @Test fun sizeParse() {
        assertEquals(Size(640, 480), Size.parse("640x480"))
        assertEquals(null, Size.parse("640"))
        assertEquals(null, Size.parse("0x480"))
        assertEquals(null, Size.parse("axb"))
        assertEquals(null, Size.parse(null))
    }

    @Test fun bitrateIsLive() {
        val r = ok(mapOf("bitrate" to 4_000_000))
        assertEquals(4_000_000, r.settings.bitrate)
        assertEquals(ApplyKind.LIVE, r.kind)
    }

    @Test fun torchAndQualityAreLive() {
        assertEquals(ApplyKind.LIVE, ok(mapOf("torch" to true)).kind)
        assertEquals(ApplyKind.LIVE, ok(mapOf("mjpegQuality" to 50)).kind)
    }

    @Test fun sizeFpsRotationCameraRestartStream() {
        assertEquals(ApplyKind.STREAM_RESTART, ok(mapOf("size" to "640x480")).kind)
        assertEquals(ApplyKind.STREAM_RESTART, ok(mapOf("fps" to 15)).kind)
        assertEquals(ApplyKind.STREAM_RESTART, ok(mapOf("rotation" to 90)).kind)
        assertEquals(ApplyKind.STREAM_RESTART, ok(mapOf("camera" to "front")).kind)
    }

    @Test fun portsRestartServer() {
        assertEquals(ApplyKind.SERVER_RESTART, ok(mapOf("httpPort" to 8081)).kind)
        assertEquals(ApplyKind.SERVER_RESTART, ok(mapOf("rtspPort" to 8555)).kind)
    }

    @Test fun sameValueIsNone() {
        assertEquals(ApplyKind.NONE, ok(mapOf("fps" to 20)).kind)
    }

    @Test fun jsonNumberTypesAccepted() {
        assertEquals(15, ok(mapOf("fps" to 15L)).settings.fps)
        assertEquals(15, ok(mapOf("fps" to 15.0)).settings.fps)
        assertEquals("fps", invalidField(mapOf("fps" to 15.5)))
        assertEquals("fps", invalidField(mapOf("fps" to "15")))
    }

    @Test fun rangesEnforced() {
        assertEquals("fps", invalidField(mapOf("fps" to 0)))
        assertEquals("fps", invalidField(mapOf("fps" to 31)))
        assertEquals("bitrate", invalidField(mapOf("bitrate" to 50_000)))
        assertEquals("rotation", invalidField(mapOf("rotation" to 45)))
        assertEquals("mjpegQuality", invalidField(mapOf("mjpegQuality" to 100)))
        assertEquals("httpPort", invalidField(mapOf("httpPort" to 80)))
        assertEquals("httpPort", invalidField(mapOf("httpPort" to 8554)))
        assertEquals("torch", invalidField(mapOf("torch" to "yes")))
        assertEquals("nope", invalidField(mapOf("nope" to 1)))
    }

    @Test fun unsupportedSizeRejected() {
        assertEquals("size", invalidField(mapOf("size" to "1920x1080", "camera" to "front")))
        assertEquals("size", invalidField(mapOf("size" to "800x600")))
        assertEquals("size", invalidField(mapOf("size" to "big")))
    }

    @Test fun cameraSwitchPicksClosestSize() {
        val r = ok(mapOf("camera" to "front"))
        assertEquals(Facing.FRONT, r.settings.facing)
        assertEquals(Size(1280, 960), r.settings.size)
    }

    @Test fun missingCameraRejected() {
        val backOnly = Capabilities(mapOf(Facing.BACK to listOf(Size(1280, 720))))
        val r = SettingsRules.applyPatch(base, mapOf("camera" to "front"), backOnly, fromWeb = true)
        assertEquals("camera", (r as PatchResult.Invalid).field)
    }

    @Test fun webCannotSetFirstPassword() {
        assertEquals("password", invalidField(mapOf("username" to "u", "password" to "p")))
    }

    @Test fun phoneCanSetFirstPassword() {
        val r = ok(mapOf("username" to "u", "password" to "p"), fromWeb = false)
        assertTrue(r.settings.authEnabled)
        assertEquals(ApplyKind.SERVER_RESTART, r.kind)
    }

    @Test fun webCanChangeExistingPassword() {
        val withAuth = base.copy(username = "u", password = "p")
        val r = ok(mapOf("password" to "q"), current = withAuth)
        assertEquals("q", r.settings.password)
    }

    @Test fun usernameAndPasswordGoTogether() {
        assertEquals("password", invalidField(mapOf("username" to "u"), fromWeb = false))
        val withAuth = base.copy(username = "u", password = "p")
        assertEquals("password", invalidField(mapOf("password" to ""), current = withAuth))
        val cleared = ok(mapOf("username" to "", "password" to ""), current = withAuth)
        assertEquals(false, cleared.settings.authEnabled)
    }

    @Test fun sanitizeFixesBadSavedValues() {
        val bad = Settings(
            facing = Facing.FRONT, size = Size(1920, 1080), fps = 99, bitrate = 1, rotation = 7,
            mjpegQuality = 0, rtspPort = 80, httpPort = 80, username = "u", password = "",
        )
        val s = SettingsRules.sanitize(bad, caps)
        assertEquals(Facing.FRONT, s.facing)
        assertEquals(Size(1280, 960), s.size)
        assertEquals(20, s.fps)
        assertEquals(2_500_000, s.bitrate)
        assertEquals(0, s.rotation)
        assertEquals(70, s.mjpegQuality)
        assertEquals(8554, s.rtspPort)
        assertEquals(8080, s.httpPort)
        assertEquals("", s.username)
    }

    @Test fun sanitizeKeepsGoodValues() {
        val good = Settings(size = Size(640, 480), fps = 15)
        assertEquals(good, SettingsRules.sanitize(good, caps))
    }

    @Test fun sanitizeFallsBackWhenCameraMissing() {
        val backOnly = Capabilities(mapOf(Facing.BACK to listOf(Size(1280, 720))))
        assertEquals(Facing.BACK, SettingsRules.sanitize(Settings(facing = Facing.FRONT), backOnly).facing)
    }
}
