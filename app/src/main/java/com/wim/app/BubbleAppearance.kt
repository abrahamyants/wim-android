package com.wim.app

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Bubble visual properties — theme drawable swap (bronze / aluminum /
 * gunmetal) + size + opacity. Both are read directly from WimApp prefs;
 * callers fire applyTheme()/updateAppearance() when those prefs change.
 *
 * Extracted from BubbleService 2026-05-25 as the eleventh slice. Pure
 * view manipulation — owns no state of its own beyond the bubbleView
 * lookup callback.
 */
class BubbleAppearance(
    private val getBubbleView: () -> View?,
) {
    companion object {
        private const val TAG = "WiM"

        fun bezelDrawableFor(dish: String): Int = when (dish) {
            "aluminum" -> R.drawable.bubble_bezel_aluminum
            "gunmetal" -> R.drawable.bubble_bezel_gunmetal
            else -> R.drawable.bubble_bg_idle
        }

        fun knobDrawableFor(knob: String): Int = when (knob) {
            "aluminum" -> R.drawable.bubble_knob_aluminum
            "gunmetal" -> R.drawable.bubble_knob_gunmetal
            else -> R.drawable.bubble_inner_knob
        }

        /** IDLE / QUIET sit on the knob, so their ink follows the knob metal:
         *  cream-gold on dark steel, deep espresso on bronze, dark gray on
         *  aluminum. */
        fun idleLabelColorFor(knob: String): Int = when (knob) {
            "gunmetal" -> 0xFFC8B48A.toInt()
            "bronze" -> 0xFF1F1208.toInt()
            else -> 0xFF555560.toInt()
        }
    }

    /** Apply the user's bubble metals — the DISH (bezel ring) and the KNOB
     *  (raised face) are picked separately since 2026-09-06. Swaps both
     *  drawables and refreshes the IDLE label color so a stale gunmetal-gold
     *  doesn't sit invisible against a lighter knob after a flip. */
    fun applyTheme() {
        val dish = WimApp.bubbleTheme()
        val knob = WimApp.bubbleKnobTheme()
        val bezelDrawable = bezelDrawableFor(dish)
        val knobDrawable = knobDrawableFor(knob)
        val view = getBubbleView()
        view?.findViewById<FrameLayout>(R.id.bubbleBezel)?.setBackgroundResource(bezelDrawable)
        view?.findViewById<FrameLayout>(R.id.bubbleInner)?.setBackgroundResource(knobDrawable)
        // If the label is currently showing IDLE, refresh its color to match
        // the new theme. Without this, a lingering gunmetal-gold would stay
        // on the aluminum/bronze bezel and become invisible against the
        // lighter metal. REC/PROC labels are left alone — their colors are
        // managed by the recording state path.
        view?.findViewById<TextView>(R.id.bubbleLabel)?.let { label ->
            if (label.text == "IDLE") {
                label.setTextColor(idleLabelColorFor(knob))
            }
        }
    }

    /** Apply current size + opacity prefs to the bezel. Window stays
     *  fixed at MAX size (set in showBubble at 180dp = 120 * 1.5) — only
     *  the bezel (and its children: knob, label, time) are scaled. This
     *  keeps the bezel's visual center anchored to the window center
     *  across all sizes — no drift, no clipping at max scale. */
    fun updateAppearance() {
        val view = getBubbleView()
        if (view == null) {
            Log.w(TAG, "updateBubbleAppearance skipped — bubble not visible")
            return
        }
        val opacityPct = WimApp.bubbleOpacity().coerceIn(0, 100)
        val sizePct = WimApp.bubbleSize().coerceIn(70, 150)
        val opacity = opacityPct / 100f
        val scale = sizePct / 100f

        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: run {
            Log.w(TAG, "updateBubbleAppearance: bezel view not found")
            return
        }
        bezel.alpha = opacity
        // Pivot at the bezel's measured center so scaling grows/shrinks
        // around the visual center, not the top-left corner.
        if (bezel.width > 0) {
            bezel.pivotX = bezel.width / 2f
            bezel.pivotY = bezel.height / 2f
        }
        // Cancel any in-flight bezel animations (taffy stretch from a prior
        // drag, snap-back interpolator) — those keep running and overwrite
        // the scale we set here, which masks the slider as "broken."
        bezel.animate().cancel()
        bezel.scaleX = scale
        bezel.scaleY = scale
        bezel.invalidate()
        Log.i(TAG, "updateBubbleAppearance: size=${sizePct}% opacity=${opacityPct}% bezelScale=$scale bezelW=${bezel.width} pivot=(${bezel.pivotX},${bezel.pivotY})")
    }
}
