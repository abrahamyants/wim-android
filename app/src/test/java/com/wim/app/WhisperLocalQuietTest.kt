package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Quiet Mode on the local route.
 *
 * BubbleService boosts the WAV that feeds the CLOUD route. WhisperLocalEngine
 * decodes its own float buffer and never reads that file, so neither the
 * manual Quiet Mode toggle nor the automatic boost reached the local route —
 * the default, offline path. A whispered take stayed whispered, and the
 * speaker had to lean into the microphone.
 *
 * Same threshold (-22 dBFS, lavrentiy.py:263) and same +18dB gain as the
 * cloud side, so a take transcribes the same whichever route it took.
 */
class WhisperLocalQuietTest {

    private fun noise(amplitude: Float, n: Int = 16000): FloatArray {
        val r = Random(7)
        return FloatArray(n) { (r.nextFloat() * 2f - 1f) * amplitude }
    }

    @Test fun emptyTakeReportsFloor() {
        assertEquals(-120.0, WhisperLocalEngine.meanDbfs(FloatArray(0)), 0.001)
    }

    @Test fun aWhisperSoftTakeIsBoosted() {
        val soft = noise(0.01f)
        val before = WhisperLocalEngine.meanDbfs(soft)
        assertTrue("expected a soft take, got $before dBFS", before < -22.0)
        assertTrue(WhisperLocalEngine.shouldAutoBoost(before))

        WhisperLocalEngine.applyQuietBoost(soft)
        val after = WhisperLocalEngine.meanDbfs(soft)
        assertTrue("boost should raise the level: $before -> $after", after > before + 10.0)
    }

    @Test fun aNormalTakeIsLeftAlone() {
        val normal = noise(0.2f)
        val level = WhisperLocalEngine.meanDbfs(normal)
        assertTrue("expected a normal take, got $level dBFS", level > -22.0)
        assertFalse(WhisperLocalEngine.shouldAutoBoost(level))
    }

    @Test fun silenceIsNotBoosted() {
        // Pure digital silence sits below the floor. Boosting it would only
        // amplify whatever noise the microphone invented.
        assertFalse(WhisperLocalEngine.shouldAutoBoost(-120.0))
        assertFalse(WhisperLocalEngine.shouldAutoBoost(-130.0))
    }

    @Test fun boostNeverClips() {
        val samples = noise(0.5f)
        WhisperLocalEngine.applyQuietBoost(samples)
        for (s in samples) {
            assertTrue("sample out of range: $s", s in -1f..1f)
        }
    }
}
