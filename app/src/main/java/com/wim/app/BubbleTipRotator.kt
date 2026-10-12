package com.wim.app

import android.os.Handler

/**
 * Rotates a small set of "did you know?" tips through the pipeline-stage
 * transcript bar — at most once per 24 hours, after the bubble settles
 * to Idle. The 2-second post-idle delay keeps tips from flashing during
 * fast back-to-back interactions.
 *
 * Extracted from BubbleService 2026-05-25 as the thirteenth slice.
 * Pure orchestration over the transcript-bar callback — no view access,
 * no shared state beyond prefs.
 */
class BubbleTipRotator(
    private val timerHandler: Handler,
    private val onPipelineStage: (title: String, body: String) -> Unit,
    private val onHideLiveTranscript: () -> Unit,
) {
    private val tips = listOf(
        "Did you know?" to "Tap Guide at the bottom right of the app for the full manual.",
        "Hide me!" to "Drag the bubble to the bottom edge to hide. Swipe down from the top of your phone and tap WiM to bring me back.",
        "Drag me up" to "Drag the bubble to the top of the screen for quick settings — size, opacity, live text.",
    )

    fun maybeShow() {
        if (!WimApp.prefs.getBoolean("tips_enabled", true)) return
        val lastTip = WimApp.prefs.getLong("last_tip_time", 0L)
        val now = System.currentTimeMillis()
        // Show a tip at most once every 24 hours.
        if (now - lastTip < 24 * 60 * 60 * 1000) return
        val tipIndex = WimApp.prefs.getInt("tip_index", 0) % tips.size
        val tip = tips[tipIndex]
        // 2-second delay after idle so a quick tap-then-tap doesn't flash a tip.
        timerHandler.postDelayed({
            onPipelineStage(tip.first, tip.second)
            timerHandler.postDelayed({ onHideLiveTranscript() }, 5000)
        }, 2000)
        WimApp.prefs.edit()
            .putLong("last_tip_time", now)
            .putInt("tip_index", tipIndex + 1)
            .apply()
    }
}
