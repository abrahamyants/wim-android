package com.wim.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * VU meter gauge — draws an arc with needle, value, and label.
 * Replaces nixie tube stat cells.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var value: Float = 0f
        set(v) { field = v; invalidate() }
    var maxValue: Float = 100f
        set(v) { field = v; invalidate() }
    var displayText: String = "0"
        set(v) { field = v; invalidate() }
    var label: String = ""
        set(v) { field = v; invalidate() }

    // Colors from the mockup
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF3A3530.toInt()
    }
    private val redZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF5C2828.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF6A5A48.toInt()
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFD4C5A9.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFC49A6C.toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = 0xFFD4C5A9.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        color = 0xFF6A5A48.toInt()
        letterSpacing = 0.15f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cx = w / 2f
        val gaugeBottom = h * 0.6f
        val radius = min(w * 0.42f, gaugeBottom * 0.75f)

        // Arc bounds
        val arcRect = RectF(cx - radius, gaugeBottom - radius, cx + radius, gaugeBottom + radius)

        // Main arc (180° to 360° = bottom semicircle, but we want top)
        // Draw from 200° to 340° (leaving gaps at sides)
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

        // Needle — maps value to angle
        val fraction = (value / maxValue).coerceIn(0f, 1f)
        val needleAngle = Math.toRadians((startAngle + fraction * sweepAngle).toDouble())
        val needleLen = radius * 0.78f
        val nx = cx + needleLen * cos(needleAngle).toFloat()
        val ny = gaugeBottom + needleLen * sin(needleAngle).toFloat()
        canvas.drawLine(cx, gaugeBottom, nx, ny, needlePaint)

        // Pivot dot
        canvas.drawCircle(cx, gaugeBottom, w * 0.035f, pivotPaint)

        // Value text — inside the arc
        valuePaint.textSize = h * 0.22f
        canvas.drawText(displayText, cx, gaugeBottom - radius * 0.25f, valuePaint)

        // Label text — below the arc
        labelPaint.textSize = h * 0.1f
        canvas.drawText(label, cx, gaugeBottom + h * 0.18f, labelPaint)
    }
}
