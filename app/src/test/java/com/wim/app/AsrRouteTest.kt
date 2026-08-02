package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture-path truth table. Three defects lived in this decision while it
 * was buried in BubbleService.startRecording, and a fourth turned up the moment
 * it was pulled out where it could be enumerated.
 */
class AsrRouteTest {

    private fun d(layer: Int, cloud: Boolean, model: Boolean, reach: Boolean) =
        AsrRoute.decide(layer, cloud, model, reach)

    // ── The setting is honoured at every layer ──────────────────────
    // It used to apply only at layer 1, so someone who had paid for cloud
    // transcription silently got the local English-only engine everywhere else.

    @Test fun cloudPickIsHonouredAtEveryLayer() {
        for (layer in 1..3) {
            assertEquals("L$layer", AsrRoute.Decision.SelectedCloud,
                d(layer, cloud = true, model = true, reach = true))
        }
    }

    @Test fun localPickUsesTheLocalEngineWhenTheModelIsThere() {
        for (layer in 1..3) {
            assertEquals("L$layer", AsrRoute.Decision.LocalEngine,
                d(layer, cloud = false, model = true, reach = true))
        }
    }

    // ── The documented L2/L3 fallback ───────────────────────────────
    // It had no branch at all, so a signed-in user whose model had not
    // downloaded could not dictate while a recorder was already capturing.

    @Test fun l2AndL3FallBackToCloudWhenTheModelIsMissing() {
        for (layer in 2..3) {
            assertEquals("L$layer", AsrRoute.Decision.CloudFallback,
                d(layer, cloud = false, model = false, reach = true))
        }
    }

    @Test fun l1HasNowhereToFallBackTo() {
        // L1's whole contract is that no model runs, so there is no cloud
        // reconstruction to borrow.
        assertEquals(AsrRoute.Decision.Refuse, d(1, cloud = false, model = false, reach = true))
    }

    // ── A stale CLOUD pick ──────────────────────────────────────────
    // Chosen while signed in, then signed out. It used to take the cloud branch
    // with no recorder running and capture silence.

    @Test fun aStaleCloudPickFallsBackToTheLocalEngine() {
        assertEquals(AsrRoute.Decision.LocalEngine, d(2, cloud = true, model = true, reach = false))
    }

    @Test fun aStaleCloudPickWithNoModelRefuses() {
        assertEquals(AsrRoute.Decision.Refuse, d(1, cloud = true, model = false, reach = false))
    }

    // ── L4 ──────────────────────────────────────────────────────────

    @Test fun l4IsAlwaysCloudRegardlessOfTheSetting() {
        assertEquals(AsrRoute.Decision.ClinicalCloud, d(4, cloud = false, model = true, reach = true))
        assertEquals(AsrRoute.Decision.ClinicalCloud, d(4, cloud = true, model = false, reach = true))
    }

    @Test fun l4RefusesWhenCloudIsUnreachable() {
        // The defect the extraction surfaced: the old condition started no
        // recorder here because it required cloudAvailable, while the engine
        // picker still took the isL4 branch, which starts nothing either. The
        // bubble sat recording and captured silence. A local model makes no
        // difference — L4 has no local option.
        assertEquals(AsrRoute.Decision.Refuse, d(4, cloud = false, model = true, reach = false))
        assertEquals(AsrRoute.Decision.Refuse, d(4, cloud = true, model = true, reach = false))
    }

    // ── The invariant that matters ──────────────────────────────────

    @Test fun everyConfigurationEitherCapturesOrRefuses() {
        for (layer in 1..4) {
            for (cloud in listOf(false, true)) {
                for (model in listOf(false, true)) {
                    for (reach in listOf(false, true)) {
                        val r = d(layer, cloud, model, reach)
                        val captures = r.useCloudRecorder || r.useLocalEngine
                        assertTrue(
                            "L$layer cloud=$cloud model=$model reach=$reach records nothing " +
                                "without refusing",
                            captures != r.refuse,
                        )
                    }
                }
            }
        }
    }

    @Test fun aRefusalNeverStartsACaptureSource() {
        val r = d(1, cloud = false, model = false, reach = false)
        assertTrue(r.refuse)
        assertFalse(r.useCloudRecorder)
        assertFalse(r.useLocalEngine)
    }
}
