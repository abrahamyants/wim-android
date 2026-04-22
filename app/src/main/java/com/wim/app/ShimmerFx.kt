package com.wim.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Animated "flashlight passes over surface" shimmer — a very subtle light band
 * that sweeps across text or a stroked border. Same visual idea Anthropic's
 * Claude UI uses on streaming chat text + tile borders.
 *
 * Two entry points:
 *  - attachTextShimmer(textView) — animates a highlight band across the text's fill
 *  - BorderShimmerDrawable — a foreground drawable that renders an animated
 *    gradient stroke around a rounded rect, layered on top of an existing background
 *
 * Every shimmer instance drives off its own ValueAnimator. For a handful of
 * views this is fine; if the count grows beyond ~20, switch to a shared clock.
 *
 * Tuning per-design spec: highlight band ~12-15% of width, cycle 2.5-3.5s,
 * highlight color low-opacity so the effect reads as "a bright light passing"
 * rather than "the text changed color."
 */
object ShimmerFx {

    private const val DEFAULT_CYCLE_MS = 3000L
    // Highlight color at ~75% alpha — the sweep is perceptible but doesn't
    // wash out the base text/stroke color.
    private const val DEFAULT_HIGHLIGHT = 0xBFFFE8B8.toInt()

    /**
     * Attach a text-fill shimmer sweep to a TextView. Runs for the lifetime
     * the view is attached to a window; auto-cleans on detach.
     */
    fun attachTextShimmer(
        tv: TextView,
        cycleMs: Long = DEFAULT_CYCLE_MS,
        highlightColor: Int = DEFAULT_HIGHLIGHT
    ) {
        // Defer until the view has been measured — gradient math needs width.
        if (tv.width == 0) {
            tv.post { attachTextShimmer(tv, cycleMs, highlightColor) }
            return
        }
        val baseColor = tv.currentTextColor
        val width = tv.width.toFloat()
        val bandWidth = width * 0.13f
        val shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        tv.paint.shader = shader

        val matrix = Matrix()
        val animator = ValueAnimator.ofFloat(-bandWidth, width + bandWidth).apply {
            duration = cycleMs
            // A single sweep then a pause — the pause makes the effect feel
            // like a discrete passing-light event rather than a constant motion.
            // Implemented as a repeat with startDelay via the ValueAnimator's
            // internal repeat machinery is not straightforward; instead we pad
            // the sweep range so the highlight is off-screen for most of the cycle.
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                matrix.setTranslate(it.animatedValue as Float, 0f)
                shader.setLocalMatrix(matrix)
                tv.invalidate()
            }
        }
        animator.start()

        tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                animator.cancel()
                tv.paint.shader = null
            }
        })
    }

    /**
     * Foreground drawable: draws a rounded-rect stroke with an animated
     * gradient sweep on top of whatever the view's background already is.
     * Meant for layering onto section headers (glass_panel_bg_pressable etc.).
     *
     * Pass the view's corner radius in dp to match the underlying drawable.
     */
    class BorderShimmerDrawable(
        private val displayMetrics: DisplayMetrics,
        cornerRadiusDp: Float = 10f,
        strokeWidthDp: Float = 1.5f,
        private val cycleMs: Long = 3500L,
        private val highlightColor: Int = DEFAULT_HIGHLIGHT
    ) : Drawable() {

        private val cornerRadiusPx: Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, displayMetrics)
        private val strokeWidthPx: Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, strokeWidthDp, displayMetrics)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        private val rect = RectF()
        private val matrix = Matrix()
        private var shader: LinearGradient? = null
        private var phase = 0f

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycleMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidateSelf()
            }
        }

        fun start() {
            if (!animator.isStarted) animator.start()
        }

        fun stop() {
            animator.cancel()
        }

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            if (w <= 0f) return
            val band = w * 0.2f
            shader = LinearGradient(
                0f, 0f, band, 0f,
                intArrayOf(0x00000000, highlightColor, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() == 0 || b.height() == 0) return
            val s = shader ?: return
            // Inset half the stroke so the line paints fully inside the rect
            val half = strokeWidthPx / 2f
            rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half)

            // Slide the shader band across by phase fraction. Range goes from
            // off the left edge to off the right edge with a pause in between
            // (the pause is implicit: once the band exits the right edge, the
            // CLAMP tile mode + transparent outer stops paint nothing until
            // the next cycle brings it back on the left).
            val w = b.width().toFloat()
            val band = w * 0.2f
            val translateX = -band + phase * (w + band * 2f)
            matrix.setTranslate(translateX, 0f)
            s.setLocalMatrix(matrix)
            paint.shader = s

            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
