package com.wim.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * SeekBar rotated -90° so progress runs bottom→top. Width and height are
 * swapped at measure time, draw is rotated, and touch events are translated
 * to vertical progress (top of the bar = max). Listener fires normally.
 *
 * Used by the bubble menu's Opacity / Size sliders so they can sit as a
 * narrow column on the side rather than spanning the menu width.
 */
class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Tell the underlying SeekBar that its size is the swapped dimensions
        // so its internal track/thumb math operates on the rotated geometry.
        super.onSizeChanged(h, w, oldh, oldw)
    }

    @Synchronized
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                val span = (max - min).coerceAtLeast(1)
                // Top of the slider = max, bottom = min. event.y grows downward.
                val ratio = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                val newProgress = min + (span * ratio).toInt()
                progress = newProgress.coerceIn(min, max)
                onSizeChanged(width, height, 0, 0)
            }
            MotionEvent.ACTION_CANCEL -> { /* leave progress as-is */ }
        }
        return true
    }
}
