package com.wim.app

/**
 * Detects utterances where the word-per-second rate is abnormally LOW compared
 * to the user's historical baseline — a signal that the ASR engine (or the
 * speaker) dropped content.
 *
 * Samsung's native recognizer pre-smooths speech disfluencies: "go go go go go"
 * arrives as "go go go", "heyyyy" arrives as "hey". The audio was longer than
 * the word count explains. That is the rate-gap.
 *
 * This detector is information-only. It reports the gap; downstream (L4 prompt
 * with onset_weights + covert_profile) decides how to recover the missing
 * content.
 */
object RateGapDetector {

    /** Ratio below baseline that counts as a gap. */
    const val GAP_RATIO_THRESHOLD = 0.6

    /** Require at least this many prior WPS samples before trusting the baseline. */
    const val MIN_SAMPLES_FOR_BASELINE = 10

    /** Only run analysis on recordings longer than this (short clips are noisy). */
    const val MIN_AUDIO_SEC_FOR_ANALYSIS = 1.5

    data class Result(
        val wps: Double,            // words per second for this utterance
        val baselineWps: Double?,   // user's rolling-median baseline, null if insufficient history
        val ratio: Double?,         // wps / baseline, null if no baseline
        val gapDetected: Boolean    // true iff ratio < threshold
    )

    /**
     * Analyze one utterance for rate-gap against the user's baseline.
     *
     * @param wordCount words emitted by the ASR (raw, pre-filter)
     * @param audioDurationSec recording length in seconds
     * @param baseline user's rolling-median WPS across recent sessions; null if history too short
     * @param threshold ratio below which the gap flag fires
     */
    fun analyze(
        wordCount: Int,
        audioDurationSec: Double,
        baseline: Double?,
        threshold: Double = GAP_RATIO_THRESHOLD
    ): Result {
        if (audioDurationSec < MIN_AUDIO_SEC_FOR_ANALYSIS) {
            return Result(wps = 0.0, baselineWps = baseline, ratio = null, gapDetected = false)
        }
        val wps = wordCount / audioDurationSec
        val ratio = baseline?.let { if (it > 0) wps / it else null }
        val gap = ratio != null && ratio < threshold
        return Result(wps, baseline, ratio, gap)
    }
}
