package com.ancamera.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    @Test fun backoffFollowsSpecThenStaysAtMax() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L), List(6) { b.nextDelayMs() })
        b.reset()
        assertEquals(2_000L, b.nextDelayMs())
    }

    @Test fun watchdogFiresAfterTenSecondsWithoutFrames() {
        assertFalse(Watchdog.isStalled(streaming = true, lastFrameAtMs = 1_000, nowMs = 10_999))
        assertTrue(Watchdog.isStalled(streaming = true, lastFrameAtMs = 1_000, nowMs = 11_000))
        assertFalse(Watchdog.isStalled(streaming = false, lastFrameAtMs = 0, nowMs = 99_999))
    }
}
