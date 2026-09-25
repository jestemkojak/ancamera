package com.ancamera.stream

import org.json.JSONObject

/** Everything GET /api/status reports. Plain data, so JVM tests cover the JSON. */
data class StatusSnapshot(
    val state: EngineState,
    val cameraId: Int,
    val facing: String,
    val size: String,
    val fps: Int,
    val rtspClients: Int,
    val mjpegClients: Int,
    val batteryPercent: Int,
    val batteryTempC: Double,
    val uptimeSec: Long,
    val lastError: String?,
    val ip: String?,
    val rtspPort: Int,
    val httpPort: Int,
    val authEnabled: Boolean,
)

object StatusJson {
    const val HOT_BATTERY_C = 45.0

    fun toJson(s: StatusSnapshot): JSONObject {
        val host = s.ip ?: "<phone-ip>"
        return JSONObject()
            .put("state", s.state.wire)
            .put("cameraId", s.cameraId)
            .put("facing", s.facing)
            .put("size", s.size)
            .put("fps", s.fps)
            .put("rtspClients", s.rtspClients)
            .put("mjpegClients", s.mjpegClients)
            .put("batteryPercent", s.batteryPercent)
            .put("batteryTempC", s.batteryTempC)
            .put("batteryHot", s.batteryTempC > HOT_BATTERY_C)
            .put("uptimeSec", s.uptimeSec)
            .put("lastError", s.lastError ?: JSONObject.NULL)
            .put("ip", s.ip ?: JSONObject.NULL)
            .put("rtspUrl", "rtsp://$host:${s.rtspPort}/")
            .put("httpUrl", "http://$host:${s.httpPort}/")
            .put("authEnabled", s.authEnabled)
    }
}
