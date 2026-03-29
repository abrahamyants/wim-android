package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Stress tests for risk flag computation — edge cases,
 * boundary conditions, adversarial inputs.
 */
class RiskFlagsStressTest {

    // ── Empty / null-like inputs ───────────────────────

    @Test fun emptyRaw_emptyClean() {
        val flags = compute("", "")
        assertTrue(flags.contains("very_short_output"))
    }

    @Test fun emptyRaw_normalClean() {
        // Empty raw = ratio defaults to 1.0 (code guards against div-by-zero)
        val flags = compute("", "hello world")
        assertFalse("Empty raw defaults ratio to 1.0, no delta flag", flags.contains("large_length_delta"))
    }

    @Test fun normalRaw_emptyClean() {
        val flags = compute("hello world", "")
        assertTrue(flags.contains("very_short_output"))
        assertTrue(flags.contains("large_length_delta"))
    }

    @Test fun singleChar_output() {
        val flags = compute("hello", "x")
        assertTrue(flags.contains("very_short_output"))
    }

    @Test fun threeChar_output_notShort() {
        val flags = compute("hello", "hey")
        assertFalse("3 chars should not be very_short", flags.contains("very_short_output"))
    }

    // ── Length delta boundaries ─────────────────────────

    @Test fun lengthRatio_exactly30percent() {
        // 10 chars raw, 3 chars clean = 0.3 ratio (boundary)
        val flags = compute("1234567890", "abc")
        // 0.3 is the boundary — < 0.3 triggers
        assertFalse("0.3 ratio should NOT trigger (boundary)", flags.contains("large_length_delta"))
    }

    @Test fun lengthRatio_below30percent() {
        val flags = compute("12345678901", "ab")
        assertTrue("< 0.3 ratio should trigger", flags.contains("large_length_delta"))
    }

    @Test fun lengthRatio_exactly300percent() {
        // 3 chars raw, 9 chars clean = 3.0 ratio (boundary)
        val flags = compute("abc", "123456789")
        // 3.0 is boundary — > 3.0 triggers
        assertFalse("3.0 ratio should NOT trigger (boundary)", flags.contains("large_length_delta"))
    }

    @Test fun lengthRatio_above300percent() {
        val flags = compute("ab", "1234567890")
        assertTrue("> 3.0 ratio should trigger", flags.contains("large_length_delta"))
    }

    // ── Critical tokens — complex patterns ─────────────

    @Test fun criticalToken_commaNumber() {
        val flags = compute("It costs 1,500 dollars", "It costs 1,500 dollars")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_commaNumberLost() {
        val flags = compute("It costs 1,500 dollars", "It costs money")
        assertTrue(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_decimalNumber() {
        val flags = compute("The rate is 3.14", "The rate is 3.14")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_multipleNumbers() {
        val flags = compute("I need 5 apples and 3 oranges", "I need 5 apples and 3 oranges")
        assertFalse(flags.contains("critical_token_lost"))
    }

    @Test fun criticalToken_oneOfTwoLost() {
        val flags = compute("I need 5 apples and 3 oranges", "I need 5 apples and some oranges")
        assertTrue(flags.contains("critical_token_lost"))
    }

    // ── Fragment detection ──────────────────────────────

    @Test fun fragment_tripleDot() {
        assertTrue(compute("hello", "hello...").contains("contains_unfinished_fragment"))
    }

    @Test fun fragment_singleDot_notFragment() {
        assertFalse(compute("hello", "hello.").contains("contains_unfinished_fragment"))
    }

    @Test fun fragment_regularDash_notFragment() {
        assertFalse(compute("hello", "hello-world").contains("contains_unfinished_fragment"))
    }

    // ── Multiple flags simultaneously ──────────────────

    @Test fun multipleFlags_shortAndDelta() {
        val flags = compute("I need to buy 42 items from the store today", "x")
        assertTrue(flags.contains("very_short_output"))
        assertTrue(flags.contains("large_length_delta"))
        assertTrue(flags.contains("critical_token_lost"))
    }

    @Test fun multipleFlags_fragmentAndTokenLost() {
        val flags = compute("Buy 10 items", "Buy items...")
        assertTrue(flags.contains("critical_token_lost"))
        assertTrue(flags.contains("contains_unfinished_fragment"))
    }

    // ── Clean pass stress ──────────────────────────────

    @Test fun cleanPass_longIdenticalStrings() {
        val text = "The quick brown fox jumps over the lazy dog. " +
            "Pack my box with five dozen liquor jugs. " +
            "How vexingly quick daft zebras jump."
        val flags = compute(text, text)
        assertTrue("Identical long strings = no flags", flags.isEmpty())
    }

    @Test fun cleanPass_minorRephrase() {
        val flags = compute(
            "I want to go to the store",
            "I'd like to go to the store"
        )
        assertFalse("Minor rephrase should not lose tokens", flags.contains("critical_token_lost"))
        assertFalse("Similar length should not trigger delta", flags.contains("large_length_delta"))
    }

    // ── Helper ─────────────────────────────────────────

    private fun compute(raw: String, clean: String): List<String> {
        return ReconstructClient.computeRiskFlags(raw, clean)
    }
}
