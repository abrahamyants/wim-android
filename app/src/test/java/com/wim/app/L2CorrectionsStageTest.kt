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
 * Locks in [L2CorrectionsStage.apply] behavior — corrections + the L2/L3-
 * gated PhoneticMatcher call. The phonetic-matcher branch is the main
 * place this differs from L1Pipeline, so the layer-gating tests carry
 * the weight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class L2CorrectionsStageTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var profile: ProfileManager

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        prefs = context.getSharedPreferences("wim_l2corrections_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        profile = ProfileManager(prefs)
    }

    @Test fun emptyProfileLeavesTextUnchanged() {
        val result = L2CorrectionsStage.apply(
            preCleaned = "hello world",
            profile = profile,
            sessionNum = 1,
            layer = 2,
        )
        assertEquals(0, result.correctionsApplied)
        assertEquals("hello world", result.text)
    }

    @Test fun singleCorrectionFiresAtLayer2() {
        profile.addCorrection("teh", "the")
        val result = L2CorrectionsStage.apply(
            preCleaned = "teh quick fox",
            profile = profile,
            sessionNum = 1,
            layer = 2,
        )
        assertEquals(1, result.correctionsApplied)
        assertEquals("the quick fox", result.text)
    }

    @Test fun multipleCorrectionsFireIndependently() {
        profile.addCorrection("teh", "the")
        profile.addCorrection("brwon", "brown")
        val result = L2CorrectionsStage.apply(
            preCleaned = "teh quick brwon fox",
            profile = profile,
            sessionNum = 1,
            layer = 3,
        )
        assertEquals(2, result.correctionsApplied)
        assertEquals("the quick brown fox", result.text)
    }

    @Test fun correctionsAreCaseInsensitive() {
        profile.addCorrection("teh", "the")
        val result = L2CorrectionsStage.apply(
            preCleaned = "TEH QUICK FOX",
            profile = profile,
            sessionNum = 1,
            layer = 2,
        )
        assertEquals(1, result.correctionsApplied)
        assertEquals("the QUICK FOX", result.text)
    }

    @Test fun wordBoundaryRespected() {
        profile.addCorrection("teh", "the")
        val result = L2CorrectionsStage.apply(
            preCleaned = "the tether held",
            profile = profile,
            sessionNum = 1,
            layer = 2,
        )
        assertEquals(0, result.correctionsApplied)
        assertEquals("the tether held", result.text)
    }

    @Test fun logCallbackIncludesLayerNumber() {
        profile.addCorrection("teh", "the")
        val messages = mutableListOf<String>()
        L2CorrectionsStage.apply(
            preCleaned = "teh fox",
            profile = profile,
            sessionNum = 1,
            layer = 3,
            log = { messages.add(it) },
        )
        assertEquals(1, messages.size)
        assertTrue(messages[0].startsWith("L3 correction:"))
    }

    @Test fun layer4SkipsPhoneticMatcher_correctionsStillApply() {
        // PhoneticMatcher is gated to L2..3 per Lav parity. At L4, only the
        // profile-correction loop fires; the result of layer 4 should equal
        // the input EXCEPT for any profile corrections that matched.
        profile.addCorrection("teh", "the")
        val result = L2CorrectionsStage.apply(
            preCleaned = "teh fox",
            profile = profile,
            sessionNum = 1,
            layer = 4,
        )
        assertEquals(1, result.correctionsApplied)
        assertEquals("the fox", result.text)
    }

    @Test fun noCorrectionsLeavesCountAtZero() {
        // Even with PhoneticMatcher firing at L2/L3 against an empty
        // vocabulary, no profile corrections match the input → count is 0.
        val result = L2CorrectionsStage.apply(
            preCleaned = "totally unrelated text",
            profile = profile,
            sessionNum = 1,
            layer = 2,
        )
        assertEquals(0, result.correctionsApplied)
    }
}
