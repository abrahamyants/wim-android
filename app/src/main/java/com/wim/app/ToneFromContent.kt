package com.wim.app

/**
 * Decides work vs casual from the message the speaker just dictated.
 *
 * ## Why this replaced the app whitelist
 *
 * [ToneRouting] keyed tone to the foreground package: Gmail meant work,
 * WhatsApp meant casual. The operator's objection, 2026-07-29, is correct and
 * ends the argument — Gmail is where you email your brother and your landlord
 * and your lawyer, and half the world runs a business on WhatsApp. The app
 * says almost nothing about register, and worse, guessing from it is exactly
 * what stopped the work/casual question from ever being asked.
 *
 * Nor is the recipient the answer: you message the same coworker about a
 * deadline at noon and about beers at six. Tone is not a property of where you
 * are or who you are writing to. It is a property of THE MESSAGE.
 *
 * And WiM already has the message — the speaker just said it. So classify it,
 * every time, in the same pass that reconstructs it. No lists, no memory to go
 * stale, no setting to get wrong.
 *
 * ## The clock
 *
 * A real but weak signal, and it loses to content: midnight is precisely when
 * the urgent work message gets sent. So the clock only breaks a tie the words
 * left open.
 *
 * ## Ambiguity
 *
 * "Can you send that over" is genuinely either. That small remainder is what
 * the bubble's WORK?/CASUAL question is for — a question about THIS message,
 * not a preference that gets saved and rots.
 */
object ToneFromContent {

    sealed class Verdict {
        data class Use(val tone: String, val why: String) : Verdict()
        /** The words don't say. Caller may consult the clock, then ask. */
        object Ambiguous : Verdict()
    }

    private fun re(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ── Work markers ────────────────────────────────────────────────

    /** Titles and honorifics — almost never present in a casual message. */
    private val TITLE = re("""\b(?:dr|doctor|mr|mrs|ms|prof|professor|sir|madam|attorney|counsel)\b\.?""")

    /** Deadlines, commitments, scheduling formalities. */
    private val DEADLINE = re(
        """\b(?:deadline|due\s+(?:date|by|on)|eod|cob|asap|by\s+(?:end\s+of|close\s+of)|""" +
            """no\s+later\s+than|business\s+days?|working\s+days?|reschedul\w*|confirm\w*)\b"""
    )

    /** Money, references, quantities that carry consequence. */
    private val MONEY_REF = re(
        """(?:\$\s?\d|\b\d+(?:,\d{3})+\b|\b(?:invoice|purchase\s+order|\bpo\b|contract|quote|""" +
            """estimate|deposit|balance|refund|reference|case|claim|account)\s*(?:#|number|no\.?)?\s*\w*)"""
    )

    /** Task framing and business politeness. */
    private val TASK = re(
        """\b(?:regarding|per\s+our|following\s+up|as\s+discussed|attached|""" +
            """please\s+(?:find|confirm|advise|review|forward)|kindly|""" +
            """at\s+your\s+earliest|let\s+me\s+know\s+if\s+you\s+(?:need|require))\b"""
    )

    // ── Casual markers ──────────────────────────────────────────────

    /** Profanity — the single strongest casual signal. Almost never work. */
    private val PROFANITY = re(
        """\b(?:fuck\w*|shit\w*|damn|hell|crap|ass|bitch|piss\w*|bullshit)\b"""
    )

    /** Slang, netspeak, clipped spoken forms. */
    private val SLANG = re(
        """\b(?:yo|nah|yeah|yep|nope|lol|lmao|omg|wtf|idk|tbh|imo|bro|dude|man|""" +
            """gonna|wanna|gotta|kinda|sorta|ain't|cuz|sup|hey there|haha+)\b"""
    )

    /** Family and intimates. */
    private val INTIMATE = re(
        """\b(?:mom|mum|mama|dad|papa|babe|baby|honey|sweetie|love\s+you|""" +
            """bro|sis|buddy|hun)\b"""
    )

    /** Near-term social plans. */
    private val SOCIAL = re(
        """\b(?:tonight|later|this\s+weekend|grab\s+(?:a\s+)?(?:beer|drink|coffee|lunch|bite)|""" +
            """you\s+(?:coming|down|in)|hang\s+out|come\s+over|see\s+you\s+(?:there|then)|""" +
            """party|dinner\s+tonight)\b"""
    )

    private data class Hit(val name: String, val re: Regex)

    private val WORK_MARKERS = listOf(
        Hit("title", TITLE),
        Hit("deadline", DEADLINE),
        Hit("money/reference", MONEY_REF),
        Hit("task framing", TASK),
    )

    private val CASUAL_MARKERS = listOf(
        Hit("profanity", PROFANITY),
        Hit("slang", SLANG),
        Hit("family/intimate", INTIMATE),
        Hit("social plans", SOCIAL),
    )

    /**
     * Classify [text]. [domainTerms] are the speaker's own industry/vocabulary
     * words — their presence is a work signal for this speaker specifically,
     * which a generic list can never capture.
     */
    fun classify(text: String, domainTerms: List<String> = emptyList()): Verdict {
        if (text.isBlank()) return Verdict.Ambiguous

        val work = WORK_MARKERS.filter { it.re.containsMatchIn(text) }.map { it.name }.toMutableList()
        val casual = CASUAL_MARKERS.filter { it.re.containsMatchIn(text) }.map { it.name }.toMutableList()

        val lower = text.lowercase()
        val domainHit = domainTerms.filter { term ->
            val t = term.trim().lowercase()
            t.length >= 3 && Regex("""\b${Regex.escape(t)}\b""").containsMatchIn(lower)
        }
        if (domainHit.isNotEmpty()) work.add("your vocabulary: ${domainHit.first()}")

        return when {
            // Profanity outranks a lone work marker: "tell Henderson this is
            // bullshit" is a message to a friend about work, not a work message.
            casual.contains("profanity") && work.size <= 1 ->
                Verdict.Use(ToneRouting.CASUAL, "profanity")
            work.isNotEmpty() && casual.isEmpty() ->
                Verdict.Use(ToneRouting.PROFESSIONAL, work.joinToString(", "))
            casual.isNotEmpty() && work.isEmpty() ->
                Verdict.Use(ToneRouting.CASUAL, casual.joinToString(", "))
            work.size > casual.size ->
                Verdict.Use(ToneRouting.PROFESSIONAL, work.joinToString(", "))
            casual.size > work.size ->
                Verdict.Use(ToneRouting.CASUAL, casual.joinToString(", "))
            // Nothing, or a genuine tie.
            else -> Verdict.Ambiguous
        }
    }

    /**
     * Tie-breaker for [Verdict.Ambiguous] — the clock, used only here.
     * Returns null when the hour says nothing either, which is when the bubble
     * should put the question.
     *
     * @param workHours the user's `work_hours` answer; "flexible"/unset means
     *   the clock genuinely does not predict this person, and must not be
     *   forced into a guess.
     */
    fun breakTieByClock(hourOfDay: Int, isWeekend: Boolean, workHours: String?): String? {
        if (hourOfDay !in 0..23) return null
        val window = when (workHours) {
            "morning" -> 5 to 12
            "nine_to_five", "9to5" -> 9 to 17
            "evening", "night_owl" -> 17 to 23
            else -> return null
        }
        if (isWeekend) return ToneRouting.CASUAL
        return if (hourOfDay >= window.first && hourOfDay < window.second) {
            ToneRouting.PROFESSIONAL
        } else {
            ToneRouting.CASUAL
        }
    }
}
