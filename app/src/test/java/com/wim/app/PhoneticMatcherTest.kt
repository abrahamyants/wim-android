package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for PhoneticMatcher — verifies phonetic-neighbor swaps happen
 * when and only when they should. Uses Robolectric so android.util.Log calls
 * inside PhoneticMatcher don't throw in JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneticMatcherTest {

    // Common high-risk onset weights (subset from PhoneticRisk defaults).
    private val highRiskOnsets = mapOf(
        "d" to 0.65, "w" to 0.30, "k" to 0.85, "p" to 0.85, "s" to 0.50, "sm" to 0.70
    )

    @Test
    fun emptyVocabulary_returnsInputUnchanged() {
        val out = PhoneticMatcher.correct("hello there", emptyList(), highRiskOnsets)
        assertEquals("hello there", out)
    }

    @Test
    fun blankInput_returnsBlank() {
        val out = PhoneticMatcher.correct("", listOf("Dankeschön"), highRiskOnsets)
        assertEquals("", out)
    }

    @Test
    fun phoneticSwap_commonSurname_occurs() {
        // Smith / Smyth share Double Metaphone code (SM0).
        val out = PhoneticMatcher.correct(
            "please ask smyth about it",
            listOf("Smith"),
            highRiskOnsets
        )
        assertTrue("expected 'Smith' in output, got '$out'", out.contains("Smith", ignoreCase = true))
        // Case-preserving: "smyth" was lowercase so swap should be lowercase "smith".
        assertEquals("please ask smith about it", out)
    }

    @Test
    fun identicalWordAlreadyInVocab_noSwap() {
        val out = PhoneticMatcher.correct(
            "please ask Smith about it",
            listOf("Smith"),
            highRiskOnsets
        )
        assertEquals("please ask Smith about it", out)
    }

    @Test
    fun lowRiskOnset_noSwap_whenHighRiskFilterActive() {
        // "rose" starts with "r" which is NOT in our highRiskOnsets map → filter
        // suppresses the swap even if Metaphone codes match.
        val out = PhoneticMatcher.correct(
            "the roze bloomed",
            listOf("Rose"),
            highRiskOnsets
        )
        assertEquals("the roze bloomed", out)
    }

    @Test
    fun emptyOnsetMap_matchesAnyOnset() {
        // With no onset data (new user), phonetic matcher applies to all onsets.
        val out = PhoneticMatcher.correct(
            "the roze bloomed",
            listOf("Rose"),
            emptyMap()  // no filter
        )
        assertTrue("expected 'rose' in output with empty onsetMap, got '$out'", out.contains("rose", ignoreCase = true))
    }

    @Test
    fun shortWord_noSwap() {
        // Words < 3 chars are skipped (Metaphone unreliable).
        val out = PhoneticMatcher.correct("so we go", listOf("sew"), highRiskOnsets)
        assertEquals("so we go", out)
    }

    @Test
    fun punctuationPreserved() {
        val out = PhoneticMatcher.correct(
            "ask smyth, then leave.",
            listOf("Smith"),
            highRiskOnsets
        )
        assertEquals("ask smith, then leave.", out)
    }

    @Test
    fun multipleSwaps_independent() {
        // Both words pass the first-letter guard (s=s, m=m).
        val out = PhoneticMatcher.correct(
            "tell smyth and markas the plan",
            listOf("Smith", "Marcus"),
            highRiskOnsets + mapOf("m" to 0.7)
        )
        assertTrue("expected smith: '$out'", out.contains("smith", ignoreCase = true))
        assertTrue("expected marcus: '$out'", out.contains("marcus", ignoreCase = true))
    }

    @Test
    fun firstLetterConstraint_preventsFalsePositives() {
        // Regression: "bitch" and "push" share Double-Metaphone "PX" because
        // the algorithm collapses b/p, t/d, etc. to the same plosive code.
        // First-letter guard must prevent this cross-consonant swap even
        // when both words happen to be on high-risk onsets.
        val out = PhoneticMatcher.correct(
            "claude is a bitch",
            listOf("push"),   // vocabulary happens to contain "push"
            mapOf("b" to 0.7, "p" to 0.85)  // both are high-risk
        )
        assertEquals("claude is a bitch", out)
    }

    @Test
    fun firstLetterConstraint_tinDinNoSwap() {
        // Another Metaphone collision: "tin"/"din" both TN. Different first
        // letter → no swap.
        val out = PhoneticMatcher.correct(
            "pass the tin",
            listOf("din"),
            mapOf("t" to 0.8, "d" to 0.65)
        )
        assertEquals("pass the tin", out)
    }

    @Test
    fun casePreservation_allUpper() {
        val out = PhoneticMatcher.correct(
            "tell SMYTH the news",
            listOf("Smith"),
            highRiskOnsets
        )
        assertTrue("expected SMITH uppercase: '$out'", out.contains("SMITH"))
    }

    // ── A word already in the vocabulary is never swapped ──
    //
    // The identity test used to sit inside the candidate loop, so it only ran
    // if the exact entry was reached first. With both spellings in the
    // vocabulary the loop hit the sibling, matched, and returned before ever
    // seeing the word the speaker actually said:
    //
    //     vocabulary ["Smyth", "Smith"], speaker says "Smith" -> "Smyth"
    //
    // Jon/John, Sara/Sarah and Katherine/Kathryn all sit in ordinary contact
    // lists, and this rewrote the name the speaker got right.

    @Test
    fun identicalWord_notSwapped_whenSiblingIsEarlierInVocabulary() {
        val out = PhoneticMatcher.correct(
            "please ask Smith about it",
            listOf("Smyth", "Smith"),
            highRiskOnsets
        )
        assertEquals("please ask Smith about it", out)
    }

    @Test
    fun identicalWord_notSwapped_regardlessOfCase() {
        val out = PhoneticMatcher.correct(
            "please ask smith about it",
            listOf("Smyth", "Smith"),
            highRiskOnsets
        )
        assertEquals("please ask smith about it", out)
    }

    @Test
    fun siblingStillSwapsWhenTheWordIsNotInTheVocabulary() {
        val out = PhoneticMatcher.correct(
            "please ask Smyth about it",
            listOf("Smith"),
            highRiskOnsets
        )
        assertEquals("please ask Smith about it", out)
    }
}
