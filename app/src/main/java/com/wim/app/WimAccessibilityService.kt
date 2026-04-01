package com.wim.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
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

    // App category → tone mapping
    private val workApps = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.slack", "com.microsoft.teams", "com.linkedin.android",
        "com.google.android.apps.docs", "com.google.android.apps.docs.editors.sheets",
        "com.asana.app", "com.trello", "com.atlassian.android.jira.core"
    )
    private val personalApps = setOf(
        "com.whatsapp", "org.telegram.messenger", "com.facebook.orca",
        "com.instagram.android", "com.snapchat.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging"
    )

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
                // Auto-detect context and switch tone
                val pkg = event.packageName?.toString() ?: return
                autoSwitchTone(pkg)
            }
        }
    }

    private fun autoSwitchTone(packageName: String) {
        val prefs = WimApp.prefs
        if (!prefs.getBoolean("auto_tone", true)) return

        val currentTone = prefs.getString("tone", "casual") ?: "casual"
        val newTone = when {
            workApps.contains(packageName) -> "professional"
            personalApps.contains(packageName) -> "casual"
            packageName.contains("mail") || packageName.contains("email") -> "professional"
            else -> return // unknown app, don't change
        }
        if (newTone != currentTone) {
            prefs.edit().putString("tone", newTone).apply()
            Log.i("WiM", "Auto-tone: $currentTone → $newTone (app: $packageName)")
            BubbleService.instance?.updateIdleDisplay()
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
            android.widget.Toast.makeText(this, getString(R.string.toast_tap_field), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!node.refresh()) {
            activeTextField = null
            android.widget.Toast.makeText(this, getString(R.string.toast_field_lost), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val clip = android.content.ClipData.newPlainText("wim", text)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(clip)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun showBubble() {
        try {
            val intent = Intent(this, BubbleService::class.java).apply {
                action = "SHOW"
            }
            startForegroundService(intent)
            Log.i("WiM", "Bubble service started via startForegroundService")
        } catch (e: Exception) {
            Log.e("WiM", "Failed to start bubble service: ${e.message}")
        }
    }

}
