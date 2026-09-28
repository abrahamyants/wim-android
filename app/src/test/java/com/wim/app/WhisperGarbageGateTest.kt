package com.wim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garbage gate for Whisper's "I heard nothing" transcripts (9/28 R. R. R. take). */
class WhisperGarbageGateTest {
    private fun seg(t: String) = WhisperClient.TimedSegment(0.0, 1.0, t)
    private fun low(t: String) = WhisperClient.LowConfidenceSegment(t, -2.0, 0.9, true)
    private fun r(text: String, segs: List<String> = emptyList(), lows: List<String> = emptyList()) =
        WhisperClient.TranscribeResult(
            text = text, asrMs = 1,
            timedSegments = segs.map(::seg),
            lowConfidenceSegments = lows.map(::low),
        )

    @Test fun repeatedLetterIsGarbage() =
        assertTrue(WhisperClient.isGarbageTranscript(r("R. R. R. R. R. R. R. R. R. R.")))

    @Test fun singleLetterIsGarbage() =
        assertTrue(WhisperClient.isGarbageTranscript(r("R.")))

    @Test fun allSegmentsLowConfidenceIsGarbage() =
        assertTrue(WhisperClient.isGarbageTranscript(
            r("um so uh", segs = listOf("um", "so", "uh"), lows = listOf("um", "so", "uh"))))

    @Test fun realRepeatedSpeechPasses() =
        assertFalse(WhisperClient.isGarbageTranscript(r("no no no no no no")))

    @Test fun normalSentencePasses() =
        assertFalse(WhisperClient.isGarbageTranscript(
            r("Okay, go check the log.", segs = listOf("Okay, go check the log."), lows = listOf("Okay, go check the log."))))

    @Test fun someLowConfidenceSegmentsPass() =
        assertFalse(WhisperClient.isGarbageTranscript(
            r("what does our bubble show", segs = listOf("what does", "our bubble", "show"), lows = listOf("show"))))

    @Test fun blankPasses() =
        assertFalse(WhisperClient.isGarbageTranscript(r("")))
}
