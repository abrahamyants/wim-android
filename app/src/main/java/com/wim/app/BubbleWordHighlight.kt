package com.wim.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * Patent-clean word-highlight overlay (claim 13). Sits as a separate window
 * positioned over the host app's focused text field while a clarifier cycle
 * runs. Read-only on host state — no selection, no cursor move — so the
 * wherein-clause of claim 1 stays satisfied.
 *
 * Extracted from BubbleService 2026-05-25 as the first leaf-overlay slice
 * of the BubbleWindow cluster. Pure window-management — caller supplies the
 * field rect (from WimAccessibilityService) and the injection-length context
 * (from ClarifierController), so this class never reaches into other
 * subsystems.
 */
class BubbleWordHighlight(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private companion object {
        private const val TAG = "WiM"
    }

    private var view: WordHighlightView? = null
    private var params: WindowManager.LayoutParams? = null

    /**
     * Shows the highlight over [fieldRect]; if [injectionTextLength] is > 0,
     * estimates the word's sub-region from [rangeStart]..[rangeEnd] character
     * offsets and renders a narrower underline. Re-positions the existing
     * overlay on subsequent calls within the same clarifier cycle.
     */
    fun show(fieldRect: Rect, rangeStart: Int, rangeEnd: Int, injectionTextLength: Int) {
        if (view == null) {
            view = WordHighlightView(context)
            params = WindowManager.LayoutParams(
                fieldRect.width(),
                fieldRect.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // FLAG_NOT_TOUCHABLE: touches pass through to the host app.
                // FLAG_NOT_FOCUSABLE: never steals focus from the host.
                // FLAG_LAYOUT_NO_LIMITS: allow positioning into status-bar /
                // nav-bar regions if the field happens to extend there.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = fieldRect.left
                y = fieldRect.top
            }
            try {
                windowManager.addView(view, params)
                // Fade-in pulse: 0 → 1.0 → 0.65 → 1.0 over ~520ms. Tells
                // the user "the bubble is watching this field" without
                // touching its state. Patent-clean — pure overlay alpha.
                view?.alpha = 0f
                view?.animate()?.alpha(1f)?.setDuration(220)
                    ?.withEndAction {
                        view?.animate()?.alpha(0.65f)?.setDuration(160)
                            ?.withEndAction {
                                view?.animate()?.alpha(1f)?.setDuration(140)
                                    ?.start()
                            }?.start()
                    }?.start()
            } catch (e: Exception) {
                Log.w(TAG, "showWordHighlight: addView failed: ${e.message}")
                view = null
                params = null
                return
            }
        } else {
            // Re-position to track the current cycle's field rect.
            val p = params ?: return
            p.x = fieldRect.left
            p.y = fieldRect.top
            p.width = fieldRect.width()
            p.height = fieldRect.height()
            try { windowManager.updateViewLayout(view, p) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
        }

        // Sub-region horizontal estimate: most apps don't expose per-
        // character bounds via accessibility, so we approximate the word's
        // x-position from its character-offset ratio inside the injection.
        // Rough but better than full-field underline; lands the highlight
        // in the right horizontal neighborhood for single-line and most
        // short multi-line cases.
        if (injectionTextLength > 0 && rangeStart >= 0 && rangeEnd > rangeStart) {
            val fieldWidthPx = fieldRect.width().toFloat()
            val startFrac = (rangeStart.toFloat() / injectionTextLength).coerceIn(0f, 1f)
            val endFrac = (rangeEnd.toFloat() / injectionTextLength).coerceIn(0f, 1f)
            val pad = fieldWidthPx * 0.04f
            val startPx = (startFrac * fieldWidthPx - pad).coerceAtLeast(0f)
            val endPx = (endFrac * fieldWidthPx + pad).coerceAtMost(fieldWidthPx)
            view?.setSubRange(startPx, endPx)
        } else {
            view?.clearSubRange()
        }
        Log.i(TAG, "showWordHighlight: range=$rangeStart..$rangeEnd field=$fieldRect injectLen=$injectionTextLength")
    }

    fun hide() {
        val v = view ?: return
        // M6: cancel any in-flight alpha animation first — a running animator
        // keeps the view (and the service context it holds) reachable after
        // removeView, leaking the service.
        try { v.animate().cancel() } catch (_: Exception) {}
        try { windowManager.removeView(v) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
        view = null
        params = null
    }
}
