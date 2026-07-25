package com.wim.app

import android.util.Log
import android.widget.Toast

/**
 * Drives the [BubbleState.Clarifying] lifecycle — spec §6 Phase 2 step 15.
 *
 * Owns:
 *   - The cycle index (which flagged word the user is reviewing)
 *   - The 3s auto-dismiss timer (keyed in [IdleTimer])
 *   - approve / reject / next dispatches into [WimAccessibilityService]
 *   - Exit transitions back into the FSM (`SendReady` if tap-to-send eligible,
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
    /** Whether tap-to-send assist was eligible for this cycle's host app. */
    private var autoSendEligible: Boolean = false
    /** Records of (originalWord, replacement-or-empty-on-reject) for the
     *  recent-swaps memory used by [Clarifier.evaluate]'s feedback loop. */
    private val recentSwaps: ArrayDeque<Pair<String, String>> = ArrayDeque()
    private const val RECENT_SWAPS_LIMIT = 12

    /**
     * Host-app state as it was when this cycle opened — patent claim 9.
     *
     * Claim 1 stands on a wherein-clause: the host application's window focus,
     * accessibility focus, IME state, scroll position and cursor position are
     * unchanged for the whole clarification state. That is the sentence that
     * separates WiM from every voice assistant in the prior art, and nothing in
     * the app had ever measured it — the claim was design intent with no
     * evidence behind it, and an OEM keyboard could have broken it silently.
     *
     * Debug builds only (the snapshot itself returns null in release).
     */
    private var entryHostState: WimAccessibilityService.HostState? = null

    /** Whether THIS cycle performed an approved swap. `recentSwaps` can't
     *  answer that — it deliberately survives across cycles for the feedback
     *  loop, so after the user's first-ever swap it is never empty again. */
    private var swappedThisCycle = false

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
        this.swappedThisCycle = false
        this.entryHostState = WimAccessibilityService.instance?.snapshotHostState()

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
            // Claim 9: an approved swap is the one cursor move the claim allows.
            swappedThisCycle = true
            next()
        } else {
            // Swap couldn't reach the host (focus lost, app changed, field cleared).
            // Stay on this flag so the user can retry rather than silently
            // advancing past an unfixed word.
            Log.w("ClarifierController", "swap failed at idx=$cycleIndex word=${current.originalWord}; staying on flag")
            BubbleService.instance?.let {
                Toast.makeText(it, "Swap failed — tap again", Toast.LENGTH_SHORT).show()
            }
            armAutoDismiss()
        }
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
        // Clear rejection memory — an aborted cycle was not user-rejected
        // input; leaving recentSwaps populated suppresses real candidates
        // in the next cycle.
        recentSwaps.clear()
        BubbleService.instance?.hideWordHighlight()
    }

    /** Snapshot for diagnostics / tests. */
    fun isActive(): Boolean = flagged.isNotEmpty()
    fun currentIndex(): Int = cycleIndex
    fun currentTotal(): Int = flagged.size
    fun recentSwapsSnapshot(): List<Pair<String, String>> = recentSwaps.toList()
    /** Length (chars) of the active injection text — used by the
     *  WordHighlightView sub-region estimator to approximate horizontal
     *  word position without per-character bounds. */
    fun injectionTextLength(): Int = lastInjectionText.length

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
        // Patent-clean word-highlight via overlay window — claim 13's
        // "visible-anchor underline overlay." The overlay sits over the
        // host app's text field, FLAG_NOT_TOUCHABLE so touches pass
        // through, leaving cursor / selection / scroll / focus state
        // untouched (claim 1 application-context invariance).
        BubbleService.instance?.showWordHighlight(
            rangeStart = current.rangeStart,
            rangeEnd = current.rangeEnd,
        )
    }

    private fun armAutoDismiss() {
        IdleTimer.arm(IDLE_TIMER_ID, AUTO_DISMISS_MS) { dismissAll() }
    }

    /**
     * Claim 9's assertion: compare the host state at cycle exit against entry
     * and report any drift. Runs before the exit callback, while the host app
     * is still the one the cycle ran against.
     *
     * Logs each differing field by name rather than a bare boolean, because
     * WHICH one moved is the whole diagnostic — a changed IME state and a
     * changed scroll position have completely different causes.
     */
    private fun assertHostStateUnchanged() {
        val before = entryHostState ?: return
        entryHostState = null
        val after = WimAccessibilityService.instance?.snapshotHostState()
        if (after == null) {
            android.util.Log.i(
                "WiM.Claim9",
                "clarify exit: no comparable host state (field gone) — not asserting",
            )
            return
        }
        val drift = buildList {
            if (before.pkg != after.pkg) add("pkg ${before.pkg}→${after.pkg}")
            if (before.focusedWindowId != after.focusedWindowId) {
                add("windowFocus ${before.focusedWindowId}→${after.focusedWindowId}")
            }
            if (before.a11yFocusedWindowId != after.a11yFocusedWindowId) {
                add("a11yFocus ${before.a11yFocusedWindowId}→${after.a11yFocusedWindowId}")
            }
            if (before.imeVisible != after.imeVisible) {
                add("ime ${before.imeVisible}→${after.imeVisible}")
            }
            // The cursor legitimately moves when the user approves a swap —
            // that IS the claimed edit. Only an unexplained move matters, and
            // recentSwaps tells us whether one happened this cycle.
            if (!swappedThisCycle &&
                (before.selectionStart != after.selectionStart ||
                    before.selectionEnd != after.selectionEnd)
            ) {
                add(
                    "cursor ${before.selectionStart}..${before.selectionEnd}" +
                        "→${after.selectionStart}..${after.selectionEnd}"
                )
            }
            if (before.fieldLeft != after.fieldLeft || before.fieldTop != after.fieldTop) {
                add(
                    "fieldPos ${before.fieldLeft},${before.fieldTop}" +
                        "→${after.fieldLeft},${after.fieldTop}"
                )
            }
        }
        if (drift.isEmpty()) {
            android.util.Log.i("WiM.Claim9", "clarify exit: host state unchanged ✓")
        } else {
            android.util.Log.w("WiM.Claim9", "clarify exit: HOST STATE MOVED — ${drift.joinToString("; ")}")
            ConsoleLog.log("Claim 9 violated: ${drift.joinToString("; ")}", "error")
        }
    }

    private fun exit() {
        assertHostStateUnchanged()
        IdleTimer.cancel(IDLE_TIMER_ID)
        val cb = onExitRef
        val eligible = autoSendEligible
        flagged = emptyList()
        cycleIndex = 0
        onLabelUpdateRef = null
        onExitRef = null
        // Drop the host app's text selection so the user lands in a clean
        // cursor-at-end-of-inject state instead of a lingering highlight.
        BubbleService.instance?.hideWordHighlight()
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
