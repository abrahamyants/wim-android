package com.wim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * L1-transfer packs for non-native English speakers.
 *
 * Phonetic accent disappears at the ASR layer (Whisper outputs text, no audio).
 * What survives in the transcript: syntactic, morphological, lexical, and
 * discourse-pragmatic patterns from the speaker's first language. Russian-L1
 * speakers drop articles. Mandarin-L1 speakers drop tense markers. Spanish-L1
 * speakers calque idioms. These patterns are deterministic enough that
 * detection-and-normalization from text alone is reliable.
 *
 * Loaded once from `assets/l1_packs/<language>.json`. Activated by the
 * `profile_l1` shared pref (set by the Profile screen). Default = "en"
 * (no pack injected). When the user is a non-native English speaker, the
 * pack's markers + prompt_hints + examples get appended to the L2/L3
 * reconstruction prompt — NOT L4 (the L4 clinical prompt has its own framing
 * and double-instruction harms output quality).
 *
 * Design parallel: `DomainPackHelper`. Same Pack/cache/loader/promptInjection
 * shape, different content domain.
 *
 * v2 TODO: per-pattern toggle (user opts in/out of individual markers) and
 * normalize-vs-preserve mode (preserve = pack loaded but injected with a
 * different framing that asks the model to keep patterns as authentic voice).
 */
object L1PackHelper {

    data class Example(val input: String, val output: String)

    data class Marker(
        val id: String,
        val category: String,
        val mechanism: String,
        val examples: List<Example>,
        val promptHint: String,
        val citation: String
    )

    data class Pack(
        val language: String,
        val markers: List<Marker>,
        val notes: String?
    )

    private val cache = mutableMapOf<String, Pack?>()

    fun loadPack(context: Context, language: String): Pack? {
        if (language == "en" || language.isBlank()) return null
        cache[language]?.let { return it }
        return try {
            val raw = context.assets.open("l1_packs/$language.json")
                .bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val markersArr = json.getJSONArray("markers")
            val markers = (0 until markersArr.length()).map { i ->
                val m = markersArr.getJSONObject(i)
                val examplesArr = m.optJSONArray("examples")
                val examples = if (examplesArr != null) {
                    (0 until examplesArr.length()).map { j ->
                        val e = examplesArr.getJSONObject(j)
                        Example(e.optString("input", ""), e.optString("output", ""))
                    }
                } else emptyList()
                Marker(
                    id = m.getString("id"),
                    category = m.getString("category"),
                    mechanism = m.getString("mechanism"),
                    examples = examples,
                    promptHint = m.getString("prompt_hint"),
                    citation = m.optString("citation", "")
                )
            }
            val pack = Pack(
                language = json.getString("language"),
                markers = markers,
                notes = json.optString("notes").takeIf { it.isNotBlank() }
            )
            cache[language] = pack
            pack
        } catch (e: Exception) {
            Log.w("L1Pack", "Failed to load $language: ${e.message}")
            cache[language] = null
            null
        }
    }

    /** The pack matching the user's `profile_l1` pref, or null for native English. */
    fun activePack(context: Context): Pack? {
        val l1 = WimApp.prefs.getString("profile_l1", "") ?: ""
        return loadPack(context, l1)
    }

    /**
     * Build the L1 prompt block to inject at L2/L3. Empty string when no pack
     * is active so callers can append unconditionally.
     *
     * The block tells the model the speaker's L1 explicitly, lists each
     * marker's prompt_hint with one verbatim example, and closes with a
     * stylistic-choice carve-out so deliberate code-switching survives.
     */
    fun promptInjection(context: Context): String {
        val pack = activePack(context)
        if (pack == null) {
            Log.i("L1Pack", "promptInjection: no pack active (profile_l1=${WimApp.prefs.getString("profile_l1", "")})")
            return ""
        }
        val displayName = languageDisplayName(pack.language)
        Log.i("L1Pack", "promptInjection: injecting ${pack.language} pack — ${pack.markers.size} markers")
        return buildString {
            append("\n\nSPEAKER L1: $displayName. Their English may show systematic L1-transfer patterns documented at the group level in second-language acquisition research — not random errors. When restating into written prose, normalize the patterns listed below. Preserve the speaker's word choice, slang, profanity, and intent.")
            append("\n\nNORMALIZE these patterns:")
            pack.markers.forEachIndexed { i, m ->
                append("\n${i + 1}. ${m.promptHint}")
                m.examples.firstOrNull()?.let { e ->
                    if (e.input.isNotBlank() && e.output.isNotBlank()) {
                        append(" e.g. \"${e.input}\" → \"${e.output}\"")
                    }
                }
            }
            append("\n\nThis is normalization toward written-English convention, not erasure of the speaker's voice. If a listed pattern appears as deliberate stylistic emphasis or intentional code-switching, preserve it.")
        }
    }

    private fun languageDisplayName(code: String): String = when (code) {
        "russian" -> "Russian"
        "spanish" -> "Spanish"
        "mandarin" -> "Mandarin Chinese"
        "hindi" -> "Hindi (Indian English)"
        "arabic" -> "Arabic"
        "farsi" -> "Farsi / Persian"
        "french" -> "French"
        "german" -> "German"
        "korean" -> "Korean"
        "japanese" -> "Japanese"
        else -> code.replaceFirstChar { it.uppercase() }
    }

    /**
     * Built-in L1 keys, ordered by primary-target-first.
     * v1 ships russian/spanish/mandarin. The other 7 are listed for forward
     * compatibility — the helper resolves them as null until packs land.
     */
    val BUILTIN_KEYS = listOf(
        "en",
        "russian", "spanish", "mandarin",
        "hindi", "arabic", "farsi", "french", "german", "korean", "japanese"
    )
}
