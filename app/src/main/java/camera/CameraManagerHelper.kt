package com.example.currencyconvert.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class FocusOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt() // semi-transparent black
    }
    private val transparentPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x00000000 // transparent
    }
    private val focusRect = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = canvas.width
        val height = canvas.height

        // Define the size and position of the focus rectangle
        val focusWidth = width * 0.6 // 60% of the view width
        val focusHeight = height * 0.2 // 20% of the view height
        val left = (width - focusWidth) / 2
        val top = (height - focusHeight) / 2
        val right = left + focusWidth.toInt()
        val bottom = top + focusHeight.toInt()

        // Draw the black mask with a transparent rectangle in the center
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(),
            bottom.toFloat(), transparentPaint)
    }
}
