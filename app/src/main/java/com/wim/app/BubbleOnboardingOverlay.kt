package com.wim.app

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Full-screen dim overlay shown during the onboarding demo. Sits below
 * the floating bubble + transcript bar so they read against a dark
 * surface during the scripted walkthrough.
 *
 * Extracted from BubbleService 2026-05-25 as the fourth BubbleWindow
 * slice. Tiny but cohesive — one view, two methods, no cross-cutting
 * state. Background uses brand_dark (#1A1A1E) at ~94% opacity.
 */
class BubbleOnboardingOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private companion object {
        private const val TAG = "WiM"
    }

    private var overlay: View? = null

    fun show() {
        if (overlay != null) return
        val v = View(context).apply {
            setBackgroundColor(0xF01A1A1E.toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(v, params)
            overlay = v
        } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
    }

    fun remove() {
        overlay?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
            overlay = null
        }
    }
}
