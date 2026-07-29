package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatsTest {

    private fun session(
        raw: String,
        clean: String,
        speechMs: Long = 0L,
        asrMs: Long = 0L,
    ) = SessionHistory.Session(
        timestamp = 0L,
        rawText = raw,
        cleanText = clean,
        tone = "casual",
        layer = 2,
        situation = "default",
        asrMs = asrMs,
        reconMs = 0L,
        totalMs = 0L,
        falconApproved = true,
        riskFlags = "",
        speechMs = speechMs,
    )

    // ── WPM ───────────────────────────────────────────────────────

    @Test fun wpmUsesSpeakingTimeNotProcessingTime() {
        // 10 words spoken in 30s = 20 wpm. The old code divided by asrMs,
        // which carried an extra 3s of transcription and would have said 18.
        val s = session(
            raw = "one two three four five six seven eight nine ten",
            clean = "one two three four five six seven eight nine ten",
            speechMs = 30_000L,
            asrMs = 33_000L,
        )
        assertEquals(20, SessionStats.wpm(listOf(s)))
    }

    @Test fun wpmSkipsRowsWithNoMeasuredSpeech() {
        // Pre-v3 rows hold 0 and must not be averaged in as free time.
        val old = session("a b c d", "a b c d", speechMs = 0L, asrMs = 60_000L)
        val new = session("one two", "one two", speechMs = 60_000L)
        assertEquals(2, SessionStats.wpm(listOf(old, new)))
    }

    @Test fun wpmIsZeroWhenNothingMeasured() {
        assertEquals(0, SessionStats.wpm(listOf(session("a b", "a b"))))
        assertEquals(0, SessionStats.wpm(emptyList()))
    }

    // ── Cleanup ───────────────────────────────────────────────────

    @Test fun cleanupIsPercentOfWordsRemoved() {
        // 10 raw words -> 6 clean = 40%
        val s = session(
            raw = "um so i i i wanted to ask you",
            clean = "I wanted to ask you",
        )
        assertEquals(44, SessionStats.averageCleanupPercent(listOf(s)))
    }

    @Test fun cleanupNeverGoesNegativeWhenReconstructionAddsWords() {
        // A block leaves a gap that context fills — output longer than input.
        val s = session(raw = "send it", clean = "Please send the report over to me.")
        assertEquals(0, SessionStats.averageCleanupPercent(listOf(s)))
    }

    @Test fun cleanupIsZeroForNoSessions() {
        assertEquals(0, SessionStats.averageCleanupPercent(emptyList()))
    }

    // ── Words ─────────────────────────────────────────────────────

    @Test fun totalWordsCountsCleanOutput() {
        val a = session("x", "one two three")
        val b = session("y", "four five")
        assertEquals(5, SessionStats.totalWords(listOf(a, b)))
    }
}
