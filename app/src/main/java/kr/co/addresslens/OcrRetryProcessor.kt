package kr.co.addresslens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.ExecutorService

/** Original-resolution first pass, at most one contrast-only retry of the identical cropped ROI. */
internal class OcrRetryProcessor(private val recognizer: TextRecognizer, private val executor: ExecutorService) {
    fun recognize(bitmap: Bitmap, rotation: Int, retry: Boolean, current: () -> Boolean,
                  result: (Text?, Boolean) -> Unit, finished: () -> Unit) {
        fun complete(text: Text?, retried: Boolean, extra: Bitmap? = null) {
            try { if (current()) result(text, retried) }
            finally { extra?.recycle(); bitmap.recycle(); finished() }
        }
        fun secondPass(original: Text?) {
            try {
                executor.execute {
                    if (!current()) { bitmap.recycle(); finished(); return@execute }
                    var adjusted: Bitmap? = null
                    try {
                        adjusted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        val image = adjusted
                        val contrast = 1.45f
                        val bias = 128f * (1 - contrast)
                        val matrix = ColorMatrix(floatArrayOf(contrast,0f,0f,0f,bias, 0f,contrast,0f,0f,bias,
                            0f,0f,contrast,0f,bias, 0f,0f,0f,1f,0f))
                        Canvas(image).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
                        recognizer.process(InputImage.fromBitmap(image, rotation))
                            .addOnSuccessListener { text -> complete(if (text.text.isBlank()) original else text, true, image) }
                            .addOnFailureListener { complete(original, true, image) }
                    } catch (_: Exception) { complete(original, true, adjusted) }
                }
            } catch (_: RuntimeException) { complete(original, false) }
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, rotation))
                .addOnSuccessListener { text ->
                    if (current() && retry && AddressTextParser.extract(text.text) == null) secondPass(text)
                    else complete(text, false)
                }
                .addOnFailureListener { if (current() && retry) secondPass(null) else complete(null, false) }
        } catch (_: Exception) { complete(null, false) }
    }
}
