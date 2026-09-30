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
    // "www." is not a stretched w — it turned "www.FEMA.gov" into
    // "w.FEMA.gov" on 2026-09-29 11:41.
    private val PROLONGATION = Regex(
        """(?!www\.)([A-Za-z\u0400-\u04FF])\1{2,200}""",
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

    // Whisper also emits caption markers in ROUND brackets — "(chuckles).",
    // "(mumbling).", "(muffled speaking)" — observed on-device 2026-08-04,
    // three junk sessions in a row on the local L1 route. The bracket regex
    // above never matched them, so they pasted straight into the user's
    // field. Vocabulary is a closed list of caption stage-directions, so
    // legitimate parenthesized prose ("(see attached)") is never touched.
    private val PAREN_MARKER = Regex(
        """(?i)\((?:chuckles?|chuckling|laughs?|laughing|laughter|mumbles?|mumbling|""" +
            """muffled(?:\s+speaking)?|sighs?|sighing|coughs?|coughing|sneezes?|groans?|""" +
            """music|applause|silence|inaudible|indistinct(?:\s+chatter)?|static|""" +
            """speaking\s+in\s+foreign\s+language|foreign\s+language|breathing|snoring)\)[.,!?]*"""
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
            CAPTION_ARTIFACT.replace(
                PAREN_MARKER.replace(SYSTEM_MARKER.replace(text, ""), ""), " "
            ), " "
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
    fun stripBlockHallucinations(
        text: String,
        lowConfSegments: List<WhisperClient.LowConfidenceSegment>,
        log: (String) -> Unit = {}
    ): String {
        if (text.isBlank()) return text
        var result = CAPTION_ARTIFACT.replace(
            PAREN_MARKER.replace(SYSTEM_MARKER.replace(text, ""), ""), " "
        )
        for (seg in lowConfSegments) {
            if (!seg.blockSuspect) continue
            val segText = seg.text.trim()
            // Only strip short hallucinations (< 5 words) — longer segments are probably real
            val wordCount = segText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            if (wordCount >= 5) continue
            if (segText.isBlank()) continue
            // Strip this segment's text — but only when the transcript contains
            // it once. Regex.replace removes EVERY match, and Whisper reports a
            // suspect segment by time, not by character offset, so there is no
            // way to tell which copy it meant. A speaker who says "the report"
            // twice, or "okay" twice, lost both to one flagged segment. Short
            // phrases are exactly the ones that repeat, and this runs before
            // the filter and before every guard, so nothing downstream could
            // see the loss.
            // Letter/digit lookarounds, not \b: a suspect that ends in
            // punctuation ("R.") has no word boundary after the period, so
            // \b never matched and the flagged "R." was pasted (2026-09-30
            // 11:33, and every silent take before it).
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(segText)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            )
            val hits = pattern.findAll(result).take(2).count()
            when (hits) {
                0 -> {}
                1 -> result = pattern.replace(result, "")
                // Plain callback, not android.util.Log — this branch is exactly
                // the one JVM unit tests exercise, and an unmocked Log.i throws
                // there. Same shape as L1Pipeline's log parameter.
                else -> log(
                    "block-suspect \"$segText\" appears more than once — leaving it, " +
                        "cannot tell which copy Whisper meant"
                )
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

        // Step 4: Strip filler words.
        //
        // Two passes, because step 4 used to split on whitespace and compare
        // single tokens — so a filler made of two words could never equal a
        // one-word token and was silently inert. "you know" and "i mean" are
        // in DEFAULT_FILLERS and had never once been stripped, along with 9
        // multi-word entries across the Spanish, French, Portuguese and
        // Russian packs ("o sea", "en fait", "как бы").
        //
        // It only showed at L1. From L2 up the prompt carries "Strip these
        // fillers: ..." and the model handles a phrase without help, which is
        // why the gap survived: the layer with no model was the only one
        // affected, and it is also the layer with no guard to notice.
        val allFillers = fillerWords.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        for (phrase in allFillers.filter { it.contains(' ') }.sortedByDescending { it.length }) {
            // Whole phrase only, and never when it is the entire utterance —
            // the same rule the single-word pass below applies.
            if (cleaned.trim().equals(phrase, ignoreCase = true)) continue
            val re = Regex(
                """\b""" + Regex.escape(phrase).replace("\\ ", """\s+""") + """\b[,]?""",
                RegexOption.IGNORE_CASE
            )
            cleaned = re.replace(cleaned, " ")
        }
        cleaned = MULTI_SPACE.replace(cleaned, " ").trim()

        val fillerSet = allFillers.toSet()
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

        // Repair punctuation orphaned by every strip above.
        cleaned = tidyPunctuation(cleaned)

        // Don't return empty — fall back to original
        return cleaned.ifBlank { text }
    }

    // ── Whisper junk gates (2026-09-30) ──────────────────────────────
    // Five of 201 takes over 09-29/09-30 pasted garbage. Each gate below is
    // one of them; the log file names the take.

    /**
     * Drop every segment whose gzip compression ratio passed 2.4 — Whisper's
     * own "failed decode" mark for the repetition loop. 09-30 07:20: a 4 s
     * take came back as "1, 1, 1, ..." x112 and was pasted (336 chars). The
     * insane-run cap skips digits on purpose, so this is the gate that
     * catches a numeric loop.
     */
    fun stripLoopSegments(
        text: String,
        segments: List<WhisperClient.LowConfidenceSegment>,
        log: (String) -> Unit = {}
    ): String {
        if (text.isBlank()) return text
        var result = text
        for (seg in segments) {
            if (!seg.loop) continue
            val segText = seg.text.trim()
            if (segText.isBlank()) continue
            val before = result
            result = result.replaceFirst(Regex(Regex.escape(segText), RegexOption.IGNORE_CASE), "")
            if (result != before) {
                log(
                    "Whisper loop segment dropped (ratio ${"%.1f".format(seg.compressionRatio)}): " +
                        "'${segText.take(60)}'"
                )
            }
        }
        return MULTI_SPACE.replace(result, " ").trim()
    }

    // A web address: "www.x", "http…", or "name.com/.org/.gov/…".
    private const val DOMAIN =
        """(?:https?://\S+|www\.\S+|[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.(?:com|org|gov|net|edu|io|ai)\b\S*)"""

    // "… good for you. Go to Beadaholique.com for all of your beading supply
    // needs!" (09-29 10:23). A sentence that opens with an advert verb and
    // carries a domain, glued after a finished sentence.
    private val TRAILING_AD_SENTENCE = Regex(
        """([.!?]["')\]]*)\s+(?:go to|visit|check out|for more|learn more|find out more|head over to|shop at|order at)\b[^.!?]*$DOMAIN[^.!?]*[.!?]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    // "… You tell me. www.FEMA.gov" (09-29 11:41). A bare domain as the
    // final token after a finished sentence.
    private val TRAILING_BARE_URL = Regex(
        """([.!?]["')\]]*)\s+$DOMAIN[.!?]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Cut an advert or bare web address that Whisper glued onto the end of a
     * finished take. Both patterns need a sentence end BEFORE the junk, so a
     * take that IS a web address ("go to my baking creations dot com") stays.
     */
    fun stripJunkTail(text: String, log: (String) -> Unit = {}): String {
        if (text.isBlank()) return text
        var result = text.trim()
        for (regex in listOf(TRAILING_AD_SENTENCE, TRAILING_BARE_URL)) {
            val m = regex.find(result) ?: continue
            val cut = result.substring(m.range.first + m.groupValues[1].length).trim()
            result = result.substring(0, m.range.first + m.groupValues[1].length).trim()
            log("Whisper junk tail stripped: '${cut.take(80)}'")
        }
        return result
    }

    // One letter, optionally punctuated, repeated any number of times and
    // nothing else: "R.", "R. R. R. R. R. R. R."
    private val LONE_LETTER_RUN = Regex(
        """^\s*([\p{L}])[.,!?]*(?:\s+\1[.,!?]*)*\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Whisper's answer to silence on this mic is the letter R (09-28 15:06,
     * 09-29 14:29 and 14:33, 09-30 11:33). When the whole transcript is one
     * letter repeated AND Whisper flagged a no-speech segment, there was no
     * speech. The flag is required so a real one-letter answer survives.
     */
    fun stripLoneLetterNoise(
        text: String,
        segments: List<WhisperClient.LowConfidenceSegment>,
        log: (String) -> Unit = {}
    ): String {
        if (text.isBlank()) return text
        if (!segments.any { it.blockSuspect }) return text
        if (!LONE_LETTER_RUN.matches(text)) return text
        log("Whisper lone-letter noise dropped: '${text.take(40)}'")
        return ""
    }

    // Sanity cap for Whisper's repetition-loop failure. Live 2026-08-05
    // 14:06: the speaker demonstrated a repetition, Whisper extended it to
    // ~112 copies of "to", and L4 — which skips filter() on purpose so the
    // model can SEE disfluencies — pasted the avalanche. No real disfluency
    // repeats a word this many times (same reasoning as WORD_REPEAT's cap);
    // beyond [INSANE_RUN_KEEP] copies it is ASR garbage. The cap keeps the
    // first eight — evidence of the repetition survives for the clinical
    // prompt — and drops the rest. Tail bounded at 40 with iterative
    // collapse, same stack-safety pattern as WORD_REPEAT. Numeric runs are
    // untouched: digits are data.
    private const val INSANE_RUN_KEEP = 8
    private val INSANE_RUN = Regex(
        """\b(\w+)((?:[,;:]?\s+\1\b){7})(?:[,;:]?\s+\1\b){1,40}[,;:]?""",
        RegexOption.IGNORE_CASE
    )

    /** Collapse runs of more than [INSANE_RUN_KEEP] identical tokens down to
     *  [INSANE_RUN_KEEP]. Applied at EVERY layer, including L4's unfiltered
     *  path — this is corruption control, not disfluency cleaning. */
    fun capInsaneRuns(text: String): String {
        if (text.isBlank()) return text
        var out = text
        repeat(8) {
            val next = INSANE_RUN.replace(out) { m ->
                if (isNumericToken(m.groupValues[1])) m.value
                else m.groupValues[1] + m.groupValues[2]
            }
            if (next == out) return out
            out = next
        }
        return out
    }

    /**
     * Fillers that are also ordinary English: the verb "like", the phrase
     * "you know" as subject+verb, "I mean" as a statement opener. A plain
     * word-list strip eats the real ones — "I like how it looks" became
     * "I how it looks" and "You know what I want" became "what I want",
     * both observed 2026-08-04. Deterministic code cannot tell the two
     * apart from text alone; the clock can.
     */
    val RISKY_FILLERS = setOf("like", "you know", "i mean", "so", "well", "right")

    /**
     * Strip [RISKY_FILLERS] only where they open a sentence.
     *
     * The clock-broom above is the good tool and it needs word timings, which
     * only whisper-1 returns. Everywhere else these six words were handed to
     * the naive word-list pass, which cannot tell a discourse marker from the
     * word itself. Measured on device 2026-08-22: "Is everything going well
     * here?" arrived as "Is everything going here?", and "regardless of layer,
     * right?" as "regardless of layer," — which then collected a full stop from
     * Repunctuate and reached the user as "layer,.".
     *
     * Position is the signal the clock is standing in for. A speaker filling
     * time does it at the top of a clause — "So, back to the thing", "Well, I
     * think" — while the same word inside a clause is doing work: "going well",
     * "the right one", "so tired". So: strip only at the start of the text or
     * immediately after . ! ?, AND only when a comma follows. The comma is what
     * separates "So, back to the thing" from "So tired" — a speaker who pauses
     * long enough for the ASR to punctuate was filling time. Requiring it means
     * some fillers survive; that is the trade taken deliberately, since from L2
     * up the model strips the leftovers and at L1 an extra "well" is cheaper
     * than a missing one.
     *
     * Conservative by construction — a missed filler is a word the reader
     * skims past, a deleted real word changes what was said.
     */
    fun stripLeadingMarkers(text: String, fillers: Set<String> = RISKY_FILLERS): String {
        if (text.isBlank()) return text
        var out = text
        for (phrase in fillers.sortedByDescending { it.length }) {
            val re = Regex(
                """(^|[.!?]\s+)""" + Regex.escape(phrase).replace("\\ ", """\s+""") + """\s*,\s*""",
                RegexOption.IGNORE_CASE,
            )
            out = re.replace(out) { m -> m.groupValues[1] }
        }
        return MULTI_SPACE.replace(out, " ").trim().ifBlank { text }
    }

    /**
     * Repair punctuation left stranded by a removed word.
     *
     * Every strip in this file replaces a token with a space and leaves the
     * punctuation that was attached to it. "…of layer, right?" loses "right?"
     * and keeps the comma, so the sentence ends on a comma; Repunctuate then
     * appends a full stop and the user reads "layer,.". The same removal
     * mid-sentence leaves ", ," and " ." behind.
     *
     * Runs last, after every other pass, and only touches punctuation.
     */
    fun tidyPunctuation(text: String): String {
        if (text.isBlank()) return text
        var out = text
        out = Regex("""\s+([,.;:!?])""").replace(out, "$1")      // " ,"  -> ","
        out = Regex("""([,;:])\s*([,;:])+""").replace(out, "$1")  // ",,"  -> ","
        out = Regex("""[,;:]+([.!?])""").replace(out, "$1")        // ",."  -> "."
        out = Regex("""([.!?])\1+""").replace(out, "$1")          // ".."  -> "."
        out = Regex("""[,;:]+\s*$""").replace(out, "")            // trailing comma
        return MULTI_SPACE.replace(out, " ").trim().ifBlank { text }
    }

    // A filler is a hesitation: the speaker pauses, drops the token in,
    // pauses again. Meaning runs tight into its neighbors. 350 ms is well
    // above whisper-1's word-boundary jitter and well below a real
    // hesitation pause.
    private const val FILLER_PAUSE_S = 0.35

    private fun tok(s: String) = s.lowercase().trim(
        '.', ',', '!', '?', ';', ':', '"', '’', '\'', '(', ')'
    )

    /**
     * Clock-broom for the risky fillers, cloud-L1 only. Uses whisper-1
     * word timestamps: an occurrence is stripped ONLY when a real pause
     * (>= [FILLER_PAUSE_S]) sits on BOTH sides of it (utterance edges
     * count as pauses). Callers must also remove [RISKY_FILLERS] from the
     * word-list they hand to [filter], or the naive pass eats what this
     * one protected.
     *
     * Occurrences are matched k-th-regex-hit to k-th-token-run; both scan
     * the same word sequence left to right. When the timing stream and
     * the text disagree (Whisper trims tokens differently), the occurrence
     * is KEPT — deleting a real word is the failure mode this exists to
     * stop, a hallucinated filler is what L2+ already tolerates.
     */
    fun stripTimedFillers(
        text: String,
        timedWords: List<WhisperClient.TimedWord>,
        log: (String) -> Unit = {}
    ): String {
        if (text.isBlank() || timedWords.isEmpty()) return text
        val tokens = timedWords.map { tok(it.text) }
        var result = text
        for (phrase in RISKY_FILLERS) {
            val phraseToks = phrase.split(' ')
            // Every token-run start index for this phrase, in time order.
            val runs = (0..tokens.size - phraseToks.size).filter { i ->
                phraseToks.indices.all { j -> tokens[i + j] == phraseToks[j] }
            }
            if (runs.isEmpty()) continue
            val re = Regex(
                """\b""" + Regex.escape(phrase).replace("\\ ", """\s+""") + """\b[,]?""",
                RegexOption.IGNORE_CASE
            )
            var k = 0
            result = re.replace(result) { m ->
                val run = runs.getOrNull(k++)
                if (run == null) {
                    m.value // more text hits than timed runs — keep, don't guess
                } else {
                    val first = timedWords[run]
                    val last = timedWords[run + phraseToks.size - 1]
                    val gapBefore =
                        if (run == 0) FILLER_PAUSE_S
                        else first.startS - timedWords[run - 1].endS
                    val gapAfter =
                        if (run + phraseToks.size >= timedWords.size) FILLER_PAUSE_S
                        else timedWords[run + phraseToks.size].startS - last.endS
                    if (gapBefore >= FILLER_PAUSE_S && gapAfter >= FILLER_PAUSE_S) {
                        log(
                            "timed filler \"$phrase\" stripped " +
                                "(%.2fs / %.2fs pause)".format(gapBefore, gapAfter)
                        )
                        " "
                    } else m.value
                }
            }
        }
        return MULTI_SPACE.replace(result, " ").trim().ifBlank { text }
    }
}
