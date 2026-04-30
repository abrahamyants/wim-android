package com.wim.app

/**
 * Drives the [BubbleState.Clarifying] lifecycle — spec §6 Phase 2 step 15.
 *
 * Owns:
 *   - The cycle index (which flagged word the user is reviewing)
 *   - The 3s auto-dismiss timer (keyed in [IdleTimer])
 *   - approve / reject / next dispatches into [WimAccessibilityService]
 *   - Exit transitions back into the FSM (`SendReady` if auto-send eligible,
 *     else `Idle`)
 *
 * Invariants:
 *   - `enter()` is the only path into [BubbleState.Clarifying].
 *   - Every exit (manual, auto-dismiss, mid-cycle recording start) advances
 *     the FSM exactly once and cancels the auto-dismiss timer.
 *   - The 3s timer resets on EVERY user action (approve / reject / next).
 *     A user actively engaged keeps the cycle open.
 *
 * Phase 2 ships the controller without WordHighlightView — visual underline
 * overlay is deferred. The label-swap + tap UX is enough for the EARLIEST
 * DEMO MILESTONE.
 */
object ClarifierController {

    private const val IDLE_TIMER_ID = "clarifier-auto-dismiss"
    private const val AUTO_DISMISS_MS = 3000L

    /** Last reconstruction's text (the inject target for word swaps). */
    private var lastInjectionText: String = ""

    /** Active cycle's flagged words. Empty when no cycle is running. */
    private var flagged: List<FlaggedWord> = emptyList()
    private var cycleIndex: Int = 0
    /** Whether auto-send was eligible for this cycle's host app. */
    private var autoSendEligible: Boolean = false
    /** Records of (originalWord, replacement-or-empty-on-reject) for the
     *  recent-swaps memory used by [Clarifier.evaluate]'s feedback loop. */
    private val recentSwaps: ArrayDeque<Pair<String, String>> = ArrayDeque()
    private const val RECENT_SWAPS_LIMIT = 12

    /**
     * Enter the clarifier cycle. Called from `BubbleService.processText`
     * after a successful inject when [Clarifier.evaluate] returned ≥1 flag.
     *
     * Caller is responsible for advancing the FSM to `Injected(...)` first
     * (the spec requires `Injected → Clarifying`, never `Verified → Clarifying`).
     */
    fun enter(
        flaggedWords: List<FlaggedWord>,
        injectedText: String,
        autoSendEligible: Boolean,
        onLabelUpdate: (flagged: FlaggedWord, idx: Int, total: Int) -> Unit,
        onExit: (autoSendEligible: Boolean) -> Unit,
    ) {
        if (flaggedWords.isEmpty()) {
            // Defensive — caller should have skipped enter() in this case.
            onExit(autoSendEligible)
            return
        }
        this.flagged = flaggedWords
        this.cycleIndex = 0
        this.lastInjectionText = injectedText
        this.autoSendEligible = autoSendEligible
        this.onLabelUpdateRef = onLabelUpdate
        this.onExitRef = onExit

        BubbleStateBus.advance(
            BubbleState.Clarifying(
                flagged = flaggedWords,
                cycleIndex = 0,
                enteredAtMs = System.currentTimeMillis(),
            )
        )
        emitLabelUpdate()
        armAutoDismiss()
    }

    /**
     * User approved the current flagged word — accept the top candidate
     * (replace in injected text), record the swap, advance to next.
     */
    fun approve() {
        val current = flagged.getOrNull(cycleIndex) ?: return
        val replacement = current.candidates.firstOrNull() ?: return
        // Edit the host text via accessibility.
        val ok = WimAccessibilityService.instance?.swapWordInLastInjection(
            rangeStart = current.rangeStart,
            rangeEnd = current.rangeEnd,
            replacement = replacement,
        ) ?: false
        if (ok) {
            // Update the in-memory injection text so subsequent ranges still match.
            lastInjectionText = applyEdit(lastInjectionText, current.rangeStart, current.rangeEnd, replacement)
            recordSwap(current.originalWord, replacement)
        }
        next()
    }

