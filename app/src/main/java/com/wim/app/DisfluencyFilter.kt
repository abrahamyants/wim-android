package com.wim.app

/**
 * Pre-reconstruction disfluency stripping.
 *
 * Ported from lavrentiy.py strip_disfluencies() — removes obvious
 * speech artifacts before GPT sees the text. For L1, this IS the
 * cleanup. For L2+ it pre-cleans before reconstruction.
 *
 * Handles:
 *   1. Stutter fragments:   "p- p- pop"          -> "pop"
 *   2. Word repetitions:    "I I I want"          -> "I want"
 *   3. Phrase repetitions:  "I want I want to go" -> "I want to go"
 *   4. Filler words:        "um I want uh a dog"  -> "I want a dog"
 *   5. False starts:        "I was going to-- I went to the store" -> "I went to the store"
 */
object DisfluencyFilter {

    // Step 1: Stutter fragments — "p- p- pop" -> "pop", "be- be- become" -> "become"
    private val STUTTER_FRAGMENT = Regex(
        """(\b\w+)-\s+(?:\1-\s+)*""",
        RegexOption.IGNORE_CASE
    )

    // Step 2: Consecutive word repetitions — "I I I want" -> "I want"
    private val WORD_REPEAT = Regex(
        """\b(\w+)(?:\s+\1)+\b""",
        RegexOption.IGNORE_CASE
    )

    // Step 3: Phrase repetitions (2-3 word phrases) — "I want I want to go" -> "I want to go"
    private val PHRASE_REPEAT = Regex(
        """\b(\w+\s+\w+(?:\s+\w+)?)\s+\1\b""",
        RegexOption.IGNORE_CASE
    )

    // Step 5: False starts — "I was going to-- I went" or "I was going to... I went"
    // Matches clause + dash/ellipsis + restart
    private val FALSE_START = Regex(
        """[A-Za-z\u0400-\u04FF][^.!?]*?[—\u2014\u2013-]{1,3}\s*(?=[A-Z\u0410-\u042F])""",
        RegexOption.DOT_MATCHES_ALL
    )

    // Multi-space collapse
    private val MULTI_SPACE = Regex("""\s{2,}""")

    /**
     * Strip disfluencies from raw ASR text.
     *
     * @param text raw speech-to-text output
     * @param fillerWords list of filler words to strip (from profile)
     * @return cleaned text with disfluencies removed
     */
    fun filter(text: String, fillerWords: List<String>): String {
        if (text.isBlank()) return text

        // Step 1: Remove stutter fragments
        var cleaned = STUTTER_FRAGMENT.replace(text, "")

        // Step 2: Remove consecutive word repetitions
        cleaned = WORD_REPEAT.replace(cleaned) { it.groupValues[1] }

        // Step 3: Remove phrase repetitions
        cleaned = PHRASE_REPEAT.replace(cleaned) { it.groupValues[1] }

        // Step 4: Strip filler words
        val fillerSet = fillerWords.map { it.lowercase() }.toSet()
        val words = cleaned.split("\\s+".toRegex())
        val filtered = mutableListOf<String>()

        for (w in words) {
            val wLower = w.lowercase().trimEnd('.', ',', '!', '?', ';', ':')
            if (wLower in fillerSet) {
                // Keep filler only if it's the entire utterance
                if (words.size == 1) {
                    filtered.add(w)
                }
                // Otherwise skip it
                continue
            }
            filtered.add(w)
        }

        cleaned = filtered.joinToString(" ").trim()

        // Step 5: Strip false starts (clause + em-dash + restart)
        cleaned = FALSE_START.replace(cleaned, "")

        // Collapse multiple spaces
        cleaned = MULTI_SPACE.replace(cleaned, " ").trim()

        // Don't return empty — fall back to original
        return cleaned.ifBlank { text }
    }
}
