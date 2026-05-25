package com.wim.app

import android.content.Context
import android.os.Handler
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Voice-triggered knob rotation — user says "knob size" or "knob opacity",
 * the bubble enlarges to its max size, the inner tick becomes a draggable
 * dial, and the user rotates it 9-o'clock-to-3-o'clock to set the value.
 * Lift-off shows a checkmark; tapping inside the inner knob commits, tap
 * outside reverts to pre-twist values.
 *
 * Extracted from BubbleService 2026-05-25 as the seventh slice. Cohesive:
 * one enum + ~5 state fields + ~7 methods. Owns its own state machine
 * and timeout. Callbacks bring the side effects (appearance refresh,
 * bubble-idle reset) back to the service.
 *
 * Triggers are intentionally strict prefix matches to avoid false
 * positives — saying "the settings size on this phone" would NOT enter
 * twist mode.
 */
class BubbleTwistController(
    private val context: Context,
    private val getBubbleView: () -> View?,
    private val timerHandler: Handler,
    private val onAppearanceChanged: () -> Unit,
    private val onExit: () -> Unit
) {
    companion object {
        private const val TAG = "WiM"
        private const val TIMEOUT_INTERACTIVE_MS = 6_000L
        private const val TIMEOUT_CONFIRM_MS = 5_000L

        /** Pure-function trigger detection — used by the recording pipeline
         *  to decide whether to enter twist mode for the next utterance.
         *  Public to support direct unit testing without constructing a
         *  full controller. */
        fun detectTrigger(rawText: String): Mode? {
            val lower = rawText.trim().lowercase().trimEnd('.', '!', '?', ',')
            // Strict prefix match — must start with trigger phrase.
            if (lower.startsWith("settings size") || lower.startsWith("knob size")) {
                return Mode.SIZE
            }
            if (lower.startsWith("settings opacity") || lower.startsWith("knob opacity")) {
                return Mode.OPACITY
            }
            return null
        }
    }

    enum class Mode { NONE, SIZE, OPACITY }

    private var mode: Mode = Mode.NONE
    private var preSize = -1
    private var preOpacity = -1
    private var confirmShown = false
    private val timeoutRunnable = Runnable { if (mode != Mode.NONE) exit(commit = false) }

    fun isActive(): Boolean = mode != Mode.NONE

    /** Public re-export of the trigger detector — keeps callers from
     *  reaching into the companion. */
    fun trigger(rawText: String): Mode? = detectTrigger(rawText)

    fun enter(target: Mode) {
        mode = target
        confirmShown = false

        // Save current values for revert on cancel.
        preSize = WimApp.bubbleSize()
        preOpacity = WimApp.bubbleOpacity()

        // Enlarge bubble to max (clarifier size) so the twist gesture is
        // ergonomic — small bubble + finger drag = imprecise angle.
        WimApp.prefs.edit().putInt("bubble_size", 150).putInt("bubble_opacity", 100).apply()
        onAppearanceChanged()

        val bubbleView = getBubbleView()
        // Swap label to mode indicator.
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = if (target == Mode.SIZE) "SIZE" else "DIM"
            setTextColor(0xFFC8B48A.toInt())  // cream, matches logo
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }

        // Hide timer during twist; ensure tick visible (might've been
        // hidden by confirm state on a prior cycle).
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.visibility = View.VISIBLE

        // Rotate tick to match the current value so the dial picks up
        // where the user left off instead of snapping to 12 o'clock.
        val currentValue = if (target == Mode.SIZE) preSize else preOpacity
        val min = if (target == Mode.SIZE) 70 else 20
        val max = if (target == Mode.SIZE) 150 else 100
        val normalized = (currentValue - min).toFloat() / (max - min).toFloat()
        val angle = -90f + normalized.coerceIn(0f, 1f) * 180f
        rotateTick(angle)

        // Timeout: no touch interaction for 6s cancels.
        timerHandler.removeCallbacks(timeoutRunnable)
        timerHandler.postDelayed(timeoutRunnable, TIMEOUT_INTERACTIVE_MS)

        Log.i(TAG, "Entered twist mode: $target, from=$currentValue")
    }

    fun exit(commit: Boolean) {
        if (mode == Mode.NONE) return
        val exitedMode = mode

        timerHandler.removeCallbacks(timeoutRunnable)

        if (!commit) {
            // Revert to pre-twist values; commit path leaves the prefs as
            // last-written by the rotation handler.
            WimApp.prefs.edit()
                .putInt("bubble_size", preSize)
                .putInt("bubble_opacity", preOpacity)
                .apply()
            onAppearanceChanged()
        }

        val bubbleView = getBubbleView()
        // Hide checkmark, reset tick.
        bubbleView?.findViewById<TextView>(R.id.twistConfirmCheck)?.visibility = View.GONE
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.visibility = View.VISIBLE
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.rotation = 0f

        // Restore timer.
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE

        mode = Mode.NONE
        confirmShown = false
        preSize = -1
        preOpacity = -1

        Log.i(TAG, "Exited twist mode: $exitedMode, commit=$commit")
        onExit()
    }

    private fun rotateTick(degrees: Float) {
        val tick = getBubbleView()?.findViewById<View>(R.id.bubbleTick) ?: return
        tick.post {
            // Pivot is 20dp below the tick's top edge — that's the knob's
            // center (tick is 8dp tall with 2dp marginTop from 44dp knob top →
            // knob center at 22dp).
            val dp = context.resources.displayMetrics.density
            tick.pivotX = tick.width / 2f
            tick.pivotY = (tick.height / 2f) + (16f * dp)
            tick.rotation = degrees
        }
    }

    /** Called from the bubble's onTouchListener when twist mode is active.
     *  Returns true if the event was consumed (skip normal touch handling). */
    fun handleTouch(event: MotionEvent, view: View): Boolean {
        if (mode == Mode.NONE) return false

        if (confirmShown) {
            return handleConfirmTouch(event, view)
        }

        val cx = view.width / 2f
        val cy = view.height / 2f
        val dx = event.x - cx
        val dy = event.y - cy

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Reset timeout while finger is interacting.
                timerHandler.removeCallbacks(timeoutRunnable)
                timerHandler.postDelayed(timeoutRunnable, TIMEOUT_INTERACTIVE_MS)

                // atan2(dx, -dy): 0° = 12 o'clock, -90° = 9 o'clock,
                // +90° = 3 o'clock.
                val angleRad = atan2(dx.toDouble(), (-dy).toDouble())
                val angleDeg = Math.toDegrees(angleRad).toFloat().coerceIn(-90f, 90f)

                rotateTick(angleDeg)

                // Map angle to value.
                val normalized = (angleDeg + 90f) / 180f
                val min = if (mode == Mode.SIZE) 70 else 20
                val max = if (mode == Mode.SIZE) 150 else 100
                val value = (min + normalized * (max - min)).toInt()

                val prefKey = if (mode == Mode.SIZE) "bubble_size" else "bubble_opacity"
                WimApp.prefs.edit().putInt(prefKey, value).apply()
                onAppearanceChanged()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                showConfirm()
                return true
            }
        }
        return true  // always consume during twist mode
    }

    private fun showConfirm() {
        confirmShown = true
        val bubbleView = getBubbleView()
        bubbleView?.findViewById<TextView>(R.id.twistConfirmCheck)?.apply {
            visibility = View.VISIBLE
        }
        // Hide tick so the checkmark is clearly the only action.
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.visibility = View.INVISIBLE
        // Also hide the SIZE/DIM label while confirm is shown.
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.visibility = View.GONE

        timerHandler.removeCallbacks(timeoutRunnable)
        timerHandler.postDelayed(timeoutRunnable, TIMEOUT_CONFIRM_MS)
    }

    private fun handleConfirmTouch(event: MotionEvent, view: View): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true  // consume DOWN/MOVE

        val cx = view.width / 2f
        val cy = view.height / 2f
        val dx = event.x - cx
        val dy = event.y - cy
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        // Inner knob is ~44dp on 120dp view = 36% width, radius ~18% of view width.
        val knobRadius = view.width * 0.20f

        // Restore label visibility (exit path resets everything).
        getBubbleView()?.findViewById<TextView>(R.id.bubbleLabel)?.visibility = View.VISIBLE

        if (dist <= knobRadius) {
            exit(commit = true)
        } else {
            exit(commit = false)
        }
        return true
    }
}
