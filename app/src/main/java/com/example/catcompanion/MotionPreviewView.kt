package com.example.catcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/** 60 Hz preview of the same PNG sequence used by the floating overlay. */
internal class MotionPreviewView(context: Context, action: PetView.Action) : View(context) {
    private val stream = FrameStream(context, action) { postInvalidateOnAnimation() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val started = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(253, 246, 239))
        val boxSize = min(width, height) * .92f
        val left = (width - boxSize) / 2f
        val top = (height - boxSize) / 2f
        paint.color = Color.argb(38, 70, 50, 42)
        canvas.drawOval(
            RectF(width * .34f, top + boxSize * .92f,
                width * .66f, top + boxSize * .965f),
            paint
        )
        val bitmap = stream.get(SystemClock.uptimeMillis() - started)
        if (bitmap != null) {
            paint.color = Color.WHITE
            paint.alpha = 255
            canvas.drawBitmap(
                bitmap, null,
                RectF(left, top, left + boxSize, top + boxSize), paint
            )
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        stream.close()
        super.onDetachedFromWindow()
    }
}
