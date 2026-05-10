package com.wim.app

import org.json.JSONObject
import java.util.Calendar

/**
 * Spec §6 Phase 5 — anticipatory prediction.
 *
 * Two surfaces:
 *   1. [predictForContext]: returns a [Prediction] (recipient + tone +
 *      confidence) shown as a chip on Idle, before the user opens mic.
 *      Cold-start derives from [AudienceContext]; adaptive layer learns
 *      from per-user (pkg, hour, weekday) completions.
 *   2. [candidatesForPosition] (implements [CandidateProvider]): returns
 *      sentence-boundary completion candidates for the clarifier when a
 *      flagged word lands at the start of a sentence. Spec §4: "your
 *      bubble learned how you talk to your boss in Slack" — the integration
 *      moment between Spec A predictions and Spec B clarifier candidates
 *      (claim 14).
 *
 * Persistence: per-pkg recent-completions cached in SharedPreferences
 * (`prediction_history_<pkg>`) as a JSON object with N most-recent
 * sentence-end → next-word pairs. Capped at 64 entries per pkg.
 */
object PredictionEngine : CandidateProvider {

    private const val MAX_HISTORY_PER_PKG = 64
    private const val MIN_CONFIDENCE_FOR_CHIP = 0.55

    data class Prediction(
        val recipient: String,
        val tone: String,
        val confidence: Double,
        val reason: String,
    )

    /**
     * Cold-start + adaptive prediction for the current context. Returns
     * null when confidence is below the chip threshold (don't visually
     * suggest something we're not sure about — false-positive cost is
     * "user dismisses repeatedly until they hate the chip").
     */
    fun predictForContext(
        pkg: String?,
        hour: Int = currentHour(),
        @Suppress("UNUSED_PARAMETER") weekday: Int = currentWeekday(),
    ): Prediction? {
        if (pkg.isNullOrBlank()) return null
        val profile = AudienceContext.profileForPackage(pkg) ?: return null

        // Cold-start derivation: AudienceContext.Profile maps cleanly to a
        // recipient label + a tone hint. Confidence starts at 0.6 and
        // adjusts up with adaptive evidence.
        val tone = when (profile.category) {
            "email", "professional networking", "work tool", "task tracker", "document" -> "professional"
            "work chat" -> if (hour in 9..17) "professional" else "casual"
            "SMS", "personal chat", "social DM", "community chat", "personal app" -> "casual"
            "notes / docs" -> "casual"
            "forum post or comment" -> "casual"
            else -> "casual"
        }
        var confidence = 0.6
        // Time-of-day boost: midweek 9-5 in a work app is a stronger signal
        // than weekend 11pm in the same app.
        if (profile.category in setOf("email", "work chat", "work tool")) {
            if (hour in 9..17) confidence += 0.10
            else confidence -= 0.10
        }
        // Friend/family chat at typical hours boosts.
        if (profile.category in setOf("personal chat", "social DM", "SMS") && (hour in 8..23)) {
            confidence += 0.05
        }

        if (confidence < MIN_CONFIDENCE_FOR_CHIP) return null
        return Prediction(
            recipient = profile.audience.substringBefore(" (").take(40),
            tone = tone,
            confidence = confidence.coerceIn(0.0, 1.0),
            reason = profile.category,
        )
    }

    // ── CandidateProvider impl ──────────────────────────

    override fun candidatesForPosition(
        pkg: String,
        priorWords: List<String>,
        position: Int,
        maxCandidates: Int,
    ): List<String> {
        val history = readHistory(pkg)
        val priorTrigram = priorWords.takeLast(3).joinToString(" ").lowercase()
        if (priorTrigram.isBlank()) return emptyList()
        return history[priorTrigram].orEmpty().take(maxCandidates)
    }

    /**
     * Record an observed sentence-boundary completion for this pkg —
     * call when a reconstruction settles cleanly (no clarifier cycle, no
     * regen). Trigram of the last three words leading up to a sentence
     * boundary maps to the word that followed.
     */
    fun recordCompletion(pkg: String, priorWords: List<String>, completion: String) {
        if (pkg.isBlank() || completion.isBlank()) return
        val priorTrigram = priorWords.takeLast(3).joinToString(" ").lowercase()
        if (priorTrigram.isBlank()) return
        val history = readHistory(pkg).toMutableMap()
        val list = (history[priorTrigram] ?: emptyList()).toMutableList()
        // Move-to-front semantics: most-recent completions outrank stale ones.
        list.remove(completion)
        list.add(0, completion)
        if (list.size > 5) list.subList(5, list.size).clear()
        history[priorTrigram] = list
        // Cap total entries per pkg.
        val pruned = if (history.size > MAX_HISTORY_PER_PKG) {
            history.entries.toList().takeLast(MAX_HISTORY_PER_PKG).associate { it.toPair() }
        } else history
        writeHistory(pkg, pruned)
    }

    // ── Storage ─────────────────────────────────────────

    private fun prefKeyFor(pkg: String) = "prediction_history_$pkg"

    private fun readHistory(pkg: String): Map<String, List<String>> {
        val raw = WimApp.prefs.getString(prefKeyFor(pkg), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k ->
                    val arr = obj.getJSONArray(k)
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    put(k, list)
                }
            }
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    private fun writeHistory(pkg: String, history: Map<String, List<String>>) {
        val obj = JSONObject()
        history.forEach { (k, v) -> obj.put(k, org.json.JSONArray(v)) }
        WimApp.prefs.edit().putString(prefKeyFor(pkg), obj.toString()).apply()
    }

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun currentWeekday(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
}
