package com.wim.app

/**
 * Deterministic meaning guard + Layer-2 rewrite loop for the device-direct
 * reconstruction path.
 *
 * Port of `lavrentiy/wim/api/meaning_guard.py` and the retry helpers in
 * `wim/api/prompt_builder.py` (commits d88f7fd, 6a0ad9a). The signed-in
 * backend (wim-reconstruct-00032-zit) already runs this logic server-side;
 * this port gives the device-direct path (giveaway builds, signed-out dev
 * builds) the same behavior. Keep the two in sync — if a rule changes in
 * meaning_guard.py, change it here.
 *
 * Two directions, not one: the guard catches anchors that DISAPPEAR (a name
 * the speaker said was dropped) and anchors that APPEAR (the reconstructor
 * filled a destroyed span with a confident, plausible, wrong value — the
 * "Henderson" failure). Loss is recoverable by falling back to raw text;
 * invention is not, because it reads as correct.
 */
object MeaningGuard {

    const val MAX_REWRITE_ATTEMPTS = 3

    data class GuardResult(
        val ok: Boolean,
        val lost: List<String>,
        val invented: List<String>,
        val unchanged: Boolean = false,
    )

    // ── Anchors worth protecting — keep byte-identical in intent to
    //    meaning_guard.py. If you change one, change both. ──

    private val CRITICAL_TOKEN_RE =
        Regex("""\b(?:\d+(?:[.,]\d+)?%?|\$?\d+(?:,\d{3})*(?:\.\d+)?)\b""")

