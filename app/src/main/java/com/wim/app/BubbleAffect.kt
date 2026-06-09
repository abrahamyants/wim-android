package com.wim.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

/**
 * Spec §3 — observes [BubbleStateBus.state] and animates the bubble's
 * personality. Affects are decorations, not states; they observe the FSM
 * and self-cancel on the next transition (spec §1 priority-3 rule).
 *
 * Phase 3 ships the core 80%: [Affect.RESTING] (idle breathe),
 * [Affect.LEANING_FORWARD] (listening lift), [Affect.TA_DA] (inject pop),
 * [Affect.HEAD_TILT] (clarifying knob rotation), plus the clarifier
 * trigger pulse + approve/reject flashes.
 *
 * Deferred to Phase 6: [Affect.THINKING_SWAY], [Affect.COMMISERATE].
 *
 * The affect lives behind a single observer; it never touches FSM state.
 * It animates on `bubbleBezel` (FrameLayout) and `bubbleInner` (FrameLayout)
 * — physical views looked up by R.id from the bubble layout.
 */
class BubbleAffect(
    private val bubbleViewProvider: () -> View?,
    private val vibrator: Vibrator?,
) {

    enum class Affect { RESTING, LEANING_FORWARD, TA_DA, HEAD_TILT, NONE }

    private var observeJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var restingAnim: ValueAnimator? = null
    private var leaningAnim: ValueAnimator? = null
    private var leanBreatheAnim: ValueAnimator? = null
    private var tadaAnim: ValueAnimator? = null
    private var headTiltAnim: ValueAnimator? = null
    private var swayAnim: ValueAnimator? = null
    private var commiserateAnim: ValueAnimator? = null
    private var swayArmTask: Runnable? = null
    private var lastInjectedAtMs: Long = 0L

    fun bind(scope: CoroutineScope) {
        observeJob?.cancel()
        observeJob = BubbleStateBus.state
            .onEach { state -> onState(state) }
            .launchIn(scope)
    }

    fun unbind() {
        observeJob?.cancel()
        observeJob = null
        cancelAll()
    }

    private fun onState(state: BubbleState) {
        // Cancel non-applicable affects first so we never overlap two
        // animations on the same view.
        when (state) {
            is BubbleState.Idle -> applyResting()
            is BubbleState.Listening -> applyLeaningForward()
            is BubbleState.Injected -> applyTaDa(state.cue)
            is BubbleState.Clarifying -> {
                // Spec §3 + §6 step 24: HEAD_TILT fires on Clarifying entry.
                // TA_DA already played on Injected (sequenced, not overlapped).
                // The clarifier trigger pulse runs alongside (§6 step 25).
                applyHeadTilt(enter = true)
                emitClarifierTriggerPulse()
            }
            is BubbleState.Reconstructing -> {
                cancelMotion(applyRestingFallback = false)
                armThinkingSway()
            }
            is BubbleState.SendReady -> cancelMotion(applyRestingFallback = false)
            is BubbleState.Failed -> applyCommiserate()
            else -> {
                // Pipeline mid-states (Transcribing/Stripping/Reading/Verified):
                // cancel any prior affect.
                cancelMotion(applyRestingFallback = false)
            }
        }
    }

    // ── RESTING ─────────────────────────────────────────

    /**
     * User's bubble-size pref (70-150%) as a multiplier base. Every
     * animation that scales the bezel must multiply through this so the
     * affect catalog respects the slider — without it, animations
     * silently overwrite the user's chosen size on every frame and the
     * slider appears broken.
     */
    private fun baseScale(): Float =
        (WimApp.bubbleSize().coerceIn(70, 150) / 100f)

    private fun applyResting() {
        cancelMotion(applyRestingFallback = false)
        val view = bubbleViewProvider() ?: return
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                val s = baseScale() * (1.0f + 0.012f * t)
                bezel.scaleX = s
                bezel.scaleY = s
            }
        }
        restingAnim = anim
        anim.start()
    }

    // ── LEANING_FORWARD ─────────────────────────────────

    private fun applyLeaningForward() {
        cancelMotion(applyRestingFallback = false)
        val view = bubbleViewProvider() ?: return
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
        val inner = view.findViewById<FrameLayout>(R.id.bubbleInner) ?: return
        val density = view.resources.displayMetrics.density
        val targetTransY = -3f * density
        val leanScale = 1.04f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                bezel.translationY = targetTransY * t
                inner.scaleX = 1.0f + (leanScale - 1.0f) * t
                inner.scaleY = 1.0f + (leanScale - 1.0f) * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Recording isn't a static pose — once leaned in, the knob
                    // breathes (slow swell/relax) for as long as we're Listening,
                    // so the bubble reads as alive, not a blinking sticker.
                    // Cancelled + reset on the next state transition.
                    startLeanBreathe(inner, leanScale)
                }
            })
        }
        leaningAnim = anim
        anim.start()
    }

    /**
     * Sustained recording presence: after the lean-in settles, the inner knob
     * breathes — a slow scale swell and relax — until the next FSM transition
     * cancels it. Replaces the old alpha "blink" overlay with organic motion.
     */
    private fun startLeanBreathe(inner: FrameLayout, leanBase: Float) {
        leanBreatheAnim?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                val s = leanBase + 0.05f * t   // 1.04 → ~1.09 → 1.04, a calm breath
                inner.scaleX = s
                inner.scaleY = s
            }
        }
        leanBreatheAnim = anim
        anim.start()
    }

    // ── TA_DA ───────────────────────────────────────────

    /**
     * Spec §6 Phase 6 step 39 — variable success cues. Branch the inject
     * celebration on the [SuccessCue] payload so each kind reads
     * differently:
     *   PASTE           — standard 380ms three-stage pop (the canonical TA_DA)
     *   AUTO_SEND       — 380ms pop + a follow-up tighter pulse (~"sealed")
     *   COMMAND_REPLACE — fast 220ms double-tap (transform completed)
     *   REGEN_SUCCESS   — 380ms pop with a 0.93 contraction (re-roll, "got it")
     */
    private fun applyTaDa(cue: SuccessCue) {
        cancelMotion(applyRestingFallback = false)
        lastInjectedAtMs = System.currentTimeMillis()
        val view = bubbleViewProvider() ?: return
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
        val (durationMs, peak, contract, hapticMs) = when (cue) {
            SuccessCue.PASTE -> SuccessCueShape(380L, 1.10f, 0.96f, 24L)
            SuccessCue.AUTO_SEND -> SuccessCueShape(380L, 1.12f, 0.94f, 28L)
            SuccessCue.COMMAND_REPLACE -> SuccessCueShape(220L, 1.06f, 0.98f, 18L)
            SuccessCue.REGEN_SUCCESS -> SuccessCueShape(380L, 1.08f, 0.93f, 22L)
        }
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                val scale = when {
                    t < 0.4f -> 1.0f + ((peak - 1.0f) * (t / 0.4f))
                    t < 0.7f -> peak - ((peak - contract) * ((t - 0.4f) / 0.3f))
                    else -> contract + ((1.0f - contract) * ((t - 0.7f) / 0.3f))
                }
                val s = baseScale() * scale
                bezel.scaleX = s
                bezel.scaleY = s
            }
        }
        tadaAnim = anim
        anim.start()
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(hapticMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) { /* haptic optional */ }
        if (cue == SuccessCue.AUTO_SEND) {
            // Follow-up "sealed" pulse 200ms after the main pop — communicates
            // "ready to send", not just "pasted".
            mainHandler.postDelayed({
                if (BubbleStateBus.current() is BubbleState.SendReady) bezelFlash(0.04f, 180)
            }, 420)
        }
    }

    private data class SuccessCueShape(
        val durationMs: Long,
        val peakScale: Float,
        val contractScale: Float,
        val hapticMs: Long,
    )

    // ── THINKING_SWAY (Reconstructing > 1.5s) ──────────

    private fun armThinkingSway() {
        // Arm a one-shot delayed start so a fast L1/L2 reconstruction
        // (sub-1.5s) never sees the sway. Long L4 (8-12s) catches it.
        swayArmTask?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if (BubbleStateBus.current() !is BubbleState.Reconstructing) return@Runnable
            val view = bubbleViewProvider() ?: return@Runnable
            val inner = view.findViewById<FrameLayout>(R.id.bubbleInner) ?: return@Runnable
            val anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                duration = 2600
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { v ->
                    val phase = v.animatedValue as Float
                    inner.rotation = 2.4f * Math.sin(phase.toDouble()).toFloat()
                }
            }
            swayAnim = anim
            anim.start()
        }
        swayArmTask = task
        mainHandler.postDelayed(task, 1500)
    }

    // ── COMMISERATE (Failed sigh) ──────────────────────

    private fun applyCommiserate() {
        cancelMotion(applyRestingFallback = false)
        val view = bubbleViewProvider() ?: return
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                // 0..0.5: Y-squash to 0.96; 0.5..1: recover to 1.0.
                val sy = if (t < 0.5f) 1.0f - (0.04f * (t / 0.5f))
                else 0.96f + (0.04f * ((t - 0.5f) / 0.5f))
                bezel.scaleY = sy
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bezel.scaleY = 1f
                }
            })
        }
        commiserateAnim = anim
        anim.start()
        // No haptic, no pulse rings. Spec §3: the sigh is mute.
    }

    // ── HEAD_TILT ───────────────────────────────────────

    private fun applyHeadTilt(enter: Boolean) {
        cancelMotion(applyRestingFallback = false)
        val view = bubbleViewProvider() ?: return
        val inner = view.findViewById<FrameLayout>(R.id.bubbleInner) ?: return
        val target = if (enter) 8f else 0f
        val from = inner.rotation
        val anim = ValueAnimator.ofFloat(from, target).apply {
            duration = if (enter) 240 else 180
            interpolator = OvershootInterpolator(if (enter) 1.8f else 1.0f)
            addUpdateListener { v ->
                inner.rotation = v.animatedValue as Float
            }
        }
        headTiltAnim = anim
        anim.start()
    }

    // ── Clarifier trigger pulse (spec §6 step 25) ──────

    private fun emitClarifierTriggerPulse() {
        val view = bubbleViewProvider() ?: return
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
        // 280ms scale-pulse with the bezel cream tone (we don't actually
        // change color here — just the scale-and-decay envelope).
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                val s = baseScale() * (1.0f + (0.06f * (1f - t)))
                bezel.scaleX = s
                bezel.scaleY = s
            }
        }
        anim.start()
    }

    /**
     * Spec §6 step 25 — flash the bezel green for approve, red for reject.
     * Called from BubbleService after each clarifier tap so the user gets
     * visual confirmation before the cycle advances.
     */
    fun emitApproveFlash() = bezelFlash(scaleBoost = 0.05f, durationMs = 140)
    fun emitRejectFlash() = bezelFlash(scaleBoost = -0.04f, durationMs = 160)

    private fun bezelFlash(scaleBoost: Float, durationMs: Long) {
        val view = bubbleViewProvider() ?: return
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                val s = baseScale() * (1.0f + (scaleBoost * (1f - t)))
                bezel.scaleX = s
                bezel.scaleY = s
            }
        }
        anim.start()
    }

    // ── Lifecycle / cancellation ────────────────────────

    private fun cancelMotion(applyRestingFallback: Boolean) {
        restingAnim?.cancel(); restingAnim = null
        leaningAnim?.cancel(); leaningAnim = null
        leanBreatheAnim?.cancel(); leanBreatheAnim = null
        tadaAnim?.cancel(); tadaAnim = null
        headTiltAnim?.cancel(); headTiltAnim = null
        swayAnim?.cancel(); swayAnim = null
        commiserateAnim?.cancel(); commiserateAnim = null
        swayArmTask?.let { mainHandler.removeCallbacks(it) }; swayArmTask = null
        // Reset transforms so the next affect starts from a clean view.
        val view = bubbleViewProvider() ?: return
        view.findViewById<FrameLayout>(R.id.bubbleBezel)?.apply {
            // Reset to user's bubble-size pref, NOT 1.0 — otherwise
            // cancelMotion silently shrinks the bezel back to 100%
            // each time it runs, undoing the slider.
            val base = baseScale()
            scaleX = base; scaleY = base; translationY = 0f
        }
        view.findViewById<FrameLayout>(R.id.bubbleInner)?.apply {
            scaleX = 1f; scaleY = 1f; rotation = 0f
        }
        if (applyRestingFallback) applyResting()
    }

    private fun cancelAll() {
        cancelMotion(applyRestingFallback = false)
        mainHandler.removeCallbacksAndMessages(null)
    }
}
