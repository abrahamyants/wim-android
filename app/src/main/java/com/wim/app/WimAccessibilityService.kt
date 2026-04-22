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
        /**
         * Packages where the per-app Send-node finder (findSendNodeForApp)
         * has verified strategies. BubbleService gates auto-send on this
         * list so we never try to click a phantom Send in an unknown app.
         */
        val SUPPORTED_AUTOSEND_APPS = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.google.android.gm",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "org.thoughtcrime.securesms",
        )
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
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk) {
            Log.i("WiM", "injectText: ACTION_SET_TEXT ok (${finalText.length} chars)")
        } else {
            Log.w("WiM", "injectText: ACTION_SET_TEXT failed — falling back to clipboard+PASTE")
            val clip = android.content.ClipData.newPlainText("wim", finalText)
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(clip)
            val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteOk) {
                Log.i("WiM", "injectText: ACTION_PASTE fallback ok")
            } else {
                Log.e("WiM", "injectText: both ACTION_SET_TEXT and ACTION_PASTE failed — text did not land")
                android.widget.Toast.makeText(this, getString(R.string.toast_field_lost), android.widget.Toast.LENGTH_SHORT).show()
            }
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

    /**
     * Find and click the Send button. Two-tier strategy:
     *   1. Walk the tree, try ACTION_CLICK on the matched node (cheap, works
     *      when the node has a real click handler).
     *   2. If that fails or the node isn't clickable, get its screen bounds
     *      and dispatch a synthetic tap gesture at the center — bypasses
     *      any framework quirk where the node reports unclickable but the
     *      actual touch target responds normally.
     *   3. If no node matched, dump the accessibility tree to logcat with
     *      tag "WiM.tree" so we can see what Samsung's Messages / whatever
     *      app is actually exposing. Third time's a diagnostic step.
     */
    fun clickSendButton(): Boolean {
        val editable = activeTextField ?: findFocusedEditableAcrossWindows()
        if (editable == null) {
            Log.w("WiM", "clickSendButton: no editable field — can't locate the app window")
            return false
        }
        // Remember the target app's package from the editable itself, since
        // the node's window reference can expire between inject and send.
        val targetPkg = editable.packageName?.toString()
        val appWindowType = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        // Preference ladder for finding the right window root:
        //   1. The editable's own window (best — same node owned it a moment ago)
        //   2. An APPLICATION window matching targetPkg (re-find by package)
        //   3. Any APPLICATION window that isn't WiM or systemui (last resort)
        // Without this ladder, we fell through to windows.firstOrNull() which
        // grabbed com.android.systemui (the nav bar) and scanned Back/Home/
        // Recents for a Send button. It wasn't there. Obviously.
        val root: AccessibilityNodeInfo? = editable.window?.root
            ?: windows.firstOrNull {
                it.type == appWindowType && it.root?.packageName?.toString() == targetPkg
            }?.root
            ?: windows.firstOrNull {
                val pkg = it.root?.packageName?.toString()
                it.type == appWindowType && pkg != packageName && pkg != "com.android.systemui"
            }?.root
        if (root == null) {
            Log.w("WiM", "clickSendButton: no app window root found (targetPkg=$targetPkg)")
            return false
        }
        val pkg = root.packageName?.toString() ?: ""
        val sendNode = findSendNodeForApp(root, pkg)
        if (sendNode == null) {
            Log.w("WiM", "clickSendButton: no Send node found in $pkg — dumping a11y tree")
            dumpAccessibilityTree(root, 0)
            return false
        }
        // Tier 1: ACTION_CLICK on the node, walking up to a clickable ancestor.
        var clickTarget: AccessibilityNodeInfo? = sendNode
        while (clickTarget != null && !clickTarget.isClickable) {
            clickTarget = clickTarget.parent
        }
        if (clickTarget != null) {
            val actionOk = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (actionOk) {
                Log.i("WiM", "clickSendButton: $pkg ACTION_CLICK ok")
                return true
            }
            Log.i("WiM", "clickSendButton: $pkg ACTION_CLICK returned false — falling through to gesture")
        }
        // Tier 2: synthetic tap at the node's screen center. "Hijack the
        // coordinate" — we know where the button is, fire a real touch event
        // there. Works even when the node isn't exposed as clickable.
        val bounds = android.graphics.Rect()
        sendNode.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w("WiM", "clickSendButton: $pkg send node has zero bounds")
            return false
        }
        return dispatchTapAt(bounds.exactCenterX(), bounds.exactCenterY(), pkg)
    }

    /** Dispatch a 50ms down+up gesture at the given screen coordinates. */
    private fun dispatchTapAt(x: Float, y: Float, reason: String): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        var dispatched = false
        val ok = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                dispatched = true
                Log.i("WiM", "dispatchTapAt($reason): completed at $x,$y")
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                Log.w("WiM", "dispatchTapAt($reason): cancelled at $x,$y")
            }
        }, null)
        Log.i("WiM", "dispatchTapAt($reason) at $x,$y dispatch-submitted=$ok")
        return ok
    }

    /** Recursive tree dump — logs each node's class, desc, text, id, bounds. */
    private fun dumpAccessibilityTree(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 30) return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val desc = node.contentDescription?.toString()?.take(40) ?: ""
        val text = node.text?.toString()?.take(30) ?: ""
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val click = if (node.isClickable) "C" else "."
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        Log.i("WiM.tree", "$indent[$click] $cls desc=\"$desc\" text=\"$text\" id=$id bounds=${bounds.flattenToString()}")
        for (i in 0 until node.childCount) {
            dumpAccessibilityTree(node.getChild(i), depth + 1)
        }
    }

    /**
     * Per-app Send node finder. When adding a new app:
     * 1. open it, with an editable compose field and visible Send button
     * 2. `adb shell uiautomator dump` → inspect the XML for content-description
     *    / resource-id of the Send node
     * 3. add a branch here with whichever identifier is stable
     */
    private fun findSendNodeForApp(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        when (pkg) {
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging.sms" -> {
                return findByContentDescription(root, listOf("Send SMS", "Send message", "Send"))
            }
            "com.google.android.gm" -> {
                return findByContentDescription(root, listOf("Send"))
                    ?: findByResourceIdSuffix(root, "send")
            }
            "com.whatsapp", "com.whatsapp.w4b" -> {
                val byId = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
                if (byId != null) return byId
                return findByContentDescription(root, listOf("Send"))
            }
            "org.telegram.messenger", "org.thunderdog.challegram" -> {
                return findByContentDescription(root, listOf("Send", "Send message"))
            }
            "org.thoughtcrime.securesms" -> { // Signal
                return findByContentDescription(root, listOf("Send"))
            }
        }
        // Generic fallback — any clickable whose contentDescription reads Send-ish.
        return findByContentDescription(root, listOf("Send", "Send message", "Send SMS"))
    }

    private fun findByContentDescription(
        node: AccessibilityNodeInfo?,
        targets: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty()) {
            for (target in targets) {
                // Substring + ignoreCase — Samsung Messages sometimes emits
                // "Send SMS to +1…" or localized variants; exact-equals was
                // too strict. "Send" is specific enough that false matches
                // are rare in compose rows.
                if (desc.contains(target, ignoreCase = true)) return node
            }
        }
        // Text also worth checking — some buttons expose a visible "Send"
        // label without a content-description.
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) {
            for (target in targets) {
                if (text.equals(target, ignoreCase = true)) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByContentDescription(child, targets)
            if (result != null) return result
        }
        return null
    }

    private fun findByResourceIdSuffix(
        node: AccessibilityNodeInfo?,
        suffix: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val id = node.viewIdResourceName
        if (!id.isNullOrEmpty() && id.endsWith(":id/$suffix")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByResourceIdSuffix(child, suffix)
            if (result != null) return result
        }
        return null
    }

    /** Whether the focused app is in the auto-send per-app whitelist. */
    fun focusedAppSupportsAutoSend(): Boolean {
        val editable = activeTextField ?: findFocusedEditableAcrossWindows() ?: return false
        val pkg = editable.packageName?.toString() ?: return false
        return pkg in SUPPORTED_AUTOSEND_APPS
    }
}
