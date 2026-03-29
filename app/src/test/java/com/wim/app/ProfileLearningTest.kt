package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the position-aware learning algorithm logic.
 * Since ProfileManager needs SharedPreferences (Android),
 * we test the core matching algorithm directly.
 */
class ProfileLearningTest {

    // ── Position-aware word matching ───────────────────

    @Test fun positionMatch_simpleSubstitution() {
        // "gonna" at position 1 should match "going" at position 1, not "to" at position 2
        val result = findBestMatch("gonna", 1,
            rawWords = listOf("i", "gonna", "make"),
            cleanWords = listOf("i'm", "going", "to", "make")
        )
        assertEquals("going", result)
    }

    @Test fun positionMatch_noJunkPromotion() {
        // Should NOT match "i'm" (position 0) for "gonna" (position 1)
        // because "going" (position 1) is closer
        val result = findBestMatch("gonna", 1,
            rawWords = listOf("i", "gonna", "make", "the", "thing"),
            cleanWords = listOf("i'm", "going", "to", "make", "the", "thing")
        )
        assertNotEquals("i'm", result)
    }

    @Test fun positionMatch_tooFarAway() {
        // Word at position 0, closest candidate at position 5 — too far (> 2 positions)
        val result = findBestMatch("bad", 0,
            rawWords = listOf("bad", "a", "b", "c", "d"),
            cleanWords = listOf("a", "b", "c", "d", "e", "good")
        )
        assertNull("Should not match if > 2 positions away", result)
    }

    @Test fun positionMatch_exactPosition() {
        val result = findBestMatch("dunno", 2,
            rawWords = listOf("i", "just", "dunno"),
            cleanWords = listOf("i", "just", "don't", "know")
        )
        assertEquals("don't", result)
    }

    @Test fun positionMatch_noCandidates() {
        // All clean words also in raw — nothing to learn
        val result = findBestMatch("hello", 0,
            rawWords = listOf("hello", "world"),
            cleanWords = listOf("hello", "world")
        )
        assertNull(result)
    }

    @Test fun positionMatch_shortWordsFiltered() {
        // Candidates shorter than 3 chars should be filtered
        val result = findBestMatch("gonna", 1,
            rawWords = listOf("i", "gonna"),
            cleanWords = listOf("i", "to")  // "to" is only 2 chars
        )
        assertNull("Should not match words < 3 chars", result)
    }

    // ── Threshold verified via matching logic ───────────

    @Test fun threshold_firstMatchDoesNotPromote() {
        // A single match should return the word, but ProfileManager's
        // count logic requires 3 occurrences before promoting.
        // Here we just verify the MATCHING finds the right word.
        val result = findBestMatch("gonna", 0,
            rawWords = listOf("gonna", "work"),
            cleanWords = listOf("going", "work")
        )
        assertEquals("Match found on first try", "going", result)
        // The count threshold (3) is tested via ProfileManagerTest with Robolectric
    }

    // ── Helper: replicates ProfileManager logic ────────

    private fun findBestMatch(
        rawWord: String,
        rawIdx: Int,
        rawWords: List<String>,
        cleanWords: List<String>
    ): String? {
        val cleanPositions = cleanWords.mapIndexed { i, w -> w to i }
        val rawSet = rawWords.toSet()

        val candidates = cleanPositions
            .filter { (w, _) -> w !in rawSet && w.length >= 3 }
            .sortedBy { (_, pos) -> kotlin.math.abs(pos - rawIdx) }

        val best = candidates.firstOrNull()
        return if (best != null && kotlin.math.abs(best.second - rawIdx) <= 2) {
            best.first
        } else {
            null
        }
    }
}
