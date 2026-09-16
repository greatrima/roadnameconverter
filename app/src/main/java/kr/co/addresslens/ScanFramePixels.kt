package kr.co.addresslens

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor

/** Raw, unrotated camera pixels. Right and bottom are exclusive. */
internal data class ScanPixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        fun inside(left: Float, top: Float, right: Float, bottom: Float,
                   limit: ScanPixelBounds): ScanPixelBounds? {
            if (!listOf(left, top, right, bottom).all(Float::isFinite)) return null
            val bounds = ScanPixelBounds(
                maxOf(ceil(left).toInt(), limit.left), maxOf(ceil(top).toInt(), limit.top),
                minOf(floor(right).toInt(), limit.right), minOf(floor(bottom).toInt(), limit.bottom)
            )
            return bounds.takeIf { it.width >= 2 && it.height >= 2 }
        }
    }
}

/** Copies only the scan window; no outside camera pixels are passed to OCR. */
internal object ScanFramePixels {
    fun copyLuminance(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, bounds: ScanPixelBounds,
        cornerRadiusX: Float = 0f, cornerRadiusY: Float = 0f
    ): IntArray {
        require(rowStride > 0 && pixelStride > 0 && bounds.left >= 0 && bounds.top >= 0)
        require(bounds.width > 0 && bounds.height > 0)
        require(bounds.right.toLong() * pixelStride <= rowStride.toLong() + pixelStride - 1)
        val source = buffer.duplicate()
        val origin = source.position()
        val last = origin.toLong() + (bounds.bottom - 1L) * rowStride + (bounds.right - 1L) * pixelStride
        require(last < source.limit()) { "Camera scan window exceeds the pixel buffer" }
        val pixels = IntArray(bounds.width * bounds.height)
        val rx = cornerRadiusX.coerceIn(0f, bounds.width / 2f)
        val ry = cornerRadiusY.coerceIn(0f, bounds.height / 2f)
        for (y in 0 until bounds.height) {
            val row = origin + (bounds.top + y) * rowStride + bounds.left * pixelStride
            for (x in 0 until bounds.width) {
                val dx = when {
                    x + 0.5f < rx -> (rx - x - 0.5f) / rx
                    x + 0.5f > bounds.width - rx -> (x + 0.5f - bounds.width + rx) / rx
                    else -> 0f
                }
                val dy = when {
                    y + 0.5f < ry -> (ry - y - 0.5f) / ry
                    y + 0.5f > bounds.height - ry -> (y + 0.5f - bounds.height + ry) / ry
                    else -> 0f
                }
                val luminance = if (dx * dx + dy * dy > 1f) 255
                    else source.get(row + x * pixelStride).toInt() and 0xff
                pixels[y * bounds.width + x] = (0xff shl 24) or
                    (luminance shl 16) or (luminance shl 8) or luminance
            }
        }
        return pixels
    }
}
