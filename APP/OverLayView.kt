package com.example.myapplication4

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var rects: List<Rect> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private var viewWidth = 1
    private var viewHeight = 1

    fun setRects(
        rects: List<Rect>,
        imageWidth: Int = 1,
        imageHeight: Int = 1,
        viewWidth: Int = width,
        viewHeight: Int = height
    ) {
        this.rects = rects
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0 || viewWidth == 0 || viewHeight == 0) return

        val scale = minOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val offsetX = (viewWidth - imageWidth * scale) / 2f
        val offsetY = (viewHeight - imageHeight * scale) / 2f

        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val centerX = viewWidth / 2f
        val correctionFactorX = -1200f  // תכוונן ידנית לפי ניסוי (ערך אופייני: 20–80)

        for (rect in rects) {
            val left = rect.left * scale + offsetX
            val top = rect.top * scale + offsetY
            val right = rect.right * scale + offsetX
            val bottom = rect.bottom * scale + offsetY

            val scaledRect = RectF(left, top, right, bottom)

            // חישוב הזחה דינמית בציר X לפי המרחק מהמרכז
            val dxNorm = (scaledRect.centerX() - centerX) / centerX  // בין -1 ל-1
            val correctionX = dxNorm * correctionFactorX

            scaledRect.offset(-correctionX, 0f)

            canvas.drawRect(scaledRect, paint)
        }
    }


}
