package com.wim.app

import android.content.SharedPreferences
import android.util.Log
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

                Log.i(TAG, "Profile loaded: ${vocabulary.size} vocab, ${corrections.size} corrections, ${fillerWords.size} fillers")
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
        }
        prefs.edit().putString(PREF_KEY, obj.toString()).apply()
        Log.i(TAG, "Profile saved")
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

    private fun resetToDefaults() {
        vocabulary = mutableListOf()
        corrections = mutableMapOf()
        fillerWords = DEFAULT_FILLERS.toMutableList()
        saveProfile()
    }
}
