package com.wim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Per-language disfluency pack loaded from app assets. Provides language-specific
 * filler-word lists and natural-repeat allow-lists (emphatic patterns that must
 * NOT be stripped as repetitions — "sí sí", "そうそう", "nein nein", etc.).
 *
 * English has no pack; DisfluencyFilter falls back to its hardcoded defaults.
 * Source JSONs are mirrored from lavrentiy/lang_packs/ — keep in sync.
 */
object LangPack {
    private const val TAG = "WiM.LangPack"
    private const val ASSETS_DIR = "lang_packs"

    // Language codes that ship a pack in assets.
    private val AVAILABLE = setOf("ar", "de", "es", "fr", "hi", "it", "ja", "ko", "pt", "zh")

    @Volatile private var cachedCode: String = "en"
    @Volatile private var cachedFillers: List<String> = emptyList()
    @Volatile private var cachedNaturalRepeats: Set<String> = emptySet()

    /**
     * Normalize 'en-US' → 'en', 'es-ES' → 'es'. Null/blank → 'en'.
     */
    fun normalizeLangCode(raw: String?): String {
        if (raw.isNullOrBlank()) return "en"
        return raw.trim().split("-", limit = 2)[0]
            .split("_", limit = 2)[0]
            .lowercase()
    }

    /**
     * Load the pack for [code] (already normalized) from assets and cache it.
     * Idempotent — re-calling with the same code is a no-op. Call on app start
     * and whenever the user changes the primary language.
     */
    @Synchronized
    fun load(context: Context, code: String) {
        if (code == cachedCode) return
        if (code !in AVAILABLE) {
            cachedCode = code
            cachedFillers = emptyList()
            cachedNaturalRepeats = emptySet()
            return
        }
        try {
            val json = context.assets.open("$ASSETS_DIR/$code.json")
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val fillers = obj.optJSONArray("fillers")?.let { arr ->
                List(arr.length()) { arr.getString(it) }
            } ?: emptyList()
            val natural = obj.optJSONArray("natural_repeats")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
            } ?: emptySet()
            cachedFillers = fillers
            cachedNaturalRepeats = natural
            cachedCode = code
            Log.i(TAG, "Loaded pack '$code': ${fillers.size} fillers, ${natural.size} natural-repeats")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load pack '$code': ${e.message}")
            cachedCode = code
            cachedFillers = emptyList()
            cachedNaturalRepeats = emptySet()
        }
    }

    /**
     * Load the pack for the current user language from WimApp prefs.
     * Prefers 'primary_language' set by the settings UI / OnDeviceRecognizer.
     */
    fun loadFromPrefs(context: Context) {
        val pref = WimApp.prefs.getString("primary_language", "en-US") ?: "en-US"
        load(context, normalizeLangCode(pref))
    }

    /** Language-specific fillers (empty for English — hardcoded defaults still apply). */
    fun fillers(): List<String> = cachedFillers

    /**
     * Language-specific natural-repeat allow-list. Returns null when no pack is
     * loaded (English or unrecognized), signalling DisfluencyFilter to use its
     * hardcoded English + Russian defaults.
     */
    fun naturalRepeats(): Set<String>? =
        if (cachedCode in AVAILABLE) cachedNaturalRepeats else null

    /** Current loaded language code (normalized). */
    fun currentCode(): String = cachedCode
}
