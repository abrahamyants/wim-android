package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode while the speaker is still talking.
 *
 * The whole take used to be decoded after the stop tap, so the wait grew
 * with the take (about 1 s per 15 s spoken on the operator's phone). Now the
 * capture thread cuts a piece at a real pause once 12 s of un-decoded audio
 * has piled up, and decodes it in the background. choosePauseCut is the pure
 * part: where that cut lands, and when there is no honest place to cut yet.
 */
class WhisperLocalEarlyDecodeTest {

    private val sampleRate = 16000

    private fun loud(seconds: Double, level: Float = 0.5f) =
        FloatArray((sampleRate * seconds).toInt()) { level }

    private fun withDip(samples: FloatArray, atSec: Double, lenSec: Double, level: Float): FloatArray {
        val from = (sampleRate * atSec).toInt()
        val to = from + (sampleRate * lenSec).toInt()
        for (i in from until minOf(to, samples.size)) samples[i] = level
        return samples
    }

    private fun sec(cut: Int) = cut / sampleRate.toFloat()

    @Test fun nothingIsCutBeforeTwelveSeconds() {
        val samples = withDip(loud(10.0), atSec = 9.0, lenSec = 0.3, level = 0f)
        assertEquals(-1, WhisperLocalEngine.choosePauseCut(samples, 0, samples.size))
    }

    @Test fun theCutLandsInTheRealPause() {
        val samples = withDip(loud(14.0), atSec = 13.0, lenSec = 0.3, level = 0f)
        val cut = WhisperLocalEngine.choosePauseCut(samples, 0, samples.size)
        assertTrue("cut at ${sec(cut)}s should be inside the 13.0–13.3s pause",
            cut in (sampleRate * 13.0).toInt()..(sampleRate * 13.3).toInt())
    }

    @Test fun noPauseMeansNotYet() {
        // Even loudness all the way: the seam hunt of chooseSplit would pick a
        // stopwatch mark here; the early cut must refuse and wait for a pause.
        val samples = loud(14.0)
        assertEquals(-1, WhisperLocalEngine.choosePauseCut(samples, 0, samples.size))
    }

    @Test fun aDipBetweenSyllablesIsNotAPause() {
        // 60% of the speaking level is a soft syllable, not a gap between phrases.
        val samples = withDip(loud(14.0), atSec = 13.0, lenSec = 0.3, level = 0.3f)
        assertEquals(-1, WhisperLocalEngine.choosePauseCut(samples, 0, samples.size))
    }

    @Test fun aPauseOlderThanTwoSecondsIsIgnored() {
        // Only the newest 2 s are searched, so the cut stays near the live edge.
        val samples = withDip(loud(14.0), atSec = 5.0, lenSec = 0.3, level = 0f)
        assertEquals(-1, WhisperLocalEngine.choosePauseCut(samples, 0, samples.size))
    }

    @Test fun theLatestPauseWins() {
        var samples = withDip(loud(14.0), atSec = 12.3, lenSec = 0.2, level = 0f)
        samples = withDip(samples, atSec = 13.5, lenSec = 0.2, level = 0f)
        val cut = WhisperLocalEngine.choosePauseCut(samples, 0, samples.size)
        assertTrue("cut at ${sec(cut)}s should sit in the later 13.5–13.7s pause",
            cut in (sampleRate * 13.5).toInt()..(sampleRate * 13.7).toInt())
    }

    @Test fun theCutRespectsTheStartOffset() {
        // Second early piece: the window is measured from where the last cut
        // ended, not from the top of the buffer.
        val samples = withDip(loud(30.0), atSec = 25.0, lenSec = 0.3, level = 0f)
        val start = (sampleRate * 12.0).toInt()
        val cut = WhisperLocalEngine.choosePauseCut(samples, start, (sampleRate * 26.0).toInt())
        assertTrue("cut at ${sec(cut)}s should be inside the 25.0–25.3s pause",
            cut in (sampleRate * 25.0).toInt()..(sampleRate * 25.3).toInt())
        assertEquals(-1, WhisperLocalEngine.choosePauseCut(samples, start, (sampleRate * 23.0).toInt()))
    }
}
