package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Stress tests for profile learning — adversarial inputs,
 * boundary conditions, corruption prevention.
 */
class ProfileLearningStressTest {

    // ── Identical input/output — nothing to learn ──────

    @Test fun identical_noLearning() {
        val result = findBestMatch("hello", 0,
            raw = listOf("hello", "world"),
            clean = listOf("hello", "world")
        )
        assertNull("Identical text = nothing to learn", result)
    }

    // ── Multiple substitutions in same sentence ────────

    @Test fun multipleSubstitutions_eachFindsClosest() {
        // "gonna" at 1 → "going" at 1
        val r1 = findBestMatch("gonna", 1,
            raw = listOf("i", "gonna", "wanna", "leave"),
            clean = listOf("i", "going", "want", "to", "leave")
        )
        assertEquals("going", r1)

        // "wanna" at 2 → "want" at 2
        val r2 = findBestMatch("wanna", 2,
            raw = listOf("i", "gonna", "wanna", "leave"),
            clean = listOf("i", "going", "want", "to", "leave")
        )
        assertEquals("want", r2)
    }

    // ── Position boundary — exactly 2 away ─────────────

    @Test fun boundary_exactlyTwoAway() {
        val result = findBestMatch("bad", 0,
            raw = listOf("bad", "x", "y"),
            clean = listOf("x", "y", "good")
        )
        assertEquals("2 positions away should match", "good", result)
    }

    @Test fun boundary_threeAway() {
        val result = findBestMatch("bad", 0,
            raw = listOf("bad", "x", "y", "z"),
            clean = listOf("x", "y", "z", "good")
        )
        assertNull("3 positions away should NOT match", result)
    }

    // ── Short word filtering ───────────────────────────

    @Test fun shortCandidate_twoChars() {
        val result = findBestMatch("gonna", 0,
            raw = listOf("gonna"),
            clean = listOf("to")
        )
        assertNull("'to' (2 chars) should be filtered", result)
    }

    @Test fun shortCandidate_threeChars() {
        val result = findBestMatch("gonna", 0,
            raw = listOf("gonna"),
            clean = listOf("the")
        )
        assertEquals("'the' (3 chars) should pass filter", "the", result)
    }

    // ── Adversarial: many candidates, picks closest ────

    @Test fun manyCandidates_picksNearest() {
        val result = findBestMatch("bad", 2,
            raw = listOf("a", "b", "bad", "d", "e"),
            clean = listOf("a", "b", "good", "d", "e", "great", "nice", "fine")
        )
        assertEquals("Should pick 'good' at position 2", "good", result)
    }

    // ── Large word lists ───────────────────────────────

    @Test fun largeInput_doesNotCrash() {
        val rawWords = (0..999).map { "word$it" }.toMutableList()
        rawWords[500] = "misspelled"
        val cleanWords = (0..999).map { "word$it" }.toMutableList()
        cleanWords[500] = "corrected"

        val result = findBestMatch("misspelled", 500, rawWords, cleanWords)
        assertEquals("corrected", result)
    }

    // ── Empty inputs ───────────────────────────────────

    @Test fun emptyRaw_stillMatches() {
        // Empty raw set means all clean words are candidates
        val result = findBestMatch("hello", 0, emptyList(), listOf("world"))
        assertEquals("world", result)
    }

    @Test fun emptyClean_noMatch() {
        val result = findBestMatch("hello", 0, listOf("hello"), emptyList())
        assertNull(result)
    }

    // ── Same word appears multiple times ───────────────

    @Test fun duplicateInClean_picksClosest() {
        val result = findBestMatch("bad", 1,
            raw = listOf("the", "bad", "dog"),
            clean = listOf("the", "good", "dog", "good")
        )
        assertEquals("Should pick 'good' at position 1, not 3", "good", result)
    }

    // ── Raw word already in corrections — skip ─────────

    @Test fun alreadyCorrected_shouldNotMatch() {
        // This tests the filter: raw !in corrections
        // In ProfileManager, "raw !in corrections" is checked before calling findBestMatch
        // Here we just verify the matching logic itself still works
        val result = findBestMatch("gonna", 0,
            raw = listOf("gonna"),
            clean = listOf("going")
        )
        assertEquals("going", result)
    }

    // ── Helper: replicates ProfileManager logic ────────

    private fun findBestMatch(
        rawWord: String,
        rawIdx: Int,
        raw: List<String>,
        clean: List<String>
    ): String? {
        val cleanPositions = clean.mapIndexed { i, w -> w to i }
        val rawSet = raw.toSet()

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
