package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PhoneticMatcher.neighbours] — the third tier of the clarifier's
 * candidate-priority claim, which until 2026-07-25 was never populated by
 * anything and so silently did not exist at runtime.
 */
class PhoneticNeighboursTest {

    @Test fun findsAVocabularyWordThatSoundsTheSame() {
        val out = PhoneticMatcher.neighbours("fone", listOf("phone", "banana"))
        assertTrue("expected 'phone' in $out", out.contains("phone"))
    }

    @Test fun doesNotOfferUnrelatedVocabulary() {
        val out = PhoneticMatcher.neighbours("fone", listOf("banana", "elephant"))
        assertTrue("expected nothing, got $out", out.isEmpty())
    }

    @Test fun neverOffersTheWordItself() {
        val out = PhoneticMatcher.neighbours("phone", listOf("phone", "fone"))
        assertFalse("must not suggest the word the user already has", out.contains("phone"))
    }

    @Test fun caseInsensitiveOnTheSelfCheck() {
        val out = PhoneticMatcher.neighbours("Phone", listOf("PHONE"))
        assertTrue("case-different same word is still the same word: $out", out.isEmpty())
    }

    @Test fun trailingPunctuationDoesNotBreakTheMatch() {
        // Clarifier words arrive trimmed, but a stray comma must not silently
        // produce zero candidates and make the flag undismissable-by-approve.
        val out = PhoneticMatcher.neighbours("fone,", listOf("phone"))
        assertTrue("expected 'phone' in $out", out.contains("phone"))
    }

    @Test fun tooShortToBeWorthMatching() {
        assertTrue(PhoneticMatcher.neighbours("hi", listOf("high")).isEmpty())
    }

    @Test fun emptyVocabularyIsEmptyResult() {
        assertTrue(PhoneticMatcher.neighbours("fone", emptyList()).isEmpty())
    }

    @Test fun respectsTheMaxCandidateCap() {
        val out = PhoneticMatcher.neighbours(
            "kat",
            listOf("cat", "cate", "katt", "kate", "cot"),
            max = 2,
        )
        assertTrue("cap not honoured: $out", out.size <= 2)
    }

    @Test fun deduplicatesRepeatedVocabularyEntries() {
        val out = PhoneticMatcher.neighbours("fone", listOf("phone", "phone", "phone"))
        assertEquals(1, out.size)
    }
}
