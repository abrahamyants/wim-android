package com.wim.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (instrumented) coverage for ProfileManager — WiM's first androidTest.
 *
 * The unit suite exercises ProfileManager against Robolectric's *shadow*
 * SharedPreferences. This runs it against the REAL Android SharedPreferences on a
 * physical device, where apply()/commit() actually marshal to disk on a background
 * thread and real thread scheduling applies. It pins the trigger-word write-race
 * fix (full read-modify-write under PROFILE_WRITE_LOCK) on real hardware, not just
 * the shadow.
 *
 * Uses a dedicated "wim_instrumented_test" prefs file and clears it each run — it
 * never reads or writes the app's real profile_json on the device.
 */
@RunWith(AndroidJUnit4::class)
class ProfileManagerInstrumentedTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var profile: ProfileManager

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        prefs = ctx.getSharedPreferences("wim_instrumented_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        profile = ProfileManager(prefs)
    }

    @Test fun realPrefs_concurrentWrites_loseNoData_andPreserveSyncedFields() {
        // Seed a "synced from desktop" field the in-memory model never holds — it
        // must survive concurrent saveProfile read-modify-writes on real prefs.
        prefs.edit().putString(
            "profile_json",
            org.json.JSONObject()
                .put("onset_weights", org.json.JSONObject(mapOf("p" to 0.9, "b" to 0.8)))
                .put("filler_words", org.json.JSONArray(ProfileManager.DEFAULT_FILLERS))
                .toString()
        ).commit()
        val p = ProfileManager(prefs)

        val perThread = 40
        val writers = (1..3).map { tid ->
            Thread {
                for (i in 0 until perThread) {
                    p.addVocabulary("v_${tid}_$i")
                    p.addTriggerWord("t_${tid}_$i")
                }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(15_000L) }

        val reloaded = ProfileManager(prefs)
        assertEquals(
            "Lost vocab writes under concurrency (real prefs)",
            3 * perThread,
            reloaded.getVocabulary().count { it.startsWith("v_") },
        )
        assertEquals(
            "Lost trigger writes under concurrency (real prefs)",
            3 * perThread,
            reloaded.getTriggerWords().count { it.startsWith("t_") },
        )
        val onset = reloaded.getOnsetWeights()
        assertEquals("Synced onset_weights wiped (real prefs)", 0.9, onset["p"]!!, 1e-9)
        assertEquals(0.8, onset["b"]!!, 1e-9)
    }

    @Test fun realPrefs_roundTrip_persistsAcrossInstances() {
        profile.addVocabulary("WiM")
        profile.addCorrection("gonna", "going to")
        profile.addTriggerWord("problem")
        // A fresh ProfileManager reading the same on-device prefs file proves the
        // write reached real persistence, not just an in-process cache.
        val reloaded = ProfileManager(prefs)
        assertTrue(reloaded.getVocabulary().contains("WiM"))
        assertEquals("going to", reloaded.getCorrections()["gonna"])
        assertTrue(reloaded.getTriggerWords().contains("problem"))
    }
}
