package com.wim.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline release gate imported from Lavrentiy's hard-speech corpus. */
class HeavySpeechCorpusTest {
    private fun cases() = javaClass.classLoader!!
        .getResourceAsStream("heavy_speech_cases.json")!!
        .bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }

    @Test fun corpusHasAllEighteenRealisticCases() {
        val cases = cases()
        assertEquals(18, cases.length())
        val ids = mutableSetOf<String>()
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            assertTrue(ids.add(item.getString("id")))
            assertFalse(item.getString("disfluent").contains("[BLOCK]"))
            assertTrue(item.getString("intended").isNotBlank())
        }
    }

    @Test fun everyCaseSurvivesTheOfflineCleanupWithoutGoingBlank() {
        val fillers = ProfileManager.DEFAULT_FILLERS + listOf("uhh")
        val cases = cases()
        for (i in 0 until cases.length()) {
            val raw = cases.getJSONObject(i).getString("disfluent")
            val noHallucination = WhisperClient.stripHallucinationsForTest(raw)
            val clean = DisfluencyFilter.filter(noHallucination, fillers)
            assertTrue(cases.getJSONObject(i).getString("id"), clean.isNotBlank())
        }
    }

    @Test fun highStakesNegationNumbersAndNamesArePreserved() {
        val fillers = ProfileManager.DEFAULT_FILLERS + listOf("uhh")
        fun clean(index: Int): String {
            val raw = cases().getJSONObject(index).getString("disfluent")
            return DisfluencyFilter.filter(WhisperClient.stripHallucinationsForTest(raw), fillers).lowercase()
        }
        assertTrue(clean(13).contains("not"))
        assertTrue(clean(5).contains("forty"))
        assertTrue(clean(6).contains("george"))
        assertTrue(clean(9).contains("henderson"))
        assertTrue(clean(9).contains("marriott"))
    }

    @Test fun canonicalSilenceHallucinationsAreRemovedAcrossCorpus() {
        val cases = cases()
        for (i in 0 until cases.length()) {
            val raw = cases.getJSONObject(i).getString("disfluent")
            val clean = WhisperClient.stripHallucinationsForTest(raw).lowercase()
            assertFalse(cases.getJSONObject(i).getString("id"), clean.contains("watching"))
        }
    }
}
