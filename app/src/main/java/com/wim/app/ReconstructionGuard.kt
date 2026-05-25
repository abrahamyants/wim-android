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
 * pre-cleaned ASR output). Patterns ported verbatim from Lavrentiy 2026-04-21.
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
        // Leak filter — substring match against known chatbot leak patterns.
        val reconLower = reconstructed.trim().lowercase()
        for (pattern in LLM_LEAK_PATTERNS) {
            if (reconLower.startsWith(pattern) || pattern in reconLower) {
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

    private val LLM_LEAK_PATTERNS = listOf(
        "i'm sorry", "i apologize", "i didn't catch that",
        "could you please clarify", "could you please repeat",
        "could you say that again", "i'm not sure what you meant",
        "please clarify", "please provide more context",
        "i cannot assist", "i can't assist", "i can't help",
        "as an ai", "as a language model",
        "please ensure that you", "ensure that you construct",
        "i'd be happy to help",
        "no output", "identified as hallucinated", "hallucinated filler",
        "no transcription", "unable to transcribe", "no speech detected",
        "[no output", "[hallucin", "[unable to"
    )
}
