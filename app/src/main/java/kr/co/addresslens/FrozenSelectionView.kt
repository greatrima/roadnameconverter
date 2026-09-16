package kr.co.addresslens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/** In-memory photo only. Drawing and OCR crop use the same immutable source coordinates. */
class FrozenSelectionView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var photo: Bitmap? = null
    private var geometry: SelectionGeometry? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var initial: FloatBox? = null
    private var handle = -1
    private var lastX = 0f
    private var lastY = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var pinched = false
    var onSelectionChanged: (() -> Unit)? = null
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinched = true; focusX = detector.focusX; focusY = detector.focusY
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            geometry?.zoom(detector.scaleFactor, detector.focusX, detector.focusY,
                detector.focusX - focusX, detector.focusY - focusY)
            focusX = detector.focusX; focusY = detector.focusY
            changed()
            return true
        }
    })

    internal fun setPhoto(bitmap: Bitmap, initialBox: FloatBox) {
        clearPhoto()
        photo = bitmap; initial = initialBox
        configureGeometry()
        invalidate()
    }

    fun clearPhoto() { photo?.recycle(); photo = null; geometry = null; invalidate() }

    private fun configureGeometry() {
        val image = photo ?: return
        if (width <= 0 || height <= 0) return
        geometry = SelectionGeometry(image.width, image.height, width.toFloat(), height.toFloat()).also { model ->
            initial?.let { model.select(FloatBox(it.left * width, it.top * height, it.right * width, it.bottom * height)) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configureGeometry()
        onSelectionChanged?.invoke()
    }

    internal fun selectionBounds() = geometry?.sourceBounds()
    internal fun cropSelection(): Bitmap? {
        val image = photo ?: return null
        val box = selectionBounds() ?: return null
        // Copy so resuming capture can release the photo while this OCR task still owns its crop.
        return Bitmap.createBitmap(image, box.left, box.top, box.width, box.height)
            .let { if (it === image) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = photo ?: return
        val model = geometry ?: return
        paint.color = Color.WHITE; paint.style = Paint.Style.FILL
        canvas.drawBitmap(image, null, RectF(model.offsetX, model.offsetY,
            model.offsetX + image.width * model.scale, model.offsetY + image.height * model.scale), paint)
        val b = model.box
        paint.color = 0x99000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), b.top, paint)
        canvas.drawRect(0f, b.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, b.top, b.left, b.bottom, paint)
        canvas.drawRect(b.right, b.top, width.toFloat(), b.bottom, paint)
        paint.color = 0xff5de0cc.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * resources.displayMetrics.density
        canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
        paint.style = Paint.Style.FILL
        for ((x, y) in listOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)) {
            canvas.drawCircle(x, y, 6 * resources.displayMetrics.density, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val model = geometry ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinched = false; lastX = event.x; lastY = event.y
                val b = model.box
                val radius = 28 * resources.displayMetrics.density
                handle = listOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)
                    .indexOfFirst { abs(it.first - event.x) < radius && abs(it.second - event.y) < radius }
                if (handle < 0 && RectF(b.left, b.top, b.right, b.bottom).contains(event.x, event.y)) handle = 4
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && !pinched && event.pointerCount == 1) {
                val dx = event.x - lastX; val dy = event.y - lastY
                when (handle) {
                    in 0..3 -> model.resize(handle, dx, dy)
                    4 -> model.move(dx, dy)
                }
                lastX = event.x; lastY = event.y
                if (handle >= 0) changed()
            }
            MotionEvent.ACTION_UP -> { handle = -1; performClick() }
            MotionEvent.ACTION_CANCEL -> handle = -1
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun changed() { invalidate(); onSelectionChanged?.invoke() }
}
