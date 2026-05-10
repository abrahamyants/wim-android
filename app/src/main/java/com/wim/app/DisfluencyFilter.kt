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

    // Natural repeats — grammatically valid repeated words that should NOT be stripped
    // Ported from Лаврентий NATURAL_REPEATS set (including the eval-build extensions
    // that protect emphatic doublings: "really really", "many many", etc.)
    private val NATURAL_REPEATS = setOf(
        "had had", "that that", "is is", "was was", "do do",
        "can can", "no no", "bye bye", "so so", "very very",
        "go go", "now now", "come come", "well well",
        "out out", "boo boo", "ha ha", "ho ho",
        "knock knock", "tsk tsk", "aye aye",
        // Emphatic doublings (eval-build additions from Лаврентий)
        "really really", "many many", "much much", "right right",
        "sure sure", "okay okay", "just just",
        // Russian
        "да да", "нет нет", "ну ну"
    )

    // Step 1a: Stutter fragments with whitespace — "p- p- pop" -> "pop",
    // "s- stop" -> "stop", "be- be- become" -> "become".
    private val STUTTER_FRAGMENT = Regex(
        """(\b\w+)-\s+(?:\1-\s+)*""",
        RegexOption.IGNORE_CASE
    )

    // Step 1b: Stutter fragments without whitespace — "w-w-want" -> "want",
    // "m-m-m-meeting" -> "meeting". Requires at least one repeat so legitimate
    // hyphenated words ("pre-existing", "well-known", "re-record") don't match.
    private val STUTTER_FRAGMENT_NOSPACE = Regex(
        """(\b\w+)-(?:\1-)+""",
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

    // Step 5: False starts — "I was going to-- I went", "I was going to... I went",
    // "I was going to— I went". Accepts 2+ ASCII hyphens, 3+ periods, or a single
    // em-dash/en-dash. The following capital-letter lookahead still guards against
    // prose em-dashes mid-sentence.
    private val FALSE_START = Regex(
        """[A-Za-z\u0400-\u04FF][^.!?]*?(?:[-]{2,}|\.{3,}|[\u2014\u2013])\s*(?=[A-Z\u0410-\u042F])""",
        RegexOption.DOT_MATCHES_ALL
    )

    // Step 6: Prolongations — "mmmmaybe" -> "maybe", "sssso" -> "so", "yessss" -> "yes",
    // "heyyyy" -> "hey". Match 3+ consecutive identical characters anywhere in a token
    // and collapse to one. Common double letters ("success", "Mississippi") stay intact
    // because the regex demands 3+ in a row.
    private val PROLONGATION = Regex(
        """(\w)\1{2,}""",
        RegexOption.IGNORE_CASE
    )

    // Multi-space collapse
    private val MULTI_SPACE = Regex("""\s{2,}""")

    /**
     * Strip short hallucinated filler phrases from segments Whisper flagged
     * as block suspects (high no_speech_prob). These are usually words like
     * "thank you", "okay", "so" that Whisper fabricates during silent blocks.
     *
     * Ported from Лаврентий strip_block_hallucinations(). Apply at ALL layers
     * when Whisper confidence data is available.
     *
     * @param text full transcription
     * @param lowConfSegments from WhisperClient — segments with avgLogprob, noSpeechProb, blockSuspect
     * @return text with block-suspect hallucinated phrases removed
     */
    fun stripBlockHallucinations(text: String, lowConfSegments: List<WhisperClient.LowConfidenceSegment>): String {
        if (text.isBlank() || lowConfSegments.isEmpty()) return text
        var result = text
        for (seg in lowConfSegments) {
            if (!seg.blockSuspect) continue
            val segText = seg.text.trim()
            // Only strip short hallucinations (< 5 words) — longer segments are probably real
            val wordCount = segText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            if (wordCount >= 5) continue
            if (segText.isBlank()) continue
            // Escape for regex and strip this segment's text
            val pattern = Regex("\\b${Regex.escape(segText)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, "")
            }
        }
        // Collapse multiple spaces left by removals
        return MULTI_SPACE.replace(result, " ").trim()
    }

    /**
     * Strip disfluencies from raw ASR text.
     *
     * @param text raw speech-to-text output
     * @param fillerWords list of filler words to strip (from profile)
     * @return cleaned text with disfluencies removed
     */
    fun filter(
        text: String,
        fillerWords: List<String>,
        naturalRepeats: Set<String>? = null
    ): String {
        if (text.isBlank()) return text

        // When the user is dictating in a non-English language, LangPack supplies
        // the matching natural-repeat allow-list (e.g. "sí sí", "そうそう"). English
        // falls back to the hardcoded NATURAL_REPEATS set.
        val activeNaturalRepeats = naturalRepeats ?: NATURAL_REPEATS

        // Step 1: Remove stutter fragments (two passes — with and without whitespace)
        var cleaned = STUTTER_FRAGMENT.replace(text, "")
        cleaned = STUTTER_FRAGMENT_NOSPACE.replace(cleaned, "")

        // Step 2: Remove consecutive word repetitions (protect natural repeats).
        // Natural repeats ("bye bye", "go go", "sí sí") are valid at exactly 2
        // occurrences; 3+ means the speaker was blocked, not being idiomatic.
        cleaned = WORD_REPEAT.replace(cleaned) { match ->
            val word = match.groupValues[1]
            val pair = "${word.lowercase()} ${word.lowercase()}"
            val totalCount = match.value.trim().split("\\s+".toRegex()).size
            if (pair in activeNaturalRepeats && totalCount == 2) match.value else word
        }

        // Step 3: Remove phrase repetitions — but skip phrases that are just
        // the same word duplicated, which word-repeat already handled correctly.
        cleaned = PHRASE_REPEAT.replace(cleaned) { match ->
            val phrase = match.groupValues[1]
            val words = phrase.trim().split("\\s+".toRegex()).map { it.lowercase() }
            if (words.toSet().size == 1) match.value else phrase
        }

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

        // Step 6: Strip prolongations — "mmmmaybe" -> "maybe", "yessss" -> "yes"
        cleaned = PROLONGATION.replace(cleaned) { it.groupValues[1] }

        // Collapse multiple spaces
        cleaned = MULTI_SPACE.replace(cleaned, " ").trim()

        // Don't return empty — fall back to original
        return cleaned.ifBlank { text }
    }
}
