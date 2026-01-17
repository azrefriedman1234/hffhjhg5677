package com.pasiflonet.mobile.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

object ImageUtils {

    /** מחזיר path מקומי לקובץ (אם זה content:// הוא יועתק ל-cache) */
    fun getFilePath(context: Context, uri: Uri): String? {
        return try {
            if (uri.scheme == "file") uri.path
            else copyUriToCache(context, uri)
        } catch (_: Exception) { null }
    }

    /** תאימות לקוד ישן שמצפה callback */
    fun getFilePath(context: Context, uri: Uri, onResult: (String?) -> Unit) {
        onResult(getFilePath(context, uri))
    }

    private fun copyUriToCache(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val name = "in_${System.currentTimeMillis()}.bin"
        val out = File(context.cacheDir, name)
        cr.openInputStream(uri)?.use { ins ->
            FileOutputStream(out).use { fos -> ins.copyTo(fos) }
        } ?: return null
        return out.absolutePath
    }

    /**
     * עיבוד תמונה:
     * - blurRects: אפשר יחסיים (0..1) או פיקסלים
     * - לוגו: logoRelX/logoRelY יחסיים (0..1), logoRelW יחסית לרוחב התמונה
     * מחזיר true אם נוצר קובץ תקין.
     */
    fun processImage(
        context: Context,
        inputPath: String,
        outputPath: String,
        blurRects: List<BlurRect>,
        logoUri: Uri?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float
    ): Boolean {
        val src = decodeToBitmap(context, inputPath) ?: return false
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)

        // blur strength: 15% יותר (כלומר scale קטן יותר)
        val baseScale = 0.14f
        val scale = (baseScale / 1.15f).coerceIn(0.08f, 0.20f)

        for (r in blurRects) {
            val rr = toPxRect(bmp.width, bmp.height, r) ?: continue
            blurRect(canvas, bmp, rr, scale)
        }

        // לוגו
        if (logoUri != null && logoRelW > 0.01f) {
            val logo = decodeLogo(context, logoUri)
            if (logo != null) {
                val targetW = (bmp.width * logoRelW).roundToInt().coerceAtLeast(24)
                val ratio = logo.height.toFloat() / max(1, logo.width).toFloat()
                val targetH = (targetW * ratio).roundToInt().coerceAtLeast(24)
                val scaled = Bitmap.createScaledBitmap(logo, targetW, targetH, true)

                val x = (bmp.width * logoRelX).roundToInt().coerceIn(0, max(0, bmp.width - targetW))
                val y = (bmp.height * logoRelY).roundToInt().coerceIn(0, max(0, bmp.height - targetH))

                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 230 }
                canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), p)
            }
        }

        return try {
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                // jpg אם לא png
                val isPng = outputPath.endsWith(".png", true)
                val fmt = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val q = if (isPng) 100 else 92
                bmp.compress(fmt, q, fos)
            }
            outFile.exists() && outFile.length() > 0
        } catch (_: Exception) {
            false
        } finally {
            try { src.recycle() } catch (_: Exception) {}
        }
    }

    private fun decodeToBitmap(context: Context, path: String): Bitmap? {
        return try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                BitmapFactory.decodeFile(path)
            }
        } catch (_: Exception) { null }
    }

    private fun decodeLogo(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) { null }
    }

    private fun toPxRect(w: Int, h: Int, r: BlurRect): Rect? {
        // אם זה נראה יחסי (0..1.2) – נמיר ל-px
        val relative = maxOf(r.left, r.top, r.right, r.bottom) <= 1.2f
        val l = if (relative) (r.left * w) else r.left
        val t = if (relative) (r.top * h) else r.top
        val rr = if (relative) (r.right * w) else r.right
        val bb = if (relative) (r.bottom * h) else r.bottom

        val left = floor(min(l, rr)).toInt().coerceIn(0, w - 1)
        val top = floor(min(t, bb)).toInt().coerceIn(0, h - 1)
        val right = ceil(max(l, rr)).toInt().coerceIn(left + 1, w)
        val bottom = ceil(max(t, bb)).toInt().coerceIn(top + 1, h)

        if (right - left < 2 || bottom - top < 2) return null
        return Rect(left, top, right, bottom)
    }

    /** blur לא מפוקסל: downscale+FILTER ואז upscale */
    private fun blurRect(canvas: Canvas, src: Bitmap, rect: Rect, scale: Float) {
        val rw = rect.width()
        val rh = rect.height()

        val region = Bitmap.createBitmap(src, rect.left, rect.top, rw, rh)

        val sw = max(1, (rw * scale).roundToInt())
        val sh = max(1, (rh * scale).roundToInt())

        val small = Bitmap.createScaledBitmap(region, sw, sh, true)
        val blurred = Bitmap.createScaledBitmap(small, rw, rh, true)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
        canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), paint)

        try { region.recycle() } catch (_: Exception) {}
        try { small.recycle() } catch (_: Exception) {}
        try { blurred.recycle() } catch (_: Exception) {}
    }
}
