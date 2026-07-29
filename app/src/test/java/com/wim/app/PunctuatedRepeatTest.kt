package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Local and cloud ASR must clean the same utterance the same way.
 *
 * Measured on device 2026-07-29: "i i i need to um to reschedule the the thing
 * on tuesday" came back as "I need to reschedule the thing on Tuesday." on the
 * local engine and "I, I, I need to reschedule the thing on Tuesday." through
 * the cloud call. Cloud Whisper punctuates as it transcribes, and the
 * repeated-word rule only matched repeats separated by a bare space — so the
 * comma form was never stripped. Every cloud-ASR user was affected.
 */
class PunctuatedRepeatTest {

    private fun clean(text: String) = DisfluencyFilter.filter(text, emptyList(), null)

    @Test fun stripsCommaSeparatedRepeats() {
        assertEquals(
            "I need to reschedule the thing on Tuesday.",
            clean("I, I, I need to reschedule the thing on Tuesday."),
        )
    }

    @Test fun stripsBareSpaceRepeatsStill() {
        assertEquals(
            "I need to reschedule the thing on Tuesday",
            clean("i i i need to reschedule the the thing on tuesday")
                .replaceFirstChar { it.uppercase() }
                .replace("tuesday", "Tuesday"),
        )
    }

    @Test fun localAndCloudFormsAgree() {
        val local = clean("i i i need the the report")
        val cloud = clean("I, I, I need the, the report")
        assertEquals(local.lowercase().trimEnd('.'), cloud.lowercase().trimEnd('.'))
    }

    // ── Emphatic repeats must still survive ───────────────────────

    @Test fun emphaticNoNoSurvivesWithComma() {
        val out = clean("No, no, that's not what I meant")
        assertEquals("No, no, that's not what I meant", out)
    }

    @Test fun emphaticNoNoSurvivesWithoutComma() {
        assertEquals("no no that's not right", clean("no no that's not right"))
    }

    @Test fun threeInARowIsDisfluencyEvenForNaturalPairs() {
        // "no no" is emphatic; "no no no" is a stutter run — the count check
        // already draws that line, and punctuation must not change it.
        assertEquals("No that's wrong", clean("No, no, no that's wrong"))
    }
}
