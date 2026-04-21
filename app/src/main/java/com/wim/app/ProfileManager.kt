package com.wim.app

import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores and manages the user's voice profile as JSON in SharedPreferences.
 *
 * Profile structure:
 * {
 *   "vocabulary": ["word1", "word2"],
 *   "corrections": {"wrong": "right"},
 *   "filler_words": ["um", "uh", ...]
 * }
 *
 * At L3+ the profile's vocabulary and corrections are injected into the
 * GPT reconstruction prompt for personalised cleanup.
 */
class ProfileManager(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "WiM"
        private const val PREF_KEY = "profile_json"

        /** Default filler words (EN + RU bilingual) */
        val DEFAULT_FILLERS = listOf(
            // English
            "um", "uh", "uhm", "umm", "erm", "er", "ah", "hm", "hmm",
            "like", "you know", "i mean", "so", "well", "right",
            // Russian
            "\u044d", "\u044d\u044d", "\u044d\u043c",
            "\u043d\u0443", "\u043d\u0443\u0443",
            "\u044d\u0442\u043e", "\u0432\u043e\u0442",
            "\u0442\u0438\u043f\u0430", "\u043a\u0430\u043a \u0431\u044b",
            "\u0437\u043d\u0430\u0447\u0438\u0442", "\u043a\u043e\u0440\u043e\u0447\u0435"
        )
    }

    private var vocabulary: MutableList<String> = mutableListOf()
    private var corrections: MutableMap<String, String> = mutableMapOf()
    private var fillerWords: MutableList<String> = mutableListOf()

    /** Tracks how many times reconstruction changed a given word, for auto-learning. */
    private var correctionCounts: MutableMap<String, Int> = mutableMapOf()

    /** Tracks session number when correction was last used (for decay). */
    private var correctionLastUsed: MutableMap<String, Int> = mutableMapOf()

    /** Rolling history of words-per-second per session; RateGapDetector uses the median. */
    private var wpsHistory: MutableList<Double> = mutableListOf()
    private val WPS_HISTORY_SIZE = 30

    init {
        loadProfile()
    }

    fun loadProfile() {
        val json = prefs.getString(PREF_KEY, null)
        if (json != null) {
            try {
                val obj = JSONObject(json)

                vocabulary = mutableListOf<String>().also { list ->
                    val arr = obj.optJSONArray("vocabulary") ?: JSONArray()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                }

                corrections = mutableMapOf<String, String>().also { map ->
                    val corr = obj.optJSONObject("corrections") ?: JSONObject()
                    corr.keys().forEach { key -> map[key] = corr.getString(key) }
                }

                fillerWords = mutableListOf<String>().also { list ->
                    val arr = obj.optJSONArray("filler_words") ?: JSONArray()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                }

                correctionCounts = mutableMapOf<String, Int>().also { map ->
                    val counts = obj.optJSONObject("correction_counts") ?: JSONObject()
                    counts.keys().forEach { key -> map[key] = counts.getInt(key) }
                }

                correctionLastUsed = mutableMapOf<String, Int>().also { map ->
                    val lastUsed = obj.optJSONObject("correction_last_used") ?: JSONObject()
                    lastUsed.keys().forEach { key -> map[key] = lastUsed.getInt(key) }
                }

                wpsHistory = mutableListOf<Double>().also { list ->
                    val arr = obj.optJSONArray("wps_history") ?: JSONArray()
                    for (i in 0 until arr.length()) list.add(arr.getDouble(i))
                }

                Log.i(TAG, "Profile loaded: ${vocabulary.size} vocab, ${corrections.size} corrections, ${fillerWords.size} fillers, ${wpsHistory.size} wps samples")
            } catch (e: Exception) {
                Log.e(TAG, "Profile parse error, resetting to defaults: ${e.message}")
                resetToDefaults()
            }
        } else {
            resetToDefaults()
        }
    }

    fun saveProfile() {
        val obj = JSONObject().apply {
            put("vocabulary", JSONArray(vocabulary))
            put("corrections", JSONObject(corrections as Map<*, *>))
            put("filler_words", JSONArray(fillerWords))
            put("correction_counts", JSONObject(correctionCounts as Map<*, *>))
            put("correction_last_used", JSONObject(correctionLastUsed as Map<*, *>))
            put("wps_history", JSONArray(wpsHistory))
        }
        prefs.edit().putString(PREF_KEY, obj.toString()).apply()
        Log.i(TAG, "Profile saved")
    }

    /**
     * Record one session's words-per-second. Keeps a rolling window of the last
     * [WPS_HISTORY_SIZE] samples. Ignores zero/non-finite values.
     */
    fun recordWps(wps: Double) {
        if (wps <= 0.0 || !wps.isFinite()) return
        wpsHistory.add(wps)
        while (wpsHistory.size > WPS_HISTORY_SIZE) wpsHistory.removeAt(0)
        saveProfile()
    }

    /**
     * Rolling-median WPS across the stored samples. Returns null until we have
     * at least [RateGapDetector.MIN_SAMPLES_FOR_BASELINE] samples — too few and
     * the baseline is noise.
     */
    fun baselineWps(): Double? {
        if (wpsHistory.size < RateGapDetector.MIN_SAMPLES_FOR_BASELINE) return null
        val sorted = wpsHistory.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    /**
     * Mark a correction as used (updates its last-used session number).
     * Called from L1 pipeline when a correction is applied.
     */
    fun markCorrectionUsed(wrong: String, sessionNum: Int) {
        correctionLastUsed[wrong.lowercase()] = sessionNum
    }

    /**
     * Decay sweep — remove corrections unused for 100+ sessions.
     * Runs every 30 sessions (DECAY_EVERY from Лаврентий).
     * Protects corrections that have been used recently.
     */
    fun decayStaleCorrections(currentSession: Int) {
        val staleThreshold = 100
        val toRemove = corrections.keys.filter { key ->
            val lastUsed = correctionLastUsed[key] ?: 0
            currentSession - lastUsed > staleThreshold
        }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach {
                corrections.remove(it)
                correctionLastUsed.remove(it)
            }
            Log.i(TAG, "Decayed ${toRemove.size} stale corrections: $toRemove")
            saveProfile()
        }
    }

    fun addCorrection(from: String, to: String) {
        corrections[from.lowercase()] = to
        saveProfile()
    }

    fun addVocabulary(word: String) {
        val w = word.trim()
        if (w.isNotEmpty() && w !in vocabulary) {
            vocabulary.add(w)
            saveProfile()
        }
    }

    fun getFillerWords(): List<String> = fillerWords.toList()

    fun getCorrections(): Map<String, String> = corrections.toMap()

    fun getVocabulary(): List<String> = vocabulary.toList()

    /** Trigger words — populated by backend learning, empty until then */
    fun getTriggerWords(): List<String> {
        val json = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("trigger_words") ?: return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    /** Onset weights — phonetic difficulty map, populated by backend */
    fun getOnsetWeights(): Map<String, Double> {
        val json = prefs.getString(PREF_KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json).optJSONObject("onset_weights") ?: return emptyMap()
            val map = mutableMapOf<String, Double>()
            obj.keys().forEach { map[it] = obj.getDouble(it) }
            map
        } catch (_: Exception) { emptyMap() }
    }

    /** Covert avoidance profile — word substitution pairs, populated by backend */
    fun getCovertProfile(): Map<String, String> {
        val json = prefs.getString(PREF_KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json).optJSONObject("covert_profile") ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.getString(it) }
            map
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * Learn from usage: when reconstruction consistently changes a word,
     * auto-add to corrections after seeing it 3+ times.
     */
    fun learnFromReconstruction(rawText: String, cleanText: String) {
        val rawWords = rawText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanWords = cleanText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }

        // Position-aware diff: align by shared anchor words, then find substitutions
        // Build position map of clean words for proximity matching
        val cleanPositions = cleanWords.mapIndexed { i, w -> w to i }

        for ((rawIdx, raw) in rawWords.withIndex()) {
            if (raw !in cleanWords && raw.length >= 3 && raw !in corrections) {
                val count = (correctionCounts[raw] ?: 0) + 1
                correctionCounts[raw] = count

                if (count >= 3) {
                    // Find the closest positional replacement:
                    // Look for clean words not in raw, near the same position
                    val candidates = cleanPositions
                        .filter { (w, _) -> w !in rawWords && w.length >= 3 }
                        .sortedBy { (_, pos) -> kotlin.math.abs(pos - rawIdx) }

                    val best = candidates.firstOrNull()
                    if (best != null && kotlin.math.abs(best.second - rawIdx) <= 2) {
                        // Only learn if the replacement is within 2 positions of the original
                        corrections[raw] = best.first
                        correctionCounts.remove(raw)
                        Log.i(TAG, "Auto-learned correction: \"$raw\" -> \"${best.first}\"")
                        saveProfile()
                    }
                }
            }
        }
    }

    // ── Firestore sync: pull learned profile from Lavrentiy desktop ──

    private var syncListener: ListenerRegistration? = null

    fun startSync(uid: String) {
        stopSync()
        syncListener = FirebaseFirestore.getInstance()
            .collection("wim_users")
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Profile sync error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    mergeRemoteProfile(snapshot)
                }
            }
        Log.i(TAG, "Profile sync started for uid=$uid")
    }

    fun stopSync() {
        syncListener?.remove()
        syncListener = null
    }

    private fun mergeRemoteProfile(doc: DocumentSnapshot) {
        try {
            val json = prefs.getString(PREF_KEY, null)
            val obj = if (json != null) JSONObject(json) else JSONObject()

            // Remote wins for clinical fields (learned by Lavrentiy desktop)
            doc.get("trigger_words")?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                val list = raw as? List<String> ?: emptyList()
                obj.put("trigger_words", JSONArray(list))
                Log.i(TAG, "Synced ${list.size} trigger words from desktop")
            }

            doc.get("onset_weights")?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                val map = raw as? Map<String, Number> ?: emptyMap()
                obj.put("onset_weights", JSONObject(map))
                Log.i(TAG, "Synced ${map.size} onset weights from desktop")
            }

            doc.get("covert_profile")?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                val map = raw as? Map<String, Any> ?: emptyMap()
                obj.put("covert_profile", JSONObject(map))
                Log.i(TAG, "Synced covert profile from desktop")
            }

            // Merge (union) for shared fields — don't overwrite local additions
            doc.get("vocabulary")?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                val remote = (raw as? List<String>)?.toSet() ?: emptySet()
                val local = vocabulary.toSet()
                val merged = (local + remote).toList()
                vocabulary = merged.toMutableList()
                obj.put("vocabulary", JSONArray(merged))
            }

            doc.get("corrections")?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                val remote = raw as? Map<String, String> ?: emptyMap()
                remote.forEach { (k, v) -> corrections.putIfAbsent(k, v) }
                obj.put("corrections", JSONObject(corrections as Map<*, *>))
            }

            doc.get("filler_words")?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                val remote = (raw as? List<String>)?.toSet() ?: emptySet()
                val local = fillerWords.toSet()
                val merged = (local + remote).toList()
                fillerWords = merged.toMutableList()
                obj.put("filler_words", JSONArray(merged))
            }

            prefs.edit().putString(PREF_KEY, obj.toString()).apply()
            Log.i(TAG, "Remote profile merged successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Remote profile merge failed: ${e.message}")
        }
    }

    private fun resetToDefaults() {
        vocabulary = mutableListOf()
        corrections = mutableMapOf()
        fillerWords = DEFAULT_FILLERS.toMutableList()
        saveProfile()
    }
}
