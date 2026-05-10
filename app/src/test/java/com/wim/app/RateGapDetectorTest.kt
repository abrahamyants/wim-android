package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RateGapDetector].
 *
 * Pure Kotlin object with no Android dependencies — plain JUnit 4.
 */
class RateGapDetectorTest {

    // ── Short-clip guard: below MIN_AUDIO_SEC_FOR_ANALYSIS ─

    @Test fun shortClip_returnsFalse_gapNotDetected() {
        // 1.0s < MIN_AUDIO_SEC_FOR_ANALYSIS (1.5s)
        val result = RateGapDetector.analyze(
            wordCount = 1,
            audioDurationSec = 1.0,
            baseline = 3.0,
        )
        assertFalse("Short clip must never trigger a gap", result.gapDetected)
    }

    @Test fun shortClip_wpsIsZero() {
        val result = RateGapDetector.analyze(
            wordCount = 10,
            audioDurationSec = 0.5,
            baseline = 3.0,
        )
        assertEquals(0.0, result.wps, 0.0001)
    }

    @Test fun shortClip_ratioIsNull() {
        val result = RateGapDetector.analyze(
            wordCount = 5,
            audioDurationSec = 1.4,
            baseline = 3.0,
        )
        assertNull("Ratio must be null for sub-minimum clips", result.ratio)
    }

    @Test fun exactlyAtMinDuration_isNotTooShort() {
        // Exactly MIN_AUDIO_SEC_FOR_ANALYSIS = 1.5 — should proceed to analysis
        val result = RateGapDetector.analyze(
            wordCount = 3,
            audioDurationSec = RateGapDetector.MIN_AUDIO_SEC_FOR_ANALYSIS,
            baseline = 3.0,
        )
        // wps = 3 / 1.5 = 2.0; ratio = 2.0/3.0 ≈ 0.667 > 0.6 → no gap
        assertFalse(result.gapDetected)
        assertNotNull("Ratio should be non-null when exactly at threshold", result.ratio)
    }

    // ── Cold start: baseline == null ────────────────────

    @Test fun coldStart_nullBaseline_gapNotDetected() {
        val result = RateGapDetector.analyze(
            wordCount = 2,
            audioDurationSec = 2.0,
            baseline = null,
        )
        assertFalse("No baseline → gap cannot be detected", result.gapDetected)
    }

    @Test fun coldStart_nullBaseline_ratioIsNull() {
        val result = RateGapDetector.analyze(
            wordCount = 5,
            audioDurationSec = 3.0,
            baseline = null,
        )
        assertNull(result.ratio)
    }

    @Test fun coldStart_nullBaseline_wpsIsComputed() {
        // WPS is still computed even without a baseline
        val result = RateGapDetector.analyze(
            wordCount = 6,
            audioDurationSec = 3.0,
            baseline = null,
        )
        assertEquals(2.0, result.wps, 0.0001)
    }

    @Test fun coldStart_nullBaseline_baselineWpsIsNull() {
        val result = RateGapDetector.analyze(wordCount = 5, audioDurationSec = 2.0, baseline = null)
        assertNull(result.baselineWps)
    }

    // ── GAP_RATIO_THRESHOLD boundary ────────────────────

    @Test fun ratioAboveThreshold_noGapDetected() {
        // ratio = wps / baseline; threshold = 0.6
        // wps = 4 / 2.0 = 2.0; baseline = 3.0; ratio = 2.0/3.0 ≈ 0.667 > 0.6
        val result = RateGapDetector.analyze(
            wordCount = 4,
            audioDurationSec = 2.0,
            baseline = 3.0,
        )
        assertFalse("Ratio above threshold should NOT detect a gap", result.gapDetected)
    }

    @Test fun ratioExactlyAtThreshold_noGapDetected() {
        // ratio = 0.6 exactly → condition is ratio < threshold → false → no gap
        // wps = 1.8 / 2.0 = 0.9; baseline = 1.5; ratio = 0.9/1.5 = 0.6 exactly
        val result = RateGapDetector.analyze(
            wordCount = 9,             // wps = 9 / 5.0 = 1.8
            audioDurationSec = 5.0,
            baseline = 3.0,           // ratio = 1.8 / 3.0 = 0.6 exactly
        )
        assertFalse("Ratio exactly at threshold (0.6) must NOT trigger gap (strict <)", result.gapDetected)
    }

