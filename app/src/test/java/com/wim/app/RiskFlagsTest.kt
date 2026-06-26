package com.wim.app

import org.junit.Assert.*
import org.junit.Test

class RiskFlagsTest {

    // ── Critical token retention ───────────────────────

    @Test fun criticalToken_numberPreserved() {
        val flags = compute("I need 42 items", "I need 42 items")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_numberLost() {
        val flags = compute("I need 42 items", "I need some items")
        assertTrue(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_dollarPreserved() {
        val flags = compute("It costs \$500", "It costs \$500")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_dollarLost() {
        val flags = compute("It costs \$500", "It costs money")
        assertTrue(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_percentPreserved() {
        val flags = compute("90% done", "90% done")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_percentLost() {
        val flags = compute("90% done", "almost done")
        assertTrue(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_noNumbers_noFlag() {
        val flags = compute("hello world", "hello there world")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_selfCorrection_notFlagged() {
        // "I mean" retracts the 3 — dropping it is correct, not a loss.
        val flags = compute("meeting at 3pm, I mean 4pm", "meeting at 4pm")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_currencyReformatting_notFlagged() {
        // "5 dollars" reformatted to "$5" is the same value, not a dropped token.
        val flags = compute("it was 5 dollars", "it was \$5")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_realDrop_stillFlagged() {
        // No self-correction marker and the 42 genuinely vanished — must flag.
        val flags = compute("I need 42 items", "I need items")
        assertTrue(flags.contains("critical_token_lost"))
    }

    // ── Very short output ──────────────────────────────

    @Test fun veryShort_twoChars() {
        val flags = compute("I want to go to the store", "ok")
        assertTrue(flags.contains("very_short_output"))
    }

    @Test fun veryShort_normal() {
        val flags = compute("hello", "hello there")
        assertFalse(flags.contains("very_short_output"))
    }

    // ── Large length delta ─────────────────────────────

    @Test fun lengthDelta_tooShort() {
        val flags = compute("I want to go to the store and buy groceries", "go")
        assertTrue(flags.contains("large_length_delta"))
    }

    @Test fun lengthDelta_tooLong() {
        val flags = compute("hi", "Well hello there my friend how are you doing on this fine evening")
        assertTrue(flags.contains("large_length_delta"))
    }

    @Test fun lengthDelta_normal() {
        val flags = compute("I want to go to the store", "I want to go to the store")
        assertFalse(flags.contains("large_length_delta"))
    }

    // ── Unfinished fragment ────────────────────────────

    @Test fun unfinished_ellipsis() {
        val flags = compute("hello", "hello...")
        assertTrue(flags.contains("contains_unfinished_fragment"))
    }

    @Test fun unfinished_emDash() {
        val flags = compute("hello", "hello\u2014")
        assertTrue(flags.contains("contains_unfinished_fragment"))
    }

    @Test fun unfinished_normal() {
        val flags = compute("hello", "hello")
        assertFalse(flags.contains("contains_unfinished_fragment"))
    }

    // ── Clean pass ─────────────────────────────────────

    @Test fun cleanPass_noFlags() {
        val flags = compute(
            "I need to pick up groceries from the store",
            "I need to pick up groceries from the store"
        )
        assertTrue("Should have no flags", flags.isEmpty())
    }

    // ── Helper ─────────────────────────────────────────

    private fun compute(raw: String, clean: String): List<String> {
        return ReconstructClient.computeRiskFlags(raw, clean)
    }
}