    private val NEGATION_RE = Regex(
        """\b(?:no|not|never|neither|nor|without|cannot|can't|won't|don't|doesn't|""" +
            """didn't|isn't|aren't|wasn't|weren't|shouldn't|wouldn't|couldn't|mustn't)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val DATE_WORD_RE = Regex(
        """\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
            """january|february|march|april|may|june|july|august|september|october|""" +
            """november|december)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PROPER_NOUN_RE = Regex("""\b[A-Z][a-z]+\b""")

    /**
     * Honorifics and titles. Protected as their own anchor class because the
     * proper-noun check cannot see them: ASR output is lowercase, so "doctor
     * williams" contains no capitalised token, and dropping "doctor" leaves a
     * grammatical sentence that reads fine.
     *
     * Measured on-device 2026-07-29: "tell doctor williams about the
     * appointment" lost the title in 7 of 8 runs across every layer and tone.
     * In a medical or legal message that is a real change of meaning — who the
     * message is addressed to, and in what capacity.
     */
    private val TITLE_RE = Regex(
        """\b(?:doctor|dr|professor|prof|nurse|officer|detective|judge|attorney|counsel|""" +
            """reverend|father|sister|rabbi|imam|senator|governor|mayor|chief|captain|""" +
            """sergeant|lieutenant|colonel|general|admiral|principal|dean|coach|""" +
            """mr|mrs|ms|miss|sir|madam|ma'am)\b\.?""",
        RegexOption.IGNORE_CASE,
    )

    // Sentence-openers and auxiliaries that get capitalised by reconstruction
    // and would otherwise read as invented names. "Okay" is here on purpose —
    // a sentence-opening Okay is not a person (spec 2026-07-29).
    private val PROPER_NOUN_EXCLUDE = setOf(
        "A", "An", "And", "Are", "As", "At", "But", "By", "Can", "Could",
        "Do", "For", "From", "How", "I", "If", "In", "Is", "It", "My",
        "No", "Not", "Okay", "On", "Or", "Please", "Set", "Show", "Tell", "The",
        "This", "To", "Turn", "What", "When", "Where", "Which", "Who", "Why",
        "Will", "With", "Would", "You", "Your",
        "Actually", "After", "Also", "Any", "Be", "Been", "Before", "Both",
        "Did", "Does", "Every", "Get", "Give", "Going", "Had", "Has", "Have",
        "He", "Her", "Here", "His", "Just", "Let", "Like", "Make", "May",
        "Me", "Might", "Must", "Need", "Never", "Now", "Of", "Once", "One",
        "Only", "Our", "Out", "Over", "Say", "See", "She", "Should", "Since",
        "So", "Some", "Still", "Such", "Than", "That", "Their", "Them", "Then",
        "There", "These", "They", "Those", "Though", "Through", "Thus", "Too",
        "Under", "Until", "Up", "Us", "Was", "We", "Were", "While", "Yes",
        "Yet", "Its", "Whether",
    )

    // Spelled-out figures. Presence of any of these in the input means a
    // numeric token in the output is a reformatting, not an invention.
    private val NUMBER_WORD_RE = Regex(
        """\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|""" +
            """twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|""" +
            """twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|""" +
            """thousand|million|billion|half|quarter|dozen|""" +
            """first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Whisper's stock output when it is decoding silence. Presence in a
    // transcript means a span of the recording produced no speech — a block.
    private val HALLUCINATION_ARTIFACT_RE = Regex(
        """(thanks?\s+for\s+watching|thank\s+you\s+for\s+watching|""" +
            """don'?t\s+forget\s+to\s+subscribe|please\s+subscribe|\bsubscribe\b|""" +
            """transcribed\s+by|subtitles?\s+by|amara\.org|otter\.ai|""" +
            """\[BLANK_AUDIO\]|\[INAUDIBLE\]|\[MUSIC\])""",
        RegexOption.IGNORE_CASE,
    )

    // Function words reconstruction is free to add, remove or swap.
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "than", "that", "this",
        "these", "those", "there", "here", "is", "are", "was", "were", "be", "been",
        "being", "am", "do", "does", "did", "have", "has", "had", "will", "would",
        "can", "could", "shall", "should", "may", "might", "must", "to", "of", "in",
        "on", "at", "by", "for", "with", "from", "into", "about", "as", "so", "not",
        "no", "yes", "it", "its", "i", "you", "your", "we", "our", "they", "them",
        "their", "he", "she", "his", "her", "him", "me", "my", "mine", "us", "who",
        "what", "when", "where", "why", "how", "which", "just", "very", "really",
        "some", "any", "all", "both", "each", "more", "most", "other", "such",
        "only", "own", "same", "too", "up", "out", "down", "over", "under", "again",
        "get", "got", "go", "going", "went", "let", "like", "want", "need", "make",
        "made", "take", "took", "come", "came", "know", "think", "say", "said",
        "tell", "told", "please", "okay", "well", "now", "also", "still", "back",
    )

    private fun properNouns(text: String): List<String> =
        PROPER_NOUN_RE.findAll(text).map { it.value }
            .filter { it !in PROPER_NOUN_EXCLUDE }
            .toList()

    /** Meaning-carrying words: not function words, at least four characters. */
    private fun contentWords(text: String): List<String> =
        Regex("""[A-Za-z']{4,}""").findAll(text).map { it.value }
            .filter { it.lowercase() !in FUNCTION_WORDS }
            .toList()

    /**
     * True if `word` plausibly derives from something in the input. Prefix
     * comparison on the first five characters absorbs ordinary inflection
     * (prescription/prescriptions, call/called) without a stemmer.
     */
    private fun hasSource(word: String, rawLower: String): Boolean {
        val w = word.lowercase()
        if (w in rawLower) return true
        val stem = w.take(5)
        return Regex("""[a-z']+""").findAll(rawLower).any { it.value.startsWith(stem) }
    }

    /**
     * Anchors present in the input that did not survive reconstruction.
     * `vocabulary` closes the lowercase-ASR blind spot: profile terms present
     * in the input are critical regardless of case.
     */
    fun checkRetention(
        rawText: String,
        cleanText: String,
        vocabulary: List<String> = emptyList(),
    ): List<String> {
        val rawLower = rawText.lowercase()

        val critical = mutableListOf<String>()
        critical.addAll(CRITICAL_TOKEN_RE.findAll(rawText).map { it.value })
        critical.addAll(DATE_WORD_RE.findAll(rawText).map { it.value })
        critical.addAll(properNouns(rawText))
        // Titles: invisible to the proper-noun check on lowercase ASR output,
        // and dropping one still reads as a valid sentence. See TITLE_RE.
        critical.addAll(TITLE_RE.findAll(rawText).map { it.value.trimEnd('.') })

        for (termRaw in vocabulary) {
            val term = termRaw.trim()
            if (term.length < 2) continue
            if (Regex("""\b""" + Regex.escape(term.lowercase()) + """\b""").containsMatchIn(rawLower)) {
                critical.add(term)
            }
        }

        val cleanLower = cleanText.lowercase()
        val seen = mutableSetOf<String>()
        val lost = mutableListOf<String>()
        for (t in critical) {
            val key = t.lowercase()
            if (key in seen) continue
            seen.add(key)
            if (key !in cleanLower) lost.add(t)
        }

        // "whether X or not" legitimately reconstructs to "whether X" — do not
        // count the dropped "not" as a lost negation when the uncertainty
        // survives (spec 2026-07-29: allow whether X or not → whether X).
        val rawWhetherOrNot = Regex(
            """\bwhether\b[^.?!]*\bor\s+not\b""", RegexOption.IGNORE_CASE
        ).containsMatchIn(rawText)
        val cleanKeepsUncertainty = Regex(
            """\b(?:whether|if)\b""", RegexOption.IGNORE_CASE
        ).containsMatchIn(cleanText)
        if (
            NEGATION_RE.containsMatchIn(rawText) &&
            !NEGATION_RE.containsMatchIn(cleanText) &&
            !(rawWhetherOrNot && cleanKeepsUncertainty)
        ) {
            lost.add("<negation>")
        }
        return lost
    }

    /** Anchors that appear in the output but were never in the input. */
    fun checkFabrication(rawText: String, cleanText: String): List<String> {
        val rawLower = rawText.lowercase()
        val invented = mutableListOf<String>()

        // Product-context exemption: "Layer 2 reconstruction" phrasing in the
        // output is the app's own terminology, not an invented name/figure,
        // when the input was talking about the product.
        val productLayer = Regex(
            """\bLayer\s+([1-4])\s+(?:transcription|reconstruction|profile|advanced assist)\b""",
            RegexOption.IGNORE_CASE,
        ).find(cleanText)
        val rawHasProductContext = Regex(
            """\b(?:layer|transcription|reconstruction|reduction|profile|advanced)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(rawText)

        for (token in properNouns(cleanText)) {
            if (token.lowercase() == "layer" && productLayer != null && rawHasProductContext) continue
            if (token.lowercase() !in rawLower) invented.add(token)
        }

        // Ordinary content words are checked ONLY when the input contains a
        // Whisper hallucination artifact — a span of silence the model may be
        // filling with a confident guess.
        if (HALLUCINATION_ARTIFACT_RE.containsMatchIn(rawText)) {
            for (word in contentWords(cleanText)) {
                if (word in invented) continue
                if (!hasSource(word, rawLower)) invented.add(word)
            }
        }

        // A figure in the output is only suspicious when the input offered
        // nothing numeric at all — "four thousand two hundred" -> "$4,200" is
        // a correct reconstruction, not an invention.
        if (CRITICAL_TOKEN_RE.containsMatchIn(cleanText)) {
            val rawHasNumber = CRITICAL_TOKEN_RE.containsMatchIn(rawText) ||
                NUMBER_WORD_RE.containsMatchIn(rawText)
            if (!rawHasNumber) {
                for (m in CRITICAL_TOKEN_RE.findAll(cleanText)) {
                    val figure = m.value
                    if (productLayer != null && rawHasProductContext &&
                        figure == productLayer.groupValues[1]
                    ) continue
                    invented.add(figure)
                }
            }
        }
        return invented
    }

    // ── Numeric value checking ────────────────────────────────────
    //
    // checkFabrication only asks whether a figure appeared out of NOTHING: if
    // the input contained any number at all, in words or digits, every figure
    // in the output is waved through as reformatting. That rule is inherited
    // from the backend and it cannot see a number that CHANGED.
    //
    // Found on-device 2026-07-29: "wire four thousand two hundred to Henderson"
    // came back as "$1,200", then "$1,002", from the same input, three runs in
    // a row, and nothing objected — because the input did contain number words,
    // so any figure was legal. For a wire transfer that is the worst failure
    // this app can produce. Compare the VALUES.

    private val UNITS = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L,
        "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
    )
    private val SCALES = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L)

    /**
     * Every number the text states, however written: digits, "4,200", "$4,200",
     * and spelled-out runs like "four thousand two hundred".
     */
    fun numericValues(text: String): Set<Long> {
        val out = mutableSetOf<Long>()

        for (m in CRITICAL_TOKEN_RE.findAll(text)) {
            m.value.replace(",", "").removePrefix("$").removeSuffix("%")
                .toDoubleOrNull()?.let { if (it == Math.floor(it)) out.add(it.toLong()) }
        }

        // Spelled-out runs: accumulate until a word that is neither a unit, a
        // scale, nor a joining "and" ends the run.
        var current = 0L
        var total = 0L
        var sawAny = false
        fun flush() {
            if (sawAny) out.add(total + current)
            current = 0L; total = 0L; sawAny = false
        }
        for (w in Regex("""[a-z]+""", RegexOption.IGNORE_CASE).findAll(text.lowercase()).map { it.value }) {
            when {
                UNITS.containsKey(w) -> { current += UNITS.getValue(w); sawAny = true }
                w == "hundred" && sawAny -> { current *= 100L }
                SCALES.containsKey(w) && sawAny -> {
                    total += current * SCALES.getValue(w); current = 0L
                }
                w == "and" && sawAny -> { /* "four thousand and two" — keep going */ }
                else -> flush()
            }
        }
        flush()
        return out
    }

    /**
     * Figures in the output whose VALUE is not stated anywhere in the input.
     * This is what catches 4200 becoming 1200 — the fabrication check cannot,
     * because the input did contain numbers.
     */
    fun checkNumericDrift(rawText: String, cleanText: String): List<String> {
        val rawValues = numericValues(rawText)
        if (rawValues.isEmpty()) return emptyList()
        val drifted = mutableListOf<String>()
        for (m in CRITICAL_TOKEN_RE.findAll(cleanText)) {
            val v = m.value.replace(",", "").removePrefix("$").removeSuffix("%")
                .toDoubleOrNull() ?: continue
            if (v != Math.floor(v)) continue
            val asLong = v.toLong()
            // Ordinals and small counts legitimately appear from restructuring
            // ("the 15th"), and a value present in the input is fine.
            if (asLong !in rawValues) drifted.add(m.value)
        }
        return drifted
    }

    /** Full check: ok is false when reconstruction dropped or invented an anchor. */
    fun guard(
        rawText: String,
        cleanText: String,
        vocabulary: List<String> = emptyList(),
    ): GuardResult {
        val lost = checkRetention(rawText, cleanText, vocabulary)
        val invented = (checkFabrication(rawText, cleanText) + checkNumericDrift(rawText, cleanText))
            .distinct()
        return GuardResult(ok = lost.isEmpty() && invented.isEmpty(), lost = lost, invented = invented)
    }

    // ── Layer 2 rewrite loop (port of prompt_builder.py 6a0ad9a) ──

    private fun words(text: String): List<String> =
        Regex("""\w+""").findAll(text.lowercase()).map { it.value }.toList()

    /** True when Layer 2 changed only case, punctuation, or whitespace. */
    fun isEffectivelyUnchanged(rawText: String, reconstructedText: String): Boolean {
        val rawWords = words(rawText)
        return rawWords.isNotEmpty() && rawWords == words(reconstructedText)
    }

    /** Layer 2 must produce a non-identity rewrite that passes the guard. */
    fun needsRetry(rawText: String, candidate: String, guard: GuardResult): Boolean =
        candidate.isBlank() ||
            isEffectivelyUnchanged(rawText, candidate) ||
            guard.lost.isNotEmpty() ||
            guard.invented.isNotEmpty()

    /** Tell the model exactly why its rewrite was rejected and require repair. */
    fun buildRepairInstruction(
        rawText: String,
        candidate: String,
        guard: GuardResult,
    ): String {
        val reasons = mutableListOf<String>()
        if (candidate.isBlank()) {
            reasons.add("- The previous answer was empty.")
        } else if (isEffectivelyUnchanged(rawText, candidate)) {
            reasons.add(
                "- The previous answer merely repeated the transcript. " +
                    "Use different wording or sentence structure."
            )
        }
        val anchors = guard.lost.filter { it != "<negation>" }
        if (anchors.isNotEmpty()) {
            reasons.add(
                "- It dropped protected content. Preserve these exact items: " +
                    anchors.joinToString(", ") { "'$it'" } + "."
            )
        }
        if ("<negation>" in guard.lost) {
            reasons.add("- It changed negative meaning. Keep the original negation explicit.")
        }
        if (guard.invented.isNotEmpty()) {
            reasons.add(
                "- It invented protected content. Remove these unsupported items: " +
                    guard.invented.joinToString(", ") { "'$it'" } + "."
            )
        }
        return "RECONSTRUCTION REPAIR REQUIRED.\n" +
            reasons.joinToString("\n") +
            "\nRewrite the original transcript again. Produce a materially different, " +
            "natural reconstruction while preserving every fact and instruction. " +
            "Return only the rewritten text on one line."
    }

    data class RewriteOutcome(
        val cleanText: String,
        val attempts: Int,
        val guard: GuardResult,
    )

    /**
     * Run Layer 2/3 until the output is both materially rewritten and
     * guard-approved, up to [maxAttempts]. [rewriteOnce] performs one model
     * call for the current conversation; [appendRepair] extends the
     * conversation with the failed candidate and the repair instruction
     * before the next attempt.
     */
    fun runRewriteLoop(
        rawText: String,
        vocabulary: List<String> = emptyList(),
        maxAttempts: Int = MAX_REWRITE_ATTEMPTS,
        rewriteOnce: (attemptIndex: Int) -> String,
        appendRepair: (candidate: String, instruction: String) -> Unit,
        onRetry: ((attempt: Int, candidate: String, guard: GuardResult) -> Unit)? = null,
    ): RewriteOutcome {
        var cleanText = ""
        var guardResult = GuardResult(ok = true, lost = emptyList(), invented = emptyList())
        var attempts = 0
        // Earliest attempt that kept every anchor and said something, whose only
        // fault was leaving the wording alone. The loop retries past it looking
        // for an actual rewrite, and the attempts that follow can be worse.
        var safeText: String? = null
        var safeGuard: GuardResult? = null

        for (attemptIndex in 0 until maxAttempts) {
            cleanText = rewriteOnce(attemptIndex).trim()
            attempts += 1
            guardResult = guard(rawText, cleanText, vocabulary)
            if (!needsRetry(rawText, cleanText, guardResult)) break
            if (safeText == null && cleanText.isNotBlank() &&
                guardResult.lost.isEmpty() && guardResult.invented.isEmpty()
            ) {
                safeText = cleanText
                safeGuard = guardResult
            }
            if (attemptIndex + 1 >= maxAttempts) break
            onRetry?.invoke(attempts, cleanText, guardResult)
            appendRepair(cleanText, buildRepairInstruction(rawText, cleanText, guardResult))
        }

        // Attempts exhausted holding a candidate that dropped an anchor,
        // invented one, or came back blank. Returning it costs the user the
        // whole reconstruction: the caller reads lost/invented as a hard
        // failure and pastes the raw disfluent transcript instead. An earlier
        // attempt that merely echoed the speaker is worth more than that —
        // ReconstructClient keeps unchanged-only output on purpose, because
        // identical words are safe words.
        val finalFailed = cleanText.isBlank() ||
            guardResult.lost.isNotEmpty() || guardResult.invented.isNotEmpty()
        if (finalFailed && safeText != null) {
            cleanText = safeText
            guardResult = safeGuard!!
        }

        val unchanged = isEffectivelyUnchanged(rawText, cleanText)
        return RewriteOutcome(
            cleanText = cleanText,
            attempts = attempts,
            guard = guardResult.copy(
                unchanged = unchanged,
                ok = guardResult.ok && !unchanged && cleanText.isNotBlank(),
            ),
        )
    }
}
