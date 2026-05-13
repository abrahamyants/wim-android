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
         * Foreground app's package name as captured from the most recent
         * accessibility event. Read by ReconstructClient at reconstruction
         * time to format the READER block (audience-context plumbing —
         * see AudienceContext.kt). Null until the first window-state
         * change after service start.
         */
        var currentForegroundPackage: String? = null
            private set

        /**
         * Snapshot of the most recently injected text (with leading/trailing
         * pad as it landed in the host field). Empty when no injection has
         * happened yet. Used by [ClarifierController] to track edits across
         * approve/reject swaps.
         */
        fun lastInjectedTextSnapshot(): String = instance?.lastInjectedExact.orEmpty()
    }

    private var recordingUnsub: (() -> Unit)? = null

    // Tracking of the last successful injectText, so a regenerate can replace
    // *exactly* what was pasted rather than appending a second copy. Reset on
    // every fresh inject. -1 means "no valid range stored".
    private var lastInjectStart: Int = -1
    private var lastInjectEnd: Int = -1
    private var lastInjectedExact: String = ""
    /** Whitespace that injectText prepended to the caller's text (smart-spacing).
     *  swapWordInLastInjection adds this to caller-supplied ranges when they are
     *  relative to the reconstruction (no leading space) instead of [lastInjectedExact]. */
    private var lastInjectedLeadingPad: Int = 0

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
                // Cache the package for reconstruction-time READER-block
                // formatting. AudienceContext.formatReaderBlock() reads this
                // during ReconstructClient.reconstruct() to tune the model
                // for the active app's audience and medium expectations.
                currentForegroundPackage = pkg
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
        // The bezel tap is the user's explicit work/personal toggle. Within
        // 30 seconds of that tap, suppress auto-tone so the very next
        // accessibility event for the same host app doesn't ping-pong the
        // tone back to whatever the host-app whitelist would say.
        val manualSetMs = prefs.getLong("manual_tone_set_ms", 0L)
        if (System.currentTimeMillis() - manualSetMs < 30_000L) return

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
        // Some compose UIs (Samsung Messages 2024+, certain Material 3 TextFields)
        // expose the hint/placeholder ("Text message") via .text when the field is
        // empty. Treat hint == text as empty so we don't feed "Text message" into
        // the L4 reconstruction prompt as preceding context.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()
            if (!hint.isNullOrEmpty() && hint == text) return null
        }
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
        // Treat node.text as empty when it equals the hint/placeholder. Samsung
        // Messages 2024+ (and some Material 3 compose TextFields) return the
        // hint string ("Text message", "Type a message", etc.) via .text when
        // the field has no real content. Without this guard, ACTION_SET_TEXT
        // would write "Text message hello" instead of just "hello".
        val rawText = node.text?.toString().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val existing = if (rawText.isNotEmpty() && rawText == hint) "" else rawText

        // Smart spacing: prepend a space if the character before the cursor
        // isn't already a space or newline, so consecutive dictations don't
        // smash together (matches Wispr Flow / Dragon behavior).
        var finalText = text
        if (existing.isNotEmpty()) {
            val cursorEnd = if (node.textSelectionEnd >= 0) node.textSelectionEnd else existing.length
            if (cursorEnd > 0) {
                val charBefore = existing[cursorEnd - 1]
                if (charBefore != ' ' && charBefore != '\n' && charBefore != '\t') {
                    finalText = " $text"
                }
            }
        }
        // Trailing space — gives the cursor breathing room from the last letter
        // (the blinking caret abutting a glyph reads as glitchy) AND ensures the
        // next inject lands cleanly separated. Skip if text already ends in
        // whitespace or punctuation that already implies a break.
        if (finalText.isNotEmpty() && !finalText.last().let { it == ' ' || it == '\n' || it == '\t' }) {
            finalText = "$finalText "
        }
        // Use ACTION_SET_TEXT to avoid clipboard sound on Android 13+
        val currentText = existing
        val insertPos = if (currentText.isEmpty()) {
            0
        } else if (node.textSelectionEnd >= 0) {
            node.textSelectionEnd.coerceAtMost(currentText.length)
        } else {
            currentText.length
        }
        val newText = currentText.substring(0, insertPos) + finalText + currentText.substring(insertPos)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk) {
            Log.i("WiM", "injectText: ACTION_SET_TEXT ok (${finalText.length} chars)")
            lastInjectStart = insertPos
            lastInjectEnd = insertPos + finalText.length
            lastInjectedExact = finalText
            // Trimmed view used by [swapWordInLastInjection] when the
            // caller's range is relative to the unpadded reconstruction.
            lastInjectedLeadingPad = finalText.length - finalText.trimStart().length
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
     * Replace the most recently injected text with [replacement]. Returns true
     * if the replacement landed cleanly. If the user has edited the field
     * since the last inject (so our saved range no longer matches), the
     * replacement falls back to a normal append via [injectText] so we never
     * clobber the user's edits.
     */
    fun replaceLastInjected(replacement: String): Boolean {
        if (lastInjectStart < 0 || lastInjectEnd < lastInjectStart) {
            injectText(replacement); return false
        }
        var node = activeTextField
        if (node != null && !node.refresh()) node = null
        if (node == null) node = findFocusedEditableAcrossWindows()
        if (node == null) {
            injectText(replacement); return false
        }
        val current = node.text?.toString().orEmpty()
        if (lastInjectEnd > current.length ||
            current.substring(lastInjectStart, lastInjectEnd) != lastInjectedExact) {
            // Field state diverged from our snapshot — give up on in-place edit.
            injectText(replacement); return false
        }
        // Reapply smart-spacing using the same rules as injectText so the
        // replacement reads identically to a fresh inject.
        var spaced = replacement
        if (lastInjectStart > 0) {
            val before = current[lastInjectStart - 1]
            if (before != ' ' && before != '\n' && before != '\t') spaced = " $spaced"
        }
        if (spaced.isNotEmpty() && spaced.last().let { it != ' ' && it != '\n' && it != '\t' }) {
            spaced = "$spaced "
        }
        val combined = current.substring(0, lastInjectStart) + spaced + current.substring(lastInjectEnd)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            lastInjectEnd = lastInjectStart + spaced.length
            lastInjectedExact = spaced
            Log.i("WiM", "replaceLastInjected: ok (${spaced.length} chars)")
        } else {
            Log.w("WiM", "replaceLastInjected: SET_TEXT failed — appending instead")
            injectText(replacement)
        }
        return ok
    }

    /**
     * Swap a single word range inside the most recent injection — spec §6
     * Phase 2 step 13, claim 11(e). Returns true if the swap landed.
     *
     * [rangeStart] / [rangeEnd] are character offsets within the
     * RECONSTRUCTION (the unpadded text passed to [injectText]). Internally
     * the method shifts by [lastInjectedLeadingPad] to land in the padded
     * [lastInjectedExact] coordinate space, then by [lastInjectStart] to
     * land in absolute field coordinates.
     *
     * Defensive: if the field has been edited since the last inject (range
     * doesn't match), the swap is refused — we never clobber the user's
     * own edits.
     */
    fun swapWordInLastInjection(rangeStart: Int, rangeEnd: Int, replacement: String): Boolean {
        if (lastInjectStart < 0 || lastInjectEnd < lastInjectStart) {
            Log.w("WiM", "swapWord: no recent injection to edit")
            return false
        }
        if (rangeStart < 0 || rangeEnd <= rangeStart) {
            Log.w("WiM", "swapWord: invalid range $rangeStart..$rangeEnd")
            return false
        }
        var node = activeTextField
        if (node != null && !node.refresh()) node = null
        if (node == null) node = findFocusedEditableAcrossWindows()
        if (node == null) {
            Log.w("WiM", "swapWord: no focused field")
            return false
        }
        val current = node.text?.toString().orEmpty()
        // Verify our snapshot still matches the field text.
        if (lastInjectEnd > current.length ||
            current.substring(lastInjectStart, lastInjectEnd) != lastInjectedExact
        ) {
            Log.w("WiM", "swapWord: field state diverged — refusing to edit")
            return false
        }
        // Shift caller-supplied (reconstruction-relative) coords into absolute
        // field coords via the leading-pad adjustment.
        val absStart = lastInjectStart + lastInjectedLeadingPad + rangeStart
        val absEnd = lastInjectStart + lastInjectedLeadingPad + rangeEnd
        if (absEnd > lastInjectEnd) {
            Log.w("WiM", "swapWord: range $rangeStart..$rangeEnd extends past last injection")
            return false
        }
        val combined = current.substring(0, absStart) + replacement + current.substring(absEnd)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            // Shift the saved range to reflect the new injection length.
            val delta = replacement.length - (absEnd - absStart)
            lastInjectEnd += delta
            lastInjectedExact = combined.substring(lastInjectStart, lastInjectEnd)
            Log.i("WiM", "swapWord: ok ($absStart..$absEnd → \"${replacement.take(40)}\")")
        } else {
            Log.w("WiM", "swapWord: ACTION_SET_TEXT failed")
        }
        return ok
    }

    /**
     * Return the screen-coordinate bounds of the currently focused text
     * field, or null if no field is focused / accessible. Used by the
     * overlay-window WordHighlightView in BubbleService to position the
     * underline.
     *
     * NOTE: this method deliberately DOES NOT touch the host field's
     * selection state. Earlier iteration used `ACTION_SET_SELECTION` to
     * highlight the flagged word — that approach moved the cursor for the
     * duration of the clarifier cycle, which violated the
     * "text-field cursor position remain unchanged" wherein-clause of
     * patent claim 1. The overlay-window approach reads coordinates only.
     */
    fun focusedFieldBounds(): android.graphics.Rect? {
        var node = activeTextField
        if (node != null && !node.refresh()) node = null
        if (node == null) node = findFocusedEditableAcrossWindows()
        if (node == null) return null
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null
        return rect
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
        // Preference ladder for finding the right window root:
        //   1. The editable's own window (best — same node owned it a moment ago)
        //   2. Any visible window whose package matches targetPkg (re-find by package)
        //   3. Any visible window that isn't WiM or systemui (last resort)
        //
        // Earlier version restricted tier 2/3 to TYPE_APPLICATION, but Samsung /
        // Google Messages reports something else for its chat window on some
        // device builds, so the filter missed everything. Dropping the type
        // filter; matching by package is the correctness lever.
        val root: AccessibilityNodeInfo? = editable.window?.root
            ?: windows.firstOrNull {
                it.root?.packageName?.toString() == targetPkg
            }?.root
            ?: windows.firstOrNull {
                val pkg = it.root?.packageName?.toString()
                pkg != null && pkg != packageName && pkg != "com.android.systemui"
            }?.root
        if (root == null) {
            Log.w("WiM", "clickSendButton: no window root (targetPkg=$targetPkg) — dumping windows")
            for ((i, w) in windows.withIndex()) {
                val wpkg = w.root?.packageName?.toString() ?: "<null>"
                Log.w("WiM", "  window[$i] type=${w.type} pkg=$wpkg focused=${w.isFocused}")
            }
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
    fun getSendButtonCenter(): Pair<Int, Int>? {
        val editable = activeTextField ?: findFocusedEditableAcrossWindows() ?: return null
        val targetPkg = editable.packageName?.toString() ?: return null
        val root = editable.window?.root
            ?: windows.firstOrNull { it.root?.packageName?.toString() == targetPkg }?.root
            ?: return null
        val sendNode = findSendNodeForApp(root, targetPkg) ?: return null
        val rect = android.graphics.Rect()
        sendNode.getBoundsInScreen(rect)
        return Pair(rect.centerX(), rect.centerY())
    }

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
            "com.google.android.apps.messaging.sms" -> {
                // Compose-based UI uses resource-id "Compose:Draft:Send" with
                // EMPTY content-description. Older (View-based) builds had
                // content-description "Send SMS". Try both.
                return findByResourceIdExact(root, "Compose:Draft:Send")
                    ?: findByContentDescription(root, listOf("Send SMS", "Send message", "Send"))
            }
            "com.samsung.android.messaging" -> {
                return findByContentDescription(root, listOf("Send SMS", "Send message", "Send"))
                    ?: findByResourceIdSuffix(root, "send_btn")
                    ?: findByResourceIdSuffix(root, "send")
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

    /**
     * Exact resource-id match. Jetpack Compose exposes testTags as
     * resource-ids WITHOUT the traditional `package:id/name` format —
     * Google Messages uses bare identifiers like "Compose:Draft:Send".
     * findAccessibilityNodeInfosByViewId often misses these, so walk
     * the tree ourselves.
     */
    private fun findByResourceIdExact(
        node: AccessibilityNodeInfo?,
        targetId: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName == targetId) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByResourceIdExact(child, targetId)
            if (result != null) return result
        }
        return null
    }

    /**
     * Whether an editable field is currently focused in any non-WiM app.
     * No per-app whitelist — every app gets auto-send. If the Send-node
     * finder misses, the green SEND just reverts to IDLE on its timer;
     * worst case is no worse than the pre-feature baseline.
     */
    fun focusedAppSupportsAutoSend(): Boolean {
        val editable = activeTextField ?: findFocusedEditableAcrossWindows() ?: return false
        val pkg = editable.packageName?.toString() ?: return false
        return pkg != packageName
    }
}
