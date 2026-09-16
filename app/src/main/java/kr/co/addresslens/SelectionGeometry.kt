package kr.co.addresslens

import kotlin.math.max
import kotlin.math.min

internal data class FloatBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Preview and capture share one CameraX ViewPort. Rotation is clockwise, before fill-center. */
internal object FrameGeometry {
    fun viewToBuffer(box: FloatBox, viewWidth: Float, viewHeight: Float,
                     crop: ScanPixelBounds, rotation: Int): ScanPixelBounds? {
        if (viewWidth <= 0 || viewHeight <= 0 || crop.width <= 0 || crop.height <= 0) return null
        if (rotation !in listOf(0, 90, 180, 270)) return null
        val rotatedWidth = if (rotation % 180 == 0) crop.width.toFloat() else crop.height.toFloat()
        val rotatedHeight = if (rotation % 180 == 0) crop.height.toFloat() else crop.width.toFloat()
        val scale = max(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val offsetX = (viewWidth - rotatedWidth * scale) / 2f
        val offsetY = (viewHeight - rotatedHeight * scale) / 2f
        val points = listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom)
            .map { (vx, vy) ->
                val x = (vx - offsetX) / scale
                val y = (vy - offsetY) / scale
                val raw = when (rotation) {
                    90 -> y to crop.height - x
                    180 -> crop.width - x to crop.height - y
                    270 -> crop.width - y to x
                    else -> x to y
                }
                raw.first + crop.left to raw.second + crop.top
            }
        return ScanPixelBounds.inside(points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first }, points.maxOf { it.second }, crop)
    }
}

/** Selection stays in view coordinates; each operation is mapped back to the immutable source. */
internal class SelectionGeometry(val imageWidth: Int, val imageHeight: Int, val viewWidth: Float, val viewHeight: Float) {
    private val baseScale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    var scale = baseScale; private set
    var offsetX = (viewWidth - imageWidth * scale) / 2; private set
    var offsetY = (viewHeight - imageHeight * scale) / 2; private set
    var box = FloatBox(viewWidth * .08f, viewHeight * .1f, viewWidth * .92f, viewHeight * .9f); private set

    fun select(value: FloatBox) {
        val minimum = min(32f, min(viewWidth, viewHeight) / 4f)
        val l = value.left.coerceIn(0f, max(0f, viewWidth - minimum))
        val t = value.top.coerceIn(0f, max(0f, viewHeight - minimum))
        box = FloatBox(l, t, value.right.coerceIn(l + minimum, viewWidth), value.bottom.coerceIn(t + minimum, viewHeight))
    }
    fun move(dx: Float, dy: Float) {
        val x = dx.coerceIn(-box.left, viewWidth - box.right)
        val y = dy.coerceIn(-box.top, viewHeight - box.bottom)
        box = FloatBox(box.left + x, box.top + y, box.right + x, box.bottom + y)
    }
    fun resize(corner: Int, dx: Float, dy: Float) {
        val minimum = min(32f, min(viewWidth, viewHeight) / 4f)
        box = FloatBox(
            if (corner == 0 || corner == 3) (box.left + dx).coerceIn(0f, box.right - minimum) else box.left,
            if (corner == 0 || corner == 1) (box.top + dy).coerceIn(0f, box.bottom - minimum) else box.top,
            if (corner == 1 || corner == 2) (box.right + dx).coerceIn(box.left + minimum, viewWidth) else box.right,
            if (corner == 2 || corner == 3) (box.bottom + dy).coerceIn(box.top + minimum, viewHeight) else box.bottom)
    }
    fun zoom(factor: Float, focusX: Float, focusY: Float, panX: Float = 0f, panY: Float = 0f) {
        val newScale = (scale * factor).coerceIn(baseScale, baseScale * 8)
        val ratio = newScale / scale
        offsetX = (focusX - (focusX - offsetX) * ratio + panX).coerceIn(viewWidth - imageWidth * newScale, 0f)
        offsetY = (focusY - (focusY - offsetY) * ratio + panY).coerceIn(viewHeight - imageHeight * newScale, 0f)
        scale = newScale
    }
    fun sourceBounds(): ScanPixelBounds? = ScanPixelBounds.inside(
        (box.left - offsetX) / scale, (box.top - offsetY) / scale,
        (box.right - offsetX) / scale, (box.bottom - offsetY) / scale,
        ScanPixelBounds(0, 0, imageWidth, imageHeight))
}
