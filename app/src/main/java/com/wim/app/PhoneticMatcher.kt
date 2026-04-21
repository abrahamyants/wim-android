package com.wim.app

import android.util.Log
import org.apache.commons.codec.language.DoubleMetaphone

/**
 * Phonetic-neighbor correction for ASR output.
 *
 * When an on-device ASR (Vosk, Google native, etc.) outputs a word that is
 * phonetically close to a word in the user's learned vocabulary, swap it.
 * Catches domain-specific names and loanwords the ASR engine doesn't know —
 * "dunken" → "Dankeschön", "wisper" → "Wispr" — without requiring an explicit
 * correction pair in the user's profile.
 *
 * Uses Double Metaphone (Apache Commons Codec) because it handles proper
 * nouns and foreign words better than Soundex. Runs only on words whose onset
 * appears in the user's high-risk onset map — targets the exact onsets the
 * user personally struggles with instead of every word in the utterance.
 *
 * Reuses the personal onset-weight model from PhoneticRisk as the "judge"
 * for which words are worth re-scoring.
 */
object PhoneticMatcher {

    private const val TAG = "WiM.PhoneticMatcher"
    private const val HIGH_RISK_THRESHOLD = 0.5
    private const val MIN_WORD_LENGTH = 3

    private val dm = DoubleMetaphone().apply { maxCodeLen = 4 }

    private data class VocabEntry(
        val original: String,
        val primary: String,
        val alternate: String
    )

    /**
     * For each word in [text], swap it with a phonetically-similar vocabulary
     * word IF the word's onset is on the user's high-risk list AND Double
     * Metaphone codes match. Returns text with swaps applied; untouched
     * otherwise (punctuation, whitespace, non-matching words preserved).
     *
     * @param text raw text post-DisfluencyFilter
     * @param vocabulary user's learned vocabulary
     * @param onsetWeights user's personal onset-difficulty map. Empty ⇒ match
     *                     all onsets (new user without profile data).
     */
    fun correct(
        text: String,
        vocabulary: List<String>,
        onsetWeights: Map<String, Double> = emptyMap()
    ): String {
        if (text.isBlank() || vocabulary.isEmpty()) return text

        val vocabCodes = vocabulary.mapNotNull { w ->
            val clean = w.trim()
            if (clean.length < MIN_WORD_LENGTH) return@mapNotNull null
            val primary = dm.doubleMetaphone(clean).orEmpty()
            val alternate = dm.doubleMetaphone(clean, true).orEmpty()
            if (primary.isBlank()) null else VocabEntry(clean, primary, alternate)
        }
        if (vocabCodes.isEmpty()) return text

        // High-risk onsets — null means no filter (accept any onset).
        val highRiskOnsets: Set<String>? = if (onsetWeights.isNotEmpty()) {
            onsetWeights.filter { it.value > HIGH_RISK_THRESHOLD }.keys
        } else {
            null
        }

        // Tokenize preserving whitespace/punctuation so we can rebuild losslessly.
        val tokenRe = Regex("""(\p{L}+(?:'\p{L}+)?)|([^\p{L}]+)""")
        val sb = StringBuilder()
        var swapCount = 0

        tokenRe.findAll(text).forEach { match ->
            val word = match.groupValues[1]
            val filler = match.groupValues[2]
            if (word.isNotEmpty()) {
                val swap = maybeSwap(word, vocabCodes, highRiskOnsets)
                if (swap != null) {
                    sb.append(preserveCasing(word, swap))
                    swapCount++
                    Log.i(TAG, "Phonetic swap: '$word' → '$swap'")
                } else {
                    sb.append(word)
                }
            } else {
                sb.append(filler)
            }
        }

        return if (swapCount > 0) sb.toString() else text
    }

    /** Best phonetic match in [vocabCodes], or null if none / already in vocab. */
    private fun maybeSwap(
        asrWord: String,
        vocabCodes: List<VocabEntry>,
        highRiskOnsets: Set<String>?
    ): String? {
        val clean = asrWord.lowercase()
        if (clean.length < MIN_WORD_LENGTH) return null

        val onset = PhoneticRisk.extractOnset(clean)
        if (highRiskOnsets != null && onset !in highRiskOnsets) return null

        val asrPrimary = dm.doubleMetaphone(clean).orEmpty()
        val asrAlternate = dm.doubleMetaphone(clean, true).orEmpty()
        if (asrPrimary.isBlank()) return null

        for (entry in vocabCodes) {
            // Already identical (case-insensitive) — no swap needed.
            if (entry.original.equals(asrWord, ignoreCase = true)) return null

            val codeMatch = asrPrimary == entry.primary
                || (asrAlternate.isNotBlank() && asrAlternate == entry.primary)
                || (entry.alternate.isNotBlank() && asrPrimary == entry.alternate)
            if (codeMatch) return entry.original
        }
        return null
    }

    /** Match casing: all-lower / all-upper / other. For "other" keep vocab form. */
    private fun preserveCasing(original: String, replacement: String): String = when {
        original.all { it.isLowerCase() } -> replacement.lowercase()
        original.all { it.isUpperCase() } -> replacement.uppercase()
        else -> replacement
    }
}
