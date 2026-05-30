package com.wim.app

import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Toggles the regenerate icon (R.id.bezelRegen) on the bubble's lower
 * bezel — shown for 8 s after a successful L2/L3/L4 paste so the user
 * can re-roll the phrasing by tapping the lower-bezel hit zone, hidden
 * automatically after the auto-timeout (or explicitly on next record).
 *
 * Extracted from BubbleService 2026-05-25 as the fourteenth slice.
 * Child-view manipulation only — the icon lives inside the bubble
 * layout, so this class doesn't manage a separate WindowManager view.
 */
class BubbleRegenIcon(
    private val getBubbleView: () -> View?,
    private val scope: CoroutineScope,
) {
    private companion object {
        private const val AUTO_HIDE_MS = 8_000L
        private const val FADE_IN_MS = 200L
        private const val FADE_OUT_MS = 150L
    }

    private var hideJob: Job? = null

    /** True while the icon is visible (and the regen tap-zone is live). */
    var isAvailable: Boolean = false
        private set

    fun show() {
        val view = getBubbleView() ?: return
        // bezelRegen was an ImageView before the May 2026 bezel refactor,
        // is now a FrameLayout. The methods we call (alpha, visibility,
        // animate) are all on View, so widen the cast and we keep working
        // through any future layout swap.
        val icon = view.findViewById<View>(R.id.bezelRegen) ?: return
        icon.alpha = 0f
        icon.visibility = View.VISIBLE
        icon.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        isAvailable = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            hide()
        }
    }

    fun hide() {
        val view = getBubbleView() ?: return
        val icon = view.findViewById<View>(R.id.bezelRegen) ?: return
        icon.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            icon.visibility = View.GONE
        }.start()
        isAvailable = false
        hideJob?.cancel()
        hideJob = null
    }
}
