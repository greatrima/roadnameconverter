package kr.co.addresslens

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ScanFramePixelsTest {
    @Test fun roundsInwardsAndClampsToVisibleCameraCrop() {
        assertEquals(ScanPixelBounds(11, 21, 39, 59), ScanPixelBounds.inside(
            10.2f, 20.4f, 39.9f, 59.8f, ScanPixelBounds(0, 0, 100, 100)))
        assertEquals(ScanPixelBounds(20, 30, 60, 80), ScanPixelBounds.inside(
            -5f, -10f, 200f, 200f, ScanPixelBounds(20, 30, 60, 80)))
    }

    @Test fun invalidOrInvisibleWindowNeverFallsBackToFullFrame() {
        val limit = ScanPixelBounds(0, 0, 100, 100)
        assertNull(ScanPixelBounds.inside(110f, 0f, 200f, 50f, limit))
        assertNull(ScanPixelBounds.inside(Float.NaN, 0f, 10f, 10f, limit))
        assertNull(ScanPixelBounds.inside(0f, 0f, 0.5f, 10f, limit))
    }

    @Test fun pixelsOutsideWindowNeverReachOcrInput() {
        val buffer = ByteBuffer.wrap(ByteArray(8 * 6) { 17 })
        for (y in 2..3) for (x in 2..5) buffer.put(y * 8 + x, 220.toByte())
        val cropped = ScanFramePixels.copyLuminance(buffer, 8, 1, ScanPixelBounds(2, 2, 6, 4))
        assertEquals(8, cropped.size)
        assertTrue(cropped.all { it and 0xff == 220 })
    }

    @Test fun respectsRowPaddingPixelStrideAndBufferPosition() {
        val buffer = ByteBuffer.allocate(50)
        buffer.position(3)
        for (y in 0..3) for (x in 0..3) buffer.put(3 + y * 10 + x * 2, (y * 10 + x).toByte())
        val cropped = ScanFramePixels.copyLuminance(buffer, 10, 2, ScanPixelBounds(1, 1, 3, 3))
        assertEquals(listOf(11, 12, 21, 22), cropped.map { it and 0xff })
        assertEquals(3, buffer.position())
    }

    @Test fun excludesPixelsOutsideRoundedCorners() {
        val cropped = ScanFramePixels.copyLuminance(ByteBuffer.wrap(ByteArray(100)), 10, 1,
            ScanPixelBounds(0, 0, 10, 10), 4f, 4f)
        for (index in listOf(0, 9, 90, 99)) assertEquals(255, cropped[index] and 0xff)
        assertEquals(0, cropped[55] and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPlaneRatherThanReadingOtherMemory() {
        ScanFramePixels.copyLuminance(ByteBuffer.allocate(5), 4, 1, ScanPixelBounds(0, 0, 4, 4))
    }
}
