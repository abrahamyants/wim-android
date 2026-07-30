package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile term must not eat an ordinary English word.
 *
 * Measured on device 2026-07-29: "I need you to send the Henderson invoice
 * before Friday" came back as "I need you to Git the Henderson invoice before
 * Friday". A vocabulary entry captured a common verb in a sentence that had
 * nothing to do with it. The approximate-match blocklist existed for exactly
 * this — it already stopped could→Claude — but contained only function words,
 * so every everyday verb was unprotected.
 *
 * Approximate matching runs only on words the ASR flagged uncertain, so these
 * tests pass the target word in via lowConfTexts. That is the condition under
 * which the capture actually happened; without it the path is never entered
 * and the test would pass for the wrong reason.
 */
class CommonWordCaptureTest {

    private fun recover(
        text: String,
        vocabulary: List<String> = emptyList(),
        corrections: Map<String, String> = emptyMap(),
        uncertain: List<String> = listOf(text),
    ): String = ProfileTermRecovery.apply(
        text = text,
        corrections = corrections,
        vocabulary = vocabulary,
        lowConfTexts = uncertain,
    ).first

    // ── The failure that shipped ──────────────────────────────────

    @Test fun vocabularyDoesNotEatGet() {
        val out = recover("I need you to get the invoice", vocabulary = listOf("Git"))
        assertEquals("I need you to get the invoice", out)
    }

    @Test fun vocabularyDoesNotEatSend() {
        val out = recover("I need you to send the invoice", vocabulary = listOf("Git"))
        assertTrue("send was captured: $out", out.contains("send"))
    }

    @Test fun claudeStillDoesNotEatCould() {
        val out = recover("could you check that", vocabulary = listOf("Claude"))
        assertTrue("could was captured: $out", out.contains("could"))
    }

    // ── Other everyday words, same shape ──────────────────────────

    @Test fun vocabularyDoesNotEatMake() {
        val out = recover("make the call", vocabulary = listOf("Mako"))
        assertTrue("make was captured: $out", out.contains("make"))
    }

    @Test fun vocabularyDoesNotEatSaid() {
        val out = recover("he said no", vocabulary = listOf("Sedd"))
        assertTrue("said was captured: $out", out.contains("said"))
    }

    @Test fun vocabularyDoesNotEatWork() {
        val out = recover("work on it tonight", vocabulary = listOf("Werk"))
        assertTrue("work was captured: $out", out.contains("work"))
    }

    @Test fun vocabularyDoesNotEatTime() {
        val out = recover("what time is it", vocabulary = listOf("Tyme"))
        assertTrue("time was captured: $out", out.contains("time"))
    }

    // ── Real recovery must still happen ───────────────────────────

    @Test fun distinctiveTermIsStillRecovered() {
        val out = recover("we use kubernetis for that", vocabulary = listOf("Kubernetes"))
        assertTrue("expected Kubernetes in: $out", out.contains("Kubernetes"))
    }

    @Test fun exactCorrectionOnACommonWordStillApplies() {
        // The blocklist gates approximate matching only. A user who explicitly
        // saved a correction still gets it applied.
        val out = recover("send the file", corrections = mapOf("send" to "Send"))
        assertTrue("explicit correction was dropped: $out", out.contains("Send"))
    }

    @Test fun properNounNextToACommonWordSurvives() {
        val out = recover("send it to hendersen", vocabulary = listOf("Henderson"))
        assertTrue("expected Henderson in: $out", out.contains("Henderson"))
        assertTrue("send was captured: $out", out.contains("send"))
    }
}
