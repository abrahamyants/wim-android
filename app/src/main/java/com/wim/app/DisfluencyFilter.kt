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
    //
    // The trailing word is captured and the fragment is only dropped when it is
    // a prefix of that word, which is what makes a fragment a fragment: you
    // block on the onset of the word you are trying to say. Without that check
    // the `{0,40}` matched a single hyphen-then-space with no repetition at
    // all, so "co- founder" became "founder" and "pre- existing" became
    // "existing" — the speaker's word deleted outright. The sibling regex below
    // already guards the same hazard by requiring a repeat, and its comment
    // spells out why; this one was left open.
    //
    // L1 is where this bites hardest: no reconstruction, no meaning guard,
    // nothing comparing output to input. Whatever this emits is pasted.
    private val STUTTER_FRAGMENT = Regex(
        """(\b\w+)-\s+((?:\1-\s+){0,40})(\w+)""",
        RegexOption.IGNORE_CASE
    )

    // Step 1b: Stutter fragments without whitespace — "w-w-want" -> "want",
    // "m-m-m-meeting" -> "meeting". Requires at least one repeat so legitimate
    // hyphenated words ("pre-existing", "well-known", "re-record") don't match.
    private val STUTTER_FRAGMENT_NOSPACE = Regex(
        """(\b\w+)-(?:\1-){1,40}""",
        RegexOption.IGNORE_CASE
    )

    // Step 2: Consecutive word repetitions — "I I I want" -> "I want".
    // The repeat count is capped at 40 (not unbounded +): an open backreference
    // quantifier makes java.util.regex recurse once per repeat, so a pathological
    // ASR repetition loop (hundreds of identical tokens, which Whisper hallucinates
    // on silence/noise) overflows the stack. No real disfluency repeats one word
    // 40+ times; large runs simply collapse over successive matches instead.
    // The separator allows an optional comma / semicolon / colon before the
    // whitespace. Cloud Whisper punctuates as it transcribes and returns
    // "I, I, I need to reschedule"; the local whisper-tiny engine returns bare
    // "i i i need to reschedule". With a whitespace-only separator the comma
    // form never matched, so the SAME utterance came out clean on the local
    // route and still stuttering on the cloud route — measured on device
    // 2026-07-29. That is a parity break for every cloud-ASR user, not just a
    // test artifact.
    private val WORD_REPEAT = Regex(
        """\b(\w+)(?:[,;:]?\s+\1){1,40}\b[,;:]?""",
        RegexOption.IGNORE_CASE
    )

    // A token that carries digits is data, never a disfluency. Nobody stutters
    // in numerals: once the ASR has rendered "5", a following "5" is the next
    // digit of an account, PIN, phone number or zip — not a repetition to
    // collapse. Steps 1, 2 and 3 all use `\w`, which includes digits, so each
    // one had to be taught this separately.
    private fun isNumericToken(token: String): Boolean =
        token.any { it.isDigit() }

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
    // LETTERS ONLY. `\w` includes digits, and a prolongation collapse over
    // digits destroys amounts: "wire 1000 dollars" became "wire 10 dollars",
    // "the balance is 2000" became "20", "account 1,000" became "1,0".
    // This runs inside filter(), which is applied at L1, L2 and L3 — and it
    // runs BEFORE the numeric guard sees the text, so computeRiskFlags compared
    // an already-mangled raw against the output and could never flag it. At L1
    // there is no model and no guard at all: the altered number went straight
    // into the message. Cyrillic range matches the FALSE_START convention above.
    private val PROLONGATION = Regex(
        """([A-Za-z\u0400-\u04FF])\1{2,200}""",
        RegexOption.IGNORE_CASE
    )

    // Multi-space collapse
    private val MULTI_SPACE = Regex("""\s{2,}""")

    // Whisper-family engines sometimes return caption/system markers instead
    // of speech. Local L1 does not always provide confidence segments, so these
    // exact markers must be removed independently of confidence metadata.
    // The second branch accepts a missing closing bracket only at end-of-text;
    // this catches observed output such as "[BLANK_AUDIO" without damaging
    // legitimate bracketed prose such as "[Music Hall]".
    private val SYSTEM_MARKER = Regex(
        """(?i)\[(?:blank[_ ]audio|inaudible|music|applause|silence|no[_ ]audio|laughter)\]|""" +
            """\[(?:blank[_ ]audio|inaudible|music|applause|silence|no[_ ]audio|laughter)\s*$"""
    )

    /**
     * Caption boilerplate Whisper emits when it decodes silence — training-data
     * leakage from subtitled video. For this user, silence usually means a
     * block, so these phrases appear exactly where a word failed to come out.
     *
     * Deliberately narrower than [MeaningGuard]'s detection regex. That one may
     * flag a bare "subscribe" because a false positive there only triggers a
     * retry; here a false positive DELETES text the speaker said. So only
     * multi-word caption formulas are stripped — never a bare "subscribe" or a
     * bare "thank you", both of which are ordinary speech.
     */
    private val CAPTION_ARTIFACT = Regex(
        """(?i)\b(?:thanks?\s+(?:you\s+)?for\s+watching|""" +
            """(?:don'?t\s+forget\s+to|please|like\s+and)\s+subscribe|""" +
            """transcribed\s+by[^.,;!?]*|subtitles?\s+by[^.,;!?]*|""" +
            """amara\.org|otter\.ai)\b[.,!?]*"""
    )

    /**
     * Remove caption boilerplate without needing Whisper confidence metadata.
     *
     * Used on the reconstruction-refusal path: when the meaning guard rejects
     * every candidate because the model kept inventing a replacement for a
     * blocked word, WiM falls back to the raw transcript — and that transcript
     * is exactly the one containing "thanks for watching". Pasting it hands the
     * user garbage in the name of not fabricating. Found 2026-07-29 in the
     * 15-case disfluency corpus (case 2).
     *
     * Returns the original text if stripping would empty it — an incomplete
     * sentence beats an empty paste.
     */
    fun stripCaptionArtifacts(text: String): String {
        if (text.isBlank()) return text
        // SYSTEM_MARKER as well as CAPTION_ARTIFACT: a block is silence, and
        // Whisper fills silence with "[BLANK_AUDIO]" as readily as with "thanks
        // for watching". This helper covered only the second, so a refusal on a
        // bracketed-marker transcript still pasted the bracket into the message.
        // stripBlockHallucinations has always removed both — but it needs the
        // Whisper confidence metadata that the refusal path does not carry.
        val stripped = MULTI_SPACE.replace(
            CAPTION_ARTIFACT.replace(SYSTEM_MARKER.replace(text, ""), " "), " "
        ).trim()
        return if (stripped.isBlank()) text else stripped
    }

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
        if (text.isBlank()) return text
        var result = CAPTION_ARTIFACT.replace(SYSTEM_MARKER.replace(text, ""), " ")
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
        var cleaned = STUTTER_FRAGMENT.replace(text) { m ->
            val fragment = m.groupValues[1]
            val word = m.groupValues[3]
            if (word.startsWith(fragment, ignoreCase = true)) word else m.value
        }
        cleaned = STUTTER_FRAGMENT_NOSPACE.replace(cleaned) { m ->
            if (isNumericToken(m.groupValues[1])) m.value else ""
        }

        // Step 2: Remove consecutive word repetitions (protect natural repeats).
        // Natural repeats ("bye bye", "go go", "sí sí") are valid at exactly 2
        // occurrences; 3+ means the speaker was blocked, not being idiomatic.
        cleaned = WORD_REPEAT.replace(cleaned) { match ->
            val word = match.groupValues[1]
            val pair = "${word.lowercase()} ${word.lowercase()}"
            val totalCount = match.value.trim().split("\\s+".toRegex()).size
            when {
                isNumericToken(word) -> match.value
                pair in activeNaturalRepeats && totalCount == 2 -> match.value
                else -> word
            }
        }

        // Step 3: Remove phrase repetitions — but skip phrases that are just
        // the same word duplicated, which word-repeat already handled correctly.
        cleaned = PHRASE_REPEAT.replace(cleaned) { match ->
            val phrase = match.groupValues[1]
            val words = phrase.trim().split("\\s+".toRegex()).map { it.lowercase() }
            when {
                words.any { isNumericToken(it) } -> match.value
                words.toSet().size == 1 -> match.value
                else -> phrase
            }
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
