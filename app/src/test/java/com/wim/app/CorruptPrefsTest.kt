package com.wim.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A wrongly-typed preference must never make the app unlaunchable.
 *
 * This is not hypothetical. WimApp's own KDoc records it happening: a test
 * broadcast wrote a String where `l1_cloud_asr` expected a Boolean, and since
 * these are read from WimApp.onCreate, the next launch died — with clearing
 * app data, and the user's whole speech profile, as the only way out. The
 * self-healing accessors were the fix; nothing tested them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CorruptPrefsTest {

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        WimApp.prefs = context.getSharedPreferences("wim_corrupt_test", Context.MODE_PRIVATE)
        WimApp.prefs.edit().clear().commit()
    }

    @Test fun stringWhereBooleanExpected_selfHeals() {
        WimApp.prefs.edit().putString("l1_cloud_asr", "true").commit()
        // Must return the default rather than throw ClassCastException.
        assertFalse(WimApp.l1CloudAsr())
        // And must drop the poisoned value so the next launch is clean.
        assertFalse(WimApp.prefs.contains("l1_cloud_asr"))
    }

    @Test fun stringWhereIntExpected_selfHeals() {
        WimApp.prefs.edit().putString("layer", "4").commit()
        assertEquals(1, WimApp.layer())
        assertFalse(WimApp.prefs.contains("layer"))
    }

    @Test fun intWhereStringExpected_selfHeals() {
        WimApp.prefs.edit().putInt("tone", 3).commit()
        assertEquals("casual", WimApp.tone())
        assertFalse(WimApp.prefs.contains("tone"))
    }

    @Test fun booleanWhereIntExpected_selfHeals() {
        WimApp.prefs.edit().putBoolean("bubble_opacity", true).commit()
        assertEquals(100, WimApp.bubbleOpacity())
    }

    @Test fun aCorruptValueDoesNotTakeNeighboursWithIt() {
        WimApp.prefs.edit()
            .putString("layer", "garbage")
            .putString("tone", "professional")
            .putInt("bubble_size", 80)
            .commit()
        assertEquals(1, WimApp.layer())          // healed
        assertEquals("professional", WimApp.tone())  // untouched
        assertEquals(80, WimApp.bubbleSize())        // untouched
    }

    @Test fun everyReadAccessorSurvivesATotallyPoisonedStore() {
        // Write the wrong type into everything the app reads at startup.
        WimApp.prefs.edit().apply {
            putString("layer", "x")
            putInt("tone", 1)
            putString("bubble_opacity", "x")
            putString("bubble_size", "x")
            putInt("bubble_theme", 1)
            putString("daf_enabled", "x")
            putString("daf_delay", "x")
            putString("situation", "ok")
            putInt("l1_cloud_asr", 1)
        }.commit()

        // None of these may throw — this is the launch path.
        WimApp.layer(); WimApp.tone(); WimApp.bubbleOpacity(); WimApp.bubbleSize()
        WimApp.bubbleTheme(); WimApp.dafEnabled(); WimApp.dafDelay()
        WimApp.situation(); WimApp.l1CloudAsr()
        assertTrue(true)
    }
}
