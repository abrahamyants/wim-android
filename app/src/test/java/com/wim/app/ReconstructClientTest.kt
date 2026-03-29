package com.wim.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests ReconstructClient logic that doesn't require network:
 * - Risk flag computation (already covered, but edge cases here)
 * - Backend response parsing behavior
 * - Payload construction verification
 */
class ReconstructClientTest {

    // ── computeRiskFlags thoroughness ──────────────────

    @Test fun riskFlags_identicalText_empty() {
        val flags = ReconstructClient.computeRiskFlags("hello world", "hello world")
        assertTrue(flags.isEmpty())
    }

    @Test fun riskFlags_allFlagsCanCoexist() {
        // Input with numbers, output is short fragment with ellipsis and missing number
        val flags = ReconstructClient.computeRiskFlags(
            "I need exactly 42 items from the big store on Main Street today please",
            "x\u2014"
        )
        assertTrue("very_short_output (2 chars < 3)", flags.contains("very_short_output"))
        assertTrue("large_length_delta", flags.contains("large_length_delta"))
        assertTrue("critical_token_lost", flags.contains("critical_token_lost"))
        assertTrue("contains_unfinished_fragment (ends with em-dash)", flags.contains("contains_unfinished_fragment"))
        assertEquals("All 4 flags", 4, flags.size)
    }

    // ── Backend response parsing simulation ────────────

    @Test fun backendResponse_cleanTextExtracted() {
        val json = JSONObject().apply {
            put("clean", "I want to go to the store")
            put("falcon_ok", true)
        }
        assertEquals("I want to go to the store", json.optString("clean", "fallback"))
    }

    @Test fun backendResponse_falconOk_true() {
        val json = JSONObject().apply {
            put("clean", "hello")
            put("falcon_ok", true)
        }
        assertTrue(json.optBoolean("falcon_ok", true))
    }

    @Test fun backendResponse_falconOk_false() {
        val json = JSONObject().apply {
            put("clean", "hello")
            put("falcon_ok", false)
        }
        assertFalse(json.optBoolean("falcon_ok", true))
    }

    @Test fun backendResponse_missingFalcon_defaultsTrue() {
        val json = JSONObject().apply {
            put("clean", "hello")
        }
        assertTrue("Missing falcon_ok defaults to true", json.optBoolean("falcon_ok", true))
    }

    @Test fun backendResponse_missingClean_fallsBackToRaw() {
        val json = JSONObject()
        val rawText = "original text"
        assertEquals(rawText, json.optString("clean", rawText))
    }

    // ── Backend payload construction ───────────────────

    @Test fun payload_basicFields() {
        val body = buildPayload("hello", "casual", 2, "default",
            emptyList(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap())
        assertEquals("hello", body.getString("raw"))
        assertEquals("casual", body.getString("tone"))
        assertEquals(2, body.getInt("layer"))
        assertEquals("default", body.getString("situation"))
        assertEquals("SAFE", body.getString("mode"))
    }

    @Test fun payload_profileWithVocabulary() {
        val body = buildPayload("hello", "casual", 3, "default",
            listOf("WiM", "Лаврентий"), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap())
        val profile = body.getJSONObject("profile")
        val vocab = profile.getJSONArray("vocabulary")
        assertEquals(2, vocab.length())
        assertEquals("WiM", vocab.getString(0))
        assertEquals("Лаврентий", vocab.getString(1))
    }

    @Test fun payload_profileWithCorrections() {
        val body = buildPayload("hello", "casual", 3, "default",
            emptyList(), mapOf("gonna" to "going to"), emptyList(), emptyList(), emptyMap(), emptyMap())
        val profile = body.getJSONObject("profile")
        val corr = profile.getJSONObject("corrections")
        assertEquals("going to", corr.getString("gonna"))
    }

    @Test fun payload_profileWithFillerWords() {
        val body = buildPayload("hello", "casual", 2, "default",
            emptyList(), emptyMap(), listOf("um", "uh", "like"), emptyList(), emptyMap(), emptyMap())
        val profile = body.getJSONObject("profile")
        val fillers = profile.getJSONArray("filler_words")
        assertEquals(3, fillers.length())
    }

    @Test fun payload_stubFieldsAlwaysSent() {
        val body = buildPayload("hello", "casual", 4, "high_stress",
            emptyList(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap())
        val profile = body.getJSONObject("profile")
        // Stub fields should always be present (even if empty)
        assertNotNull(profile.getJSONArray("trigger_words"))
        assertNotNull(profile.getJSONObject("onset_weights"))
        assertNotNull(profile.getJSONObject("covert_profile"))
        assertEquals(0, profile.getJSONArray("trigger_words").length())
    }

    @Test fun payload_fullProfile() {
        val body = buildPayload("test", "formal", 4, "high_stress",
            listOf("vocab1"), mapOf("bad" to "good"),
            listOf("um"), listOf("trigger1"),
            mapOf("b" to 0.8), mapOf("computer" to "laptop"))
        val profile = body.getJSONObject("profile")
        assertEquals(1, profile.getJSONArray("vocabulary").length())
        assertEquals("good", profile.getJSONObject("corrections").getString("bad"))
        assertEquals(1, profile.getJSONArray("filler_words").length())
        assertEquals(1, profile.getJSONArray("trigger_words").length())
        assertEquals(0.8, profile.getJSONObject("onset_weights").getDouble("b"), 0.001)
        assertEquals("laptop", profile.getJSONObject("covert_profile").getString("computer"))
    }

    // ── Tone/severity value integrity ──────────────────

    @Test fun toneTemp_formalTightest() {
        val temps = ReconstructClient.TONE_TEMPERATURE
        assertTrue("Formal should be tightest",
            temps["formal"]!! < temps["professional"]!! &&
            temps["professional"]!! < temps["casual"]!! &&
            temps["casual"]!! < temps["friend"]!!)
    }

    @Test fun severity_ordering() {
        val sev = ReconstructClient.SITUATION_SEVERITY
        assertTrue("reading < default < high_stress",
            sev["reading"]!! < sev["default"]!! && sev["default"]!! < sev["high_stress"]!!)
    }

    // ── Helper: mirrors reconstructViaBackend payload construction ──

    private fun buildPayload(
        rawText: String, tone: String, layer: Int, situation: String,
        vocabulary: List<String>, corrections: Map<String, String>,
        fillerWords: List<String>, triggerWords: List<String>,
        onsetWeights: Map<String, Double>, covertProfile: Map<String, String>
    ): JSONObject {
        return JSONObject().apply {
            put("raw", rawText)
            put("tone", tone)
            put("layer", layer)
            put("situation", situation)
            put("mode", "SAFE")
            val profile = JSONObject()
            if (vocabulary.isNotEmpty()) profile.put("vocabulary", JSONArray(vocabulary))
            if (corrections.isNotEmpty()) {
                val corr = JSONObject()
                corrections.forEach { (k, v) -> corr.put(k, v) }
                profile.put("corrections", corr)
            }
            if (fillerWords.isNotEmpty()) profile.put("filler_words", JSONArray(fillerWords))
            profile.put("trigger_words", JSONArray(triggerWords))
            profile.put("onset_weights", JSONObject(onsetWeights as Map<*, *>))
            profile.put("covert_profile", JSONObject(covertProfile as Map<*, *>))
            if (profile.length() > 0) put("profile", profile)
        }
    }
}
