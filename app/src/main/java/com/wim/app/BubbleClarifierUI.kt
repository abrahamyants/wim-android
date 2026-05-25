package com.wim.app

import android.content.Context
import android.os.Handler
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Bubble's clarifier overlay — the per-word "did you mean X?" prompt the
 * bubble shows after reconstruction when an uncertain word is flagged.
 * Displays the question word on the knob with auto-sized text, ✓/✕ on
 * the split bezel, and an acknowledgment flash + sparkle sequence on
 * dismiss.
 *
 * Extracted from BubbleService 2026-05-25 as the eighth slice. Owns
 * the visual UI state (size/opacity revert, pending question text,
 * retry counter, timeout runnable) and the callback wiring. The
 * actual clarifier business logic still lives in [ClarifierController].
 *
 * Callbacks bring side effects back to the service:
 * - onAppearanceChanged → refresh bubble size/opacity
 * - onPipelineStage → render the contextQuestion in the live transcript bar
 * - onAcknowledged → setBubbleIdle after the acknowledgment animation
 */
class BubbleClarifierUI(
    private val context: Context,
    private val getBubbleView: () -> View?,
    private val timerHandler: Handler,
    private val onAppearanceChanged: () -> Unit,
    private val onPipelineStage: (stage: String, detail: String) -> Unit,
    private val onAcknowledged: () -> Unit
) {
    private companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val SPARKLE_TO_DIAMOND_MS = 400L
        private const val SPARKLE_TO_STAR_MS = 650L
        private const val SPARKLE_TO_IDLE_MS = 900L
    }

    var isActive: Boolean = false
        private set

    private var callback: ((Boolean?) -> Unit)? = null
    private var preSize = -1
    private var preOpacity = -1
    private var retryCount = 0
    private var pendingWord = ""
    private var pendingQuestion = ""
    private var pendingCallback: ((Boolean?) -> Unit)? = null

    private val timeoutRunnable = Runnable {
        // First timeout = one auto-retry. Second timeout = give up.
        if (retryCount < 1) {
            retryCount++
            pendingCallback?.let { cb ->
                show(pendingWord, pendingQuestion, cb)
            }
        } else {
            retryCount = 0
            dismiss(null)
        }
    }

    fun show(questionWord: String, contextQuestion: String = "", onAnswer: (Boolean?) -> Unit) {
        isActive = true
        callback = { answer -> onAnswer(answer) }
        pendingWord = questionWord
        pendingQuestion = contextQuestion
        pendingCallback = onAnswer

        // Save current size/opacity and max out for visibility.
        if (preSize < 0) {
            preSize = WimApp.bubbleSize()
            preOpacity = WimApp.bubbleOpacity()
        }
        WimApp.prefs.edit().putInt("bubble_size", 150).putInt("bubble_opacity", 100).apply()
        onAppearanceChanged()

        val bubbleView = getBubbleView()
        // Show question on the knob — auto-size to fill, dark gray, bold.
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = questionWord
            setTextColor(0xFF444450.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.0f
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            setAutoSizeTextTypeUniformWithConfiguration(8, 28, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE

        // Show full question on the live transcript bar.
        if (contextQuestion.isNotBlank()) {
            val retryLabel = if (retryCount > 0) "Still waiting..." else "Quick question"
            onPipelineStage(retryLabel, contextQuestion)
        }

        // Show split bezel (green/red) and ✓/✕.
        bubbleView?.findViewById<View>(R.id.clarifierBgYes)?.visibility = View.VISIBLE
        bubbleView?.findViewById<View>(R.id.clarifierBgNo)?.visibility = View.VISIBLE
        bubbleView?.findViewById<TextView>(R.id.bezelYes)?.visibility = View.VISIBLE
        bubbleView?.findViewById<TextView>(R.id.bezelNo)?.visibility = View.VISIBLE

        // Auto-retry or dismiss after 10 seconds.
        timerHandler.removeCallbacks(timeoutRunnable)
        timerHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    fun dismiss(answer: Boolean?) {
        timerHandler.removeCallbacks(timeoutRunnable)
        isActive = false
        retryCount = 0

        val bubbleView = getBubbleView()
        // Hide split bezel and ✓/✕.
        bubbleView?.findViewById<View>(R.id.clarifierBgYes)?.visibility = View.GONE
        bubbleView?.findViewById<View>(R.id.clarifierBgNo)?.visibility = View.GONE
        bubbleView?.findViewById<TextView>(R.id.bezelYes)?.visibility = View.GONE
        bubbleView?.findViewById<TextView>(R.id.bezelNo)?.visibility = View.GONE

        // Restore normal label sizing and style.
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.05f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        // Restore original size and opacity.
        if (preSize > 0) {
            WimApp.prefs.edit().putInt("bubble_size", preSize).putInt("bubble_opacity", preOpacity).apply()
            onAppearanceChanged()
            preSize = -1
            preOpacity = -1
        }

        // Show acknowledgment if user answered (not timeout).
        if (answer != null) {
            val label = bubbleView?.findViewById<TextView>(R.id.bubbleLabel)
            val bezel = bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            // Flash the checkmark, then cycle sparkle sequence.
            label?.apply {
                text = if (answer == true) "✓" else "✕"
                setTextColor(
                    if (answer == true) ContextCompat.getColor(context, R.color.orange_label)
                    else 0xFFCC4444.toInt()
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setShadowLayer(
                    16f, 0f, 0f,
                    if (answer == true) 0x80FFFFFF.toInt() else 0x80CC4444.toInt()
                )
            }
            // Quick scale-pulse animation on the bezel.
            bezel?.let { v ->
                val scaleUp = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f, 1f),
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f, 1f)
                )
                scaleUp.duration = 350
                scaleUp.interpolator = android.view.animation.OvershootInterpolator()
                scaleUp.start()
            }
            // Sparkle sequence: ✓ → ✦ → ✧ → idle.
            timerHandler.postDelayed({
                label?.text = "✦"
                label?.setTextColor(ContextCompat.getColor(context, R.color.orange_label))
            }, SPARKLE_TO_DIAMOND_MS)
            timerHandler.postDelayed({
                label?.text = "✧"
                label?.setTextColor(0x88FFFFFF.toInt())
            }, SPARKLE_TO_STAR_MS)
            timerHandler.postDelayed({
                bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE
                onAcknowledged()
            }, SPARKLE_TO_IDLE_MS)
        } else {
            bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE
            onAcknowledged()
        }

        // Fire callback.
        callback?.invoke(answer)
        callback = null
    }
}
