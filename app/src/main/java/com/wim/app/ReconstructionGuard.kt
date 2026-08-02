package com.wim.app

/**
 * Post-reconstruction guards — catches two failure modes that GPT
 * occasionally produces and that would otherwise paste garbage into the
 * user's compose field:
 *
 *   1. **LLM leak** — chatbot meta-commentary or apology text that
 *      leaked through the reconstruction prompt ("I'm sorry, I didn't
 *      catch that", "as an AI language model", etc.).
 *   2. **Length explosion** — reconstructed text more than 2.5× longer
 *      than the pre-cleaned input, which almost always means GPT
 *      fabricated content instead of cleaning speech.
 *
 * Both cases fall back to the supplied `fallbackText` (the L1
 * pre-cleaned ASR output). Patterns originally ported verbatim from Lavrentiy
 * 2026-04-21; split 2026-08-02 into machine-only strings and human-plausible
 * openers, because matching the latter as substrings caught the speaker's own
 * words. See [ECHO_GUARDED_PATTERNS].
 *
 * Extracted from BubbleService.processText 2026-05-25 as the first
 * Pipeline-cluster slice. Pure-function — no Android imports, no
 * coroutine scope, no shared state — so it tests cleanly in isolation
 * (see ReconstructionGuardTest if/when added).
 */
object ReconstructionGuard {

    /** Outcome of applying the guards. [leakReason] is non-null when a
     *  guard fired; callers should log it and surface the falcon label
     *  as "LLM leak filtered". */
    data class Result(
        val outputText: String,
        val leakDetected: Boolean,
        val leakReason: String? = null
    )

    /**
     * Run both guards in sequence. Returns the original [reconstructed]
     * text if neither guard fires; returns [fallbackText] with
     * `leakDetected = true` and a `leakReason` if either fires.
     *
     * @param reconstructed Raw output from the LLM reconstruction call.
     * @param fallbackText The L1-pre-cleaned ASR text to use if a guard
     *                     fires. Caller already computed this before the
     *                     reconstruction round-trip.
     * @param fallbackWordCount Word count of the fallback text — passed
     *                          in to avoid re-splitting (caller computed
     *                          it earlier for the pipeline-stage label).
     */
    fun apply(
        reconstructed: String,
        fallbackText: String,
        fallbackWordCount: Int
    ): Result {
        val reconLower = reconstructed.trim().lowercase()
        val rawLower = fallbackText.lowercase()

        // Machine-only strings: nobody dictates these, so position is irrelevant.
        for (pattern in MACHINE_ONLY_PATTERNS) {
            if (pattern in reconLower) {
                return Result(
                    outputText = fallbackText,
                    leakDetected = true,
                    leakReason = "LLM leak detected: \"${reconstructed.take(80)}\""
                )
            }
        }

        // Everything else on the old list is ordinary English. It counts as a
        // leak only when the speaker did not say it: the raw transcript is the
        // arbiter. Stem present means the model is carrying the user's own
        // words through; stem absent means the model put them there.
        for ((phrase, stem) in ECHO_GUARDED_PATTERNS) {
            if (phrase in reconLower && stem !in rawLower) {
                return Result(
                    outputText = fallbackText,
                    leakDetected = true,
                    leakReason = "LLM leak detected: \"${reconstructed.take(80)}\""
                )
            }
        }

        // Length explosion guard — skipped on very short inputs where the
        // ratio is noisy (a single-word raw can legitimately expand to
        // 3-4 words when GPT adds punctuation/articles).
        if (fallbackWordCount >= 3) {
            val reconWordCount = reconstructed.split("\\s+".toRegex())
                .filter { it.isNotBlank() }.size
            val lenRatio = reconWordCount.toFloat() / fallbackWordCount.toFloat()
            if (lenRatio > 2.5f) {
                return Result(
                    outputText = fallbackText,
                    leakDetected = true,
                    leakReason = "LLM length explosion: ${fallbackWordCount}w → ${reconWordCount}w (${lenRatio}x)"
                )
            }
        }

        return Result(reconstructed, false, null)
    }

    /**
     * Strings no speaker produces. Safe to match anywhere in the output.
     */
    private val MACHINE_ONLY_PATTERNS = listOf(
        "as an ai", "as a language model",
        "identified as hallucinated", "hallucinated filler",
        "ensure that you construct",
        "[no output", "[hallucin", "[unable to",
    )

    /**
     * Ordinary English that also happens to be how a model editorialises.
     *
     * These used to be matched as bare substrings, so dictating "I'm sorry,
     * I'll be late" or "tell him I can't help with the move" threw away a
     * perfectly good reconstruction and pasted the raw disfluent ASR instead.
     * Silently — a guard hit is logged as a leak, never surfaced as a
     * downgrade, so the user just sees their stutter come back. Apologising is
     * among the most common things anyone dictates.
     *
     * Each phrase carries the stem that appears in the raw transcript when the
     * speaker genuinely said it. Stems rather than whole phrases because
     * pre-cleaning rewrites contractions and spacing on the way through.
     */
    private val ECHO_GUARDED_PATTERNS = listOf(
        "i'm sorry" to "sorr",
        "i am sorry" to "sorr",
        "i apologize" to "apolog",
        "i apologise" to "apolog",
        "i didn't catch that" to "catch",
        "could you please clarify" to "clarif",
        "could you please repeat" to "repeat",
        "could you say that again" to "again",
        "i'm not sure what you meant" to "not sure",
        "please clarify" to "clarif",
        "please provide more context" to "context",
        "i cannot assist" to "assist",
        "i can't assist" to "assist",
        "i can't help" to "help",
        "i'd be happy to help" to "happy to help",
        "please ensure that you" to "ensure",
        "no output" to "output",
        "no transcription" to "transcri",
        "unable to transcribe" to "transcri",
        "no speech detected" to "speech",
    )
}
