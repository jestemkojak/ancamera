package com.ancamera.settings

import android.content.Context

/** Keeps [Settings] in SharedPreferences. Values are not validated here; see [SettingsRules.sanitize]. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            facing = Facing.fromWire(prefs.getString("camera", d.facing.wire)) ?: d.facing,
            size = Size.parse(prefs.getString("size", d.size.toString())) ?: d.size,
            fps = prefs.getInt("fps", d.fps),
            bitrate = prefs.getInt("bitrate", d.bitrate),
            rotation = prefs.getInt("rotation", d.rotation),
            torch = prefs.getBoolean("torch", d.torch),
            mjpegQuality = prefs.getInt("mjpegQuality", d.mjpegQuality),
            rtspPort = prefs.getInt("rtspPort", d.rtspPort),
            httpPort = prefs.getInt("httpPort", d.httpPort),
            username = prefs.getString("username", d.username) ?: "",
            password = prefs.getString("password", d.password) ?: "",
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("camera", s.facing.wire)
            .putString("size", s.size.toString())
            .putInt("fps", s.fps)
            .putInt("bitrate", s.bitrate)
            .putInt("rotation", s.rotation)
            .putBoolean("torch", s.torch)
            .putInt("mjpegQuality", s.mjpegQuality)
            .putInt("rtspPort", s.rtspPort)
            .putInt("httpPort", s.httpPort)
            .putString("username", s.username)
            .putString("password", s.password)
            .apply()
    }
}
