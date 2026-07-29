package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tone is a property of the message, not of the app or the recipient
 * (operator 2026-07-29). These lock the classifier's behavior on the cases
 * that argument was made with.
 */
class ToneFromContentTest {

    private fun tone(text: String, vocab: List<String> = emptyList()): String? =
        when (val v = ToneFromContent.classify(text, vocab)) {
            is ToneFromContent.Verdict.Use -> v.tone
            is ToneFromContent.Verdict.Ambiguous -> null
        }

    // ── Work ──────────────────────────────────────────────────────

    @Test fun deadlineAndName() {
        assertEquals(
            ToneRouting.PROFESSIONAL,
            tone("let Henderson know I need the invoice no later than Friday")
        )
    }

    @Test fun title() {
        assertEquals(
            ToneRouting.PROFESSIONAL,
            tone("tell Dr. Williams my son has an appointment on the fifteenth")
        )
    }

    @Test fun moneyAndReference() {
        assertEquals(ToneRouting.PROFESSIONAL, tone("wire $4,200 against invoice 88213"))
    }

    @Test fun businessFraming() {
        assertEquals(ToneRouting.PROFESSIONAL, tone("following up regarding the attached quote"))
    }

    @Test fun ownVocabularyCountsAsWork() {
        // A generic list can't know this speaker's field. Their own profile can.
        assertEquals(
            ToneRouting.PROFESSIONAL,
            tone("run the deposition prep tonight", vocab = listOf("deposition"))
        )
    }

    // ── Casual ────────────────────────────────────────────────────

    @Test fun socialPlans() {
        assertEquals(ToneRouting.CASUAL, tone("you coming tonight or nah"))
    }

    @Test fun slang() {
        assertEquals(ToneRouting.CASUAL, tone("yo I'm gonna be late haha"))
    }

    @Test fun family() {
        assertEquals(ToneRouting.CASUAL, tone("tell mom I'll call her later"))
    }

    @Test fun profanityBeatsALoneWorkMarker() {
        // A message to a friend ABOUT work is not a work message.
        assertEquals(ToneRouting.CASUAL, tone("tell Henderson this whole thing is bullshit"))
    }

    // ── The app is not the signal ─────────────────────────────────

    @Test fun sameAppBothRegisters() {
        // Exactly the operator's objection: one app, two tones.
        assertEquals(ToneRouting.PROFESSIONAL, tone("please confirm the deadline for the contract"))
        assertEquals(ToneRouting.CASUAL, tone("wanna grab a beer later"))
    }

    // ── Ambiguous — the small remainder the question is for ───────

    @Test fun bareRequestIsAmbiguous() {
        assertEquals(null, tone("can you send that over"))
        assertEquals(null, tone("call me"))
    }

    @Test fun blankIsAmbiguous() {
        assertEquals(null, tone(""))
    }

    // ── Clock only breaks ties, and only when hours are meaningful ──

    @Test fun clockSaysNothingWhenHoursAreFlexible() {
        assertEquals(null, ToneFromContent.breakTieByClock(14, false, "flexible"))
        assertEquals(null, ToneFromContent.breakTieByClock(14, false, null))
    }

    @Test fun clockWithinNineToFiveIsWork() {
        assertEquals(
            ToneRouting.PROFESSIONAL,
            ToneFromContent.breakTieByClock(11, false, "nine_to_five")
        )
    }

    @Test fun midnightIsCasualOnATie() {
        assertEquals(
            ToneRouting.CASUAL,
            ToneFromContent.breakTieByClock(0, false, "nine_to_five")
        )
    }

    @Test fun weekendIsCasual() {
        assertEquals(
            ToneRouting.CASUAL,
            ToneFromContent.breakTieByClock(11, true, "nine_to_five")
        )
    }

    @Test fun midnightDoesNotOverrideWorkWords() {
        // The clock never gets consulted when the words already decided —
        // midnight is exactly when the urgent work message gets sent.
        assertEquals(
            ToneRouting.PROFESSIONAL,
            tone("let Henderson know I need the invoice no later than Friday")
        )
    }

    @Test fun reasonIsReported() {
        val v = ToneFromContent.classify("please confirm the deadline")
        assertTrue(v is ToneFromContent.Verdict.Use)
        assertTrue((v as ToneFromContent.Verdict.Use).why.isNotBlank())
    }
}
