package com.wim.app

/**
 * Single owner of the bubble's overlay-strip text per spec §2 row 2.
 *
 * Multiple subsystems want to write to the same TextView:
 *   1. Clarifier candidate display (highest)
 *   2. Pipeline stage label
 *   3. Streaming partials (typewriter)
 *   4. Streak banners / micro-delights
 *   5. Onboarding scripted text
 *
 * This controller serializes their access. Higher-priority writes preempt
 * the current display and stash the prior message; on preempt-clear, the
 * stashed message is restored. Lower-priority writes are dropped if a
 * higher-priority write is currently active.
 *
 * The actual TextView is not held here — callers register a [Render] lambda
 * once at startup (typically in [BubbleService.showBubble]). This keeps the
 * controller free of Android view dependencies and unit-testable.
 */
object TranscriptBarController {

    enum class Priority(val rank: Int) {
        ONBOARDING(1),
        STREAK(2),
        STREAMING(3),
        STAGE(4),
        CLARIFIER(5),
    }

    /** Lambda the caller wires to `transcriptBar.text = it`. */
    fun interface Render {
        fun apply(text: String)
    }

    private data class Message(val priority: Priority, val text: String, val shownAtMs: Long)

    /**
     * Spec §6 Phase 1 step 9 — minimum hold per priority so a fast pipeline
     * (Stripping in 50ms) doesn't visually flash. Replacements at the same
     * priority within the dwell window queue and apply on dwell expiry.
     * Higher-priority preempts ignore the dwell — user-facing reactivity wins.
     */
    private val minDwellMs: Map<Priority, Long> = mapOf(
        Priority.ONBOARDING to 0L,
        Priority.STREAK to 800L,
        Priority.STREAMING to 0L,    // typewriter rendering paces itself
        Priority.STAGE to 200L,      // each pipeline label held ≥200ms
        Priority.CLARIFIER to 0L,
    )

    private var render: Render? = null
    private var currentActive: Message? = null
    /** Stack of preempted messages so each unwind restores the right thing. */
    private val stashed = ArrayDeque<Message>()
    /** Same-priority messages queued behind dwell. */
    private val pending = ArrayDeque<Message>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val drainPendingTask = Runnable { drainPending() }

    /** Wire the renderer once. Subsequent calls replace it. */
    fun bind(render: Render) {
        this.render = render
        currentActive?.let { render.apply(it.text) }
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    /** Detach the renderer (e.g. service onDestroy). */
    fun unbind() {
        render = null
    }

    /**
     * Show [text] at [priority]. Preempts an active lower-priority message
     * (stashing it for restore). Same-or-higher priority replaces in place
     * subject to per-priority min-dwell (the prior message is held until
     * its dwell elapses, then this one applies). Lower-priority writes
     * during an active higher-priority message are dropped — they were
     * never shown.
     *
     * Higher-priority preempts ignore the dwell window of the active
     * message (user-facing reactivity wins).
     */
    fun show(priority: Priority, text: String) {
        val active = currentActive
        when {
            active == null -> {
                applyNow(Message(priority, text, now()))
            }
            priority.rank > active.priority.rank -> {
                // Preempt: stash current, ignore its remaining dwell.
                stashed.addLast(active)
                applyNow(Message(priority, text, now()))
            }
            priority.rank == active.priority.rank -> {
                val dwellRemaining = dwellRemaining(active)
                if (dwellRemaining <= 0) {
                    applyNow(Message(priority, text, now()))
                } else {
                    // Replace any queued same-priority message with this one
                    // (fresher news wins — only the latest is interesting).
                    pending.removeAll { it.priority == priority }
                    pending.addLast(Message(priority, text, 0L))
                    handler.removeCallbacks(drainPendingTask)
                    handler.postDelayed(drainPendingTask, dwellRemaining)
                }
            }
            else -> { /* lower-priority: drop */ }
        }
    }

    private fun applyNow(msg: Message) {
        currentActive = msg
        render?.apply(msg.text)
    }

    private fun dwellRemaining(msg: Message): Long {
        val dwell = minDwellMs[msg.priority] ?: 0L
        return (msg.shownAtMs + dwell - now()).coerceAtLeast(0L)
    }

    private fun drainPending() {
        val active = currentActive ?: return
        if (dwellRemaining(active) > 0) {
            handler.postDelayed(drainPendingTask, dwellRemaining(active))
            return
        }
        val next = pending.removeFirstOrNull() ?: return
        applyNow(next.copy(shownAtMs = now()))
        if (pending.isNotEmpty()) {
            handler.postDelayed(drainPendingTask, dwellRemaining(currentActive!!))
        }
    }

    /**
     * Clear the active message at [priority]. If the cleared message was
     * the active one, the most recently stashed message becomes active.
     * If [priority] doesn't match the active message, just drop any
     * stashed entry of that priority (stale).
     */
    fun clear(priority: Priority) {
        val active = currentActive
        if (active != null && active.priority == priority) {
            currentActive = if (stashed.isNotEmpty()) stashed.removeLast() else null
            render?.apply(currentActive?.text ?: "")
        } else {
            // Strip any stashed entries at this priority (out-of-band release).
            val it = stashed.iterator()
            while (it.hasNext()) {
                if (it.next().priority == priority) it.remove()
            }
        }
    }

    /** Snapshot for diagnostics / tests. */
    fun activeText(): String = currentActive?.text ?: ""
    fun activePriority(): Priority? = currentActive?.priority
    fun stashedDepth(): Int = stashed.size

    /** Reset to empty state. Test-only / onDestroy. */
    fun reset() {
        currentActive = null
        stashed.clear()
        render?.apply("")
    }
}
