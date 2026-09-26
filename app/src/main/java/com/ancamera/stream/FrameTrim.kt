package com.ancamera.stream

import java.nio.ByteBuffer

/**
 * Returns a view of [buffer] that holds only the frame bytes `[offset, offset + size)`.
 *
 * On API < 21, MediaCodec does not set `position`/`limit` from `BufferInfo` on the buffers from
 * `getOutputBuffers()`. So `buffer.limit()` is the full buffer capacity (1-2 MB), not the frame
 * size (about 10-50 KB). A naive copy of `[0, limit())` copies the whole capacity per frame.
 *
 * This function does not copy any bytes. It shares memory with [buffer]. The caller must not
 * write to [buffer] until it is done with the returned view. It does not change the position or
 * limit of [buffer].
 *
 * The returned buffer has position 0 and limit == [size]. If the frame range is not fully in the
 * buffer capacity, the function returns null, and the caller must drop the frame.
 */
fun trimFrame(buffer: ByteBuffer, offset: Int, size: Int): ByteBuffer? {
    // Long math: offset + size must not overflow.
    if (offset < 0 || size < 0 || offset.toLong() + size > buffer.capacity()) return null
    val view = buffer.duplicate()
    // Set the limit before the position: the position must not be more than the limit.
    view.limit(offset + size)
    view.position(offset)
    return view.slice()
}
