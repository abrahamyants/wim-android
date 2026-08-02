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

    // ── The speaker's own words are not a leak ──────────────────────
    //
    // These phrases were matched as bare substrings anywhere in the output, so
    // dictating an apology threw away the reconstruction and pasted the raw
    // disfluent ASR. The raw transcript now decides: if the speaker said it,
    // the model carrying it through is faithful, not a leak.

    @Test fun `dictated apology is not a leak`() {
        val raw = "i i i am sorry i will be late for the meeting"
        val r = ReconstructionGuard.apply(
            reconstructed = "I'm sorry, I'll be late for the meeting.",
            fallbackText = raw,
            fallbackWordCount = 12,
        )
        assertFalse(r.leakDetected)
        assertEquals("I'm sorry, I'll be late for the meeting.", r.outputText)
    }

    @Test fun `dictated apologize is not a leak`() {
        val raw = "please apologize to them for the the delay"
        val r = ReconstructionGuard.apply(
            reconstructed = "I apologize for the delay.",
            fallbackText = raw,
            fallbackWordCount = 7,
        )
        assertFalse(r.leakDetected)
    }

    @Test fun `dictated cannot help is not a leak`() {
        val raw = "tell him i i cant help with the move this weekend"
        val r = ReconstructionGuard.apply(
            reconstructed = "Tell him I can't help with the move this weekend.",
            fallbackText = raw,
            fallbackWordCount = 10,
        )
        assertFalse(r.leakDetected)
    }

    @Test fun `dictated please clarify is not a leak`() {
        val raw = "please clarify what you need from me"
        val r = ReconstructionGuard.apply(
            reconstructed = "Please clarify what you need from me.",
            fallbackText = raw,
            fallbackWordCount = 7,
        )
        assertFalse(r.leakDetected)
    }

    @Test fun `model apology on an unrelated raw is still a leak`() {
        // Same phrase, but nothing in the raw motivates it.
        val r = ReconstructionGuard.apply(
            reconstructed = "I'm sorry, could you please repeat that?",
            fallbackText = fallback,
            fallbackWordCount = fallbackWordCount,
        )
        assertTrue(r.leakDetected)
        assertEquals(fallback, r.outputText)
    }

    @Test fun `machine-only string leaks regardless of raw`() {
        // No stem escape hatch for these — nobody dictates them.
        val r = ReconstructionGuard.apply(
            reconstructed = "As an AI, I think you meant the store.",
            fallbackText = "as an ai i think you meant the store",
            fallbackWordCount = 9,
        )
        assertTrue(r.leakDetected)
    }
}
