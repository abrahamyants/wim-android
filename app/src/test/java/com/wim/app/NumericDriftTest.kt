package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard could not catch a number that CHANGED — only one invented from
 * nothing. Because "wire four thousand two hundred" contains number words,
 * any figure in the output counted as reformatting, so $1,200 and $1,002 both
 * shipped unchallenged on 2026-07-29, three runs in a row, on a wire-transfer
 * instruction. Values are compared now.
 */
class NumericDriftTest {

    // ── Spelled-out numbers parse to values ───────────────────────

    @Test fun parsesSpelledOutRuns() {
        val v = MeaningGuard.numericValues("wire four thousand two hundred to henderson")
        assertTrue("expected 4200 in $v", v.contains(4200L))
    }

    @Test fun parsesDigitsAndCurrency() {
        val v = MeaningGuard.numericValues("wire \$4,200 today")
        assertTrue(v.contains(4200L))
    }

    @Test fun parsesTeensAndTens() {
        assertTrue(MeaningGuard.numericValues("fifteen people").contains(15L))
        assertTrue(MeaningGuard.numericValues("twenty two dollars").contains(22L))
    }

    // ── The failure that shipped ──────────────────────────────────

    @Test fun catchesTheWireTransferError() {
        val drift = MeaningGuard.checkNumericDrift(
            "wire f-f-four thousand two hundred to h-h-henderson before monday",
            "Wire \$1,200 to Henderson by Tuesday.",
        )
        assertTrue("expected 1,200 flagged, got $drift", drift.any { it.contains("1,200") })
    }

    @Test fun catchesTheSecondWrongAmountToo() {
        val drift = MeaningGuard.checkNumericDrift(
            "wire f-f-four thousand two hundred to h-h-henderson before monday",
            "Wire \$1,002 to Henderson by Tuesday, not Monday.",
        )
        assertTrue(drift.isNotEmpty())
    }

    // ── Legitimate reformatting must NOT be flagged ───────────────

    @Test fun spelledOutToDigitsIsFine() {
        assertEquals(
            emptyList<String>(),
            MeaningGuard.checkNumericDrift("wire four thousand two hundred", "Wire \$4,200.")
        )
    }

    @Test fun commaFormattingIsFine() {
        assertEquals(
            emptyList<String>(),
            MeaningGuard.checkNumericDrift("send 4200 dollars", "Send \$4,200.")
        )
    }

    @Test fun noNumbersInInputMeansNoDriftCheck() {
        // checkFabrication owns the invented-from-nothing case; drift only
        // compares against numbers the speaker actually said.
        assertEquals(
            emptyList<String>(),
            MeaningGuard.checkNumericDrift("send the report", "Send the 3 reports.")
        )
    }

    // ── End to end through the guard ──────────────────────────────

    @Test fun guardRejectsAWrongAmount() {
        val g = MeaningGuard.guard(
            "wire four thousand two hundred to henderson before monday",
            "Wire \$1,200 to Henderson by Tuesday.",
        )
        assertTrue(!g.ok)
        assertTrue(g.invented.isNotEmpty())
    }

    @Test fun guardAcceptsCorrectReformatting() {
        val g = MeaningGuard.guard(
            "wire four thousand two hundred to henderson before monday",
            "Wire \$4,200 to Henderson before Monday.",
        )
        assertTrue("unexpected: lost=${g.lost} invented=${g.invented}", g.ok)
    }

    // ── critical_token_lost and self-correction ─────────────────────
    //
    // The exemption for self-corrections used to be blanket: one "I mean"
    // anywhere in the utterance switched the numeric check off for every number
    // in it. Self-corrections are constant in disfluent speech, so the guard
    // was absent from a large share of real utterances — including the amount
    // drops it exists to catch.

    private fun lostFlag(raw: String, clean: String) =
        ReconstructClient.computeRiskFlags(raw, clean).contains("critical_token_lost")

    @Test fun supersededValueMayDrop() {
        assertFalse(lostFlag("the meeting is at 3 pm i mean 4 pm on tuesday",
            "The meeting is at 4 PM on Tuesday."))
    }

    @Test fun selfCorrectionDoesNotExcuseAnUnrelatedAmount() {
        // "I mean" corrects the folder count. The 5 has nothing to do with it
        // and vanished anyway.
        assertTrue(lostFlag("send 5 copies and 10 folders i mean 12 folders",
            "Send 12 folders."))
    }

    @Test fun correctedValueDropsWhileOthersSurvive() {
        assertFalse(lostFlag("send 5 copies and 10 folders i mean 12 folders",
            "Send 5 copies and 12 folders."))
    }

    @Test fun selfCorrectionWithNoReplacementStillProtectsTheAmount() {
        assertTrue(lostFlag("wire 4200 to henderson i mean before monday",
            "Wire to Henderson before Monday."))
    }

    @Test fun markerBeforeTheNumberGivesItNoCover() {
        assertTrue(lostFlag("i mean send 500 dollars", "Send dollars."))
    }

    @Test fun theDocumentedL4AmountFailureIsCaught() {
        assertTrue(lostFlag("wire 4200 before monday", "Wire \$1,200 before Monday."))
    }

    @Test fun reformattingIsNotDrift() {
        assertFalse(lostFlag("transfer \$1,000 today", "Transfer 1000 today."))
        assertFalse(lostFlag("call me at 90% capacity", "Call me at 90% capacity."))
    }

    @Test fun noWaitAlsoScopesToTheCorrectedValue() {
        assertFalse(lostFlag("meet at 3 no wait 4 and bring 12 chairs",
            "Meet at 4 and bring 12 chairs."))
    }
}
