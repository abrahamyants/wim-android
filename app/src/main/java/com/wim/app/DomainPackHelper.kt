package com.wim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Domain-specific vocabulary packs (legal, medical, finance, hipaa).
 *
 * Loaded once from `assets/domain_packs/<key>.json`. Each pack supplies a short
 * prose hint for the reconstruction prompt + a vocab list (canonical spellings
 * the model must respect) + phonetic_aliases (common ASR mishearings the model
 * should correct silently).
 *
 * The active pack is selected by the `domain` shared pref. Default = "general"
 * (no pack injected). When set to a domain key, the L2-L4 reconstruction prompt
 * gets the pack's `prompt_note`, `vocab` list, and a list of phonetic aliases
 * to silently correct.
 *
 * Why JSON-in-assets vs hardcoded Kotlin: vocab packs are content, not code.
 * Editing a JSON to add terms doesn't require a rebuild of the engine layer
 * and lets non-coders contribute domain expertise.
 */
object DomainPackHelper {

    data class Pack(
        val key: String,
        val name: String,
        val promptNote: String,
        val vocab: List<String>,
        val phoneticAliases: Map<String, String>
    )

    private val cache = mutableMapOf<String, Pack?>()

    /**
     * Load the pack for the given key. Returns null if the key is "general"
     * or the asset file is missing/malformed.
     */
    fun loadPack(context: Context, key: String): Pack? {
        if (key == "general" || key.isBlank()) return null
        cache[key]?.let { return it }
        return try {
            val raw = context.assets.open("domain_packs/$key.json").bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val vocab = json.optJSONArray("vocab")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val aliases = json.optJSONObject("phonetic_aliases")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } ?: emptyMap()
            val pack = Pack(
                key = key,
                name = json.optString("name", key),
                promptNote = json.optString("prompt_note", ""),
                vocab = vocab,
                phoneticAliases = aliases
            )
            cache[key] = pack
            pack
        } catch (e: Exception) {
            Log.w("DomainPack", "Failed to load $key: ${e.message}")
            cache[key] = null
            null
        }
    }

    /**
     * Map the existing `profile_industry` pref (set by the Profile screen's
     * industry chips) to a pack key. The Profile is the single source of truth —
     * no separate `domain` pref. Industries without a built pack fall through
     * to "general" (no injection).
     */
    private fun industryToPackKey(industry: String): String = when (industry) {
        "finance" -> "finance"
        "legal" -> "legal"
        "medical" -> "medical"
        "tech", "sales" -> "sf_pro"
        else -> "general"  // trades / food / education / other / "" → no pack
    }

    /** The currently selected domain pack per Profile.industry, or null if none. */
    fun activePack(context: Context): Pack? {
        val industry = WimApp.prefs.getString("profile_industry", "") ?: ""
        val key = industryToPackKey(industry)
        return loadPack(context, key)
    }

    /**
     * Build the chunk of prompt text to inject. Empty string when no pack
     * is selected so callers can append unconditionally.
     */
    fun promptInjection(context: Context): String {
        val pack = activePack(context)
        if (pack == null) {
            Log.i("DomainPack", "promptInjection: no pack active (industry=${WimApp.prefs.getString("profile_industry", "")})")
            return ""
        }
        Log.i("DomainPack", "promptInjection: injecting ${pack.key} pack — ${pack.vocab.size} vocab + ${pack.phoneticAliases.size} aliases")
        return buildString {
            append("\n\n${pack.promptNote}")
            if (pack.vocab.isNotEmpty()) {
                append("\nDomain vocabulary (canonical spellings — preserve exactly): ")
                append(pack.vocab.joinToString(", "))
            }
            if (pack.phoneticAliases.isNotEmpty()) {
                append("\nCRITICAL CANONICAL FORMS — these spelled-out words ARE the canonical acronyms/terms in this domain. Replace the lowercase/spelled-out form with the canonical form EVERY TIME, regardless of how the speaker pronounced them. NEVER expand the canonical form back to its long version unless the speaker explicitly said the long version.")
                pack.phoneticAliases.entries.forEach { (wrong, right) ->
                    append("\n  • \"$wrong\" → \"$right\" (do NOT expand \"$right\" to its long form)")
                }
            }
        }
    }

    /**
     * Built-in domain keys, ordered by primary-target-first.
     * sf_pro = day-to-day tech/finance professional shorthand (SF FiDi default).
     * legal/medical/finance/hipaa = specialized academic-glossary packs.
     */
    val BUILTIN_KEYS = listOf("general", "sf_pro", "legal", "medical", "finance", "hipaa")
}
