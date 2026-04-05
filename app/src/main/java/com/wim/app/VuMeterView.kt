package com.wim.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

/**
 * VU meter gauge — draws an arc with needle, value, and label.
 * Replaces nixie tube stat cells.
 *
 * Needle sweeps smoothly to new values like a real tachometer.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var displayedFraction: Float = 0f  // what we actually draw (animated)
    private var targetFraction: Float = 0f     // what we're animating toward
    private var needleAnimator: ValueAnimator? = null
    private var hasRunStartupSweep = false

    var value: Float = 0f
        set(v) {
            field = v
            val frac = (v / maxValue).coerceIn(0f, 1f)
            targetFraction = frac
            if (hasRunStartupSweep) animateNeedleTo(frac)
        }
    var maxValue: Float = 100f
        set(v) {
            field = v
            val frac = (value / v).coerceIn(0f, 1f)
            targetFraction = frac
            if (hasRunStartupSweep) animateNeedleTo(frac)
        }
    var displayText: String = "0"
        set(v) { field = v; invalidate() }
    var label: String = ""
        set(v) { field = v; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Dashboard power-on sweep: 0 → redline → settle at target
        postDelayed({ runStartupSweep() }, 200)
    }

    private fun runStartupSweep() {
        if (hasRunStartupSweep) return
        hasRunStartupSweep = true
        needleAnimator?.cancel()
        // Sweep to redline
        needleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                displayedFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
        // After hitting redline, settle to target
        postDelayed({
            needleAnimator?.cancel()
            needleAnimator = ValueAnimator.ofFloat(1f, targetFraction).apply {
                duration = 900
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { anim ->
                    displayedFraction = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }, 800)
    }

    private fun animateNeedleTo(newFraction: Float) {
        if (abs(newFraction - displayedFraction) < 0.001f) return
        needleAnimator?.cancel()
        needleAnimator = ValueAnimator.ofFloat(displayedFraction, newFraction).apply {
            duration = 600
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                displayedFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // Theme detection — VU meter colors adapt to light/dark mode
    private val isNight: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val arcColor: Int get() = if (isNight) 0xFF3A3530.toInt() else 0xFF6B4423.toInt()
    private val redZoneColor: Int get() = if (isNight) 0xFF5C2828.toInt() else 0xFF8B3A1F.toInt()
    private val tickColor: Int get() = if (isNight) 0xFF6A5A48.toInt() else 0xFF4A3520.toInt()
    private val needleColor: Int get() = if (isNight) 0xFFD4C5A9.toInt() else 0xFF2A2420.toInt()
    private val pivotColor: Int get() = if (isNight) 0xFFC49A6C.toInt() else 0xFF8A5A2F.toInt()
    private val valueColor: Int get() = if (isNight) 0xFFD4C5A9.toInt() else 0xFF2A2420.toInt()
    private val labelColor: Int get() = if (isNight) 0xFF6A5A48.toInt() else 0xFF6B5D4A.toInt()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val redZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        letterSpacing = 0.15f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Refresh colors from theme on each draw
        arcPaint.color = arcColor
        redZonePaint.color = redZoneColor
        tickPaint.color = tickColor
        needlePaint.color = needleColor
        pivotPaint.color = pivotColor
        valuePaint.color = valueColor
        labelPaint.color = labelColor

        val cx = w / 2f
        val gaugeBottom = h * 0.6f
        // Radius constrained by both dimensions — prevents landscape blowup
        val radius = min(w * 0.42f, gaugeBottom * 0.75f)

        // Arc bounds
        val arcRect = RectF(cx - radius, gaugeBottom - radius, cx + radius, gaugeBottom + radius)

        val startAngle = 200f
        val sweepAngle = 140f
        val redStart = 305f // red zone starts at ~75% of sweep

        // Main arc
        canvas.drawArc(arcRect, startAngle, redStart - startAngle, false, arcPaint)
        // Red zone
        canvas.drawArc(arcRect, redStart, startAngle + sweepAngle - redStart, false, redZonePaint)

        // Tick marks (5 evenly spaced)
        for (i in 0..4) {
            val frac = i / 4f
            val angle = Math.toRadians((startAngle + frac * sweepAngle).toDouble())
            val innerR = radius * 0.85f
            val outerR = radius * 0.95f
            val x1 = cx + innerR * cos(angle).toFloat()
            val y1 = gaugeBottom + innerR * sin(angle).toFloat()
            val x2 = cx + outerR * cos(angle).toFloat()
            val y2 = gaugeBottom + outerR * sin(angle).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Needle — uses animated displayedFraction for smooth sweep
        val needleAngle = Math.toRadians((startAngle + displayedFraction * sweepAngle).toDouble())
        val needleLen = radius * 0.78f
        val nx = cx + needleLen * cos(needleAngle).toFloat()
        val ny = gaugeBottom + needleLen * sin(needleAngle).toFloat()
        canvas.drawLine(cx, gaugeBottom, nx, ny, needlePaint)

        // Pivot dot — sized relative to radius (scales with gauge, not width)
        canvas.drawCircle(cx, gaugeBottom, radius * 0.08f, pivotPaint)

        // Value text — inside the arc
        valuePaint.textSize = radius * 0.45f
        canvas.drawText(displayText, cx, gaugeBottom - radius * 0.25f, valuePaint)

        // Label text — below the arc, matches status chip label style (8sp monospace)
        val density = resources.displayMetrics.density
        labelPaint.textSize = 8f * density
        canvas.drawText(label, cx, gaugeBottom + radius * 0.4f, labelPaint)
    }
}
