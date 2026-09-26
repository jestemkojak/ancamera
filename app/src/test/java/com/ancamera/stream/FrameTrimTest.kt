package com.ancamera.stream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameTrimTest {
    @Test fun trimsAFrameAtOffsetZero() {
        val buffer = ByteBuffer.allocate(1024)
        for (i in 0 until 50) buffer.put(i, i.toByte())

        val frame = trimFrame(buffer, offset = 0, size = 50)

        assertEquals(0, frame.position())
        assertEquals(50, frame.limit())
        for (i in 0 until 50) assertEquals(i.toByte(), frame.get(i))
    }

    @Test fun trimsAFrameAtANonZeroOffset() {
        val buffer = ByteBuffer.allocate(1024)
        for (i in 0 until 1024) buffer.put(i, i.toByte())

        val frame = trimFrame(buffer, offset = 100, size = 40)

        assertEquals(0, frame.position())
        assertEquals(40, frame.limit())
        for (i in 0 until 40) assertEquals((100 + i).toByte(), frame.get(i))
    }

    @Test fun doesNotChangeTheInputBufferPositionOrLimit() {
        val buffer = ByteBuffer.allocate(1024)
        buffer.position(7)
        buffer.limit(900)

        trimFrame(buffer, offset = 10, size = 30)

        assertEquals(7, buffer.position())
        assertEquals(900, buffer.limit())
    }

    @Test fun sharesMemoryWithTheInputBuffer() {
        val buffer = ByteBuffer.allocate(1024)
        val frame = trimFrame(buffer, offset = 10, size = 20)

        buffer.put(10, 0x42)

        assertEquals(0x42.toByte(), frame.get(0))
    }

    @Test fun sizeZeroGivesAnEmptyBuffer() {
        val buffer = ByteBuffer.allocate(1024)

        val frame = trimFrame(buffer, offset = 5, size = 0)

        assertEquals(0, frame.position())
        assertEquals(0, frame.limit())
    }
}
