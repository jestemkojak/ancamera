package com.ancamera.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsJsonTest {
    private val caps = Capabilities(
        mapOf(Facing.BACK to listOf(Size(1280, 720), Size(640, 480)), Facing.FRONT to listOf(Size(640, 480))),
        maxFps = 30,
        sceneModes = mapOf(Facing.BACK to listOf("auto", "sports")),
        isoValues = mapOf(Facing.BACK to listOf("auto", "ISO100")),
        exposure = mapOf(Facing.BACK to ExposureRange(-12, 12, 1f / 6)),
    )

    @Test fun jsonHasSettingsAndAllowedValues() {
        val json = SettingsJson.toJson(Settings(username = "u", password = "secret"), caps)
        val s = json.getJSONObject("settings")
        assertEquals("back", s.getString("camera"))
        assertEquals("1280x720", s.getString("size"))
        assertEquals(true, s.getBoolean("passwordSet"))
        assertFalse(s.has("password"))
        assertFalse(json.toString().contains("secret"))
        val allowed = json.getJSONObject("allowed")
        assertEquals("640x480", allowed.getJSONObject("size").getJSONArray("back").getString(1))
        assertEquals(2, allowed.getJSONArray("camera").length())
        assertEquals(30, allowed.getJSONArray("fps").getInt(1))
    }

    @Test fun jsonHasExposureSettingsAndAllowedValuesPerCamera() {
        val json = SettingsJson.toJson(Settings(exposureCompensation = -3, sceneMode = "sports", iso = "ISO100"), caps)
        val s = json.getJSONObject("settings")
        assertEquals(-3, s.getInt("exposureCompensation"))
        assertEquals("sports", s.getString("sceneMode"))
        assertEquals("ISO100", s.getString("iso"))
        val allowed = json.getJSONObject("allowed")
        assertEquals("sports", allowed.getJSONObject("sceneMode").getJSONArray("back").getString(1))
        assertEquals("ISO100", allowed.getJSONObject("iso").getJSONArray("back").getString(1))
        val back = allowed.getJSONObject("exposureCompensation").getJSONObject("back")
        assertEquals(-12, back.getInt("min"))
        assertEquals(12, back.getInt("max"))
        assertEquals(0.16666667, back.getDouble("step"), 1e-9)
        // A camera with no data allows only the defaults.
        assertEquals("[\"auto\"]", allowed.getJSONObject("sceneMode").getJSONArray("front").toString())
        assertEquals("[\"auto\"]", allowed.getJSONObject("iso").getJSONArray("front").toString())
        val front = allowed.getJSONObject("exposureCompensation").getJSONObject("front")
        assertEquals(0, front.getInt("min"))
        assertEquals(0, front.getInt("max"))
    }

    @Test fun exposureSettingsRoundTrip() {
        val saved = Settings(exposureCompensation = 4, sceneMode = "sports", iso = "ISO100")
        val out = SettingsJson.toJson(saved, caps).getJSONObject("settings")
        val body = JSONObject()
        for (key in listOf("exposureCompensation", "sceneMode", "iso")) body.put(key, out.get(key))
        val patch = SettingsJson.patchFromJson(JSONObject(body.toString()))
        val r = SettingsRules.applyPatch(Settings(), patch, caps, fromWeb = true) as PatchResult.Ok
        assertEquals(saved, r.settings)
    }

    @Test fun oldJsonWithoutExposureSettingsGivesDefaults() {
        val patch = SettingsJson.patchFromJson(JSONObject("""{"camera":"back","size":"640x480","fps":15}"""))
        val r = SettingsRules.applyPatch(Settings(), patch, caps, fromWeb = true) as PatchResult.Ok
        assertEquals(0, r.settings.exposureCompensation)
        assertEquals("auto", r.settings.sceneMode)
        assertEquals("auto", r.settings.iso)
    }

    @Test fun patchFromJsonKeepsTypesAndNull() {
        val patch = SettingsJson.patchFromJson(JSONObject("""{"fps":15,"torch":true,"size":"640x480","x":null}"""))
        assertEquals(15, patch["fps"])
        assertEquals(true, patch["torch"])
        assertEquals("640x480", patch["size"])
        assertNull(patch["x"])
        assertEquals(4, patch.size)
    }
}
