package com.ancamera.stream

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.ancamera.settings.Facing
import com.ancamera.settings.Settings
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.RtspServerCamera1
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class EngineState(val wire: String) {
    STOPPED("stopped"),
    STREAMING("streaming"),
    ERROR("error"),
}

/**
 * The only class that uses RootEncoder / RTSP-Server. Call [start], [stop], [setBitrate] and
 * [setTorch] on the main thread. [captureJpeg] and the status fields are safe from any thread.
 */
class StreamEngine(private val context: Context) : ConnectChecker {
    @Volatile var state: EngineState = EngineState.STOPPED; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var cameraId: Int = -1; private set
    @Volatile var facing: Facing = Facing.BACK; private set
    @Volatile var fps: Int = 0; private set
    @Volatile var lastFrameAtMs: Long = 0; private set

    @Volatile private var camera: RtspServerCamera1? = null
    private val grabLock = Any()
    private var lastJpeg: ByteArray? = null
    private var lastJpegAtMs = 0L

    val rtspClients: Int get() = camera?.streamClient?.getNumClients() ?: 0

    /** Stops any running stream, then starts with [s]. Returns false and sets [lastError] on failure. */
    fun start(s: Settings): Boolean {
        stop()
        val wantedId = CameraProbe.findId(s.facing)
        val id = wantedId ?: 0
        val cam = RtspServerCamera1(context.applicationContext, this, s.rtspPort)
        val client = cam.streamClient
        client.setOnlyVideo(true) // no empty AAC track in the SDP
        client.setLogs(false)
        if (s.authEnabled) client.setAuthorization(s.username, s.password)
        cam.setFpsListener { f ->
            fps = f
            lastFrameAtMs = SystemClock.elapsedRealtime()
        }
        try {
            if (!cam.prepareVideo(s.size.width, s.size.height, s.fps, s.bitrate, s.rotation)) {
                return fail("encoder does not accept ${s.size} at ${s.fps} fps")
            }
            // Library bug (RootEncoder 2.8.1): in background mode startPreview(Facing) does not
            // change the camera that opens, and the front camera opens. The ID overload works.
            cam.startPreview(id, s.size.width, s.size.height, s.fps, s.rotation)
            cam.startStream()
        } catch (e: RuntimeException) {
            Log.e(TAG, "start failed", e)
            try { cam.stopStream() } catch (_: RuntimeException) {}
            return fail("camera start failed: ${e.message ?: e.javaClass.simpleName}")
        }
        camera = cam
        cameraId = id
        facing = CameraProbe.facingOf(id)
        fps = 0
        lastFrameAtMs = SystemClock.elapsedRealtime()
        lastError = if (wantedId == null) "no ${s.facing.wire} camera, using camera 0" else null
        state = EngineState.STREAMING
        if (s.torch) setTorch(true)
        Log.i(TAG, "streaming camera $id (${facing.wire}) ${s.size}@${s.fps} on :${s.rtspPort}")
        return true
    }

    fun stop() {
        val cam = camera ?: return
        camera = null
        try {
            if (cam.isLanternEnabled) cam.disableLantern()
            cam.stopStream()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stop failed", e)
        }
        synchronized(grabLock) { lastJpeg = null }
        fps = 0
        if (state == EngineState.STREAMING) state = EngineState.STOPPED
    }

    fun setBitrate(bitrate: Int) {
        camera?.setVideoBitrateOnFly(bitrate)
    }

    fun setTorch(on: Boolean) {
        val cam = camera ?: return
        try {
            if (on) cam.enableLantern() else cam.disableLantern()
        } catch (e: Exception) {
            lastError = "torch not available: ${e.message}"
        }
    }

    /**
     * A JPEG of the current frame, or null when no frame comes within [timeoutMs].
     * Callers share one grab: a frame younger than [MIN_GRAB_INTERVAL_MS] is reused.
     */
    fun captureJpeg(quality: Int, timeoutMs: Long): ByteArray? = synchronized(grabLock) {
        val now = SystemClock.elapsedRealtime()
        val cached = lastJpeg
        if (cached != null && now - lastJpegAtMs < MIN_GRAB_INTERVAL_MS) return cached
        val cam = camera ?: return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        cam.glInterface.takePhoto { b ->
            bitmap = b
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        val b = bitmap ?: return null
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        b.recycle()
        val jpeg = out.toByteArray()
        lastJpeg = jpeg
        lastJpegAtMs = now
        jpeg
    }

    private fun fail(message: String): Boolean {
        Log.e(TAG, message)
        lastError = message
        state = EngineState.ERROR
        return false
    }

    // ConnectChecker: the RTSP server reports client events here. Only failures matter.
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onConnectionFailed(reason: String) {
        Log.w(TAG, "rtsp: $reason")
        lastError = "rtsp: $reason"
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}

    companion object {
        private const val TAG = "StreamEngine"
        /** Shared grab interval: 15 fps is the MJPEG maximum. */
        const val MIN_GRAB_INTERVAL_MS = 66L
    }
}
