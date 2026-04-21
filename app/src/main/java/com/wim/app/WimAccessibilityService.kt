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
        /** True if the currently focused text field is a password/sensitive field. */
        var isPasswordField: Boolean = false
            private set
    }

    private var recordingUnsub: (() -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showBubble()
        // Start agent socket server (debug builds only, loopback, port 9222)
        com.wim.app.agent.AgentSocketServer.start(this)
        // Bridge recording state changes to agent event stream
        recordingUnsub = RecordingStateBus.subscribe { recording ->
            com.wim.app.agent.AgentSocketServer.instance
                ?.getEventBridge()
                ?.onRecordingStateChanged(recording)
        }
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
        // Forward to agent event bridge (filters internally based on subscription)
        com.wim.app.agent.AgentSocketServer.instance
            ?.getEventBridge()
            ?.onAccessibilityEvent(event)

        if (event.packageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    activeTextField = source
                    isPasswordField = detectPasswordField(source)
                    if (isPasswordField) Log.i("WiM", "Password field focused — dictation disabled")
                } else {
                    isPasswordField = false
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    activeTextField = source
                    isPasswordField = detectPasswordField(source)
                } else {
                    isPasswordField = false
                }
                // Auto-detect context and switch tone
                val pkg = event.packageName?.toString() ?: return
                autoSwitchTone(pkg)
            }
        }
    }

    /**
     * Detect if a node is a password/sensitive field.
     * Checks the .isPassword flag (set by TYPE_TEXT_VARIATION_PASSWORD,
     * TYPE_NUMBER_VARIATION_PASSWORD, and TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).
     */
    private fun detectPasswordField(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.isPassword
        } catch (_: Exception) {
            false
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
        isPasswordField = false
    }

    override fun onDestroy() {
        com.wim.app.agent.AgentSocketServer.stop()
        recordingUnsub?.invoke()
        recordingUnsub = null
        instance = null
        activeTextField = null
        isPasswordField = false
        super.onDestroy()
    }

    /**
     * Returns up to [maxChars] of text immediately preceding the cursor in
     * the currently focused editable field. Returns null for password fields
     * or when no field is active. Used to give the reconstructor conversational
     * context so it can disambiguate ambiguous dictations.
     */
    fun getPreCursorContext(maxChars: Int = 200): String? {
        if (isPasswordField) return null
        val node = activeTextField ?: return null
        if (!node.refresh()) return null
        val text = node.text?.toString() ?: return null
        if (text.isEmpty()) return null
        val cursorEnd = (if (node.textSelectionEnd >= 0) node.textSelectionEnd else text.length)
            .coerceAtMost(text.length)
        val start = (cursorEnd - maxChars).coerceAtLeast(0)
        val slice = text.substring(start, cursorEnd)
        return slice.takeIf { it.isNotBlank() }
    }

    /**
     * Paste text into the currently focused text field.
     */
    fun injectText(text: String) {
        // Try the stored reference first (fast path). When it goes stale or is
        // missing, re-find across ALL visible windows. rootInActiveWindow is
        // unreliable when WiM's own bubble overlay or the IME window is top-
        // most — iterating windows and skipping our own package is robust.
        var node = activeTextField
        if (node != null && !node.refresh()) {
            Log.i("WiM", "Stored text field stale, searching all windows for focused editable")
            node = null
        }
        if (node == null) {
            val refound = findFocusedEditableAcrossWindows()
            if (refound != null) {
                activeTextField = refound
                isPasswordField = detectPasswordField(refound)
                node = refound
                Log.i("WiM", "Re-found focused editable in window stack — proceeding with paste")
            } else {
                activeTextField = null
                android.widget.Toast.makeText(this, getString(R.string.toast_tap_field), android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Smart spacing: prepend a space if the character before the cursor
        // isn't already a space or newline, so consecutive dictations don't
        // smash together (matches Wispr Flow / Dragon behavior).
        var finalText = text
        val existing = node.text?.toString()
        if (!existing.isNullOrEmpty()) {
            val cursorEnd = if (node.textSelectionEnd >= 0) node.textSelectionEnd else existing.length
            if (cursorEnd > 0) {
                val charBefore = existing[cursorEnd - 1]
                if (charBefore != ' ' && charBefore != '\n' && charBefore != '\t') {
                    finalText = " $text"
                }
            }
        }
        // Use ACTION_SET_TEXT to avoid clipboard sound on Android 13+
        val currentText = node.text?.toString() ?: ""
        val insertPos = if (node.textSelectionEnd >= 0) node.textSelectionEnd else currentText.length
        val newText = currentText.substring(0, insertPos) + finalText + currentText.substring(insertPos)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            // Fallback to clipboard if ACTION_SET_TEXT fails
            val clip = android.content.ClipData.newPlainText("wim", finalText)
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(clip)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    /**
     * Iterate every accessibility-visible window and find a focused editable
     * node. Skips WiM's own overlay windows because `rootInActiveWindow` can
     * return our bubble's window, which has no editable content. Falls back
     * from `findFocus(FOCUS_INPUT)` to a recursive descent for editable+focused
     * nodes for apps whose WebViews or custom views report focus oddly.
     */
    private fun findFocusedEditableAcrossWindows(): AccessibilityNodeInfo? {
        val ownPackage = packageName
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackage) continue
            val viaFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (viaFocus != null && viaFocus.isEditable) return viaFocus
            val viaDescent = findEditableFocusedDescendant(root)
            if (viaDescent != null) return viaDescent
        }
        return null
    }

    /** Recursive descent: first node where isEditable && isFocused both hold. */
    private fun findEditableFocusedDescendant(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableFocusedDescendant(child)
            if (result != null) return result
        }
        return null
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
