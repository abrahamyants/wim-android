package com.wim.app

import android.os.Handler
import android.os.Looper

/**
 * Shared idle-timer utility per spec §2 row 4.
 *
 * Multiple subscribers (clarifier 3s auto-dismiss, prediction-chip 800ms
 * trigger, breathe-pause-on-screen-off, etc.) need independent countdown
 * slots without contention. This holds N independent Runnable+delay slots
 * keyed by a string ID. Resetting one slot doesn't touch the others.
 *
 * All callbacks fire on the main thread. Re-arming an existing slot
 * cancels the prior pending callback first — typical "reset on activity"
 * semantics.
 */
object IdleTimer {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, Runnable>()

    /**
     * Schedule [task] to run after [delayMs]. If a task with the same [id]
     * is already pending, it's cancelled first.
     */
    fun arm(id: String, delayMs: Long, task: () -> Unit) {
        cancel(id)
        val r = Runnable {
            pending.remove(id)
            task()
        }
        pending[id] = r
        handler.postDelayed(r, delayMs)
    }

    /** Cancel the pending callback for [id]. No-op if none pending. */
    fun cancel(id: String) {
        pending.remove(id)?.let { handler.removeCallbacks(it) }
    }

    /** Cancel every pending callback. Call from onDestroy paths. */
    fun cancelAll() {
        pending.values.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }

    /** True iff a callback for [id] is currently pending. */
    fun isArmed(id: String): Boolean = pending.containsKey(id)
}
