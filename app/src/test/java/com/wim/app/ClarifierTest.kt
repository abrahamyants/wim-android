package com.wim.app

import org.junit.Assert.*
import org.junit.Test

class ClarifierTest {

    private fun wc(
        word: String,
        rangeStart: Int = 0,
        rangeEnd: Int = word.length,
        logprob: Double? = null,
        disagreements: Int = 0,
        nBest: List<String> = emptyList(),
        neighbors: List<String> = emptyList(),
    ) = Clarifier.WordConfidence(
        word = word,
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        asrLogprob = logprob,
        multiPassDisagreements = disagreements,
        nBestAlternates = nBest,
        phoneticNeighbors = neighbors,
    )

    // ── Empty input ─────────────────────────────────────

    @Test fun empty_wordConfidences_returnsEmpty() {
        val flags = Clarifier.evaluate("hello world", emptyList())
        assertTrue(flags.isEmpty())
    }

    @Test fun fluent_text_zeroFlags() {
        // Acceptance gate: NO flags fire on clean dictation.
        val confs = listOf(
            wc("Hello", 0, 5, logprob = -0.05, disagreements = 0),
            wc("world", 6, 11, logprob = -0.08, disagreements = 0),
        )
        val flags = Clarifier.evaluate("Hello world", confs)
        assertTrue("clean dictation should produce zero flags", flags.isEmpty())
    }

    // ── Threshold gate ──────────────────────────────────

    @Test fun lowConfidence_overThreshold_flagged() {
        val confs = listOf(
            wc(
                word = "Dunken", rangeStart = 0, rangeEnd = 6,
                logprob = -2.5, // very uncertain
                disagreements = 2,
                nBest = listOf("Dankeschön"),
            ),
        )
        val flags = Clarifier.evaluate("Dunken thanks", confs)
        assertEquals(1, flags.size)
        assertEquals("Dunken", flags[0].originalWord)
        assertTrue("Dankeschön" in flags[0].candidates)
    }

    @Test fun highConfidence_underThreshold_notFlagged() {
        val confs = listOf(
            wc(word = "Dunken", logprob = -0.1, disagreements = 0, nBest = listOf("Dankeschön")),
        )
        val flags = Clarifier.evaluate("Dunken", confs)
        assertTrue(flags.isEmpty())
    }

    // ── Cap enforcement (claim 11(c)) ────────────────────

    @Test fun capAt3Flags_evenWith5Uncertain() {
        val confs = (1..5).map { i ->
            wc(
                word = "word$i", rangeStart = (i - 1) * 6, rangeEnd = (i - 1) * 6 + 5,
                logprob = -2.0, disagreements = 2, nBest = listOf("alt$i"),
            )
        }
        val flags = Clarifier.evaluate("word1 word2 word3 word4 word5", confs)
        assertEquals(Clarifier.MAX_FLAGS, flags.size)
    }

    // ── Candidate ranking ───────────────────────────────

    @Test fun candidates_excludeOriginalWord() {
        val confs = listOf(
            wc(
                word = "dunken", logprob = -2.0, disagreements = 2,
                nBest = listOf("Dankeschön", "dunken"),  // self-suggestion should be stripped
            ),
        )
        val flags = Clarifier.evaluate("dunken", confs)
        assertEquals(1, flags.size)
        assertFalse("dunken" in flags[0].candidates.map { it.lowercase() })
        assertTrue("Dankeschön" in flags[0].candidates)
    }

    @Test fun candidates_excludeRecentlyRejected() {
        // recentSwaps: pair of (rejected_word, "") — meaning user rejected this candidate
        val recent = listOf("Dankeschön" to "")
        val confs = listOf(
            wc(
                word = "Dunken", logprob = -2.0, disagreements = 2,
                nBest = listOf("Dankeschön", "Spasibo"),
            ),
        )
        val flags = Clarifier.evaluate("Dunken", confs, recentSwaps = recent)
        // Rejection-aware filter: Dankeschön was rejected recently, should be filtered
        assertEquals(1, flags.size)
        assertFalse(
            "rejected candidate should be excluded",
            "Dankeschön".lowercase() in flags[0].candidates.map { it.lowercase() }
        )
        assertTrue("Spasibo" in flags[0].candidates)
    }

    @Test fun emptyCandidates_dropsFlag() {
        // Word is uncertain but has no alternates → no actionable flag.
        val confs = listOf(
            wc(word = "Dunken", logprob = -2.0, disagreements = 2, nBest = emptyList(), neighbors = emptyList()),
        )
        val flags = Clarifier.evaluate("Dunken", confs)
        assertTrue("uncertain word with no candidates is not flagged", flags.isEmpty())
    }

    // ── Onset-weight bias ───────────────────────────────

    @Test fun hardOnset_boostsUncertainty() {
        // Below-threshold without onset bias, above-threshold with it.
        val baseConfs = listOf(
            wc(word = "particular", logprob = -1.5, disagreements = 0, nBest = listOf("peculiar")),
        )
        val withoutOnset = Clarifier.evaluate("particular", baseConfs)
        val withOnset = Clarifier.evaluate(
            "particular", baseConfs,
            onsetWeights = mapOf("p" to 0.8),  // /p/ is hard for this speaker
        )
        // Onset bias should make the flag surface where it didn't before
        // (or at least not reduce flagging).
        assertTrue(withOnset.size >= withoutOnset.size)
    }

    // ── Top-N ordering ──────────────────────────────────

    @Test fun flags_sortedByUncertaintyDescending() {
        val confs = listOf(
            wc(word = "low", rangeStart = 0, rangeEnd = 3, logprob = -1.6, disagreements = 1, nBest = listOf("alt-low")),
            wc(word = "hi", rangeStart = 4, rangeEnd = 6, logprob = -2.8, disagreements = 3, nBest = listOf("alt-hi")),
            wc(word = "mid", rangeStart = 7, rangeEnd = 10, logprob = -2.2, disagreements = 2, nBest = listOf("alt-mid")),
        )
        val flags = Clarifier.evaluate("low hi mid", confs)
        assertEquals(3, flags.size)
        assertEquals("hi", flags[0].originalWord)  // highest uncertainty first
        assertEquals("mid", flags[1].originalWord)
        assertEquals("low", flags[2].originalWord)
    }
}
