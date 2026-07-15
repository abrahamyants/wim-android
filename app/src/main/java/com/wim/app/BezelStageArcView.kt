package com.wim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws four arc segments around the bubble's bezel, lighting up
 * sequentially as the pipeline advances:
 *
 *   stage 0 — Transcribing (12 → 3 o'clock)
 *   stage 1 — Reconstructing (3 → 6 o'clock)
 *   stage 2 — Integrity verification (6 → 9 o'clock)
 *   stage 3 — Injecting (9 → 12 o'clock)
 *
 * Idle / Listening / SendReady → all four arcs at dim alpha.
 *
 * View is drawn inside [R.id.bubbleBezel]'s FrameLayout (above the
 * bezel background, below the inner knob). When [activeStage] is
 * advanced through the pipeline, the new stage fades from dim to
 * bright over `STAGE_FADE_MS`, all earlier stages stay bright until
 * the cycle resets.
 *
 * Pure render — owns no FSM logic. BubbleService observes the
 * BubbleStateBus and calls [setActiveStage] / [reset].
 */
class BezelStageArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val STAGE_COUNT = 4
        private const val ARC_SWEEP_DEG = 78f      // 80° wide minus 1° padding each side
        private const val ARC_GAP_DEG = 12f         // visual gap between stages
        private const val STAGE_FADE_MS = 220L
        private const val DIM_ALPHA = 0x66            // 40% — clear quarter-separation on every bezel
        private const val BRIGHT_ALPHA = 0xFF
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Active stage: -1 = none lit (idle), 0..3 = up to and including
     * that stage lit. Stages 0..activeStage all draw bright; stages
     * after activeStage draw dim.
     */
    private var activeStage: Int = -1
    private var fadeStartMs: Long = 0L

    /** Brand cream-gold — same as the bubble's idle on-gunmetal label. */
    private var arcColor: Int = 0xFFC8B48A.toInt()

    /** Stroke width as a fraction of the view's smaller dimension. */
    private var strokeFraction: Float = 0.06f

    fun setActiveStage(stage: Int) {
        val clamped = stage.coerceIn(-1, STAGE_COUNT - 1)
        if (clamped == activeStage) return
        activeStage = clamped
        fadeStartMs = System.currentTimeMillis()
        invalidate()
    }

    fun reset() {
        if (activeStage == -1) return
        activeStage = -1
        fadeStartMs = System.currentTimeMillis()
        invalidate()
    }

    // Clarifier-entry cue: all four arcs flash in [flashColor] at full
    // alpha for [durationMs], then fade back to the prior dim/bright
    // pattern. Marks the visual transition INTO BubbleState.Clarifying
    // — claim 1's "entering a bidirectional clarification state" gets
    // a visible cue that distinguishes it from the rest of the
    // pipeline. No-op if a flash is already running.
    private var flashEndsAtMs: Long = 0L
    private var flashColor: Int = 0xFFFF6A1A.toInt() // warm orange

    fun flashAllArcs(durationMs: Long = 320L, color: Int = 0xFFFF6A1A.toInt()) {
        flashColor = color or 0xFF000000.toInt()
        flashEndsAtMs = System.currentTimeMillis() + durationMs
        invalidate()
    }

    fun setArcColor(color: Int) {
        arcColor = color or 0xFF000000.toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val side = minOf(w, h)
        val sw = side * strokeFraction
        paint.strokeWidth = sw
        // Inset the arc rectangle by half the stroke width so the stroke
        // sits flush against the view bounds rather than half-clipped.
        val inset = sw / 2f + side * 0.02f
        val rect = RectF(inset, inset, w - inset, h - inset)

        val now = System.currentTimeMillis()
        val fadeProgress = ((now - fadeStartMs).toFloat() / STAGE_FADE_MS).coerceIn(0f, 1f)

        // Clarifier-entry flash: all four arcs at full bright in flashColor
        // until flashEndsAtMs. After that boundary, normal stage rendering
        // resumes (we keep invalidating so we re-render the moment the
        // flash window expires).
        val flashing = now < flashEndsAtMs

        for (stage in 0 until STAGE_COUNT) {
            val targetBright = stage <= activeStage
            // Cross-fade between previous and current state. We only know the
            // current target; assume "previous" was the inverse so a stage
            // that just lit fades up, a stage that just dimmed fades down.
            val targetAlpha = if (targetBright) BRIGHT_ALPHA else DIM_ALPHA
            val sourceAlpha = if (targetBright) DIM_ALPHA else BRIGHT_ALPHA
            val alpha = (sourceAlpha + (targetAlpha - sourceAlpha) * fadeProgress).toInt()
                .coerceIn(0, 0xFF)
            val baseRgb = if (flashing) (flashColor and 0x00FFFFFF) else (arcColor and 0x00FFFFFF)
            val effectiveAlpha = if (flashing) BRIGHT_ALPHA else alpha
            paint.color = baseRgb or (effectiveAlpha shl 24)
            // Each stage owns a 90° quadrant; we draw a 78° sweep centered
            // in that quadrant so the four stages have visible gaps.
            // Start angle 0° in Android Canvas == 3 o'clock. Subtract 90 to
            // start from 12 o'clock instead.
            val quadrantStart = (stage * 90f) - 90f
            val arcStart = quadrantStart + (90f - ARC_SWEEP_DEG) / 2f
            canvas.drawArc(rect, arcStart, ARC_SWEEP_DEG, false, paint)
            // Suppress the unused variable warning.
            @Suppress("UNUSED_VARIABLE") val _gap = ARC_GAP_DEG
        }

        // Keep animating until either the fade or the flash finishes.
        if (fadeProgress < 1f || flashing) postInvalidateOnAnimation()
    }
}
