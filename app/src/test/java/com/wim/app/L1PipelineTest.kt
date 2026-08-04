package com.wim.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks in [L1Pipeline.transform] behavior so the deterministic L1 path
 * (corrections + decay scheduling + Repunctuate) doesn't regress as
 * BubbleService keeps shrinking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class L1PipelineTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var profile: ProfileManager

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        prefs = context.getSharedPreferences("wim_l1pipeline_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        profile = ProfileManager(prefs)
    }

    @Test fun emptyProfileLeavesTextAloneExceptForRepunctuate() {
        val result = L1Pipeline.transform(
            preCleaned = "hello world",
            profile = profile,
            sessionNum = 1,
        )
        assertEquals(0, result.correctionsApplied)
        // Repunctuate capitalizes first letter + adds terminal period.
        assertEquals("Hello world.", result.text)
    }

    @Test fun applyCorrectionsFalseIgnoresProfilePairs() {
        // BubbleService passes applyCorrections=false at L1 — corrections are
        // a Layer-3+ feature (design call, George 2026-08-04). Repunctuate
        // still runs; the poison pair must not.
        profile.addCorrection("with", "come")
        val result = L1Pipeline.transform(
            preCleaned = "what's wrong with whim",
            profile = profile,
            sessionNum = 1,
            applyCorrections = false,
        )
        assertEquals(0, result.correctionsApplied)
        // Repunctuate reads "what" as a question starter → "?" terminal.
        assertEquals("What's wrong with whim?", result.text)
    }

    @Test fun singleCorrectionFiresAndCountsOnce() {
        profile.addCorrection("teh", "the")
        val result = L1Pipeline.transform(
            preCleaned = "teh quick brown fox",
            profile = profile,
            sessionNum = 1,
        )
        assertEquals(1, result.correctionsApplied)
        assertTrue(result.text.startsWith("The"))
        assertTrue(result.text.contains("quick brown fox"))
    }

    @Test fun multipleCorrectionsFireIndependently() {
        profile.addCorrection("teh", "the")
        profile.addCorrection("brwon", "brown")
        val result = L1Pipeline.transform(
            preCleaned = "teh quick brwon fox",
            profile = profile,
            sessionNum = 1,
        )
        assertEquals(2, result.correctionsApplied)
        assertTrue(result.text.contains("the", ignoreCase = true))
        assertTrue(result.text.contains("brown"))
    }

    @Test fun correctionsAreCaseInsensitiveButPreserveSurroundingText() {
        profile.addCorrection("teh", "the")
        val result = L1Pipeline.transform(
            preCleaned = "TEH quick fox",
            profile = profile,
            sessionNum = 1,
        )
        assertEquals(1, result.correctionsApplied)
        // First-letter capitalization comes from Repunctuate, not the
        // correction replacement.
        assertTrue(result.text.startsWith("The"))
    }

    @Test fun wordBoundaryRespected_correctionDoesNotMatchInsideOtherWords() {
        // "teh" should NOT match inside "tether" or "techie".
        profile.addCorrection("teh", "the")
        val result = L1Pipeline.transform(
            preCleaned = "the tether held the techie",
            profile = profile,
            sessionNum = 1,
        )
        assertEquals(0, result.correctionsApplied)
        assertTrue(result.text.contains("tether"))
        assertTrue(result.text.contains("techie"))
    }

    @Test fun decayDoesNotFireAtNonMultipleOf30() {
        profile.addCorrection("teh", "the")
        // Session 5 — no decay sweep.
        val before = profile.getCorrections().size
        L1Pipeline.transform("teh world", profile, sessionNum = 5)
        assertEquals(before, profile.getCorrections().size)
    }

    @Test fun logCallbackFiresPerCorrection() {
        profile.addCorrection("teh", "the")
        profile.addCorrection("brwon", "brown")
        val messages = mutableListOf<String>()
        L1Pipeline.transform(
            preCleaned = "teh brwon fox",
            profile = profile,
            sessionNum = 1,
            log = { messages.add(it) },
        )
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.startsWith("L1 correction:") })
    }
}
