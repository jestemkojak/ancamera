package com.ancamera.stream

import android.hardware.Camera
import android.util.Log
import com.ancamera.settings.Capabilities
import com.ancamera.settings.ExposureRange
import com.ancamera.settings.Facing
import com.ancamera.settings.Size

/** Reads camera facts with the old Camera API (the only camera API on API 19). */
@Suppress("DEPRECATION")
object CameraProbe {
    private const val TAG = "CameraProbe"
    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1080

    /** Camera ID for [facing], or null when the phone has no such camera. */
    fun findId(facing: Facing): Int? {
        val want = if (facing == Facing.BACK) Camera.CameraInfo.CAMERA_FACING_BACK else Camera.CameraInfo.CAMERA_FACING_FRONT
        val info = Camera.CameraInfo()
        for (id in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(id, info)
            if (info.facing == want) return id
        }
        return null
    }

    fun facingOf(id: Int): Facing {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(id, info)
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) Facing.FRONT else Facing.BACK
    }

    /**
     * Opens each camera for a short time to read preview sizes, the fps range, scene modes,
     * ISO values and the exposure compensation range.
     * Call only while the stream does not use the camera.
     */
    fun capabilities(): Capabilities {
        val sizes = LinkedHashMap<Facing, List<Size>>()
        val sceneModes = LinkedHashMap<Facing, List<String>>()
        val isoValues = LinkedHashMap<Facing, List<String>>()
        val exposure = LinkedHashMap<Facing, ExposureRange>()
        var maxFps = 30
        for (facing in Facing.values()) {
            val id = findId(facing) ?: continue
            var camera: Camera? = null
            try {
                camera = Camera.open(id)
                val params = camera.parameters
                sizes[facing] = params.supportedPreviewSizes
                    .map { Size(it.width, it.height) }
                    .filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT && it.width % 2 == 0 && it.height % 2 == 0 }
                    .distinct()
                    .sortedByDescending { it.area }
                val top = params.supportedPreviewFpsRange?.maxOfOrNull { it[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] }
                if (top != null && top >= 1000) maxFps = minOf(maxFps, top / 1000)
                sceneModes[facing] = params.supportedSceneModes.orEmpty()
                // Qualcomm key. Not in the public API, so it can be missing.
                isoValues[facing] = params.get("iso-values").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                exposure[facing] = ExposureRange(
                    params.minExposureCompensation,
                    params.maxExposureCompensation,
                    params.exposureCompensationStep,
                )
                Log.i(TAG, "camera $id: scene modes ${sceneModes[facing]}, iso ${isoValues[facing]}, exposure ${exposure[facing]}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot read camera $id", e)
            } finally {
                camera?.release()
            }
        }
        return Capabilities(sizes, maxFps, sceneModes, isoValues, exposure)
    }
}
