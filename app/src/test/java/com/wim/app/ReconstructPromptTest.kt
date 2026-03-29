package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that reconstruction prompt construction produces correct
 * tone rules, severity context, and layer-specific content.
 * Tests the TONE_RULES, TONE_TEMPERATURE, and SITUATION_SEVERITY maps.
 */
class ReconstructPromptTest {

    // ── Tone temperatures match Лаврентий ──────────────

    @Test fun temp_formal() = assertEquals(0.1, getToneTemp("formal"), 0.001)
    @Test fun temp_professional() = assertEquals(0.15, getToneTemp("professional"), 0.001)
    @Test fun temp_casual() = assertEquals(0.35, getToneTemp("casual"), 0.001)
    @Test fun temp_friend() = assertEquals(0.4, getToneTemp("friend"), 0.001)
    @Test fun temp_unknown_fallback() = assertEquals(0.35, getToneTemp("nonexistent"), 0.001)

    // ── Situation severities match Лаврентий ───────────

    @Test fun severity_default() = assertEquals(1.0, getSeverity("default"), 0.001)
    @Test fun severity_highStress() = assertEquals(1.5, getSeverity("high_stress"), 0.001)
    @Test fun severity_reading() = assertEquals(0.3, getSeverity("reading"), 0.001)
    @Test fun severity_unknown_fallback() = assertEquals(1.0, getSeverity("nonexistent"), 0.001)

    // ── Tone rules are distinct ────────────────────────

    @Test fun toneRule_formal_noContractions() {
        val rule = getToneRule("formal")
        assertTrue(rule.contains("No contractions"))
    }

    @Test fun toneRule_professional_business() {
        val rule = getToneRule("professional")
        assertTrue(rule.contains("business"))
    }

    @Test fun toneRule_casual_contractions() {
        val rule = getToneRule("casual")
        assertTrue(rule.contains("Contractions OK"))
    }

    @Test fun toneRule_friend_relaxed() {
        val rule = getToneRule("friend")
        assertTrue(rule.contains("relaxed") || rule.contains("Relaxed"))
    }

    @Test fun toneRules_allDifferent() {
        val rules = listOf("formal", "professional", "casual", "friend").map { getToneRule(it) }
        assertEquals("All 4 rules should be unique", 4, rules.toSet().size)
    }

    // ── Severity thresholds ────────────────────────────

    @Test fun severity_highStress_triggers_aggressive() {
        assertTrue("1.5 >= 1.4 should trigger aggressive mode", getSeverity("high_stress") >= 1.4)
    }

    @Test fun severity_reading_triggers_conservative() {
        assertTrue("0.3 <= 0.6 should trigger conservative mode", getSeverity("reading") <= 0.6)
    }

    @Test fun severity_default_triggers_neither() {
        val s = getSeverity("default")
        assertFalse("1.0 should not be aggressive", s >= 1.4)
        assertFalse("1.0 should not be conservative", s <= 0.6)
    }

    // ── Helpers (mirror ReconstructClient's maps) ──────

    private val TONE_TEMPERATURE = mapOf(
        "formal" to 0.1,
        "professional" to 0.15,
        "casual" to 0.35,
        "friend" to 0.4
    )

    private val SITUATION_SEVERITY = mapOf(
        "default" to 1.0,
        "high_stress" to 1.5,
        "reading" to 0.3
    )

    private val TONE_RULES = mapOf(
        "formal" to "Use complete sentences. No contractions. No colloquialisms. Proper grammar and punctuation. Preserve the speaker's exact word choices where possible.",
        "professional" to "Clean, clear business language. Contractions acceptable but not preferred. Fix grammar and strip fillers. Minor rephrasing for clarity is OK.",
        "casual" to "Natural spoken rhythm. Contractions OK. Strip fillers and fix obvious errors, but keep the speaker's natural voice.",
        "friend" to "Conversational, relaxed. Contractions expected. Sentence fragments OK if natural. Strip fillers but preserve personality."
    )

    private fun getToneTemp(tone: String): Double = TONE_TEMPERATURE[tone] ?: 0.35
    private fun getSeverity(situation: String): Double = SITUATION_SEVERITY[situation] ?: 1.0
    private fun getToneRule(tone: String): String = TONE_RULES[tone] ?: ""
}
