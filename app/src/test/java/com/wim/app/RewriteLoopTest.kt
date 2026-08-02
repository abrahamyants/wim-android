package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Device-direct Layer 2/3 retry behavior (port of run_layer2_rewrite).
 * The signed-in backend has its own copy of this loop server-side; these
 * tests cover the device-direct route only.
 */
class RewriteLoopTest {

    private fun runLoop(
        raw: String,
        outputs: List<String>,
        vocabulary: List<String> = emptyList(),
    ): Triple<MeaningGuard.RewriteOutcome, Int, MutableList<String>> {
        var calls = 0
        val repairs = mutableListOf<String>()
        val outcome = MeaningGuard.runRewriteLoop(
            rawText = raw,
            vocabulary = vocabulary,
            rewriteOnce = { _ ->
                val out = outputs.getOrElse(calls) { outputs.last() }
                calls += 1
                out
            },
            appendRepair = { _, instruction -> repairs.add(instruction) },
        )
        return Triple(outcome, calls, repairs)
    }

    @Test fun acceptsGoodRewriteFirstTry() {
        val (outcome, calls, _) = runLoop(
            "can you send me the report by friday",
            listOf("Please send me the report by Friday."),
        )
        assertEquals(1, calls)
        assertEquals(1, outcome.attempts)
        assertTrue(outcome.guard.ok)
    }

    @Test fun retriesEmptyOutput() {
        val (outcome, calls, repairs) = runLoop(
            "send the report",
            listOf("", "Send the report over when you can."),
        )
        assertEquals(2, calls)
        assertTrue(repairs.first().contains("previous answer was empty"))
        assertEquals("Send the report over when you can.", outcome.cleanText)
    }

    @Test fun retriesUnchangedOutput() {
        val (outcome, calls, repairs) = runLoop(
            "send the report now",
            listOf("Send the report now.", "Please send that report right away."),
        )
        assertEquals(2, calls)
        assertTrue(repairs.first().contains("merely repeated the transcript"))
        assertTrue(outcome.guard.ok)
    }

    @Test fun retriesLostAnchorWithExactFeedback() {
        val (_, calls, repairs) = runLoop(
            "wire 42 dollars to Henderson",
            listOf(
                "Wire the money over.", // dropped 42 and Henderson
                "Wire 42 dollars to Henderson today.",
            ),
        )
        assertEquals(2, calls)
        assertTrue(repairs.first().contains("'Henderson'"))
        assertTrue(repairs.first().contains("'42'"))
    }

    @Test fun stopsAtThreeAttempts() {
        val (outcome, calls, _) = runLoop(
            "send the report now",
            listOf("send the report now", "send the report now", "send the report now"),
        )
        assertEquals(3, calls)
        assertEquals(3, outcome.attempts)
        // Exhausted with an unchanged-only failure: output kept, flagged unchanged.
        assertTrue(outcome.guard.unchanged)
        assertFalse(outcome.guard.ok)
    }

    @Test fun shortInputsAreStillReconstructed() {
        // No length gate: a two-word input goes through the same loop.
        val (outcome, calls, _) = runLoop(
            "send it",
            listOf("Please send it."),
        )
        assertEquals(1, calls)
        assertTrue(outcome.cleanText.isNotBlank())
    }

    @Test fun exhaustedLoopKeepsTheSafeEarlierAttempt() {
        // Attempt 1 kept every anchor and only failed the must-change rule.
        // Attempts 2 and 3 dropped the date. Returning attempt 3 would make the
        // caller treat the whole reconstruction as failed and paste the raw.
        val (outcome, calls, _) = runLoop(
            "send the report to henderson by friday",
            listOf(
                "send the report to henderson by friday",
                "Send the report to Henderson.",
                "Send the report over to Henderson.",
            ),
        )
        assertEquals(3, calls)
        assertEquals("send the report to henderson by friday", outcome.cleanText)
        assertTrue(outcome.guard.lost.isEmpty())
        assertTrue(outcome.guard.unchanged)
    }

    @Test fun exhaustedLoopWithNoSafeAttemptKeepsTheLastOne() {
        // Every attempt dropped the date — nothing safe to fall back to, so the
        // caller still sees a hard failure and can paste the raw itself.
        val (outcome, _, _) = runLoop(
            "send the report to henderson by friday",
            listOf(
                "Send the report to Henderson.",
                "Send it to Henderson.",
                "Send Henderson the report.",
            ),
        )
        assertEquals("Send Henderson the report.", outcome.cleanText)
        assertTrue(outcome.guard.lost.isNotEmpty())
        assertFalse(outcome.guard.ok)
    }

    @Test fun inventedAnchorTriggersRetry() {
        val (_, calls, repairs) = runLoop(
            "please forward the file to subscribe at the london office",
            listOf(
                "Please forward the file to Henderson at the London office.",
                "Please forward the file to the London office.",
            ),
        )
        assertEquals(2, calls)
        assertTrue(repairs.first().contains("invented protected content"))
        assertTrue(repairs.first().contains("'Henderson'"))
    }
}