    /**
     * User rejected the current flagged word — keep the original, record
     * the rejection (close the loop on candidate ranking), advance to next.
     */
    fun reject() {
        val current = flagged.getOrNull(cycleIndex) ?: return
        // Record candidates as rejected so they're filtered from future cycles.
        current.candidates.forEach { recordSwap(it, "") }
        next()
    }

    /**
     * Skip to the next flagged word without making a decision. If we were
     * on the last word, exit the cycle (treat as "user dismissed by
     * cycling past the end").
     */
    fun next() {
        cycleIndex += 1
        if (cycleIndex >= flagged.size) {
            exit()
            return
        }
        BubbleStateBus.advance(
            BubbleState.Clarifying(
                flagged = flagged,
                cycleIndex = cycleIndex,
                enteredAtMs = System.currentTimeMillis(),
            )
        )
        emitLabelUpdate()
        armAutoDismiss()
    }

    /**
     * Auto-dismissal — timer fired without user action. Per claim 12, this
     * applies a default-reject decision to all unreviewed flagged words
     * (the originally injected text is preserved without modification).
     */
    fun dismissAll() {
        if (flagged.isEmpty()) return
        // Record remaining as rejections for the feedback loop.
        for (i in cycleIndex until flagged.size) {
            flagged[i].candidates.forEach { recordSwap(it, "") }
        }
        exit()
    }

    /**
     * Hard-exit, used when the user starts a new recording mid-cycle
     * (`Clarifying → Listening` per spec §1 transition matrix).
     */
    fun cancelForNewRecording() {
        IdleTimer.cancel(IDLE_TIMER_ID)
        flagged = emptyList()
        cycleIndex = 0
        onLabelUpdateRef = null
        onExitRef = null
        WimAccessibilityService.instance?.clearHighlight()
    }

    /** Snapshot for diagnostics / tests. */
    fun isActive(): Boolean = flagged.isNotEmpty()
    fun currentIndex(): Int = cycleIndex
    fun currentTotal(): Int = flagged.size
    fun recentSwapsSnapshot(): List<Pair<String, String>> = recentSwaps.toList()

    /** Test-only / onDestroy. */
    fun reset() {
        IdleTimer.cancel(IDLE_TIMER_ID)
        flagged = emptyList()
        cycleIndex = 0
        lastInjectionText = ""
        autoSendEligible = false
        recentSwaps.clear()
        onLabelUpdateRef = null
        onExitRef = null
    }

    // ── Internals ───────────────────────────────────────

    private var onLabelUpdateRef: ((FlaggedWord, Int, Int) -> Unit)? = null
    private var onExitRef: ((Boolean) -> Unit)? = null

    private fun emitLabelUpdate() {
        val current = flagged.getOrNull(cycleIndex) ?: return
        onLabelUpdateRef?.invoke(current, cycleIndex, flagged.size)
        // Visual word-highlight: ask the accessibility service to set the
        // host app's text selection over the flagged word's range. The
        // host app renders its own selection (blue/yellow), giving us
        // word-level visual feedback without an overlay window.
        WimAccessibilityService.instance?.highlightFlaggedWord(
            rangeStart = current.rangeStart,
            rangeEnd = current.rangeEnd,
        )
    }

    private fun armAutoDismiss() {
        IdleTimer.arm(IDLE_TIMER_ID, AUTO_DISMISS_MS) { dismissAll() }
    }

    private fun exit() {
        IdleTimer.cancel(IDLE_TIMER_ID)
        val cb = onExitRef
        val eligible = autoSendEligible
        flagged = emptyList()
        cycleIndex = 0
        onLabelUpdateRef = null
        onExitRef = null
        // Drop the host app's text selection so the user lands in a clean
        // cursor-at-end-of-inject state instead of a lingering highlight.
        WimAccessibilityService.instance?.clearHighlight()
        cb?.invoke(eligible)
    }

    private fun recordSwap(original: String, replacement: String) {
        recentSwaps.addLast(original to replacement)
        while (recentSwaps.size > RECENT_SWAPS_LIMIT) recentSwaps.removeFirst()
    }

    private fun applyEdit(text: String, start: Int, end: Int, replacement: String): String {
        if (start < 0 || end > text.length || start > end) return text
        return text.substring(0, start) + replacement + text.substring(end)
    }
}
