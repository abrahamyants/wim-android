package com.wim.app

/**
 * Deterministic profanity gate — the last stop before injection, and only
 * when the tone is professional or formal ("the transistor switch",
 * George 2026-08-04). Casual and every other tone never reach this code.
 *
 * This is net two. Net one is the reconstruction prompt, which TRANSLATES
 * in-message heat into the register ("fucking great" → "genuinely
 * excellent") and reads better when it works — but it is a model, and a
 * model coin-flips. This gate is the guarantee: plain word list, word
 * boundaries, no model, no latency, testable line by line. On the days
 * the prompt does its job the gate catches nothing and changes nothing.
 *
 * Deliberately dumb: it DROPS the token and tidies the seams. It does not
 * try to rephrase — that is the prompt's job. Word-boundary matching keeps
 * "class", "assassin", "Scunthorpe" and friends safe. English + Russian,
 * matching the app's language packs.
 */
object SwearGate {

    /** Tones that close the gate. Everything else passes untouched. */
    private val GATED_TONES = setOf("professional", "formal")

    // No \b and no (?U): desktop JVM needs (?U) for Cyrillic word edges,
    // but Android's ICU regex REJECTS the (?U) flag at compile time — the
    // gate's <clinit> threw on-device and killed every L2+ dictation
    // (caught live 2026-08-04 12:13). Explicit \p{L}\p{N} lookarounds mean
    // the same thing in BOTH dialects. Trailing [,]? eats the comma an
    // interjection drags along ("Fuck, the deal…") but leaves
    // sentence-ending periods alone.
    private val SWEARS = Regex(
        """(?ix)(?<![\p{L}\p{N}_])(?:
            fuck(?:er|ers|ing|ed|s)?|motherfuck(?:er|ers|ing)?|
            shit(?:ty|s|ted|ting)?|bullshit|horseshit|
            bitch(?:es|y)?|
            ass(?:es|hole|holes)?|
            dick(?:head|heads|s)?|
            piss(?:ed|es|ing)?|
            cunt(?:s)?|
            bastard(?:s)?|
            goddamn(?:it|ed)?|damn(?:it|ed)?|
            crap(?:py|s)?|
            сука|суки|блять|блядь|бля|гандон(?:ы)?|пиздец|
            хуй[\p{L}]*|хуев[\p{L}]*|ебан[\p{L}]*|заебал[\p{L}]*|нахуй|нихуя
        )(?![\p{L}\p{N}_])[,]?"""
    )

    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([.,!?;:])""")
    private val ORPHAN_PUNCT_RUN = Regex("""([.,!?])[\s]*([.,!?])""")
    private val LEADING_PUNCT = Regex("""^[\s.,!?;:]+""")

    data class Result(val text: String, val caught: Int)

    /**
     * Run the gate. Returns the input untouched (caught = 0) when the tone
     * is not gated, the text is blank, or nothing matches. Never returns
     * blank for non-blank input — if stripping empties the text, the
     * original comes back; a swear beats an empty paste.
     */
    fun apply(text: String, tone: String): Result {
        if (tone.lowercase() !in GATED_TONES) return Result(text, 0)
        if (text.isBlank()) return Result(text, 0)

        val caught = SWEARS.findAll(text).count()
        if (caught == 0) return Result(text, 0)

        var cleaned = SWEARS.replace(text, " ")
        cleaned = MULTI_SPACE.replace(cleaned, " ")
        cleaned = SPACE_BEFORE_PUNCT.replace(cleaned) { m -> m.groupValues[1] }
        // Collapse doubled punctuation left where a swear sat between marks
        // ("great , ." → "great."). Two passes cover a three-mark pile-up.
        repeat(2) {
            cleaned = ORPHAN_PUNCT_RUN.replace(cleaned) { m -> m.groupValues[2] }
        }
        cleaned = LEADING_PUNCT.replace(cleaned.trim(), "")
        // Re-capitalize if the gate ate the opening word.
        if (cleaned.isNotEmpty() && cleaned[0].isLowerCase() &&
            text.isNotEmpty() && text[0].isUpperCase()
        ) {
            cleaned = cleaned[0].uppercaseChar() + cleaned.substring(1)
        }
        return if (cleaned.isBlank()) Result(text, 0) else Result(cleaned, caught)
    }
}
