package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down the post-reconstruction guard behavior so future changes to
 * BubbleService.processText (or a refactored Pipeline class that calls
 * this guard) can't silently regress the leak filter or length-explosion
 * fallback paths.
 *
 * Each test exercises one of the 24 LLM_LEAK_PATTERNS or the >2.5x
 * length ratio guard. Output text on guard-trigger must equal the
 * supplied fallback (the L1-pre-cleaned ASR text).
 */
class ReconstructionGuardTest {

    private val fallback = "I want to go to the store"
    private val fallbackWordCount = 7

    @Test fun `clean text passes through unchanged`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "I'd like to head to the grocery store this afternoon.",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertFalse(r.leakDetected)
        assertNull(r.leakReason)
        assertEquals("I'd like to head to the grocery store this afternoon.", r.outputText)
    }

    @Test fun `apology prefix triggers leak filter and returns fallback`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "I'm sorry, I didn't catch that. Could you please repeat?",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
        assertNotNull(r.leakReason)
        assertEquals(fallback, r.outputText)
    }

    @Test fun `as-an-AI meta-commentary triggers leak filter`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "As an AI language model, I can help reconstruct that.",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
        assertEquals(fallback, r.outputText)
    }

    @Test fun `no-output bracket pattern triggers in mid-string`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "The user said [no output detected]",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
        assertEquals(fallback, r.outputText)
    }

    @Test fun `length explosion above 2 point 5x triggers guard`() {
        // 7-word fallback → 25-word reconstruction = 3.57x ratio, above 2.5x.
        val r = ReconstructionGuard.apply(
            reconstructed = "I would love to go to the store and pick up some groceries and then walk back home through the park slowly today this afternoon perhaps",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
        assertEquals(fallback, r.outputText)
        assertTrue(r.leakReason!!.contains("length explosion"))
    }

    @Test fun `length expansion below 2 point 5x is allowed`() {
        // 7-word fallback → 17-word reconstruction = 2.43x ratio, under 2.5x.
        val expanded = "I would like to go down to the corner store and pick up some milk"
        val r = ReconstructionGuard.apply(
            reconstructed = expanded,
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertFalse(r.leakDetected)
        assertEquals(expanded, r.outputText)
    }

    @Test fun `length guard is skipped on very short inputs`() {
        // Fallback under 3 words — guard not applied. A single-word raw
        // legitimately expanding to "I'd like to go." (4 words = 4x) is OK.
        val r = ReconstructionGuard.apply(
            reconstructed = "I would like to go to the store.",
            fallbackText = "go",
            fallbackWordCount = 1,
        )
        assertFalse(r.leakDetected)
        assertEquals("I would like to go to the store.", r.outputText)
    }

    @Test fun `case-insensitive matching catches uppercase apology`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "I'M SORRY — I missed that.",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
    }

    @Test fun `leading whitespace is stripped before leak match`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "    \n  I cannot assist with that request.",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
    }

    @Test fun `unable to transcribe pattern triggers`() {
        val r = ReconstructionGuard.apply(
            reconstructed = "I was unable to transcribe the audio.",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
    }
}
