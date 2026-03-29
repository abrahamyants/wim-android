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

    /**
     * Learn from usage: when reconstruction consistently changes a word,
     * auto-add to corrections after seeing it 3+ times.
     */
    fun learnFromReconstruction(rawText: String, cleanText: String) {
        val rawWords = rawText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanWords = cleanText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }

        // Simple diff: find words in raw that got consistently replaced
        for (raw in rawWords) {
            if (raw !in cleanWords && raw.length >= 3 && raw !in corrections) {
                val count = (correctionCounts[raw] ?: 0) + 1
                correctionCounts[raw] = count

                if (count >= 3) {
                    // Find what it likely became — look for a clean word not in raw
                    val candidates = cleanWords.filter { it !in rawWords && it.length >= 3 }
                    if (candidates.isNotEmpty()) {
                        val replacement = candidates.first()
                        corrections[raw] = replacement
                        correctionCounts.remove(raw)
                        Log.i(TAG, "Auto-learned correction: \"$raw\" -> \"$replacement\"")
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