    @Test fun ratioBelowThreshold_gapDetected() {
        // wps = 6 / 10.0 = 0.6; baseline = 3.0; ratio = 0.6/3.0 = 0.2 < 0.6
        val result = RateGapDetector.analyze(
            wordCount = 6,
            audioDurationSec = 10.0,
            baseline = 3.0,
        )
        assertTrue("Ratio below threshold should detect a gap", result.gapDetected)
    }

    @Test fun ratioBelowThreshold_ratioValueCorrect() {
        val result = RateGapDetector.analyze(
            wordCount = 3,
            audioDurationSec = 5.0,    // wps = 0.6
            baseline = 3.0,            // ratio = 0.6 / 3.0 = 0.2
        )
        assertEquals(0.2, result.ratio!!, 0.0001)
    }

    // ── WPS calculation correctness ──────────────────────

    @Test fun wps_calculatedCorrectly() {
        val result = RateGapDetector.analyze(
            wordCount = 10,
            audioDurationSec = 4.0,   // wps = 2.5
            baseline = 3.0,
        )
        assertEquals(2.5, result.wps, 0.0001)
    }

    @Test fun ratio_calculatedCorrectly() {
        // wps = 9 / 3.0 = 3.0; baseline = 4.0; ratio = 3.0/4.0 = 0.75
        val result = RateGapDetector.analyze(
            wordCount = 9,
            audioDurationSec = 3.0,
            baseline = 4.0,
        )
        assertEquals(0.75, result.ratio!!, 0.0001)
    }

    // ── Normal speech rate — no gap ─────────────────────

    @Test fun normalSpeech_2wps_baseline3wps_noGap() {
        // ratio ≈ 0.667 > 0.6 → no gap
        val result = RateGapDetector.analyze(
            wordCount = 4,
            audioDurationSec = 2.0,
            baseline = 3.0,
        )
        assertFalse(result.gapDetected)
        assertEquals(4.0 / 2.0, result.wps, 0.0001)
    }

    // ── Disfluent speech — gap detected ─────────────────

    @Test fun disfluent_1wps_baseline4wps_gapDetected() {
        // wps = 4 / 4.0 = 1.0; baseline = 4.0; ratio = 0.25 < 0.6
        val result = RateGapDetector.analyze(
            wordCount = 4,
            audioDurationSec = 4.0,
            baseline = 4.0,
        )
        assertTrue("Severely reduced WPS relative to baseline should trigger gap", result.gapDetected)
    }

    // ── Custom threshold ─────────────────────────────────

    @Test fun customThreshold_higher_detectsMoreGaps() {
        // At default 0.6: ratio=0.667 → no gap. At threshold=0.7: ratio=0.667 < 0.7 → gap.
        val wc = 4
        val dur = 2.0
        val bl = 3.0
        val resultDefault = RateGapDetector.analyze(wc, dur, bl)
        val resultHigher = RateGapDetector.analyze(wc, dur, bl, threshold = 0.7)
        assertFalse(resultDefault.gapDetected)
        assertTrue("Higher threshold should flag the same ratio as a gap", resultHigher.gapDetected)
    }

    // ── Result fields propagated ─────────────────────────

    @Test fun result_baselineWpsIsPassedThrough() {
        val result = RateGapDetector.analyze(
            wordCount = 5,
            audioDurationSec = 2.0,
            baseline = 3.5,
        )
        assertEquals(3.5, result.baselineWps!!, 0.0001)
    }

    @Test fun zeroBaseline_noGap_ratioNull() {
        // baseline == 0.0 → guard against division-by-zero; ratio should be null
        val result = RateGapDetector.analyze(
            wordCount = 5,
            audioDurationSec = 2.0,
            baseline = 0.0,
        )
        assertNull("Zero baseline should produce null ratio (guard against /0)", result.ratio)
        assertFalse("Zero baseline should not detect a gap", result.gapDetected)
    }

    @Test fun zeroWordCount_longRecording_gapDetected() {
        // Recognizer returned 0 words for a 5-second clip → wps=0; baseline=3.0; ratio=0 < 0.6
        val result = RateGapDetector.analyze(
            wordCount = 0,
            audioDurationSec = 5.0,
            baseline = 3.0,
        )
        assertTrue("Zero words on a long recording should be flagged as a gap", result.gapDetected)
        assertEquals(0.0, result.wps, 0.0001)
    }
}
