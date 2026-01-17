package com.pasiflonet.mobile.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // אנחנו שומרים את הריבועים באחוזים (0.0-1.0) ולא בפיקסלים!
    private val relativeRects = mutableListOf<BlurRect>()
    
    private val paint = Paint().apply {
        color = 0x80FF0000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    var isBlurMode = false
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false
    
    // גבולות התמונה הנוכחיים
    private var validBounds = RectF(0f, 0f, 0f, 0f)

    fun setValidBounds(bounds: RectF) {
        this.validBounds = bounds
        invalidate() // ציור מחדש במיקום המעודכן
    }

    // החזרת הרשימה המוכנה (כבר באחוזים)
    val rects: List<BlurRect>
        get() = relativeRects.toList()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isBlurMode) return false
        
        // הגנה: אם אין תמונה מזוהה, אי אפשר לצייר
        if (validBounds.width() <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDrawing = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                
                // חישוב הריבוע בפיקסלים
                var left = Math.min(startX, currentX)
                var right = Math.max(startX, currentX)
                var top = Math.min(startY, currentY)
                var bottom = Math.max(startY, currentY)
                
                // חיתוך לגבולות התמונה (Clipping)
                if (left < validBounds.left) left = validBounds.left
                if (right > validBounds.right) right = validBounds.right
                if (top < validBounds.top) top = validBounds.top
                if (bottom > validBounds.bottom) bottom = validBounds.bottom
                
                // בדיקת גודל מינימלי
                if (right - left > 10 && bottom - top > 10) {
                    // המרה לאחוזים ושמירה
                    val relLeft = (left - validBounds.left) / validBounds.width()
                    val relRight = (right - validBounds.left) / validBounds.width()
                    val relTop = (top - validBounds.top) / validBounds.height()
                    val relBottom = (bottom - validBounds.top) / validBounds.height()
                    
                    relativeRects.add(BlurRect(relLeft, relTop, relRight, relBottom))
                }
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (validBounds.width() <= 0) return

        // המרה מאחוזים לפיקסלים בזמן אמת (כדי לצייר במקום הנכון גם אם המסך זז)
        for (r in relativeRects) {
            val absLeft = validBounds.left + (r.left * validBounds.width())
            val absRight = validBounds.left + (r.right * validBounds.width())
            val absTop = validBounds.top + (r.top * validBounds.height())
            val absBottom = validBounds.top + (r.bottom * validBounds.height())
            
            canvas.drawRect(absLeft, absTop, absRight, absBottom, paint)
            canvas.drawRect(absLeft, absTop, absRight, absBottom, borderPaint)
        }

        // ציור הריבוע הזמני (תוך כדי גרירה)
        if (isDrawing) {
            val l = Math.min(startX, currentX)
            val r = Math.max(startX, currentX)
            val t = Math.min(startY, currentY)
            val b = Math.max(startY, currentY)
            canvas.drawRect(l, t, r, b, paint)
        }
    }
    
    fun undo() {
        if (relativeRects.isNotEmpty()) {
            relativeRects.removeAt(relativeRects.lastIndex)
            invalidate()
        }
    }
    
    fun clear() {
        relativeRects.clear()
        invalidate()
    }
}
