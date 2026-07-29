package com.wim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE toggle governs the whole window above the bubble — live partials
 * AND pipeline-stage callouts, which share one surface. Operator report
 * 2026-07-29: "a live transcription window pops up above the bubble even
 * though the live option is toggled off."
 */
class StageBarVisibilityTest {

    private fun show(
        liveOn: Boolean = false,
        demoActive: Boolean = false,
        l1Cloud: Boolean = false,
        force: Boolean = false,
    ) = BubbleLiveTranscript.shouldShowStage(liveOn, demoActive, l1Cloud, force)

    @Test fun liveOff_staysSilent() {
        assertFalse(show(liveOn = false))
    }

    @Test fun liveOn_showsStages() {
        assertTrue(show(liveOn = true))
    }

    @Test fun liveOff_l1Local_staysSilent() {
        // L1 local now shares the reconstruction path (2026-07-29 parity
        // change), which fires "Cleaning up" / "Cleaned" stages. Those must
        // not surface a window the user switched off.
        assertFalse(show(liveOn = false, l1Cloud = false))
    }

    @Test fun liveOn_l1Cloud_staysSilentOnFastPath() {
        assertFalse(show(liveOn = true, l1Cloud = true))
    }

    @Test fun demoScript_overridesLiveOff() {
        assertTrue(show(liveOn = false, demoActive = true))
    }

    @Test fun demoScript_overridesL1CloudSilence() {
        assertTrue(show(liveOn = true, l1Cloud = true, demoActive = true))
    }

    @Test fun force_showsOnboardingSignOffWithLiveOff() {
        // Fires right after the demo script clears itself, so demoActive is
        // already false by then.
        assertTrue(show(liveOn = false, demoActive = false, force = true))
    }
}
