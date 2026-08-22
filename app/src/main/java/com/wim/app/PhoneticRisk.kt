package com.wim.app

/**
 * Phonetic risk prediction using Brown's 4-feature model.
 *
 * Ported from Лаврентий predict_phonetic_risk() and predict_triggers_in_text().
 *
 * Brown (1945) identified 4 features that predict word difficulty for PWS:
 *   1. Initial consonant (plosives, affricates, clusters are hardest)
 *   2. Content words vs function words (content words harder)
 *   3. Word length (longer = harder)
 *   4. Sentence position (earlier in clause = harder)
 */
object PhoneticRisk {

    // English baseline — used as fallback when the user's language has no published
    // phoneme-difficulty research or when a language code is unknown.
    private val HIGH_RISK_ONSETS_EN = mapOf(
        // Unvoiced plosives (hardest)
        "p" to 0.85, "t" to 0.80, "k" to 0.85, "c" to 0.85,
        // Voiced plosives
        "b" to 0.70, "d" to 0.65, "g" to 0.70,
        // Affricates
        "ch" to 0.75, "j" to 0.70,
        // Clusters at onset
        "bl" to 0.80, "br" to 0.80, "cl" to 0.80, "cr" to 0.80,
        "dr" to 0.75, "fl" to 0.70, "fr" to 0.70, "gl" to 0.75,
        "gr" to 0.75, "pl" to 0.85, "pr" to 0.85, "sc" to 0.75,
        "sk" to 0.75, "sl" to 0.70, "sm" to 0.70, "sn" to 0.70,
        "sp" to 0.80, "st" to 0.80, "str" to 0.90, "sw" to 0.70,
        "tr" to 0.80, "tw" to 0.75, "thr" to 0.85, "shr" to 0.80,
        "scr" to 0.90, "spl" to 0.90, "spr" to 0.90
    )

    // Function words (lower risk — not content words)
    private val FUNCTION_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "so", "if", "when",
        "is", "are", "was", "were", "be", "been", "am", "do", "does",
        "did", "have", "has", "had", "can", "could", "will", "would",
        "to", "of", "in", "on", "at", "by", "for", "with", "from",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
        "my", "your", "his", "its", "our", "their", "this", "that", "these", "those"
    )

    /**
     * Onset table for the requested language, merging cited lang-pack data with the
     * English baseline so familiar clusters still score when the speaker is bilingual.
     * Languages whose pack flags `quality_note: unavailable` (zh/hi/ar/ko) fall back
     * to the English baseline.
     */
    fun highRiskOnsets(languageCode: String): Map<String, Double> {
        val normalized = LangPackHelper.normalizeLangCode(languageCode)
        if (normalized == "en") return HIGH_RISK_ONSETS_EN
        val fromPack = LangPackHelper.getLangHardOnsets(normalized)
        if (fromPack.isEmpty()) return HIGH_RISK_ONSETS_EN
        return HIGH_RISK_ONSETS_EN + fromPack
    }

    // PhoneticRisk is a pure-logic scorer; it must not hard-crash if it runs
    // before WimApp.prefs is initialized (unit tests, or any early-boot call).
    // Default to the English build's language when prefs aren't available.
    private fun activeLanguage(): String =
        try { WimApp.appLanguage() } catch (_: Throwable) { "en" }

    /**
     * Extract the initial onset (1-3 chars of consonants) from a word, using the
     * onset table for [languageCode]. Falls back to the active user language when omitted.
     */
    fun extractOnset(word: String, languageCode: String? = null): String {
        val lower = word.lowercase()
        if (lower.isEmpty()) return ""
        val table = highRiskOnsets(languageCode ?: activeLanguage())
        for (len in 3 downTo 1) {
            if (lower.length >= len) {
                val candidate = lower.substring(0, len)
                if (table.containsKey(candidate)) return candidate
            }
        }
        return lower.substring(0, 1)
    }

    /**
     * Score phonetic risk for a word (0.0 = easy, 1.0 = hard).
     * Combines: onset difficulty, word length, content vs function, sentence position.
     */
    fun predictPhoneticRisk(
        word: String,
        sentencePosition: Int? = null,
        sentenceLength: Int? = null,
        personalOnsetWeights: Map<String, Double> = emptyMap(),
        languageCode: String? = null
    ): Double {
        val clean = word.lowercase().trim()
        if (clean.length < 2) return 0.0

        val lang = languageCode ?: activeLanguage()
        val langTable = highRiskOnsets(lang)
        val onset = extractOnset(clean, lang)
        var risk = personalOnsetWeights[onset] ?: langTable[onset] ?: 0.3

        if (clean in FUNCTION_WORDS) {
            risk *= 0.4
        }

        val lengthBoost = (clean.length / 10.0).coerceAtMost(0.2)
        risk = (risk + lengthBoost).coerceAtMost(1.0)

        if (sentencePosition != null && sentenceLength != null && sentenceLength > 0) {
            val positionFrac = sentencePosition.toDouble() / sentenceLength
            if (positionFrac < 0.2) {
                risk = (risk + 0.15).coerceAtMost(1.0)
            }
        }

        return risk
    }

    /**
     * Score all content words in text, return predicted triggers above threshold.
     * Returns list of (word, risk) sorted by risk DESC.
     *
     * Ported from lavrentiy.py predict_triggers_in_text().
     */
    fun predictTriggersInText(
        text: String,
        existingTriggers: List<String> = emptyList(),
        personalOnsetWeights: Map<String, Double> = emptyMap(),
        threshold: Double = 0.6,
        languageCode: String? = null
    ): List<Pair<String, Double>> {
        val lang = languageCode ?: activeLanguage()
        val words = Regex("\\b\\w+\\b").findAll(text).map { it.value }.toList()
        val known = existingTriggers.map { it.lowercase() }.toSet()
        val predicted = mutableListOf<Pair<String, Double>>()

        for ((i, word) in words.withIndex()) {
            val clean = word.lowercase()
            if (clean.length < 2) continue

            var risk = predictPhoneticRisk(clean, i, words.size, personalOnsetWeights, lang)

            // Onset boost: shared first 2 letters with known trigger
            if (clean.length >= 2) {
                val onset2 = clean.substring(0, 2)
                for (trigger in known) {
                    if (trigger.length >= 2 && trigger.substring(0, 2) == onset2 && clean != trigger) {
                        risk = (risk + 0.2).coerceAtMost(1.0)
                        break
                    }
                }
            }

            if (risk >= threshold) {
                predicted.add(clean to ((risk * 100).toInt() / 100.0))
            }
        }

        return predicted.sortedByDescending { it.second }
    }
}
