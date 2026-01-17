package com.pasiflonet.mobile.utils

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

data class WatermarkSettings(
    val enabled: Boolean = false,
    val uri: String? = null,
    val preset: String = "bottom_right",
    val x: Float = 0.9f, val y: Float = 0.9f, val scale: Float = 0.2f, val opacity: Float = 0.8f
)

object BitmapProcessor {
    fun applyBlurAndWatermark(source: Bitmap, blurRects: List<FloatArray>, watermarkBitmap: Bitmap?, settings: WatermarkSettings): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        // Blur rectangles
        blurRects.forEach { (x, y, w, h) ->
            val rect = RectF(x * source.width, y * source.height, (x + w) * source.width, (y + h) * source.height)
            val blurred = source.extractAlpha().let { alpha ->
                val paint = Paint().apply { maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL) }
                val temp = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                Canvas(temp).drawBitmap(alpha, 0f, 0f, paint)
                temp
            }
            canvas.drawBitmap(blurred, null, rect, null)
        }

        // Watermark
        if (settings.enabled) {
            watermarkBitmap?.let {
                val scaled = Bitmap.createScaledBitmap(it, (it.width * settings.scale).toInt(), (it.height * settings.scale).toInt(), true)
                val left = when (settings.preset) {
                    "top_left" -> 0.1f * source.width
                    "top_right" -> 0.9f * source.width - scaled.width
                    "bottom_left" -> 0.1f * source.width
                    "bottom_right" -> 0.9f * source.width - scaled.width
                    "center" -> (source.width - scaled.width) / 2f
                    else -> settings.x * source.width
                }
                val top = when (settings.preset) {
                    "top_left", "top_right" -> 0.1f * source.height
                    "bottom_left", "bottom_right" -> 0.9f * source.height - scaled.height
                    "center" -> (source.height - scaled.height) / 2f
                    else -> settings.y * source.height
                }
                val paint = Paint().apply { alpha = (settings.opacity * 255).toInt() }
                canvas.drawBitmap(scaled, left, top, paint)
            }
        }

        return mutable
    }
}
