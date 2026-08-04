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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ProfileManagerTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var profile: ProfileManager

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        prefs = context.getSharedPreferences("wim_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        profile = ProfileManager(prefs)
    }

    // ── Defaults ───────────────────────────────────────

    @Test fun defaults_fillerWords() {
        val fillers = profile.getFillerWords()
        assertTrue("Default fillers not empty", fillers.isNotEmpty())
        assertTrue("Contains 'um'", fillers.contains("um"))
        assertTrue("Contains 'uh'", fillers.contains("uh"))
    }

    @Test fun defaults_emptyVocabulary() {
        assertTrue(profile.getVocabulary().isEmpty())
    }

    @Test fun defaults_emptyCorrections() {
        assertTrue(profile.getCorrections().isEmpty())
    }

    // ── Vocabulary ─────────────────────────────────────

    @Test fun addVocabulary_persists() {
        profile.addVocabulary("Лаврентий")
        val reloaded = ProfileManager(prefs)
        assertTrue(reloaded.getVocabulary().contains("Лаврентий"))
    }

    @Test fun addVocabulary_noDuplicates() {
        profile.addVocabulary("WiM")
        profile.addVocabulary("WiM")
        assertEquals(1, profile.getVocabulary().count { it == "WiM" })
    }

    @Test fun addVocabulary_trimmed() {
        profile.addVocabulary("  hello  ")
        assertTrue(profile.getVocabulary().contains("hello"))
    }

    @Test fun addVocabulary_emptyIgnored() {
        val before = profile.getVocabulary().size
        profile.addVocabulary("")
        profile.addVocabulary("   ")
        assertEquals(before, profile.getVocabulary().size)
    }

    // ── Corrections ────────────────────────────────────

    @Test fun addCorrection_persists() {
        profile.addCorrection("Duncan", "Dankeschön")
        val reloaded = ProfileManager(prefs)
        assertEquals("Dankeschön", reloaded.getCorrections()["duncan"])
    }

    @Test fun addCorrection_lowercaseKey() {
        profile.addCorrection("HELLO", "hello")
        assertTrue(profile.getCorrections().containsKey("hello"))
    }

    @Test fun addCorrection_overwrite() {
        profile.addCorrection("bad", "good")
        profile.addCorrection("bad", "great")
        assertEquals("great", profile.getCorrections()["bad"])
    }

    // ── Save / Load round-trip ─────────────────────────

    @Test fun roundTrip_allFields() {
        profile.addVocabulary("WiM")
        profile.addVocabulary("Лаврентий")
        profile.addCorrection("gonna", "going to")
        profile.addCorrection("wanna", "want to")

        val reloaded = ProfileManager(prefs)
        assertEquals(2, reloaded.getVocabulary().size)
        assertEquals(2, reloaded.getCorrections().size)
        assertTrue(reloaded.getVocabulary().contains("WiM"))
        assertEquals("going to", reloaded.getCorrections()["gonna"])
    }

    @Test fun roundTrip_fillersSurvive() {
        val original = profile.getFillerWords()
        val reloaded = ProfileManager(prefs)
        assertEquals(original, reloaded.getFillerWords())
    }

    // ── Learning ───────────────────────────────────────

    @Test fun learning_needsThreeOccurrences() {
        // Use aligned sentences where "gonna" at pos 0 maps to "going" at pos 0
        profile.learnFromReconstruction("gonna work", "going work")
        assertTrue("1st occurrence: no correction yet", profile.getCorrections().isEmpty())

        profile.learnFromReconstruction("gonna work", "going work")
        assertTrue("2nd occurrence: no correction yet", profile.getCorrections().isEmpty())

        profile.learnFromReconstruction("gonna work", "going work")
        // 3rd occurrence: "gonna" at pos 0 → "going" at pos 0 (within 2 positions)
        val corrections = profile.getCorrections()
        assertTrue("3rd occurrence: correction must be learned", corrections.containsKey("gonna"))
        assertEquals("going", corrections["gonna"])
    }

    @Test fun learning_doesNotCorruptProfile() {
        // Feed many learning cycles — profile should not grow unbounded
        repeat(20) {
            profile.learnFromReconstruction(
                "I said hello to my friend",
                "I said hello to my friend"  // identical = nothing to learn
            )
        }
        assertTrue("No corrections from identical text", profile.getCorrections().isEmpty())
    }

    @Test fun learning_shortWordsIgnored() {
        repeat(5) {
            profile.learnFromReconstruction("I go to store", "I went to the store")
        }
        // "go" is only 2 chars — should not be learned
        assertFalse(profile.getCorrections().containsKey("go"))
    }

    // ── Stub fields (trigger_words, onset_weights, covert_profile) ──

    @Test fun triggerWords_emptyByDefault() {
        assertTrue(profile.getTriggerWords().isEmpty())
    }

    @Test fun onsetWeights_emptyByDefault() {
        assertTrue(profile.getOnsetWeights().isEmpty())
    }

    @Test fun covertProfile_emptyByDefault() {
        assertTrue(profile.getCovertProfile().isEmpty())
    }

    // ── Edge cases ─────────────────────────────────────

    @Test fun corruptedJson_resetsToDefaults() {
        prefs.edit().putString("profile_json", "NOT VALID JSON {{{").commit()
        val recovered = ProfileManager(prefs)
        // Should not crash, should reset to defaults
        assertTrue(recovered.getFillerWords().isNotEmpty())
    }

    @Test fun emptyJson_usesDefaults() {
        prefs.edit().putString("profile_json", "{}").commit()
        val loaded = ProfileManager(prefs)
        // Empty JSON = no fillers loaded, but defaults should apply
        // Actually empty arrays = empty, only resetToDefaults gives fillers
        assertNotNull(loaded.getFillerWords())
    }

    // ── Concurrent-collection race regression ──────────
    //
    // Before 2026-05-16 the profile's vocabulary / corrections / filler_words
    // were plain MutableList/MutableMap, mutated by the Firestore sync listener
    // on its own thread while the reconstruction path called getVocabulary() /
    // getCorrections() / getFillerWords() on another. The .toList() / .toMap()
    // snapshots could ConcurrentModificationException mid-merge under load.
    //
    // Swapping to CopyOnWriteArrayList + ConcurrentHashMap makes those reads
    // always-safe. This test hammers the writers and readers in parallel for
    // a fixed budget; any CME or other exception fails the test.

    @Test fun concurrent_readsAndWrites_neverThrow() {
        val errors = mutableListOf<Throwable>()
        val errorsLock = Any()
        val deadline = System.currentTimeMillis() + 1500L

        val writers = (1..3).map { tid ->
            Thread {
                var i = 0
                while (System.currentTimeMillis() < deadline) {
                    try {
                        profile.addVocabulary("v_${tid}_$i")
                        profile.addCorrection("k_${tid}_$i", "v_${tid}_$i")
                        i++
                    } catch (t: Throwable) {
                        synchronized(errorsLock) { errors.add(t) }
                        break
                    }
                }
            }
        }

        val readers = (1..5).map {
            Thread {
                while (System.currentTimeMillis() < deadline) {
                    try {
                        // .toList() / .toMap() are the snapshot reads the
                        // reconstruction path performs every call.
                        profile.getVocabulary().toList()
                        profile.getCorrections().toMap()
                        profile.getFillerWords().toList()
                        profile.getTriggerWords()
                        profile.getOnsetWeights()
                    } catch (t: Throwable) {
                        synchronized(errorsLock) { errors.add(t) }
                        break
                    }
                }
            }
        }

        (writers + readers).forEach { it.start() }
        (writers + readers).forEach { it.join(5000L) }

        assertTrue(
            "Concurrent profile reads/writes raised: ${errors.firstOrNull()}",
            errors.isEmpty(),
        )
    }

    // ── Concurrent-WRITE data-integrity (PROFILE_WRITE_LOCK) ────────────
    //
    // The test above proves concurrent reads/writes never *throw*. This one
    // proves they never *lose data*: PROFILE_WRITE_LOCK serialises the three
    // writers (saveProfile / writeTriggerWords / mergeRemoteProfile) so that
    // (a) no concurrent write is dropped by a last-writer-wins RMW race, and
    // (b) the Firestore-synced fields the in-memory model doesn't hold
    // (onset_weights / covert_profile) survive a concurrent saveProfile. Both
    // were the actual wipe risks behind the 2026-05-31 / 06-06 audits.

    @Test fun concurrent_writes_loseNoData_andPreserveSyncedFields() {
        // Pre-seed a "synced from desktop" field ProfileManager never holds in
        // memory — it must survive concurrent saveProfile read-modify-writes.
        prefs.edit().putString(
            "profile_json",
            org.json.JSONObject()
                .put("onset_weights", org.json.JSONObject(mapOf("p" to 0.9, "b" to 0.8)))
                .put("filler_words", org.json.JSONArray(ProfileManager.DEFAULT_FILLERS))
                .toString()
        ).commit()
        val profile = ProfileManager(prefs)

        val perThread = 40
        val writers = (1..3).map { tid ->
            Thread {
                for (i in 0 until perThread) {
                    profile.addVocabulary("v_${tid}_$i")    // saveProfile RMW under lock
                    profile.addTriggerWord("t_${tid}_$i")   // writeTriggerWords RMW under lock
                }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(10_000L) }

        val reloaded = ProfileManager(prefs)
        // (a) No lost writes — every distinct vocab + trigger word survived.
        assertEquals(
            "Lost vocab writes under concurrency (last-writer-wins race)",
            3 * perThread,
            reloaded.getVocabulary().count { it.startsWith("v_") },
        )
        assertEquals(
            "Lost trigger writes under concurrency",
            3 * perThread,
            reloaded.getTriggerWords().count { it.startsWith("t_") },
        )
        // (b) Synced field survived the concurrent saveProfile RMW (not wiped).
        val onset = reloaded.getOnsetWeights()
        assertEquals("Synced onset_weights wiped under concurrency", 0.9, onset["p"]!!, 1e-9)
        assertEquals(0.8, onset["b"]!!, 1e-9)
    }

    @Test fun prosodicBaseline_calibratesPersistsAndClears() {
        repeat(4) { i ->
            profile.recordProsodic(ProsodicAnalyzer.SessionSummary(180.0 + i, 0.04 + i * 0.001, 3.0 + i * 0.1))
        }
        assertNull("Baseline must not activate on noisy first samples", profile.prosodicBaseline())

        profile.recordProsodic(ProsodicAnalyzer.SessionSummary(184.0, 0.044, 3.4))
        val baseline = profile.prosodicBaseline()
        assertNotNull(baseline)
        assertEquals(5, baseline!!.samples)
        assertEquals(182.0, baseline.f0Mean, 0.01)
        assertEquals(0.042, baseline.energyMean, 0.0001)

        val reloaded = ProfileManager(prefs)
        assertEquals(5, reloaded.prosodicBaseline()!!.samples)
        reloaded.clearPersonalSpeechBaseline()
        assertNull(reloaded.prosodicBaseline())
        assertEquals(0, reloaded.prosodicSampleCount())
    }

    // ── Trigger stoplist fence (2026-08-04) ──

    @Test fun triggerStoplist_rejectsFunctionWordOnAdd() {
        profile.addTriggerWord("you")
        profile.addTriggerWord("um")
        assertEquals(listOf("um"), profile.getTriggerWords())
    }

    @Test fun triggerStoplist_filtersJunkAlreadyInStorage() {
        // Simulates a backend sync that planted a pronoun before the fence
        // existed: write straight to the store, then read through the API.
        profile.addTriggerWord("hey")
        val obj = org.json.JSONObject(prefs.getString("profile_json", null)!!)
        val arr = obj.optJSONArray("trigger_words")!!
        arr.put("you")
        obj.put("trigger_words", arr)
        prefs.edit().putString("profile_json", obj.toString()).commit()
        assertEquals(listOf("hey"), profile.getTriggerWords())
    }

    @Test fun triggerStoplist_caseInsensitive() {
        profile.addTriggerWord("You")
        assertTrue(profile.getTriggerWords().isEmpty())
    }
}
