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
        val out = PhoneticMatcher.correct(
            "tell smyth and karol the plan",
            listOf("Smith", "Carol"),
            highRiskOnsets
        )
        assertTrue("expected smith: '$out'", out.contains("smith", ignoreCase = true))
        assertTrue("expected carol: '$out'", out.contains("carol", ignoreCase = true))
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
}
