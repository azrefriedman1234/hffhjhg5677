package com.pasiflonet.mobile.utils

import android.graphics.RectF
import android.widget.ImageView

object ViewUtils {
    // פונקציה שמחזירה את הריבוע האמיתי של התמונה בתוך ה-ImageView
    fun getBitmapPositionInsideImageView(imageView: ImageView?): RectF {
        val result = RectF()
        if (imageView == null || imageView.drawable == null) return result

        val d = imageView.drawable
        val imageW = d.intrinsicWidth.toFloat()
        val imageH = d.intrinsicHeight.toFloat()

        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()

        if (imageW <= 0 || imageH <= 0) return result

        val scaleX = viewW / imageW
        val scaleY = viewH / imageH
        
        // fitCenter: הסקייל הקטן מבין השניים קובע
        val scale = Math.min(scaleX, scaleY)

        val finalW = imageW * scale
        val finalH = imageH * scale

        // חישוב השוליים (מרכוז)
        val left = (viewW - finalW) / 2
        val top = (viewH - finalH) / 2

        result.set(left, top, left + finalW, top + finalH)
        return result
    }
}
