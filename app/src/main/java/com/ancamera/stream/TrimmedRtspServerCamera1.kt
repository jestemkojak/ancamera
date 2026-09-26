package com.ancamera.stream

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.base.Camera1Base
import com.pedro.rtspserver.server.RtspServer
import com.pedro.rtspserver.util.RtspServerStreamClient
import java.nio.ByteBuffer

/**
 * App copy of the library class `com.pedro.rtspserver.RtspServerCamera1` (RTSP-Server 1.4.3).
 *
 * It exists to work around a bug on API < 21: MediaCodec does not set `position`/`limit` on the
 * buffers from `getOutputBuffers()` from `BufferInfo`, so `getVideoDataImp` gets a buffer whose
 * limit is the full output buffer capacity (1-2 MB), not the frame size (about 10-50 KB). The
 * library sender copies `[0, limit())` per frame, so each frame took a 2 MB array, and the 128 MB
 * heap on the LG G3 (API 19) ran out of memory after a short network delay filled the send queue.
 *
 * This class trims the buffer to the real frame before it reaches the library, using
 * [trimFrame]. The library class is final, so it cannot be subclassed; this is a copy with only
 * [getVideoDataImp] changed.
 *
 * Only the background constructor is here, because the app uses only that one.
 */
class TrimmedRtspServerCamera1 : Camera1Base {

    private val rtspServer: RtspServer

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    constructor(context: Context, connectChecker: ConnectChecker, port: Int) : super(context) {
        rtspServer = RtspServer(connectChecker, port)
    }

    fun startStream() {
        super.startStream("")
    }

    override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
        rtspServer.setAudioInfo(sampleRate, isStereo)
    }

    override fun startStreamImp(url: String) {
        rtspServer.startServer()
    }

    override fun stopStreamImp() {
        rtspServer.stopServer()
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        rtspServer.sendAudio(audioBuffer, info)
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        val newSps = sps.duplicate()
        val newPps = pps?.duplicate()
        val newVps = vps?.duplicate()
        rtspServer.setVideoInfo(newSps, newPps, newVps)
    }

    // Trims the frame out of the encoder's output buffer before it reaches the library, so the
    // library never copies the unused rest of the buffer. The trimmed buffer starts at the frame,
    // so its own BufferInfo carries offset 0. size, presentationTimeUs and flags come from
    // [info]. This copy of info does not change the encoder's own [info].
    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val trimmed = trimFrame(videoBuffer, info.offset, info.size)
        val trimmedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }
        rtspServer.sendVideo(trimmed, trimmedInfo)
    }

    override fun getStreamClient(): RtspServerStreamClient = RtspServerStreamClient(rtspServer)

    override fun setVideoCodecImp(codec: VideoCodec) {
        rtspServer.setVideoCodec(codec)
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        rtspServer.setAudioCodec(codec)
    }
}
