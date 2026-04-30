package com.wim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Patent-clean word-highlight overlay — claim 13 ("visible-anchor underline
 * overlay") + claim 1 ("text-field cursor position remain unchanged for the
 * duration of said clarification state").
 *
 * Draws a thin yellow underline along the bottom edge of the host app's
 * focused text field while the clarifier cycle is active. Hosted in a
 * separate `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_TOUCHABLE` so
 * touches pass through to the host app and the user's interaction state
 * (cursor, selection, scroll, focus) stays untouched.
 *
 * BubbleService positions the window from the focused field's screen-rect
 * (`WimAccessibilityService.focusedFieldBounds()`) and refreshes on each
 * clarifier cycle advance. Sub-region (per-word) targeting is reserved for
 * a future revision once we have reliable per-character bounds across host
 * apps; current MVP underlines the entire field.
 */
class WordHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD580.toInt() // brand cream-gold — matches REC/PROC labels
    }

    /** Stroke height in dp; converted to px in onDraw. */
    private val strokeDp: Float = 3f

    /** Optional sub-region within the view bounds. When (0, 0, 0, 0), the
     *  whole bottom edge is underlined. Reserved for per-word targeting. */
    private var subStart: Float = 0f
    private var subEnd: Float = 0f

    fun setSubRange(startPx: Float, endPx: Float) {
        subStart = startPx
        subEnd = endPx
        invalidate()
    }

    fun clearSubRange() {
        subStart = 0f
        subEnd = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val strokePx = strokeDp * density
        val left = if (subEnd > subStart) subStart else 0f
        val right = if (subEnd > subStart) subEnd else w
        val top = h - strokePx
        canvas.drawRect(left, top, right, h, paint)
    }
}
