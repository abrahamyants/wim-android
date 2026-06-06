package com.wim.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent style-examples — (raw_input, accepted_output) pairs the user
 * has implicitly accepted by NOT hitting regenerate. Mirrors RejectionStore
 * but for the positive signal: rejections teach the model what to avoid;
 * style examples teach the model what this user prefers.
 *
 * State machine (handled in ReconstructClient.reconstruct()):
 *
 *   1. On call with previousOutputs.isNotEmpty():
 *        — Current call is a regenerate. Prior reconstruction was REJECTED.
 *        — RejectionStore.record() captures the rejection.
 *        — Pending pair (if any) is the one being rejected — DROP it,
 *          do NOT promote to accepted.
 *
 *   2. On call with previousOutputs.isEmpty():
 *        — Current call is a fresh dictation. Prior reconstruction was
 *          implicitly ACCEPTED (no regenerate fired before this).
 *        — Promote pending pair (if any) to StyleExamples.record().
 *
 *   3. After reconstruction completes:
 *        — Set pendingPair = (rawText, cleanText) so the NEXT call's
 *          verdict logic above can decide its fate.
 *
 * The state machine has one false-negative case: if the user accepts a
 * reconstruction and then never dictates again (closes the app entirely),
 * that final pair stays pending forever and never gets recorded. That's
 * fine — losing the last-utterance-of-a-session is a small cost, and the
 * v1 simplicity is worth it. Future versions can persist the pending
 * pair on app exit.
 *
 * Storage: SharedPreferences-backed JSON array of objects, capped at
 * MAX_ENTRIES (20). Newest at the end. No deduplication beyond exact
 * match against most recent entry — same input twice in a row stays
 * once.
 *
 * Privacy: all on-device. SharedPreferences is per-app sandboxed.
 * "Erase voice memory" UI hook: clear() is exposed for the Profile
 * screen to wire later.
 */
object StyleExamples {

    private const val PREF_KEY = "style_examples_json"
    private const val MAX_ENTRIES = 20
    private const val DEFAULT_RECENT_N = 5

    /**
     * Pending pair: last reconstruction's (raw, output), awaiting
     * accept-or-reject verdict on the next call.
     *
     * AtomicReference closes a race the previous `@Volatile var` version
     * had: two overlapping reconstruct() calls could each read the same
     * pending pair, decide separately what to do with it (promote or
     * drop), and both end up writing — losing the prior pair's verdict.
     * `getAndSet(null)` is atomic: exactly one caller wins the previous
     * value, the other gets null and short-circuits. Audit finding
     * 2026-06-06.
     */
    val pendingPair: AtomicReference<Pair<String, String>?> = AtomicReference(null)

    data class Example(val raw: String, val output: String)

    /** Append the (raw, output) pair to the persistent history. */
    fun record(raw: String, output: String) {
        if (raw.isBlank() || output.isBlank()) return
        val current = load().toMutableList()
        // Skip if the latest entry is the exact same pair — repeat dictations
        // of identical input shouldn't fill the buffer with copies.
        if (current.lastOrNull()?.raw == raw && current.lastOrNull()?.output == output) return
        current.add(Example(raw, output))
        while (current.size > MAX_ENTRIES) current.removeAt(0)
        persist(current)
        Log.i("StyleExamples", "Recorded accepted pair (raw='${raw.take(40)}...') — total=${current.size}")
    }

    /** Most recent up-to-n examples, oldest first. */
    fun recent(n: Int = DEFAULT_RECENT_N): List<Example> {
        val all = load()
        if (all.size <= n) return all
        return all.subList(all.size - n, all.size)
    }

    /** Erase all stored examples. Profile screen "Erase voice memory" hook. */
    fun clear() {
        WimApp.prefs.edit().remove(PREF_KEY).apply()
    }

    private fun load(): List<Example> {
        val raw = WimApp.prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Example(
                    raw = obj.optString("raw"),
                    output = obj.optString("output")
                )
            }
        } catch (e: Exception) {
            Log.w("StyleExamples", "Corrupt JSON, resetting: ${e.message}")
            emptyList()
        }
    }

    private fun persist(items: List<Example>) {
        val arr = JSONArray()
        items.forEach { ex ->
            arr.put(JSONObject().apply {
                put("raw", ex.raw)
                put("output", ex.output)
            })
        }
        WimApp.prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }
}
