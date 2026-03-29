package com.wim.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects when a text field is focused and shows/hides the floating bubble.
 * Injects reconstructed text into the active text field.
 */
class WimAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WimAccessibilityService? = null
            private set
        var activeTextField: AccessibilityNodeInfo? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    activeTextField = source
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    activeTextField = source
                }
            }
        }
    }

    override fun onInterrupt() {
        activeTextField = null
    }

    override fun onDestroy() {
        instance = null
        activeTextField = null
        super.onDestroy()
    }

    /**
     * Paste text into the currently focused text field.
     */
    fun injectText(text: String) {
        val node = activeTextField ?: run {
            android.widget.Toast.makeText(this, "Tap a text field first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!node.refresh()) {
            activeTextField = null
            android.widget.Toast.makeText(this, "Text field lost — tap a field and try again", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val clip = android.content.ClipData.newPlainText("wim", text)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(clip)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun showBubble() {
        val intent = Intent(this, BubbleService::class.java).apply {
            action = "SHOW"
        }
        startService(intent)
    }

}
