package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared corpus, run through the whole Kotlin filter.
 *
 * `test_pipeline_sweep.py` in the lavrentiy repo runs this same corpus against
 * the Python engine, and its parity stage compares the two sets of PATTERNS.
 * Patterns matching is not the same as output matching: the Kotlin filter has
 * stages Python does not (false starts, a 2+ word-repeat threshold rather than
 * 3+), so this file holds the Kotlin side to the same invariants directly.
 *
 * Each case states what must SURVIVE and what must be GONE, rather than pinning
 * exact output, so ordinary wording changes do not break it while the things
 * that matter stay guarded.
 */
class DisfluencyCorpusTest {

    // Single words only. A multi-word entry can never match: the filler stage
    // splits on whitespace and compares one token at a time, so "you know" and
    // "i mean" pass straight through. That is invisible from the API, which
    // accepts any string — including from the user's own profile.
    private val fillers = listOf("um", "uh", "like", "er")

    private fun filter(text: String) = DisfluencyFilter.filter(text, fillers)

    // ── Artifacts the speaker did not say must be gone ──────────────

    @Test fun blockedRepetitionCollapses() {
        val out = filter("i i i want to go to the store")
        assertFalse(out.lowercase().contains("i i"))
        assertTrue(out.contains("store"))
    }

    @Test fun unspacedStutterOnsetsCollapse() {
        val out = filter("c-c-can you s-s-send me the report by by friday")
        assertFalse(out.contains("c-c-"))
        assertFalse(out.contains("s-s-"))
        assertTrue(out.contains("can"))
        assertTrue(out.contains("send"))
        assertTrue(out.contains("friday"))
    }

    @Test fun titlesAndNamesSurviveTheirOwnStutter() {
        val out = filter(
            "tell d-d-doctor williams my son m-m-michael has an appointment on the the fifteenth"
        )
        assertFalse(out.contains("d-d-"))
        assertFalse(out.contains("m-m-"))
        for (anchor in listOf("doctor", "williams", "michael", "fifteenth")) {
            assertTrue("lost $anchor from $out", out.contains(anchor))
        }
    }

    @Test fun prolongationsCollapse() {
        val out = filter("mmmmaybe we should just leave it yessss")
        assertTrue(out.contains("maybe"))
        assertTrue(out.contains("yes"))
        assertFalse(out.contains("mmmm"))
        assertFalse(out.contains("yessss"))
    }

    // ── The speaker's own words must survive ────────────────────────

    @Test fun emphaticDoublingSurvives() {
        assertTrue(filter("no no that is not what i meant").lowercase().contains("no no"))
    }

    @Test fun naturalRepeatSurvives() {
        assertTrue(filter("bye bye see you tomorrow").lowercase().contains("bye bye"))
    }

    @Test fun hyphenatedCompoundsSurvive() {
        val out = filter("the co- founder signed the pre- existing agreement")
        assertTrue(out.contains("co- founder"))
        assertTrue(out.contains("pre- existing"))
    }

    @Test fun negationSurvives() {
        val out = filter("i i do not want to to cancel the appointment")
        assertTrue(out.contains("not"))
        assertTrue(out.contains("cancel"))
    }

    @Test fun fluentInputIsLeftAlone() {
        assertEquals("please send me the report tomorrow",
            filter("please send me the report tomorrow"))
    }

    // ── Figures must come through untouched ─────────────────────────

    @Test fun formattedAmountsSurvive() {
        val out = filter("transfer \$1,200 and 90% of the balance today")
        assertTrue(out.contains("\$1,200"))
        assertTrue(out.contains("90%"))
    }

    @Test fun roundAmountsSurvive() {
        // The prolongation stage used to collapse runs of identical DIGITS, so
        // "1000" became "10" and "2000" became "20".
        val out = filter("wire 1000 dollars and 2000 more")
        assertTrue(out.contains("1000"))
        assertTrue(out.contains("2000"))
    }

    // ── The self-correction marker is load-bearing ──────────────────

    @Test fun selfCorrectionMarkerSurvivesTheFilter() {
        // ReconstructClient.checkCriticalTokens reads "i mean" out of the raw
        // text to know which figure was superseded and may legitimately vanish.
        // Stripping it here would silently break that exemption and start
        // flagging correct reconstructions as having lost an amount, so any
        // future support for multi-word fillers has to leave this one alone.
        val out = filter("the meeting is at 3 pm i mean 4 pm on tuesday")
        assertTrue("self-correction marker was stripped: $out", out.contains("i mean"))
        assertTrue(out.contains("3"))
        assertTrue(out.contains("4"))
        assertTrue(out.contains("tuesday"))
    }

    // ── Degenerate inputs ───────────────────────────────────────────

    @Test fun singleWordIsUntouched() {
        assertEquals("stop", filter("stop"))
    }

    @Test fun anAllFillerUtteranceStillReturnsSomething() {
        assertTrue(filter("um uh er").isNotBlank())
    }

    @Test fun emptyInputStaysEmpty() {
        assertEquals("", filter(""))
    }
}
