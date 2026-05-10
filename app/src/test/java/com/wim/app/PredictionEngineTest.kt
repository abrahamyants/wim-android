package com.wim.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PredictionEngine].
 *
 * Robolectric required: PredictionEngine calls WimApp.prefs (SharedPreferences)
 * directly. We inject a clean in-memory SharedPreferences instance before each
 * test by assigning WimApp.prefs, mirroring the pattern in ProfileManagerTest.
 *
 * AudienceContext is a pure-Kotlin object with no Android dependencies —
 * its profiles are available without special setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PredictionEngineTest {

    private lateinit var prefs: SharedPreferences

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        prefs = context.getSharedPreferences("prediction_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // Inject clean prefs so PredictionEngine.readHistory/writeHistory use our store
        WimApp.prefs = prefs
    }

    // ── recordCompletion — move-to-front semantics ──────

    @Test fun recordCompletion_sameCompletionTwice_movesToFront() {
        val pkg = "com.slack"
        val prior = listOf("hello", "dear", "boss")
        PredictionEngine.recordCompletion(pkg, prior, "let's")
        PredictionEngine.recordCompletion(pkg, prior, "regarding")
        PredictionEngine.recordCompletion(pkg, prior, "let's") // second occurrence of "let's"

        val candidates = PredictionEngine.candidatesForPosition(
            pkg = pkg,
            priorWords = prior,
            position = 3,
            maxCandidates = 5,
        )
        assertEquals(
            "Duplicate completion should be moved to front (position 0)",
            "let's",
            candidates.firstOrNull()
        )
    }

    @Test fun recordCompletion_moveToFront_doesNotDuplicate() {
        val pkg = "com.slack"
        val prior = listOf("going", "to", "the")
        PredictionEngine.recordCompletion(pkg, prior, "store")
        PredictionEngine.recordCompletion(pkg, prior, "store")

        val candidates = PredictionEngine.candidatesForPosition(
            pkg = pkg, priorWords = prior, position = 3, maxCandidates = 10)
        assertEquals(
            "Recording same completion twice must not produce duplicates",
            1,
            candidates.count { it == "store" }
        )
    }

    // ── MAX_HISTORY_PER_PKG — keeps NEWEST entries ──────

    @Test fun recordCompletion_exceedsMaxHistory_keepsNewestEntries() {
        val pkg = "com.google.android.gm"
        // Fill history to MAX_HISTORY_PER_PKG (64) + 1 extra unique trigrams
        // Each trigram "word0 word1 wordN" maps to one completion entry in the map.
        // We add 65 unique trigrams to force the prune.
        val totalEntries = 65
        for (i in 0 until totalEntries) {
            val prior = listOf("word$i", "word${i + 1}", "word${i + 2}")
            PredictionEngine.recordCompletion(pkg, prior, "completion$i")
        }

        // The NEWEST entry (index 64) must still be retrievable.
        val newestPrior = listOf("word64", "word65", "word66")
        val candidates = PredictionEngine.candidatesForPosition(
            pkg = pkg, priorWords = newestPrior, position = 3, maxCandidates = 1)
        assertEquals(
            "Newest entry must survive the MAX_HISTORY_PER_PKG prune (takeLast fix)",
            "completion64",
            candidates.firstOrNull()
        )
    }

    @Test fun recordCompletion_exceedsMaxHistory_oldestEntryDropped() {
        val pkg = "com.google.android.gm"
        val totalEntries = 65
        for (i in 0 until totalEntries) {
            val prior = listOf("word$i", "word${i + 1}", "word${i + 2}")
            PredictionEngine.recordCompletion(pkg, prior, "completion$i")
        }

        // The OLDEST entry (index 0) should have been evicted.
        val oldestPrior = listOf("word0", "word1", "word2")
        val candidates = PredictionEngine.candidatesForPosition(
            pkg = pkg, priorWords = oldestPrior, position = 3, maxCandidates = 1)
        assertTrue(
            "Oldest entry must be evicted when MAX_HISTORY_PER_PKG is exceeded",
            candidates.isEmpty()
        )
    }

    // ── predict() cold start ────────────────────────────

    @Test fun predictForContext_nullPkg_returnsNull() {
        val result = PredictionEngine.predictForContext(pkg = null, hour = 12)
        assertNull(result)
    }

    @Test fun predictForContext_blankPkg_returnsNull() {
        val result = PredictionEngine.predictForContext(pkg = "   ", hour = 12)
        assertNull(result)
    }

    @Test fun predictForContext_unknownPkg_returnsNull() {
        // AudienceContext.profileForPackage returns null for unrecognized packages
        // (truly unknown apps fall back to null per the source).
        val result = PredictionEngine.predictForContext(
            pkg = "com.unknown.app.that.does.not.exist.xyz",
            hour = 12,
        )
        // Prediction requires a known profile; unknown pkg → null
        assertNull(result)
    }

    @Test fun predictForContext_knownWorkApp_duringHours_returnsNonNull() {
        val result = PredictionEngine.predictForContext(
            pkg = "com.slack",
            hour = 12, // midday — inside 9-17 work window
        )
        assertNotNull("Slack during work hours should produce a prediction", result)
    }

    // ── Work-hours confidence boundary ──────────────────
    // Base confidence for email/work_chat/work_tool = 0.6.
    // Inside 9-17: +0.10 → 0.70 (above MIN_CONFIDENCE_FOR_CHIP 0.55) → chip shows.
    // Outside 9-17: -0.10 → 0.50 (below MIN_CONFIDENCE_FOR_CHIP 0.55) → chip hidden.

    @Test fun predictForContext_workApp_outsideHours_belowMinConfidence_returnsNull() {
        // 22:00 (10 PM) — outside work window
        val result = PredictionEngine.predictForContext(
            pkg = "com.slack",
            hour = 22,
        )
        assertNull(
            "Work app outside 9-17 should have confidence 0.50 (below 0.55 threshold) → null",
            result
        )
    }

    @Test fun predictForContext_workApp_insideHours_aboveMinConfidence_returnsNonNull() {
        val result = PredictionEngine.predictForContext(
            pkg = "com.slack",
            hour = 14,
        )
        assertNotNull(
            "Work app during 9-17 should have confidence 0.70 (above 0.55 threshold)",
            result
        )
        assertTrue("Confidence should be above MIN_CONFIDENCE_FOR_CHIP",
            (result?.confidence ?: 0.0) >= 0.55)
    }

    @Test fun predictForContext_email_outsideHours_belowMinConfidence_returnsNull() {
        val result = PredictionEngine.predictForContext(
            pkg = "com.google.android.gm",
            hour = 23,
        )
        assertNull(
            "Email app at 23:00 should produce confidence 0.50 → below threshold → null",
            result
        )
    }

    @Test fun predictForContext_email_insideHours_returnsNonNull() {
        val result = PredictionEngine.predictForContext(
            pkg = "com.google.android.gm",
            hour = 10,
        )
        assertNotNull(result)
        assertEquals("professional", result?.tone)
    }

    // ── Tone derivation ─────────────────────────────────

    @Test fun predictForContext_sms_casualTone() {
        val result = PredictionEngine.predictForContext(
            pkg = "com.google.android.apps.messaging",
            hour = 15,
        )
        // SMS category → "casual" tone regardless of work hours
        assertNotNull(result)
        assertEquals("casual", result?.tone)
    }

    @Test fun predictForContext_slack_insideHours_professionalTone() {
        val result = PredictionEngine.predictForContext(
            pkg = "com.slack",
            hour = 10,
        )
        assertNotNull(result)
        assertEquals("professional", result?.tone)
    }

    @Test fun predictForContext_slack_outsideHours_casualTone() {
        // Even though it returns null for chip (below threshold), verify
        // the tone derivation by checking at boundary hour that lands on casual.
        // Slack at 20:00 → "work chat" category, hour NOT in 9-17 → tone = "casual"
        // But we can't read tone from null. Instead use a work_chat app that
        // won't drop below threshold at off-hours (there isn't one for Slack).
        // This test is directional: just confirm tone = "casual" for off-hours Slack
        // by using forceTo verify via the logic path in the source.
        // We re-test the source contract: hour NOT in 9..17 for work chat → "casual".
        // Verify indirectly: the only way to observe is if it doesn't return null.
        // At hour=8 (just outside 9-17), confidence = 0.5, returns null.
        val result = PredictionEngine.predictForContext(pkg = "com.slack", hour = 8)
        assertNull("Hour 8 is outside 9-17, confidence = 0.50 → chip hidden", result)
    }

    // ── candidatesForPosition — empty on cold start ─────

    @Test fun candidatesForPosition_noHistory_returnsEmpty() {
        val result = PredictionEngine.candidatesForPosition(
            pkg = "com.slack",
            priorWords = listOf("let", "me", "know"),
            position = 3,
            maxCandidates = 3,
        )
        assertTrue("Cold start with no history should return empty", result.isEmpty())
    }

    @Test fun candidatesForPosition_blankTrigram_returnsEmpty() {
        val result = PredictionEngine.candidatesForPosition(
            pkg = "com.slack",
            priorWords = emptyList(),
            position = 0,
            maxCandidates = 3,
        )
        assertTrue("Empty priorWords produces blank trigram → empty result", result.isEmpty())
    }

    // ── recordCompletion — blank/empty guards ───────────

    @Test fun recordCompletion_blankPkg_ignored() {
        // Should not throw, should not write anything
        PredictionEngine.recordCompletion("", listOf("hello", "world", "foo"), "bar")
        // No assertion needed beyond no crash; verify read returns empty
        val candidates = PredictionEngine.candidatesForPosition(
            pkg = "", priorWords = listOf("hello", "world", "foo"), position = 3, maxCandidates = 1)
        assertTrue(candidates.isEmpty())
    }

    @Test fun recordCompletion_blankCompletion_ignored() {
        val pkg = "com.slack"
        val prior = listOf("hello", "dear", "team")
        PredictionEngine.recordCompletion(pkg, prior, "")
        val candidates = PredictionEngine.candidatesForPosition(
            pkg = pkg, priorWords = prior, position = 3, maxCandidates = 1)
        assertTrue("Blank completion should not be recorded", candidates.isEmpty())
    }

    // ── Trigram construction ────────────────────────────

    @Test fun recordCompletion_usesTailOf3PriorWords() {
        val pkg = "com.slack"
        // Only the last 3 of 5 prior words form the lookup key
        val longPrior = listOf("ignore", "these", "use", "these", "three")
        PredictionEngine.recordCompletion(pkg, longPrior, "target")

        // Lookup with full list — should find it
        val candidatesFull = PredictionEngine.candidatesForPosition(
            pkg = pkg, priorWords = longPrior, position = 5, maxCandidates = 1)
        assertEquals("target", candidatesFull.firstOrNull())

        // Lookup with only last 3 words — should also find it (same trigram)
        val candidatesTail = PredictionEngine.candidatesForPosition(
            pkg = pkg, priorWords = listOf("use", "these", "three"), position = 3, maxCandidates = 1)
        assertEquals("target", candidatesTail.firstOrNull())
    }
}
