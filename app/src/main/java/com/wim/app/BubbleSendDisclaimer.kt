package com.wim.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "About to send" confirmation overlay shown before WiM taps the host app's
 * Send button on the user's behalf (auto-send flow). Three-button shape:
 * CANCEL (gold) and SEND IT (vibrant green), with explanatory body copy.
 *
 * Extracted from BubbleService 2026-05-25 as the fifth BubbleWindow slice.
 * Builds its surface programmatically — colors live in this file (legacy
 * tokens grandfathered in brand.local.md "Send disclaimer (legacy)").
 *
 * Confirmation/cancel callbacks fire after the overlay is dismissed, so the
 * caller's `onConfirm` / `onCancel` runs against a clean window stack.
 */
class BubbleSendDisclaimer(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private companion object {
        private const val TAG = "WiM"
    }

    private var view: View? = null

    fun show(onConfirm: () -> Unit, onCancel: () -> Unit) {
        dismiss()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 28)
            background = GradientDrawable().apply {
                setColor(0xFF1E1E22.toInt())
                cornerRadius = 28f
                setStroke(2, 0xFFC8B48A.toInt())
            }
        }
        val title = TextView(context).apply {
            text = "About to send"
            setTextColor(0xFFC8B48A.toInt())
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 18)
        }
        val body = TextView(context).apply {
            text = "Tapping the green bubble sends the text in the compose field through the app that's open — Google Messages, Gmail, WhatsApp, etc. — using your existing signed-in session. " +
                "WiM never sends without a deliberate tap. If the text is wrong, tap outside or wait 3 seconds to cancel and edit."
            setTextColor(0xFFB8B8B0.toInt())
            textSize = 13f
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, 24)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelBtn = TextView(context).apply {
            text = "CANCEL"
            setTextColor(0xFF8A7D68.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(36, 22, 28, 22)
            isClickable = true
        }
        val okBtn = TextView(context).apply {
            text = "SEND IT"
            setTextColor(0xFF5AD15F.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 22, 36, 22)
            isClickable = true
        }
        row.addView(cancelBtn)
        row.addView(okBtn)
        container.addView(title)
        container.addView(body)
        container.addView(row)
        val lp = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.7f
        }
        cancelBtn.setOnClickListener {
            dismiss()
            onCancel()
        }
        okBtn.setOnClickListener {
            dismiss()
            onConfirm()
        }
        view = container
        try { windowManager.addView(container, lp) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
    }

    fun dismiss() {
        view?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
        }
        view = null
    }
}
