package com.wim.app

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout

/**
 * Sound-wave bars flanking the WiM logo.
 * 7 bars per side, each with a unique keyframe animation.
 * Scaled down from the desktop Лаврентий visualizer (10-56px → 5-28dp).
 *
 * To mirror (right side), set mirror=true — reverses the height array
 * so the tallest bars stay closer to the logo on both sides.
 */
class LogoBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    // Stepped heights — tallest at index 4, creating a wave shape
    // Mobile-scaled from desktop spec (10→20→34→48→56→42→26 px)
    private val heightsDp = intArrayOf(5, 10, 17, 24, 28, 21, 13)
    private val barWidthDp = 2
    private val barSpacingDp = 2

    // 7 unique keyframe patterns — each bar dances differently
    private val patterns = listOf(
        floatArrayOf(0.7f, 1.0f, 0.5f, 0.9f, 0.7f),
        floatArrayOf(0.5f, 0.8f, 1.0f, 0.3f, 0.5f),
        floatArrayOf(1.0f, 0.4f, 0.9f, 0.6f, 1.0f),
        floatArrayOf(0.3f, 0.7f, 1.0f, 0.5f, 0.3f),
        floatArrayOf(0.8f, 0.5f, 0.9f, 1.0f, 0.8f),
        floatArrayOf(0.6f, 1.0f, 0.4f, 0.8f, 0.6f),
        floatArrayOf(0.9f, 0.3f, 0.7f, 1.0f, 0.9f)
    )
    private val keyframeTimes = floatArrayOf(0f, 0.24f, 0.5f, 0.76f, 1f)

    // Durations in the 750-1350ms range per constraint #3
    private val durationsMs = longArrayOf(1100, 850, 1300, 950, 750, 1200, 1000)

    private val bars = mutableListOf<View>()
    private val animators = mutableListOf<ObjectAnimator>()
    private var unsubscribe: (() -> Unit)? = null

    // Idle alpha vs. active (recording) alpha
    private val idleAlpha = 0.4f
    private val activeAlpha = 0.95f
    // Active state speeds up animations to make recording obviously visible
    private val activeSpeedMultiplier = 0.55f

    var mirror: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                rebuildBars()
            }
        }

    private var active: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                val targetAlpha = if (value) activeAlpha else idleAlpha
                bars.forEach { it.animate().alpha(targetAlpha).setDuration(180).start() }
                // Speed up / slow down animators by scaling durations
                val mult = if (value) activeSpeedMultiplier else 1.0f
                animators.forEachIndexed { i, animator ->
                    animator.duration = (durationsMs[i % durationsMs.size] * mult).toLong()
                }
            }
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        rebuildBars()
    }

    private fun rebuildBars() {
        animators.forEach { it.cancel() }
        animators.clear()
        removeAllViews()
        bars.clear()

        val dp = resources.displayMetrics.density
        val widthPx = (barWidthDp * dp).toInt()
        val spacingPx = (barSpacingDp * dp).toInt()

        // When mirrored, reverse heights so tallest bars stay near the logo on both sides
        val heights = if (mirror) heightsDp.reversedArray() else heightsDp

        for (i in heights.indices) {
            val heightPx = (heights[i] * dp).toInt()
            val bar = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(context.getColor(R.color.orange_label), 0xFFB55050.toInt())  // orange → maroon
                ).apply {
                    cornerRadius = widthPx.toFloat() / 2
                }
                layoutParams = LayoutParams(widthPx, heightPx).apply {
                    marginStart = spacingPx / 2
                    marginEnd = spacingPx / 2
                }
                alpha = if (active) activeAlpha else idleAlpha
            }
            addView(bar)
            bars.add(bar)
            animators.add(buildAnimator(bar, i))
        }
    }

    private fun buildAnimator(bar: View, index: Int): ObjectAnimator {
        val pattern = patterns[index % patterns.size]
        val keyframes = Array(keyframeTimes.size) { idx ->
            Keyframe.ofFloat(keyframeTimes[idx], pattern[idx])
        }
        val holder = PropertyValuesHolder.ofKeyframe("scaleY", *keyframes)

        return ObjectAnimator.ofPropertyValuesHolder(bar, holder).apply {
            duration = durationsMs[index % durationsMs.size]
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            // Stagger start times per bar + offset the right side by 400ms to desync from left
            currentPlayTime = (index * 130L) + (if (mirror) 400L else 0L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animators.forEach { if (!it.isStarted) it.start() }
        // React to recording state — brighten and speed up when the bubble records
        unsubscribe = RecordingStateBus.subscribe { recording ->
            post { active = recording }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animators.forEach { it.cancel() }
        unsubscribe?.invoke()
        unsubscribe = null
    }
}
