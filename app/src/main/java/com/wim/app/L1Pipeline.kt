package com.wim.app

/**
 * L1 deterministic transform — the no-GPT path that ships pre-cleaned ASR
 * text straight to the user's text field after applying their learned
 * corrections and deterministic punctuation.
 *
 * Extracted from BubbleService.processText 2026-05-25 as the second
 * Pipeline-cluster slice (after ReconstructionGuard). The transform side
 * is pure-function-shaped — only side effects are on the injected
 * [ProfileManager] (markCorrectionUsed + decayStaleCorrections), both of
 * which are testable via a fake profile.
 *
 * Mirrors lavrentiy.py's L1 path. PhoneticMatcher intentionally NOT called
 * here (Lav parity — phonetic_match gate `2 <= layer <= 3`); at L1 there's
 * no validator, so a wrong-direction Metaphone collision would land
 * directly in the user's field. The swap pays off only when GPT sees the
 * cleaner input downstream.
 */
object L1Pipeline {

    /**
     * @property text The transformed text — pre-cleaned input with profile
     *                corrections applied and deterministic repunctuation.
     * @property correctionsApplied How many distinct corrections fired.
     *                              Caller surfaces this in the pipeline-stage
     *                              UI ("+ corrections" suffix).
     */
    data class Result(
        val text: String,
        val correctionsApplied: Int
    )

    /**
     * Apply profile corrections (case-insensitive word-boundary regex),
     * trigger the every-30-sessions decay sweep, then Repunctuate.
     *
     * @param preCleaned Disfluency-stripped ASR text from the upstream
     *                   DisfluencyFilter stage.
     * @param profile Mutated as a side effect — corrections that fire are
     *                marked used; the decay sweep prunes stale ones.
     * @param sessionNum Current session count (from SessionHistory.count()).
     *                   Used by decay scheduling.
     * @param log Optional sink for per-correction "wrong → right" logs.
     *            Default no-op so tests can pass without capturing logs.
     * @param applyCorrections Profile corrections are a Layer-3+ feature
     *            (design call, George 2026-08-04). Applying them at L1 is
     *            how a poisoned list mangled every plain transcription for
     *            weeks: 50 auto-learned pairs ("with"→"come", "you"→"else")
     *            rewrote a quarter of the words in the no-model tier — the
     *            one tier with no guard to notice. Default true for Lav
     *            parity in tests; BubbleService passes false at L1.
     */
    fun transform(
        preCleaned: String,
        profile: ProfileManager,
        sessionNum: Int,
        log: (String) -> Unit = {},
        applyCorrections: Boolean = true
    ): Result {
        val corrections = if (applyCorrections) profile.getCorrections() else emptyMap()
        var l1Text = preCleaned
        var applied = 0
        for ((wrong, right) in corrections) {
            val pattern = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(l1Text)) {
                // Regex.replace treats $1 in the REPLACEMENT as a group reference.
                // WiM learns corrections from its own reconstructions, so the
                // moment one is learned whose target contains a dollar amount
                // ("$1,200"), every later dictation throws
                // IndexOutOfBoundsException: No group 1 — the pipeline is dead
                // until that correction is deleted. Crashlytics, 2026-07-29.
                l1Text = pattern.replace(l1Text, Regex.escapeReplacement(right))
                profile.markCorrectionUsed(wrong, sessionNum)
                log("L1 correction: \"$wrong\" → \"$right\"")
                applied++
            }
        }
        // Decay sweep every 30 sessions — prunes corrections that haven't
        // been reinforced recently to keep the active set focused on the
        // user's current vocabulary.
        if (sessionNum > 0 && sessionNum % 30 == 0) {
            profile.decayStaleCorrections(sessionNum)
        }
        // Deterministic capitalization + terminal punctuation. Idempotent
        // on already-punctuated text. Mirrors lavrentiy.py repunctuate().
        l1Text = Repunctuate.apply(l1Text)
        return Result(l1Text, applied)
    }
}
