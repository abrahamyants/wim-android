package com.wim.app

/**
 * L2/L3/L4 pre-reconstruction corrections — profile corrections (same
 * regex shape as L1) plus phonetic-neighbor correction gated to L2/L3.
 * Runs before the reconstruction call so GPT sees the cleanest input the
 * profile knows how to produce.
 *
 * Extracted from BubbleService.processText 2026-05-25 as the third
 * Pipeline-cluster slice (after ReconstructionGuard and L1Pipeline).
 * Sibling to [L1Pipeline] but different shape — L2+ skips Repunctuate
 * (GPT handles punctuation) and adds PhoneticMatcher (gated to L2/L3
 * per Lav parity; L4 Sonnet ext-think reasons through phonetic context
 * via the rich onset_weights prompt block instead).
 *
 * Phonetic-neighbor reasoning: when the user has "bake-off" in their
 * vocabulary and the ASR heard "bake off", Metaphone matches both to
 * BKF, so the swap gives the reconstruction prompt a cleaner input and
 * reduces round-trip cost of "fix this word."
 */
object L2CorrectionsStage {

    /**
     * @property text The corrected text — profile-corrections-substituted
     *                and (at L2/L3) phonetic-neighbor-aligned.
     * @property correctionsApplied How many distinct profile corrections
     *                              fired. Caller uses this for analytics
     *                              and UI surfacing.
     */
    data class Result(
        val text: String,
        val correctionsApplied: Int
    )

    /**
     * @param preCleaned Disfluency-stripped ASR text from upstream.
     * @param profile Mutated as a side effect — markCorrectionUsed fires
     *                for each correction that lands.
     * @param sessionNum Current session count, used by markCorrectionUsed.
     * @param layer Active layer (2, 3, or 4). PhoneticMatcher fires only
     *              when layer is in 2..3 per Lav parity.
     * @param log Optional sink for per-correction "wrong → right" logs.
     */
    fun apply(
        preCleaned: String,
        profile: ProfileManager,
        sessionNum: Int,
        layer: Int,
        log: (String) -> Unit = {}
    ): Result {
        // Profile corrections are a Layer-3+ feature (design call, George
        // 2026-08-04): L3 is the "profile" tier. At L2 the auto-learned list
        // used to rewrite words before GPT ever saw them, so a poisoned pair
        // corrupted even the layers with a model in the loop.
        val corrections = if (layer >= 3) profile.getCorrections() else emptyMap()
        var corrected = preCleaned
        var applied = 0
        for ((wrong, right) in corrections) {
            val pattern = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(corrected)) {
                // Regex.replace treats $1 in the REPLACEMENT as a group reference.
                // WiM learns corrections from its own reconstructions, so the
                // moment one is learned whose target contains a dollar amount
                // ("$1,200"), every later dictation throws
                // IndexOutOfBoundsException: No group 1 — the pipeline is dead
                // until that correction is deleted. Crashlytics, 2026-07-29.
                corrected = pattern.replace(corrected, Regex.escapeReplacement(right))
                profile.markCorrectionUsed(wrong, sessionNum)
                log("L$layer correction: \"$wrong\" → \"$right\"")
                applied++
            }
        }
        // Phonetic-neighbor correction before GPT — gated to L2/L3 only
        // (Lav parity — lavrentiy.py phonetic_match gate `2 <= layer <= 3`).
        // At L4 Sonnet ext-think reasons through phonetic context via the
        // rich onset_weights prompt block instead.
        if (layer in 2..3) {
            corrected = PhoneticMatcher.correct(
                corrected,
                profile.getVocabulary(),
                profile.getOnsetWeights()
            )
        }
        return Result(corrected, applied)
    }
}
