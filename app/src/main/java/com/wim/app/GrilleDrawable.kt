package com.wim.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Marshall-style honeycomb perforated metal grille.
 * Staggered dot pattern matching Лаврентий dashboard.
 */
class GrilleDrawable : Drawable() {

    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x50000000.toInt()
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF.toInt()
        style = Paint.Style.FILL
    }

    // Larger spacing + holes for mobile visibility
    private val spacingX = 16f
    private val spacingY = 14f
    private val holeRadius = 3.8f

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()

        var row = 0
        var y = 0f
        while (y < h + spacingY) {
            val offsetX = if (row % 2 == 1) spacingX / 2f else 0f
            var x = offsetX
            while (x < w + spacingX) {
                // Hole (dark punch)
                canvas.drawCircle(x, y, holeRadius, holePaint)
                // Rim highlight (light catch on upper edge)
                canvas.drawCircle(x, y - 0.6f, holeRadius + 0.3f, highlightPaint)
                x += spacingX
            }
            y += spacingY
            row++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
