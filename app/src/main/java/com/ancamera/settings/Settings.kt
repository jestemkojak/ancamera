package com.ancamera.settings

enum class Facing(val wire: String) {
    BACK("back"),
    FRONT("front");

    companion object {
        fun fromWire(value: String?): Facing? = values().firstOrNull { it.wire == value }
    }
}

data class Size(val width: Int, val height: Int) {
    val area: Int get() = width * height

    override fun toString(): String = "${width}x$height"

    companion object {
        /** Parses "1280x720". Returns null for anything else. */
        fun parse(value: String?): Size? {
            val parts = value?.split('x') ?: return null
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return if (w > 0 && h > 0) Size(w, h) else null
        }
    }
}

data class Settings(
    val facing: Facing = Facing.BACK,
    val size: Size = Size(1280, 720),
    val fps: Int = 20,
    val bitrate: Int = 2_500_000,
    val rotation: Int = 0,
    val torch: Boolean = false,
    val mjpegQuality: Int = 70,
    val rtspPort: Int = 8554,
    val httpPort: Int = 8080,
    val username: String = "",
    val password: String = "",
) {
    val authEnabled: Boolean get() = username.isNotEmpty() && password.isNotEmpty()
}

/** What the phone supports. Filled from the camera when the service starts. */
data class Capabilities(
    val sizes: Map<Facing, List<Size>>,
    val maxFps: Int = 30,
) {
    val facings: List<Facing> get() = Facing.values().filter { !sizes[it].isNullOrEmpty() }
}

/** How much work a settings change needs. Ordered from cheap to expensive. */
enum class ApplyKind(val wire: String) {
    NONE("none"),
    LIVE("live"),
    STREAM_RESTART("stream_restart"),
    SERVER_RESTART("server_restart"),
}

sealed class PatchResult {
    data class Ok(val settings: Settings, val kind: ApplyKind) : PatchResult()
    data class Invalid(val field: String, val reason: String) : PatchResult()
}
