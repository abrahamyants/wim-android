package com.wim.app

import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

        /**
         * Single shared lock serialising every read-modify-write of
         * `profile_json` in this class — saveProfile, writeTriggerWords,
         * and mergeRemoteProfile. The 2026-05-31 audit fix
         * (read-then-merge in saveProfile) stopped the *symptom* of the
         * wipe (clinical fields surviving the round trip) but the
         * underlying race — three threads (reconstruction coroutine,
         * Firestore listener, main / trigger-word UI) doing non-atomic
         * RMW on the same JSON blob — was still alive. Under interleave
         * A and B can each read the same starting JSON, each write a
         * different result, and the last writer wins, dropping the
         * other's update.
         *
         * Companion-scoped because field-initialisation ordering bites:
         * `ProfileManager.<init> → loadProfile() → resetToDefaults() →
         * saveProfile()` runs BEFORE any instance `val` declared after
         * `init {}` gets assigned, so an instance-scoped lock is null
         * during the first saveProfile from the constructor. Audit
         * finding 2026-06-06.
         */
        private val PROFILE_WRITE_LOCK = Any()

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

    // Concurrent collections — the Firestore sync listener (background thread)
    // mutates these via mergeRemoteProfile() while reconstruction reads them
    // via getVocabulary()/getCorrections()/getFillerWords() on the bubble's
    // launch scope. With plain MutableList/MutableMap the .toList()/.toMap()
    // snapshot reads can throw ConcurrentModificationException mid-merge.
    // CopyOnWriteArrayList + ConcurrentHashMap make those reads always-safe.
    private val vocabulary: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
    private val corrections: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    private val fillerWords: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Tracks how many times reconstruction changed a given word, for auto-learning. */
    private val correctionCounts: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    /** Tracks session number when correction was last used (for decay). */
    private val correctionLastUsed: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    /** Rolling history of words-per-second per session; RateGapDetector uses the median. */
    private val wpsHistory: CopyOnWriteArrayList<Double> = CopyOnWriteArrayList()
    private val WPS_HISTORY_SIZE = 30

    init {
        loadProfile()
    }

    fun loadProfile() {
        val json = prefs.getString(PREF_KEY, null)
        if (json != null) {
            try {
                val obj = JSONObject(json)

                vocabulary.clear()
                val vocabArr = obj.optJSONArray("vocabulary") ?: JSONArray()
                for (i in 0 until vocabArr.length()) vocabulary.add(vocabArr.getString(i))

                corrections.clear()
                val corr = obj.optJSONObject("corrections") ?: JSONObject()
                corr.keys().forEach { key -> corrections[key] = corr.getString(key) }

                fillerWords.clear()
                val fillerArr = obj.optJSONArray("filler_words") ?: JSONArray()
                for (i in 0 until fillerArr.length()) fillerWords.add(fillerArr.getString(i))

                correctionCounts.clear()
                val counts = obj.optJSONObject("correction_counts") ?: JSONObject()
                counts.keys().forEach { key -> correctionCounts[key] = counts.getInt(key) }

                correctionLastUsed.clear()
                val lastUsed = obj.optJSONObject("correction_last_used") ?: JSONObject()
                lastUsed.keys().forEach { key -> correctionLastUsed[key] = lastUsed.getInt(key) }

                wpsHistory.clear()
                val wpsArr = obj.optJSONArray("wps_history") ?: JSONArray()
                for (i in 0 until wpsArr.length()) wpsHistory.add(wpsArr.getDouble(i))

                Log.i(TAG, "Profile loaded: ${vocabulary.size} vocab, ${corrections.size} corrections, ${fillerWords.size} fillers, ${wpsHistory.size} wps samples")
            } catch (e: Exception) {
                // Preserve the corrupt JSON before nuking the user's learned
                // vocabulary / corrections / triggers / onset weights / wps
                // history. One bad write should not silently delete every
                // signal the user has accumulated across sessions.
                try {
                    val ts = System.currentTimeMillis()
                    prefs.edit().putString("profile_json_corrupted_$ts", json).apply()
                    Log.e(TAG, "Profile parse error, preserved as profile_json_corrupted_$ts: ${e.message}")
                } catch (preserveErr: Exception) {
                    Log.e(TAG, "Profile parse error AND backup failed: ${e.message}", preserveErr)
                }
                resetToDefaults()
            }
        } else {
            resetToDefaults()
        }
    }

    fun saveProfile() {
        synchronized(PROFILE_WRITE_LOCK) {
            // CRITICAL: read-modify-write the existing profile_json so we PRESERVE
            // fields ProfileManager doesn't hold in memory — trigger_words,
            // onset_weights, covert_profile (synced from Lavrentiy via the
            // Firestore listener at mergeRemoteProfile). Without this preserve
            // step, saveProfile silently wipes the clinical sync data because
            // it writes the whole profile_json blob from only the 6 fields the
            // ProfileManager owns in memory — the other 3 fields don't survive.
            // Bug verified by 2026-05-31 audit.
            val existing = try {
                prefs.getString(PREF_KEY, null)?.let { JSONObject(it) } ?: JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            existing.put("vocabulary", JSONArray(vocabulary))
            existing.put("corrections", JSONObject(corrections as Map<*, *>))
            existing.put("filler_words", JSONArray(fillerWords))
            existing.put("correction_counts", JSONObject(correctionCounts as Map<*, *>))
            existing.put("correction_last_used", JSONObject(correctionLastUsed as Map<*, *>))
            existing.put("wps_history", JSONArray(wpsHistory))
            // trigger_words / onset_weights / covert_profile passed through
            // untouched (already in `existing` from prefs read).
            prefs.edit().putString(PREF_KEY, existing.toString()).apply()
            Log.i(TAG, "Profile saved (preserving Firestore-synced fields)")
        }
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

    fun removeCorrection(from: String) {
        if (corrections.remove(from.lowercase()) != null) {
            saveProfile()
        }
    }

    fun addVocabulary(word: String) {
        val w = word.trim()
        if (w.isNotEmpty() && w !in vocabulary) {
            vocabulary.add(w)
            saveProfile()
        }
    }

    fun removeVocabulary(word: String) {
        if (vocabulary.remove(word.trim())) {
            saveProfile()
        }
    }

    /** Add a trigger word — written to the profile JSON's trigger_words array. */
    fun addTriggerWord(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val current = getTriggerWords().toMutableList()
        if (w in current) return
        current.add(w)
        writeTriggerWords(current)
    }

    fun removeTriggerWord(word: String) {
        val w = word.trim()
        val current = getTriggerWords().toMutableList()
        if (current.remove(w)) writeTriggerWords(current)
    }

    private fun writeTriggerWords(words: List<String>) {
        synchronized(PROFILE_WRITE_LOCK) {
            val json = prefs.getString(PREF_KEY, null)
            val obj = if (json != null) JSONObject(json) else JSONObject()
            obj.put("trigger_words", org.json.JSONArray(words))
            prefs.edit().putString(PREF_KEY, obj.toString()).apply()
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
            if (raw !in cleanWords && raw.length >= 3 && !corrections.containsKey(raw)) {
                // Atomic increment — ConcurrentHashMap.compute runs the
                // remapping function under the bin's per-stripe lock, so two
                // concurrent calls (reconstruction coroutine + Firestore
                // listener can both fire learn at once) cannot race the
                // read-then-write that the previous `[k] ?: 0 + 1` pattern
                // allowed. Audit finding 2026-06-06.
                val count = correctionCounts.compute(raw) { _, old -> (old ?: 0) + 1 }!!

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
        synchronized(PROFILE_WRITE_LOCK) {
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
                vocabulary.clear()
                vocabulary.addAll(merged)
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
                fillerWords.clear()
                fillerWords.addAll(merged)
                obj.put("filler_words", JSONArray(merged))
            }

            prefs.edit().putString(PREF_KEY, obj.toString()).apply()
            Log.i(TAG, "Remote profile merged successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Remote profile merge failed: ${e.message}")
        }
        }
    }

    // ── L1 pack convenience accessors ──

    /** Returns the current profile_l1 value ("russian", "spanish", "mandarin", "en", or null/blank). */
    fun profileL1(): String? {
        val v = prefs.getString("profile_l1", null)
        return if (v.isNullOrBlank() || v == "en") null else v
    }

    /** Writes profile_l1 (mirrors the Profile overlay tap handler). */
    fun setProfileL1(language: String) {
        prefs.edit().putString("profile_l1", language).apply()
    }

    // ── L1 pack contribution opt-in ──

    fun contributeL1Data(): Boolean = prefs.getBoolean("contribute_l1_data", false)

    fun setContributeL1Data(v: Boolean) = prefs.edit().putBoolean("contribute_l1_data", v).apply()

    // ── Anonymous contributor ID (stable, never changes after first generation) ──

    fun anonymousId(): String {
        val existing = prefs.getString("anonymous_id", null)
        if (existing != null) return existing
        val newId = java.util.UUID.randomUUID().toString().substring(0, 16)
        prefs.edit().putString("anonymous_id", newId).apply()
        return newId
    }

    // ── L1 auto-detect counters and flags ──

    fun l1DetectCount(): Int = prefs.getInt("l1_detect_count", 0)

    fun incrementL1DetectCount() = prefs.edit().putInt("l1_detect_count", l1DetectCount() + 1).apply()

    fun markL1DetectPrompted() = prefs.edit().putBoolean("l1_detect_prompted", true).apply()

    fun wasL1DetectPrompted(): Boolean = prefs.getBoolean("l1_detect_prompted", false)

    private fun resetToDefaults() {
        vocabulary.clear()
        corrections.clear()
        fillerWords.clear()
        fillerWords.addAll(DEFAULT_FILLERS)
        saveProfile()
    }
}
