package com.wim.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

/**
 * iMessage-style speech bubble: rounded rect with a downward-pointing tail
 * at the bottom-center. Used as the floating bubble menu's background so the
 * menu reads as "coming out of" the floating bubble below it.
 */
class CommentBubbleDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val displayMetrics: DisplayMetrics,
    cornerRadiusDp: Float = 16f,
    tailWidthDp: Float = 22f,
    tailHeightDp: Float = 14f,
    strokeWidthDp: Float = 1.5f,
) : Drawable() {

    private val cornerRadiusPx = cornerRadiusDp * displayMetrics.density
    private val tailWidthPx = tailWidthDp * displayMetrics.density
    val tailHeightPx = tailHeightDp * displayMetrics.density
    private val strokeWidthPx = strokeWidthDp * displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val inset = strokeWidthPx / 2f
        val tipY = h - inset
        val rectBottom = tipY - tailHeightPx
        val rectTop = inset
        val rectLeft = inset
        val rectRight = w - inset
        val cx = w / 2f

        path.rewind()
        path.moveTo(rectLeft + cornerRadiusPx, rectTop)
        path.lineTo(rectRight - cornerRadiusPx, rectTop)
        path.quadTo(rectRight, rectTop, rectRight, rectTop + cornerRadiusPx)
        path.lineTo(rectRight, rectBottom - cornerRadiusPx)
        path.quadTo(rectRight, rectBottom, rectRight - cornerRadiusPx, rectBottom)
        path.lineTo(cx + tailWidthPx / 2f, rectBottom)
        path.lineTo(cx, tipY)
        path.lineTo(cx - tailWidthPx / 2f, rectBottom)
        path.lineTo(rectLeft + cornerRadiusPx, rectBottom)
        path.quadTo(rectLeft, rectBottom, rectLeft, rectBottom - cornerRadiusPx)
        path.lineTo(rectLeft, rectTop + cornerRadiusPx)
        path.quadTo(rectLeft, rectTop, rectLeft + cornerRadiusPx, rectTop)
        path.close()

        // Drop shadow — offset down 2px so the bubble feels lifted off the bg
        canvas.save()
        canvas.translate(0f, 3f * displayMetrics.density)
        canvas.drawPath(path, shadowPaint)
        canvas.restore()

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Per-bubble-theme palette for the menu's comment bubble background.
         * Each entry: fill color, stroke color, primary text color (slider values,
         * active labels), secondary text color (section labels), accent color
         * (active layer / Settings). Picked to match the floating bubble's
         * own bezel palette (bubble_bg_idle / bubble_bezel_aluminum /
         * bubble_bezel_gunmetal).
         */
        data class Palette(
            val fill: Int,
            val stroke: Int,
            val primaryText: Int,
            val secondaryText: Int,
            val accent: Int,
            val sliderTrack: Int,
        )

        val BRONZE = Palette(
            fill = 0xFFC4A574.toInt(),       // mid-warm copper
            stroke = 0xFFB55050.toInt(),     // maroon (matches bubble bezel stroke)
            primaryText = 0xFF1F1208.toInt(),
            secondaryText = 0xFF4A2F18.toInt(),
            accent = 0xFF8B0000.toInt(),     // dark red on bronze
            sliderTrack = 0x335C3A1F,
        )

        val ALUMINUM = Palette(
            fill = 0xFFC8C8CE.toInt(),       // brushed silver mid-tone
            stroke = 0xFFB55050.toInt(),
            primaryText = 0xFF1A1A20.toInt(),
            secondaryText = 0xFF45454F.toInt(),
            accent = 0xFFB55050.toInt(),     // maroon (matches stroke)
            sliderTrack = 0x33555560,
        )

        val GUNMETAL = Palette(
            fill = 0xFF2E2E36.toInt(),       // dark brushed steel
            stroke = 0xFFB55050.toInt(),
            primaryText = 0xFFF0E8D8.toInt(),
            secondaryText = 0xFFC8B48A.toInt(), // brand cream
            accent = 0xFFEE5A24.toInt(),     // orange (existing brand accent)
            sliderTrack = 0x44C8B48A,
        )

        fun paletteFor(theme: String): Palette = when (theme) {
            "aluminum" -> ALUMINUM
            "gunmetal" -> GUNMETAL
            else -> BRONZE
        }
    }
}
