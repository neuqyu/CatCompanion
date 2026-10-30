package com.example.catcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Offline procedural animation. Imported photos float/bob; AI-generated limb motion is not implemented. */
internal class PetView(context: Context) : View(context) {
    enum class Action { WALK, SIT, SLEEP, STRETCH, WAVE, JUMP, IDLE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var current = Action.WALK
    private var actionSince = SystemClock.uptimeMillis()
    private var excitedUntil = 0L
    private var imported: Bitmap? = null
    var facingRight: Boolean = true
    var isWalking = true
    private val ink = Color.rgb(83, 57, 51)
    private val cream = Color.rgb(255, 222, 173)
    private val orange = Color.rgb(242, 170, 105)

    init { loadPhoto() }

    fun loadPhoto() {
        imported?.recycle()
        imported = null
        val file = PetSettings.petImage(context)
        if (file.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 640 && bounds.outHeight / it <= 640 }
            imported = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        invalidate()
    }

    fun setBehavior(next: Action) {
        current = next
        actionSince = SystemClock.uptimeMillis()
        invalidate()
    }

    fun poke() {
        current = Action.WAVE
        actionSince = SystemClock.uptimeMillis()
        excitedUntil = actionSince + 1900L
        invalidate()
    }

    fun isExcited() = SystemClock.uptimeMillis() < excitedUntil

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 220f, height / 220f)
        val ms = SystemClock.uptimeMillis()
        val t = ms / 1000f
        val phase = (ms - actionSince) / 180f
        paint.color = Color.argb(45, 69, 45, 43)
        paint.style = Paint.Style.FILL
        canvas.drawOval(RectF(38f, 185f, 188f, 201f), paint)
        val bob = when (current) {
            Action.WALK -> if (isWalking) sin(phase.toDouble()).toFloat() * 3.8f else 0f
            Action.JUMP -> -kotlin.math.abs(sin(phase.toDouble() * 0.65)).toFloat() * 26f
            else -> sin(t.toDouble()).toFloat() * 1.2f
        }
        canvas.translate(0f, bob)
        if (!facingRight) canvas.scale(-1f, 1f, 110f, 0f)
        if (imported == null) drawIllustratedCat(canvas, phase, t) else drawImportedPhoto(canvas, imported!!, t, phase)
        canvas.restore()
    }

    /** Shape transforms only; not synthesized limb motion. */
    private fun drawImportedPhoto(canvas: Canvas, bitmap: Bitmap, t: Float, phase: Float) {
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val width = if (ratio > 1f) 168f else (171f * ratio).coerceAtMost(168f)
        val height = if (ratio > 1f) 168f / ratio else 171f
        val bottom = 184f
        val rect = RectF(110f - width / 2, bottom - height, 110f + width / 2, bottom)
        val pulse = sin((t * 2.3).toDouble()).toFloat()
        canvas.save()
        when (current) {
            Action.WALK -> {
                canvas.rotate(pulse * 3.2f, 110f, bottom)
                canvas.skew(sin(phase.toDouble()).toFloat() * .045f, 0f)
            }
            Action.SIT -> canvas.scale(1.1f, .83f, 110f, bottom)
            Action.SLEEP -> {
                canvas.scale(1.27f, .56f, 110f, bottom)
                canvas.rotate(-7f + pulse, 110f, bottom)
            }
            Action.STRETCH -> canvas.scale(1.23f + pulse * .025f, .75f, 110f, bottom)
            Action.WAVE -> canvas.rotate(-11f + pulse * 10f, 110f, bottom - height * .55f)
            Action.JUMP -> {
                val p = kotlin.math.abs(sin(phase.toDouble() * .65)).toFloat()
                canvas.scale(1f - p * .07f, 1f + p * .09f, 110f, bottom)
            }
            Action.IDLE -> canvas.scale(1f + pulse * .008f, 1f - pulse * .012f, 110f, bottom)
        }
        paint.color = Color.WHITE
        paint.alpha = 255
        canvas.drawBitmap(bitmap, null, rect, paint)
        canvas.restore()
        if (current == Action.SLEEP) {
            paint.color = ink
            paint.textSize = 19f
            canvas.drawText("Z", 175f, 90f + pulse * 5f, paint)
        }
        if (isExcited()) drawHeart(canvas)
    }

    private fun ellipse(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawOval(x1, y1, x2, y2, paint)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, stroke: Float = 3f) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawLine(x1, y1, x2, y2, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawIllustratedCat(canvas: Canvas, phase: Float, t: Float) {
        val sleeping = current == Action.SLEEP
        val stretch = current == Action.STRETCH
        val walking = current == Action.WALK && isWalking
        val swing = if (walking) sin(phase.toDouble()).toFloat() * 12f else 0f
        val tail = sin((t * 2.5).toDouble()).toFloat() * 18f

        // Tail, then body so the tail appears behind the torso.
        paint.color = orange
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 15f
        paint.strokeCap = Paint.Cap.ROUND
        val tailPath = Path().apply {
            moveTo(152f, 140f)
            cubicTo(191f, 155f, 191f + tail, 104f, 178f + tail, 82f)
        }
        canvas.drawPath(tailPath, paint)
        paint.style = Paint.Style.FILL

        if (sleeping) {
            ellipse(canvas, 51f, 140f, 163f, 183f, orange)
            ellipse(canvas, 56f, 148f, 105f, 177f, cream)
            drawHead(canvas, 83f, 137f, 0.75f, true)
            paint.color = ink
            paint.textSize = 17f
            canvas.drawText("Z", 139f, 119f, paint)
            paint.textSize = 12f
            canvas.drawText("z", 152f, 107f, paint)
        } else {
            if (stretch) {
                ellipse(canvas, 80f, 115f, 169f, 161f, orange)
                ellipse(canvas, 111f, 123f, 157f, 153f, cream)
                drawLeg(canvas, 94f, 141f, -10f)
                drawLeg(canvas, 123f, 145f, 8f)
                drawHead(canvas, 68f, 116f, 0.83f, false)
            } else {
                ellipse(canvas, 74f, 103f, 153f, 176f, orange)
                ellipse(canvas, 86f, 118f, 139f, 168f, cream)
                drawLeg(canvas, 95f, 154f, swing)
                drawLeg(canvas, 134f, 154f, -swing)
                drawHead(canvas, 108f, 89f, 1f, false)
                if (current == Action.WAVE) {
                    canvas.save()
                    canvas.rotate(-25f - 14f * sin(t.toDouble() * 12).toFloat(), 144f, 137f)
                    ellipse(canvas, 132f, 121f, 152f, 155f, cream)
                    canvas.restore()
                }
            }
        }
        if (isExcited()) drawHeart(canvas)
    }

    private fun drawLeg(canvas: Canvas, centerX: Float, y: Float, offset: Float) {
        ellipse(canvas, centerX - 12f + offset, y, centerX + 11f + offset, 190f, orange)
        ellipse(canvas, centerX - 15f + offset, 176f, centerX + 14f + offset, 192f, cream)
    }

    private fun drawHead(canvas: Canvas, cx: Float, cy: Float, scale: Float, closedEyes: Boolean) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        val ears = Path().apply {
            moveTo(-45f, -25f); lineTo(-40f, -75f); lineTo(-12f, -53f)
            lineTo(13f, -53f); lineTo(42f, -75f); lineTo(46f, -25f); close()
        }
        paint.color = orange
        canvas.drawPath(ears, paint)
        val earInner = Path().apply {
            moveTo(-36f, -43f); lineTo(-35f, -64f); lineTo(-20f, -51f)
            moveTo(20f, -51f); lineTo(35f, -64f); lineTo(36f, -43f)
        }
        paint.color = Color.rgb(230, 139, 132)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
        canvas.drawPath(earInner, paint)
        paint.style = Paint.Style.FILL
        ellipse(canvas, -48f, -53f, 48f, 37f, orange)
        ellipse(canvas, -40f, -13f, 0f, 34f, cream)
        ellipse(canvas, 0f, -13f, 40f, 34f, cream)
        val blink = closedEyes || ((SystemClock.uptimeMillis() % 4200L) in 0L..130L)
        if (blink) {
            line(canvas, -29f, -9f, -12f, -9f, ink, 3f)
            line(canvas, 12f, -9f, 29f, -9f, ink, 3f)
        } else {
            ellipse(canvas, -26f, -19f, -17f, -4f, ink)
            ellipse(canvas, 17f, -19f, 26f, -4f, ink)
        }
        paint.color = Color.rgb(223, 129, 125)
        val nose = Path().apply { moveTo(-7f, 11f); lineTo(7f, 11f); lineTo(0f, 18f); close() }
        canvas.drawPath(nose, paint)
        line(canvas, 0f, 18f, 0f, 22f, ink, 2f)
        line(canvas, -1f, 22f, -8f, 24f, ink, 2f)
        line(canvas, 1f, 22f, 8f, 24f, ink, 2f)
        for (y in listOf(8f, 17f)) {
            line(canvas, -18f, y, -46f, y - 5f, ink, 1.5f)
            line(canvas, 18f, y, 46f, y - 5f, ink, 1.5f)
        }
        canvas.restore()
    }

    private fun drawHeart(canvas: Canvas) {
        paint.color = Color.rgb(244, 111, 126)
        paint.style = Paint.Style.FILL
        val heart = Path().apply {
            moveTo(157f, 51f)
            cubicTo(142f, 32f, 140f, 57f, 157f, 66f)
            cubicTo(177f, 55f, 170f, 32f, 157f, 51f)
        }
        canvas.drawPath(heart, paint)
    }
}
