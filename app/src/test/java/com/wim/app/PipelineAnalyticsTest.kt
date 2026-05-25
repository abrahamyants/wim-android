package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in PipelineAnalytics.jaccard arithmetic so the redo-detection
 * heuristic doesn't regress on edge cases — empty strings, full overlap,
 * zero overlap, case mismatch, whitespace differences.
 *
 * recordPredictionsFromOutput intentionally NOT tested here — it mutates
 * the PredictionEngine singleton, which would couple this test to the
 * engine's internal state. Trigram parsing is covered by the existing
 * PredictionEngineTest.
 */
class PipelineAnalyticsTest {

    private fun assertCloseTo(expected: Float, actual: Float, eps: Float = 0.001f) {
        assertTrue("expected ~$expected, got $actual", kotlin.math.abs(expected - actual) < eps)
    }

    @Test fun bothEmptyReturnsOne() {
        assertEquals(1f, PipelineAnalytics.jaccard("", ""), 0.001f)
    }

    @Test fun oneEmptyReturnsZero() {
        assertEquals(0f, PipelineAnalytics.jaccard("", "hello world"), 0.001f)
        assertEquals(0f, PipelineAnalytics.jaccard("hello world", ""), 0.001f)
    }

    @Test fun identicalStringsReturnOne() {
        assertEquals(1f, PipelineAnalytics.jaccard("the quick brown fox", "the quick brown fox"), 0.001f)
    }

    @Test fun completelyDisjointReturnsZero() {
        assertEquals(0f, PipelineAnalytics.jaccard("alpha beta gamma", "delta epsilon zeta"), 0.001f)
    }

    @Test fun partialOverlap() {
        // A = {the, quick, brown, fox, jumps}, B = {the, quick, lazy, fox, dog}.
        // Intersection {the, quick, fox} = 3; union {the, quick, brown, fox,
        // jumps, lazy, dog} = 7. Jaccard = 3/7 ≈ 0.4286.
        val score = PipelineAnalytics.jaccard("the quick brown fox jumps", "the quick lazy fox dog")
        assertCloseTo(3f / 7f, score)
    }

    @Test fun caseInsensitive() {
        assertEquals(1f, PipelineAnalytics.jaccard("Hello World", "hello world"), 0.001f)
    }

    @Test fun whitespaceCollapsesNormally() {
        // Multiple spaces split same as single spaces.
        assertEquals(1f, PipelineAnalytics.jaccard("hello world", "hello    world"), 0.001f)
    }

    @Test fun redoThresholdSanityCheck() {
        // The BubbleService.checkRedo heuristic uses > 0.7 as the redo trigger.
        // Confirm that two near-identical recordings (one word changed) cross
        // that threshold so the redo detector still fires on minor edits.
        val original = "I want to go to the store this afternoon"
        val redo = "I want to go to the store this evening"  // 1 word differs
        val score = PipelineAnalytics.jaccard(original, redo)
        assertTrue("Near-identical should exceed 0.7 redo threshold, got $score", score > 0.7f)
    }
}
