package com.wim.app

import android.util.Log
import org.json.JSONArray

/**
 * Persistent rejection history — outputs the user has rejected (via the
 * regenerate button) across sessions. Stored in SharedPreferences as a
 * JSON-encoded list of strings.
 *
 * Used by ReconstructClient to inject a "patterns the user has rejected
 * in the past — avoid producing structurally similar text" block into
 * the L3+ reconstruction prompt. This is the proactive cross-session
 * counterpart to the within-session regenerate-as-negative-example
 * that fires on `previousOutputs`.
 *
 * Capture: ReconstructClient calls `record()` whenever an incoming
 * reconstruct() call has non-empty `previousOutputs` — the last entry
 * is the output that was just rejected by hitting regenerate.
 *
 * Use: ReconstructClient calls `recent(n)` at L3+ to pull up to 10
 * historical rejections to inject as negative few-shot examples.
 *
 * Storage: capped at MAX_ENTRIES (30). Newest at the end of the JSON
 * array. Deduplicates against the most recent entry only — duplicate
 * rejections of the SAME output are common (user redoes 2-3 times
 * before giving up) and we don't want to fill the buffer with copies.
 *
 * Privacy: all on-device. SharedPreferences is per-app sandboxed.
 * "Erase rejection history" UI hook (`clear()`) is exposed for the
 * Profile screen to wire later.
 */
object RejectionStore {

    private const val PREF_KEY = "rejection_history_json"
    private const val MAX_ENTRIES = 30
    private const val DEFAULT_RECENT_N = 10

    /** Append `rejected` to the persistent history. No-op on blank input. */
    fun record(rejected: String) {
        if (rejected.isBlank()) return
        val current = load().toMutableList()
        // Skip if it equals the most recent entry — repeated regenerates
        // of the same output shouldn't fill the buffer.
        if (current.lastOrNull() == rejected) return
        current.add(rejected)
        while (current.size > MAX_ENTRIES) current.removeAt(0)
        persist(current)
        Log.i("RejectionStore", "Recorded rejection (${rejected.take(40)}...) — total=${current.size}")
    }

    /** Most recent up-to-n rejections, oldest first. */
    fun recent(n: Int = DEFAULT_RECENT_N): List<String> {
        val all = load()
        if (all.size <= n) return all
        return all.subList(all.size - n, all.size)
    }

    /** Erase all stored rejections. Hooked from Profile screen "Erase voice memory". */
    fun clear() {
        WimApp.prefs.edit().remove(PREF_KEY).apply()
    }

    private fun load(): List<String> {
        val raw = WimApp.prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Log.w("RejectionStore", "Corrupt rejection history JSON, resetting: ${e.message}")
            emptyList()
        }
    }

    private fun persist(items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        WimApp.prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }
}
