package com.ancamera.settings

import org.json.JSONArray
import org.json.JSONObject

object SettingsJson {
    /** Settings and allowed values for GET /api/settings. Never contains the password. */
    fun toJson(s: Settings, caps: Capabilities): JSONObject {
        val settings = JSONObject()
            .put("camera", s.facing.wire)
            .put("size", s.size.toString())
            .put("fps", s.fps)
            .put("bitrate", s.bitrate)
            .put("rotation", s.rotation)
            .put("torch", s.torch)
            .put("mjpegQuality", s.mjpegQuality)
            .put("rtspPort", s.rtspPort)
            .put("httpPort", s.httpPort)
            .put("username", s.username)
            .put("passwordSet", s.authEnabled)
        val sizes = JSONObject()
        for (facing in caps.facings) {
            sizes.put(facing.wire, JSONArray(caps.sizes[facing].orEmpty().map { it.toString() }))
        }
        val allowed = JSONObject()
            .put("camera", JSONArray(caps.facings.map { it.wire }))
            .put("size", sizes)
            .put("fps", JSONArray(listOf(SettingsRules.MIN_FPS, caps.maxFps)))
            .put("bitrate", JSONArray(listOf(SettingsRules.MIN_BITRATE, SettingsRules.MAX_BITRATE)))
            .put("rotation", JSONArray(SettingsRules.ROTATIONS.sorted()))
            .put("mjpegQuality", JSONArray(listOf(SettingsRules.MIN_QUALITY, SettingsRules.MAX_QUALITY)))
        return JSONObject().put("settings", settings).put("allowed", allowed)
    }

    /** Converts a POST body object to a patch map. JSON null becomes Kotlin null. */
    fun patchFromJson(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.get(key)
            out[key] = if (value == JSONObject.NULL) null else value
        }
        return out
    }
}
