package com.ancamera.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusJsonTest {
    private val snap = StatusSnapshot(
        state = EngineState.STREAMING, cameraId = 0, facing = "back", size = "1280x720", fps = 17,
        rtspClients = 1, mjpegClients = 2, batteryPercent = 80, batteryTempC = 46.5, uptimeSec = 60,
        lastError = null, ip = "192.168.1.20", rtspPort = 8554, httpPort = 8080, authEnabled = false,
    )

    @Test fun reportsFieldsAndUrls() {
        val j = StatusJson.toJson(snap)
        assertEquals("streaming", j.getString("state"))
        assertEquals(0, j.getInt("cameraId"))
        assertEquals(17, j.getInt("fps"))
        assertEquals(2, j.getInt("mjpegClients"))
        assertEquals("rtsp://192.168.1.20:8554/", j.getString("rtspUrl"))
        assertEquals("http://192.168.1.20:8080/", j.getString("httpUrl"))
        assertTrue(j.isNull("lastError"))
    }

    @Test fun flagsHotBattery() {
        assertTrue(StatusJson.toJson(snap).getBoolean("batteryHot"))
        assertEquals(false, StatusJson.toJson(snap.copy(batteryTempC = 30.0)).getBoolean("batteryHot"))
    }
}
