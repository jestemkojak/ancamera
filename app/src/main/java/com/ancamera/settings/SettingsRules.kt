package com.ancamera.settings

/** Validation and change classification. No Android dependencies, so JVM tests cover it. */
object SettingsRules {
    const val MIN_BITRATE = 100_000
    const val MAX_BITRATE = 20_000_000
    const val MIN_FPS = 1
    const val MIN_QUALITY = 10
    const val MAX_QUALITY = 95
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    val ROTATIONS = setOf(0, 90, 180, 270)

    /**
     * Applies a partial update. Keys: camera, size, fps, bitrate, rotation, torch,
     * mjpegQuality, rtspPort, httpPort, username, password.
     * [fromWeb] = true blocks setting the first password (only the phone can do that).
     */
    fun applyPatch(
        current: Settings,
        patch: Map<String, Any?>,
        caps: Capabilities,
        fromWeb: Boolean,
    ): PatchResult {
        var s = current
        for ((key, value) in patch) {
            s = when (key) {
                "camera" -> s.copy(facing = Facing.fromWire(value as? String)
                    ?: return PatchResult.Invalid(key, "must be back or front"))
                "size" -> s.copy(size = Size.parse(value as? String)
                    ?: return PatchResult.Invalid(key, "must look like 1280x720"))
                "fps" -> s.copy(fps = intOf(value) ?: return PatchResult.Invalid(key, "must be a number"))
                "bitrate" -> s.copy(bitrate = intOf(value) ?: return PatchResult.Invalid(key, "must be a number"))
                "rotation" -> s.copy(rotation = intOf(value) ?: return PatchResult.Invalid(key, "must be a number"))
                "torch" -> s.copy(torch = value as? Boolean ?: return PatchResult.Invalid(key, "must be true or false"))
                "mjpegQuality" -> s.copy(mjpegQuality = intOf(value) ?: return PatchResult.Invalid(key, "must be a number"))
                "rtspPort" -> s.copy(rtspPort = intOf(value) ?: return PatchResult.Invalid(key, "must be a number"))
                "httpPort" -> s.copy(httpPort = intOf(value) ?: return PatchResult.Invalid(key, "must be a number"))
                "username" -> s.copy(username = value as? String ?: return PatchResult.Invalid(key, "must be text"))
                "password" -> s.copy(password = value as? String ?: return PatchResult.Invalid(key, "must be text"))
                else -> return PatchResult.Invalid(key, "unknown setting")
            }
        }

        val touchesCredentials = patch.containsKey("username") || patch.containsKey("password")
        if (fromWeb && touchesCredentials && !current.authEnabled) {
            return PatchResult.Invalid("password", "set the first password on the phone")
        }
        if (s.username.isEmpty() != s.password.isEmpty()) {
            return PatchResult.Invalid("password", "set both username and password, or neither")
        }

        // A camera change keeps working without an explicit size: pick the closest size that camera has.
        val sizes = caps.sizes[s.facing].orEmpty()
        if (caps.sizes.isNotEmpty()) {
            if (sizes.isEmpty()) return PatchResult.Invalid("camera", "this phone has no ${s.facing.wire} camera")
            if (s.size !in sizes) {
                if (patch.containsKey("size")) return PatchResult.Invalid("size", "not supported by the ${s.facing.wire} camera")
                s = s.copy(size = closestSize(sizes, s.size))
            }
        }

        fieldError(s, caps)?.let { return it }
        return PatchResult.Ok(s, classify(current, s))
    }

    /** Replaces each bad field of saved settings with its default. Used at start-up. */
    fun sanitize(saved: Settings, caps: Capabilities): Settings {
        val d = Settings()
        var s = saved
        if (s.fps !in MIN_FPS..caps.maxFps) s = s.copy(fps = minOf(d.fps, caps.maxFps))
        if (s.bitrate !in MIN_BITRATE..MAX_BITRATE) s = s.copy(bitrate = d.bitrate)
        if (s.rotation !in ROTATIONS) s = s.copy(rotation = d.rotation)
        if (s.mjpegQuality !in MIN_QUALITY..MAX_QUALITY) s = s.copy(mjpegQuality = d.mjpegQuality)
        if (s.rtspPort !in MIN_PORT..MAX_PORT) s = s.copy(rtspPort = d.rtspPort)
        if (s.httpPort !in MIN_PORT..MAX_PORT) s = s.copy(httpPort = d.httpPort)
        if (s.rtspPort == s.httpPort) s = s.copy(rtspPort = d.rtspPort, httpPort = d.httpPort)
        if (s.username.isEmpty() != s.password.isEmpty()) s = s.copy(username = "", password = "")
        if (caps.sizes[s.facing].isNullOrEmpty()) s = s.copy(facing = caps.facings.firstOrNull() ?: d.facing)
        val sizes = caps.sizes[s.facing].orEmpty()
        if (sizes.isNotEmpty() && s.size !in sizes) s = s.copy(size = closestSize(sizes, d.size))
        return s
    }

    fun classify(old: Settings, new: Settings): ApplyKind = when {
        old.rtspPort != new.rtspPort || old.httpPort != new.httpPort ||
            old.username != new.username || old.password != new.password -> ApplyKind.SERVER_RESTART
        old.facing != new.facing || old.size != new.size || old.fps != new.fps ||
            old.rotation != new.rotation -> ApplyKind.STREAM_RESTART
        old.bitrate != new.bitrate || old.torch != new.torch ||
            old.mjpegQuality != new.mjpegQuality -> ApplyKind.LIVE
        else -> ApplyKind.NONE
    }

    fun closestSize(sizes: List<Size>, target: Size): Size =
        sizes.minByOrNull { Math.abs(it.area - target.area) } ?: target

    private fun fieldError(s: Settings, caps: Capabilities): PatchResult.Invalid? = when {
        s.fps !in MIN_FPS..caps.maxFps -> PatchResult.Invalid("fps", "must be $MIN_FPS..${caps.maxFps}")
        s.bitrate !in MIN_BITRATE..MAX_BITRATE -> PatchResult.Invalid("bitrate", "must be $MIN_BITRATE..$MAX_BITRATE")
        s.rotation !in ROTATIONS -> PatchResult.Invalid("rotation", "must be 0, 90, 180 or 270")
        s.mjpegQuality !in MIN_QUALITY..MAX_QUALITY -> PatchResult.Invalid("mjpegQuality", "must be $MIN_QUALITY..$MAX_QUALITY")
        s.rtspPort !in MIN_PORT..MAX_PORT -> PatchResult.Invalid("rtspPort", "must be $MIN_PORT..$MAX_PORT")
        s.httpPort !in MIN_PORT..MAX_PORT -> PatchResult.Invalid("httpPort", "must be $MIN_PORT..$MAX_PORT")
        s.rtspPort == s.httpPort -> PatchResult.Invalid("httpPort", "must differ from rtspPort")
        else -> null
    }

    /** JSON numbers arrive as Int, Long or Double. Accept only whole numbers. */
    private fun intOf(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
        is Double -> if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else null
        else -> null
    }
}
