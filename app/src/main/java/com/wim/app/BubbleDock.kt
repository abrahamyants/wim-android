package com.wim.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Point
import android.graphics.Rect
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout

/**
 * Bubble-into-dock snap + park animation. MainActivity holds dock slot
 * Rects in screen coordinates (populated via BubbleService.dockSlotRects);
 * when the user drags the bubble into the vicinity of a slot, this
 * controller animates the bubble's window down to the slot center,
 * fades + shrinks the bezel, swaps the bubble theme to match the slot,
 * and finally hides the bubble.
 *
 * Extracted from BubbleService 2026-05-25 as the tenth slice.
 *
 * Callbacks bring side effects back to the service: theme swap, menu
 * reposition, bubble hide, foreground-notification text update.
 */
class BubbleDock(
    private val windowManager: WindowManager,
    private val getBubbleView: () -> android.view.View?,
    private val getBezelHost: () -> android.view.View?,
    private val getSlotRects: () -> Map<String, Rect>?,
    private val getVibrator: () -> Vibrator?,
    private val onApplyTheme: () -> Unit,
    private val onMenuRepositioned: () -> Unit,
    private val onHideBubble: () -> Unit,
    private val onNotificationText: (String) -> Unit,
) {
    private companion object {
        private const val TAG = "WiM"
    }

    /** Walks the dock slot rects looking for a magnet-radius match around
     *  the bubble's current center; on hit, persists the slot's theme,
     *  parks the bubble into the slot, returns true. */
    fun trySnap(params: WindowManager.LayoutParams): Boolean {
        val snap = checkSnap(params) ?: return false
        park(snap.first, snap.second, params)
        return true
    }

    /** Returns (theme, slot-center-point) if the bubble's current position
     *  lands inside one of MainActivity's dock slot rects, else null. The
     *  bubble's notional center is `params.x + bubbleWidth/2` /
     *  `params.y + bubbleHeight/2`. */
    fun checkSnap(params: WindowManager.LayoutParams): Pair<String, Point>? {
        val rects = getSlotRects() ?: return null
        val view = getBubbleView() ?: return null
        val cx = params.x + view.width / 2
        val cy = params.y + view.height / 2
        for ((theme, rect) in rects) {
            // Wide release-snap radius — bubble within 80% of slot width on
            // any side counts. Pairs with the active magnet during drag for
            // a "really fucking strong" pull.
            val pad = (rect.width() * 0.8f).toInt()
            if (cx in (rect.left - pad)..(rect.right + pad) &&
                cy in (rect.top - pad)..(rect.bottom + pad)) {
                return theme to Point(rect.centerX(), rect.centerY())
            }
        }
        return null
    }

    /** Animate the bubble toward a dock slot's center, fade + shrink, swap
     *  the theme to match the slot, then hide the bubble. Visual effect:
     *  "pulled him back in." */
    fun park(
        theme: String,
        slotCenter: Point,
        layoutParams: WindowManager.LayoutParams
    ) {
        WimApp.prefs.edit().putString("bubble_theme", theme).apply()
        onApplyTheme()
        val view = getBubbleView() ?: run { onHideBubble(); return }
        val bezel = getBezelHost()?.findViewById<FrameLayout>(R.id.bubbleBezel) ?: run { onHideBubble(); return }
        // Target position: slot center minus bubble half-width/height so the
        // bubble's visual center lands on the slot center.
        val targetX = slotCenter.x - view.width / 2
        val targetY = slotCenter.y - view.height / 2
        val startX = layoutParams.x
        val startY = layoutParams.y
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            // Stronger magnet: faster overall + much sharper end-rush.
            // AccelerateInterpolator(3f) barely moves for the first half and
            // SNAPS into the slot in the last ~30% — reads as "yanked in."
            duration = 180
            interpolator = AccelerateInterpolator(3.0f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                layoutParams.x = (startX + (targetX - startX) * t).toInt()
                layoutParams.y = (startY + (targetY - startY) * t).toInt()
                try { windowManager.updateViewLayout(view, layoutParams) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
                onMenuRepositioned()
                bezel.scaleX = 1f - 0.55f * t
                bezel.scaleY = 1f - 0.55f * t
                bezel.alpha = 1f - 0.85f * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    bezel.scaleX = 1f
                    bezel.scaleY = 1f
                    bezel.alpha = 1f
                    onHideBubble()
                    onNotificationText("Bubble parked — tap a dock slot or the WiM logo to resummon")
                }
            })
        }
        // Meatier haptic to match the stronger visual snap — registers as
        // a physical "klunk" when the coat hits the hanger.
        try { getVibrator()?.vibrate(VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        anim.start()
    }
}
