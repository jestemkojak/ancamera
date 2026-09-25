package com.ancamera.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsJsonTest {
    private val caps = Capabilities(mapOf(Facing.BACK to listOf(Size(1280, 720), Size(640, 480))), maxFps = 30)

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
        assertEquals(1, allowed.getJSONArray("camera").length())
        assertEquals(30, allowed.getJSONArray("fps").getInt(1))
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
