package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a long recording gets cut into 30-second pieces.
 *
 * The window used to end at exactly `start + 30s`, a stopwatch mark with
 * nothing to do with speech. A word straddling it was cut in half and both
 * halves decoded as nonsense; a two-minute recording has three such seams,
 * and Whisper is stateless across streams so nothing downstream can repair it.
 *
 * chooseSplit keeps the 30s ceiling and backs off to the quietest 20ms frame
 * in the two seconds before it — a pause between words.
 */
class WhisperLocalSeamTest {

    private val sampleRate = 16000
    private val window = sampleRate * 30

    private fun loud(seconds: Int) = FloatArray(sampleRate * seconds) { 0.5f }

    private fun loudWithPause(seconds: Int, pauseAtSec: Double, pauseLenSec: Double): FloatArray {
        val out = loud(seconds)
        val from = (sampleRate * pauseAtSec).toInt()
        val to = from + (sampleRate * pauseLenSec).toInt()
        for (i in from until minOf(to, out.size)) out[i] = 0f
        return out
    }

    @Test fun shortRecordingIsOneChunk() {
        val samples = loud(10)
        assertEquals(samples.size, WhisperLocalEngine.chooseSplit(samples, 0))
    }

    @Test fun cutLandsInThePauseNotOnTheStopwatch() {
        val samples = loudWithPause(seconds = 40, pauseAtSec = 29.0, pauseLenSec = 0.3)
        val cut = WhisperLocalEngine.chooseSplit(samples, 0)
        val pauseFrom = (sampleRate * 29.0).toInt()
        val pauseTo = pauseFrom + (sampleRate * 0.3).toInt()
        assertTrue("cut at ${cut / sampleRate.toFloat()}s should be inside the pause",
            cut in pauseFrom..pauseTo)
    }

    @Test fun evenLoudnessFallsBackToTheCeiling() {
        // No pause to find. The old tie-break kept the earliest frame and threw
        // away two seconds of every window; the cut must sit at the ceiling.
        val samples = loud(40)
        val cut = WhisperLocalEngine.chooseSplit(samples, 0)
        assertTrue("cut at $cut should be within 20ms of the ceiling $window",
            window - cut <= sampleRate / 50)
    }

    @Test fun theLastChunkRunsToTheEndOfTheAudio() {
        val samples = loudWithPause(seconds = 40, pauseAtSec = 29.0, pauseLenSec = 0.3)
        val firstCut = WhisperLocalEngine.chooseSplit(samples, 0)
        assertEquals(samples.size, WhisperLocalEngine.chooseSplit(samples, firstCut))
    }

    @Test fun theCutAlwaysAdvances() {
        // Every chunk must consume at least one sample, or the decode loop in
        // stop() would never terminate.
        val samples = loud(95)
        var start = 0
        var chunks = 0
        while (start < samples.size) {
            val end = WhisperLocalEngine.chooseSplit(samples, start)
            assertTrue("split must advance past $start, got $end", end > start)
            start = end
            chunks++
            assertTrue("too many chunks — split is not advancing", chunks < 20)
        }
        assertEquals(4, chunks)
    }
}
