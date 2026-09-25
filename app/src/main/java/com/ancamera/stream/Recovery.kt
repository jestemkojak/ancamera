package com.ancamera.stream

/** Retry delays after a failed start: 2 s, 5 s, 10 s, 30 s, then 60 s for ever. */
class Backoff(private val delaysMs: LongArray = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val d = delaysMs[minOf(attempt, delaysMs.size - 1)]
        attempt++
        return d
    }

    fun reset() {
        attempt = 0
    }
}

object Watchdog {
    const val STALL_MS = 10_000L

    /** True when the stream should run but no frame was encoded for [STALL_MS]. */
    fun isStalled(streaming: Boolean, lastFrameAtMs: Long, nowMs: Long): Boolean =
        streaming && nowMs - lastFrameAtMs >= STALL_MS
}
