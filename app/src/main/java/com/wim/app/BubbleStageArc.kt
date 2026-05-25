package com.wim.app

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Bezel's 4-quadrant stage arc + the PROC processing-state label paint.
 * The arc visualizes pipeline progress (0=Transcribing, 1=Reconstructing,
 * 2=Verifying, 3=Injecting); the PROC label fires once the bubble
 * transitions out of recording into reconstruction.
 *
 * Extracted from BubbleService 2026-05-25 as the eighteenth slice.
 * Theme-conditional arc colors live here; the legacy hex tokens were
 * grandfathered in brand.local.md "Stage arc + processing (legacy)".
 */
class BubbleStageArc(
    private val getBubbleView: () -> View?,
) {
    /**
     * Drive the bezel's stage arc — 0=Transcribing, 1=Reconstructing,
     * 2=Verifying (Falcon), 3=Injecting. -1 resets to all dim.
     * Theme color follows the current bubble theme so the arcs harmonize
     * with bronze / aluminum / gunmetal palettes.
     */
    fun setStage(stage: Int) {
        val arc = getBubbleView()?.findViewById<BezelStageArcView>(R.id.bubbleStageArc) ?: return
        // Per-theme arc color so all four quadrants read as "quarter
        // separation" on every bezel. Aluminum's light silver bezel needs
        // a much darker arc than bronze's warm copper — `#7A1F1F` at 40%
        // alpha washed out on silver. `#2A0606` (near-black with a maroon
        // undertone) stays in the brand-maroon family but reads dark-on-
        // light. Bronze keeps the deep maroon that harmonizes with the
        // warm metal. Gunmetal stays cream-gold on dark steel.
        val arcColor = when (WimApp.bubbleTheme()) {
            "gunmetal" -> 0xFFC8B48A.toInt()
            "aluminum" -> 0xFF2A0606.toInt()
            else -> 0xFF7A1F1F.toInt()
        }
        arc.setArcColor(arcColor)
        if (stage < 0) {
            arc.reset()
            return
        }
        arc.setActiveStage(stage)
    }

    /** Bezel stays in theme color. Knob and label both stay in copper_gold
     *  — the bubble's brand color across recording AND processing — so the
     *  visual reads as one continuous "bubble is busy" affordance. The
     *  REC → PROC label change carries the state distinction; color
     *  doesn't need to do double duty. */
    fun setProcessing() {
        val view = getBubbleView() ?: return
        view.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_processing)
        view.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "PROC"
            setTextColor(0xFFFFD580.toInt())  // cream-gold — bright enough to read on red/green knob
        }
    }
}
