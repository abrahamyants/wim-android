package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Stress tests for DisfluencyFilter — adversarial inputs,
 * edge cases, Unicode, long strings, nested patterns.
 */
class DisfluencyFilterStressTest {

    private val fillers = listOf("um", "uh", "like", "you know", "i mean", "э", "ну")

    // ── Adversarial inputs ─────────────────────────────

    @Test fun allFillers() {
        // Entire utterance is fillers — should keep at least something
        val result = filter("um uh like um uh")
        assertTrue("Should not be empty", result.isNotBlank())
    }

    @Test fun singleWord() {
        assertEquals("hello", filter("hello"))
    }

    @Test fun singleFiller_kept() {
        assertEquals("um", filter("um"))
    }

    @Test fun onlySpaces() {
        assertEquals("   ", filter("   "))
    }

    @Test fun veryLongInput() {
        val input = "I " + "really ".repeat(500) + "want to go"
        val result = filter(input)
        assertTrue("Should not crash on long input", result.isNotEmpty())
    }

    @Test fun noLetters_justNumbers() {
        assertEquals("123 456 789", filter("123 456 789"))
    }

    @Test fun punctuationOnly() {
        val result = filter("... --- !!!")
        assertTrue("Should handle punctuation", result.isNotBlank())
    }

    // ── Unicode / Russian ──────────────────────────────

    @Test fun russianFillers_removed() {
        val result = filter("э я хочу ну пойти", fillers)
        assertFalse("Should remove э", result.contains("э "))
        assertFalse("Should remove ну", result.contains("ну "))
        assertTrue("Should keep content", result.contains("хочу") || result.contains("пойти"))
    }

    @Test fun mixedLanguage() {
        val result = filter("um я want uh to go ну домой")
        assertTrue("Should keep 'want'", result.contains("want"))
        assertTrue("Should keep 'домой'", result.contains("домой"))
    }

    @Test fun emojiInput() {
        val result = filter("I 😀 want to go 🎉")
        assertTrue("Should not crash on emoji", result.contains("want"))
    }

    // ── Nested / overlapping patterns ──────────────────

    @Test fun stutterInsideRepeat() {
        // "I I w- w- want" — both word repeat AND stutter fragment
        val result = filter("I I w- w- want")
        assertTrue("Should produce 'want'", result.contains("want"))
    }

    @Test fun prolongationInsideStutter() {
        // "sss- sss- so" — prolongation + stutter fragment
        val result = filter("sss- sss- so")
        assertTrue("Should contain 'so' or 's'", result.contains("s"))
    }

    @Test fun fillerBetweenStutters() {
        val result = filter("I w- um w- want to go")
        assertTrue("Should contain 'want'", result.contains("want"))
    }

    @Test fun multiplePatterns_complex() {
        val result = filter("um I I w- w- want to like go to the the store uh yeah")
        assertTrue("Contains 'want'", result.contains("want"))
        assertTrue("Contains 'store'", result.contains("store"))
    }

    // ── Prolongation edge cases ────────────────────────

    @Test fun prolongation_allSameChar() {
        // "aaaa" — all same char, no trailing word
        val result = filter("aaaa")
        assertEquals("a", result)
    }

    @Test fun prolongation_twoCharsOnly_noChange() {
        // "aab" — only 2 of same char, should NOT trigger
        assertEquals("aab", filter("aab"))
    }

    @Test fun prolongation_middleOfSentence() {
        // "like" is in default fillers, so it gets stripped too
        assertEquals("I really it", filter("I rrrrreally like it"))
    }

    @Test fun prolongation_multipleInSentence() {
        val result = filter("sssso I wwwwant to ggggo")
        assertTrue("Contains 'so'", result.contains("so"))
        assertTrue("Contains 'want'", result.contains("want"))
        assertTrue("Contains 'go'", result.contains("go"))
    }

    // ── False start edge cases ─────────────────────────

    @Test fun falseStart_noCapitalAfterDash() {
        // No capital letter after dashes — should NOT strip
        val input = "I was going to-- but then"
        val result = filter(input)
        assertTrue("Should keep content", result.contains("but") || result.contains("going"))
    }

    @Test fun falseStart_multipleDashes() {
        val result = filter("I think--- Actually I know")
        assertTrue("Should keep 'Actually I know'", result.contains("know"))
    }

    // ── Word repeat edge cases ─────────────────────────

    @Test fun wordRepeat_differentCase() {
        // "The the store" — case-insensitive repeat
        val result = filter("The the store")
        assertTrue("Should contain 'store'", result.contains("store"))
    }

    @Test fun wordRepeat_fiveRepeats() {
        assertEquals("go", filter("go go go go go"))
    }

    // ── Stutter fragment edge cases ────────────────────

    @Test fun stutterFragment_longPrefix() {
        val result = filter("ber- ber- berlin")
        assertTrue("Should contain 'berlin'", result.contains("berlin"))
    }

    @Test fun stutterFragment_withPunctuation() {
        val result = filter("I w- w- want, please.")
        assertTrue("Should contain 'want'", result.contains("want"))
        assertTrue("Should preserve punctuation", result.contains("please"))
    }

    // ── Idempotence ────────────────────────────────────

    @Test fun idempotent_cleanInput() {
        val clean = "I went to the store and bought groceries"
        assertEquals("Clean input should pass through unchanged", clean, filter(clean))
    }

    @Test fun idempotent_doubleFilter() {
        val input = "um I w- w- want to uh go"
        val first = filter(input)
        val second = filter(first)
        assertEquals("Double-filtering should be idempotent", first, second)
    }

    // ── Helper ─────────────────────────────────────────

    private fun filter(text: String, f: List<String> = fillers): String {
        return DisfluencyFilter.filter(text, f)
    }
}
