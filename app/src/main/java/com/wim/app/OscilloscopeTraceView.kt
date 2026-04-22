package com.wim.app

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * Twin oscilloscope-trace visualizer flanking the WiM logo.
 *
 * Draws a continuous amber waveform line (plus a softer afterglow line
 * underneath for CRT phosphor persistence) that pulses gently while the
 * bubble is idle and turns into an energetic composite waveform while
 * recording is in flight.
 *
 * Idle: single slow sine wave, low amplitude, low opacity — the "console
 * on standby" heartbeat. 3.5-second cycle.
 *
 * Active: sum of three unrelated-frequency sines (5 Hz + 13 Hz + 23 Hz at
 * 1.0/0.55/0.3 weights) — prime-like ratios so the waveform doesn't repeat
 * visibly. High amplitude, high opacity, with occasional single-frame
 * hotter-white transient sparkles on peaks.
 *
 * Left strip phase-advances left→right; right strip (mirror=true) flows
 * right→left. Both traces aim toward the WiM text in the middle; on heavy
 * transients they appear to collide at the logo.
 *
 * Transition signature: a "phosphor sigh" on active→idle. Instead of
 * snapping to zero, amplitude undershoots its idle target by ~15% and
 * springs back up, like a CRT's phosphor cooling.
 */
class OscilloscopeTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var mirror: Boolean = false

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.OscilloscopeTraceView)
            mirror = ta.getBoolean(R.styleable.OscilloscopeTraceView_mirror, false)
            ta.recycle()
        }
    }

    // ── Waveform math ──────────────────────────────────────────────────────

    // Horizontal sample count. 48 samples across ~48dp = ~1 sample per dp,
    // plenty of resolution for smooth curves without overdrawing.
    private val sampleCount = 48
    private val samples = FloatArray(sampleCount)

    // Continuously-advancing clock (seconds-like, but unitless) driven by
    // the infinite ValueAnimator below. Drives phase of every sine component.
    private var timePhase: Float = 0f

    // Current amplitude blend: 0.0 = pure idle, 1.0 = pure active. The
    // phosphor-sigh overshoots below 0 briefly on deactivation.
    private var amplitudeNorm: Float = 0f

    // Active-state sparkle: when > 0, an extra hotter-white highlight is
    // drawn on the peak sample for a frame or two. Decays each frame.
    private var sparkleFrames: Int = 0

    // ── Paint ──────────────────────────────────────────────────────────────

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    // Stroke widths in dp — converted to px once we know density.
    private val mainStrokeDp = 1.5f
    private val glowStrokeDp = 4f
    private val glowOffsetIdleDp = 0.5f
    private val glowOffsetActiveDp = 1.5f
    private val horizontalPaddingDp = 2f
    private val verticalPaddingDp = 2f

    // Warm champagne-gold from the WiM brand palette.
    private val amberColor = 0xFFF2CF7A.toInt()
    private val hotSparkColor = 0xFFFFF5D0.toInt()

    // ── Animators ──────────────────────────────────────────────────────────

    // Clock: linear continuously-growing value. Period not meaningful since
    // the frequency constants in the waveform math scale it.
    private val clockAnimator = ValueAnimator.ofFloat(0f, 1000f).apply {
        duration = 1000000L // ~16 minutes before it resets; loops forever
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            timePhase = (it.animatedValue as Float)
            invalidate()
        }
    }

    // State transition animator(s). Only one active at a time.
    private var transitionAnim: AnimatorSet? = null

    private var unsubscribeRecording: (() -> Unit)? = null

    // ── View lifecycle ─────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!clockAnimator.isStarted) clockAnimator.start()
        unsubscribeRecording = RecordingStateBus.subscribe { recording ->
            post { transitionTo(active = recording) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clockAnimator.cancel()
        transitionAnim?.cancel()
        unsubscribeRecording?.invoke()
        unsubscribeRecording = null
    }

    private fun transitionTo(active: Boolean) {
        transitionAnim?.cancel()
        val current = amplitudeNorm
        val target = if (active) 1f else 0f

        if (active) {
            // Activation: single ramp with a small overshoot for snap.
            val anim = ValueAnimator.ofFloat(current, target).apply {
                duration = 180L
                interpolator = OvershootInterpolator(1.6f)
                addUpdateListener {
                    amplitudeNorm = it.animatedValue as Float
                    invalidate()
                }
            }
            transitionAnim = AnimatorSet().apply {
                play(anim)
                start()
            }
        } else {
            // Deactivation: phosphor-sigh. Two-step chain — dip briefly below
            // idle (to -0.15) then spring back up to 0.
            val undershoot = ValueAnimator.ofFloat(current, -0.15f).apply {
                duration = 200L
                interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener {
                    amplitudeNorm = it.animatedValue as Float
                    invalidate()
                }
            }
            val settle = ValueAnimator.ofFloat(-0.15f, 0f).apply {
                duration = 200L
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener {
                    amplitudeNorm = it.animatedValue as Float
                    invalidate()
                }
            }
            transitionAnim = AnimatorSet().apply {
                playSequentially(undershoot, settle)
                start()
            }
        }
    }

    // ── Measurement ────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Default size if wrap_content: 48dp × 28dp.
        val dp = resources.displayMetrics.density
        val desiredW = (48 * dp).toInt()
        val desiredH = (28 * dp).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val dp = resources.displayMetrics.density
        val padX = horizontalPaddingDp * dp
        val padY = verticalPaddingDp * dp
        val traceWidth = w - padX * 2
        val centerY = h / 2f
        val maxAmplitudePx = (h / 2f) - padY

        // Compute samples for this frame.
        // The idle blend and active blend are computed independently then
        // linearly interpolated by amplitudeNorm. Idle stays on even at
        // active=1 (slight visual "carrier" under the energetic waveform).
        val activeBlend = amplitudeNorm.coerceAtLeast(0f) // ignore negative during sigh for waveform composition
        val idleBlend = 1f - activeBlend.coerceAtMost(1f)

        var peakIdx = sampleCount / 2
        var peakAbs = 0f

        for (i in 0 until sampleCount) {
            val fx = i.toFloat() / (sampleCount - 1)

            // Idle component: one gentle sine wave, 3.5s period
            // timePhase advances at 1.0 per frame ~@ 60fps; multiply down to slow it.
            val idleArg = timePhase * 0.028f + fx * (Math.PI.toFloat() * 2f)
            val idle = sin(idleArg.toDouble()).toFloat() * 0.15f

            // Active component: 3 summed sines at different temporal speeds
            // and spatial wavelengths. Weights sum normalized so peak ~= 1.0.
            val a1 = sin((timePhase * 0.5f + fx * 6f).toDouble()).toFloat()
            val a2 = sin((timePhase * 1.3f + fx * 14f).toDouble()).toFloat() * 0.55f
            val a3 = sin((timePhase * 2.3f + fx * 31f).toDouble()).toFloat() * 0.3f
            val active = ((a1 + a2 + a3) / 1.85f) * 0.85f

            val combined = idle * idleBlend + active * activeBlend
            // During phosphor sigh, amplitudeNorm goes briefly negative; that
            // visually subtracts amplitude (trace collapses toward center).
            val scaled = if (amplitudeNorm < 0f) combined * (1f + amplitudeNorm) else combined

            samples[i] = scaled

            val abs = kotlin.math.abs(scaled)
            if (abs > peakAbs) {
                peakAbs = abs
                peakIdx = i
            }
        }

        // Build path. Mirror direction: right-side strip draws right-to-left
        // so phase reads as flowing toward the logo on both sides.
        path.rewind()
        val step = traceWidth / (sampleCount - 1)
        for (i in 0 until sampleCount) {
            val readIdx = if (mirror) (sampleCount - 1 - i) else i
            val x = padX + step * i
            val y = centerY - samples[readIdx] * maxAmplitudePx
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Alpha lerps between idle and active opacities.
        val mainAlphaIdle = 0x47   // 28%
        val mainAlphaActive = 0xEB // 92%
        val glowAlphaIdle = 0x2E   // 18%
        val glowAlphaActive = 0x99 // 60%
        val mainAlpha = lerpInt(mainAlphaIdle, mainAlphaActive, activeBlend).coerceIn(0, 255)
        val glowAlpha = lerpInt(glowAlphaIdle, glowAlphaActive, activeBlend).coerceIn(0, 255)
        val glowOffsetDp = glowOffsetIdleDp + (glowOffsetActiveDp - glowOffsetIdleDp) * activeBlend
        val glowOffsetPx = glowOffsetDp * dp

        // Draw afterglow trace FIRST (under main) — wider stroke, lower alpha,
        // offset downward. On active states, add a tiny lateral jitter to
        // imitate phosphor smear.
        glowPaint.strokeWidth = glowStrokeDp * dp
        glowPaint.color = (amberColor and 0x00FFFFFF) or (glowAlpha shl 24)
        val jitterX = if (activeBlend > 0.5f) (Random.nextFloat() - 0.5f) * 0.5f * dp else 0f
        canvas.save()
        canvas.translate(jitterX, glowOffsetPx)
        canvas.drawPath(path, glowPaint)
        canvas.restore()

        // Draw main trace on top.
        tracePaint.strokeWidth = mainStrokeDp * dp
        tracePaint.color = (amberColor and 0x00FFFFFF) or (mainAlpha shl 24)
        canvas.drawPath(path, tracePaint)

        // Transient sparkle on the peak sample during active state. Random
        // kick-off probabilistically: ~once every ~600ms at 60fps is 1/36.
        if (activeBlend > 0.8f) {
            if (sparkleFrames <= 0 && Random.nextInt(36) == 0) {
                sparkleFrames = 3
            }
            if (sparkleFrames > 0) {
                val readIdx = if (mirror) (sampleCount - 1 - peakIdx) else peakIdx
                val sx = padX + step * readIdx
                val sy = centerY - samples[peakIdx] * maxAmplitudePx
                sparklePaint.color = hotSparkColor
                canvas.drawCircle(sx, sy, mainStrokeDp * dp * 1.3f, sparklePaint)
                sparkleFrames--
            }
        } else {
            sparkleFrames = 0
        }
    }

    private fun lerpInt(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt()
}
