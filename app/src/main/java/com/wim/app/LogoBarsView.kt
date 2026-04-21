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
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

/**
 * Sound-wave bars flanking the WiM logo.
 *
 * Idle: 7 bars per side with a unique keyframe wave animation (subtle, muted).
 * Active (recording): the bar set extends outward from the logo — 11 extra bars
 * appear past the base 7 so the total stretches to ~2.5× the idle footprint.
 * Colors shift to a hotter red/amber gradient and the whole set lifts a touch
 * vertically. When recording stops the extras retract with an OvershootInterpolator
 * "ping" — like puppy ears snapping back.
 *
 * Bar heights come from a tiled pattern so the extension looks like another
 * equalizer attached to the first, not a stretched version of the same one.
 *
 * To mirror (right side), set mirror=true — reverses the height array
 * so the tallest bars stay closer to the logo on both sides.
 */
class LogoBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    // Stepped heights — tallest at index 4, creating a wave shape.
    // IDLE = first 7 bars (the historic "puppy ears" shape).
    // ACTIVE = 18 bars total: base 7 + second equalizer (7) + half equalizer (4).
    // Heights for the extension bars taper the second equalizer slightly so the
    // whole strip feels continuous, not copy-pasted.
    private val heightsDp = intArrayOf(
        5, 10, 17, 24, 28, 21, 13,      // base equalizer (idle)
        9, 18, 24, 22, 16, 10, 6,       // second equalizer (slightly shorter peak)
        12, 8, 5, 3                     // half equalizer (trailing tail)
    )
    private val idleBarCount = 7
    private val activeBarCount = heightsDp.size  // 18
    private val barWidthDp = 2
    private val barSpacingDp = 2

    // 7 unique keyframe patterns — each bar dances differently. Tiled across
    // the 18 bars via `patterns[i % 7]`.
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
    // On recording the bar strip lifts a touch vertically — just enough to
    // register that something changed, but the main growth is horizontal
    // (via the extra bars below). Pivots at the bottom so bars extend UPWARD.
    private val activeExtendScaleY = 1.15f

    // Staggered reveal of the extension bars creates a left-to-right "unfurl"
    // when recording starts. Reverse order on retract gives the ear-ping effect.
    private val extensionStaggerMs = 22L

    // Idle gradient — the existing orange → maroon warm tones.
    private val idleGradient = intArrayOf(
        0xFFE08B3A.toInt(),  // orange (R.color.orange_label equivalent)
        0xFFB55050.toInt()   // maroon
    )
    // Active gradient — hotter, lighter, pushes red while keeping the warm bottom.
    private val activeGradient = intArrayOf(
        0xFFFFC870.toInt(),  // bright amber (top)
        0xFFFF4F4F.toInt()   // hot red (bottom)
    )

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
                applyActiveState(value)
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Anchor parent scale at the bottom so the bars extend upward when recording.
        pivotY = h.toFloat()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Allow children drawn past the parent's bounds during the extension
        // stagger so no bar gets clipped mid-reveal.
        clipChildren = false
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

        // When mirrored, reverse heights so tallest bars stay near the logo on both sides.
        // Mirroring now applies across the full 18-bar array so the extended shape
        // also points the right direction on the right side of the logo.
        val heights = if (mirror) heightsDp.reversedArray() else heightsDp

        for (i in heights.indices) {
            val heightPx = (heights[i] * dp).toInt()
            val bar = View(context).apply {
                background = buildGradient(widthPx)
                layoutParams = LayoutParams(widthPx, heightPx).apply {
                    marginStart = spacingPx / 2
                    marginEnd = spacingPx / 2
                }
                alpha = if (active) activeAlpha else idleAlpha
                // Each bar also pivots at its own bottom so the per-bar
                // scaleY animator grows the bar upward rather than symmetrically.
                pivotY = heightPx.toFloat()
                // Pivot the horizontal reveal at the side closest to the logo
                // so the extension unfurls outward. On the left-side strip the
                // logo is to the right (bars should unfurl leftward); mirrored
                // (right-side) strip has the logo to the left.
                pivotX = if (mirror) 0f else widthPx.toFloat()
                // Extension bars start collapsed until active.
                if (i >= idleBarCount) {
                    scaleX = 0f
                    alpha = 0f
                    visibility = GONE
                }
            }
            addView(bar)
            bars.add(bar)
            animators.add(buildAnimator(bar, i))
        }
    }

    private fun buildGradient(widthPx: Int): GradientDrawable {
        val colors = if (active) activeGradient else idleGradient
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = widthPx.toFloat() / 2
        }
    }

    private fun applyActiveState(isActive: Boolean) {
        val targetAlpha = if (isActive) activeAlpha else idleAlpha
        val targetScaleY = if (isActive) activeExtendScaleY else 1.0f

        // Swap the gradient on every bar — same shape, hotter colors when recording.
        val dp = resources.displayMetrics.density
        val widthPx = (barWidthDp * dp).toInt()
        bars.forEach { it.background = buildGradient(widthPx) }

        // Base bars: animate alpha to idle/active.
        for (i in 0 until idleBarCount.coerceAtMost(bars.size)) {
            bars[i].animate().alpha(targetAlpha).setDuration(180).start()
        }

        // Vertical lift — small (1.15×). Ping-back uses OvershootInterpolator so
        // the retract ACTUALLY flicks past 1.0 and settles, mimicking ears snapping.
        val scaleYAnim = animate().scaleY(targetScaleY).setDuration(if (isActive) 280 else 360)
        scaleYAnim.interpolator =
            if (isActive) AccelerateDecelerateInterpolator() else OvershootInterpolator(2.4f)
        scaleYAnim.start()

        // Extension bars: unfurl outward on active, retract with ping on idle.
        val extensionIndices = idleBarCount until bars.size
        val orderedIndices = if (mirror) extensionIndices.toList() else extensionIndices.toList().reversed()
        // `orderedIndices` is the stagger order. On the left-side strip the bars
        // closest to the logo (end of the list) unfurl first; on the right-side
        // strip (mirrored) the bars closest to the logo (start of the list after
        // reverse) unfurl first. Result: both sides unfurl outward from the logo.

        for ((staggerStep, i) in orderedIndices.withIndex()) {
            val bar = bars[i]
            if (isActive) {
                bar.visibility = VISIBLE
                bar.animate()
                    .scaleX(1f)
                    .alpha(activeAlpha)
                    .setStartDelay(staggerStep * extensionStaggerMs)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()
            } else {
                bar.animate()
                    .scaleX(0f)
                    .alpha(0f)
                    .setStartDelay(staggerStep * extensionStaggerMs)
                    .setDuration(180)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction { bar.visibility = GONE }
                    .start()
            }
        }

        // Speed up / slow down animators by scaling durations on active.
        val mult = if (isActive) activeSpeedMultiplier else 1.0f
        animators.forEachIndexed { i, animator ->
            animator.duration = (durationsMs[i % durationsMs.size] * mult).toLong()
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
            // Stagger start times per bar + offset the right side by 400ms to desync from left.
            currentPlayTime = (index * 130L) + (if (mirror) 400L else 0L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animators.forEach { if (!it.isStarted) it.start() }
        // React to recording state — brighten, extend, speed up when the bubble records.
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
