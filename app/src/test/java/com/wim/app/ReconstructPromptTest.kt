package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that ReconstructClient's ACTUAL maps contain correct values.
 * Reads directly from ReconstructClient — no mirrored copies.
 */
class ReconstructPromptTest {

    // ── Tone temperatures match Лаврентий ──────────────

    @Test fun temp_formal() = assertEquals(0.1, ReconstructClient.TONE_TEMPERATURE["formal"]!!, 0.001)
    @Test fun temp_professional() = assertEquals(0.15, ReconstructClient.TONE_TEMPERATURE["professional"]!!, 0.001)
    @Test fun temp_casual() = assertEquals(0.35, ReconstructClient.TONE_TEMPERATURE["casual"]!!, 0.001)
    @Test fun temp_friend() = assertEquals(0.4, ReconstructClient.TONE_TEMPERATURE["friend"]!!, 0.001)

    @Test fun temp_allFourPresent() {
        assertEquals(4, ReconstructClient.TONE_TEMPERATURE.size)
    }

    // ── Situation severities match Лаврентий ───────────

    @Test fun severity_default() = assertEquals(1.0, ReconstructClient.SITUATION_SEVERITY["default"]!!, 0.001)
    @Test fun severity_highStress() = assertEquals(1.5, ReconstructClient.SITUATION_SEVERITY["high_stress"]!!, 0.001)
    @Test fun severity_reading() = assertEquals(0.3, ReconstructClient.SITUATION_SEVERITY["reading"]!!, 0.001)

    @Test fun severity_allThreePresent() {
        assertEquals(3, ReconstructClient.SITUATION_SEVERITY.size)
    }

    // ── Tone rules are distinct and contain key phrases ─

    @Test fun toneRule_formal_noContractions() {
        // Wording was sharpened 2026-07-30 ("No" -> "NO"); the requirement is
        // that formal forbids contractions, not that it says it in one casing.
        assertTrue(
            ReconstructClient.TONE_RULES["formal"]!!.lowercase().contains("no contractions")
        )
    }

    @Test fun toneRule_professional_business() {
        assertTrue(ReconstructClient.TONE_RULES["professional"]!!.contains("business"))
    }

    @Test fun toneRule_casual_contractionsOk() {
        // "Contractions OK" became "Contractions expected" when the rules were
        // made specific — a stronger statement of the same permission.
        val rule = ReconstructClient.TONE_RULES["casual"]!!.lowercase()
        assertTrue(
            "casual must permit contractions: $rule",
            rule.contains("contractions ok") || rule.contains("contractions expected"),
        )
    }

    @Test fun toneRule_friend_relaxed() {
        val rule = ReconstructClient.TONE_RULES["friend"]!!
        assertTrue(rule.contains("relaxed") || rule.contains("Relaxed"))
    }

    @Test fun toneRules_allFourPresent() {
        assertEquals(4, ReconstructClient.TONE_RULES.size)
    }

    @Test fun toneRules_allDifferent() {
        val rules = ReconstructClient.TONE_RULES.values.toSet()
        assertEquals("All 4 rules should be unique", 4, rules.size)
    }

    // ── Severity threshold logic ───────────────────────

    @Test fun severity_highStress_triggers_aggressive() {
        assertTrue("1.5 >= 1.4 triggers aggressive", ReconstructClient.SITUATION_SEVERITY["high_stress"]!! >= 1.4)
    }

    @Test fun severity_reading_triggers_conservative() {
        assertTrue("0.3 <= 0.6 triggers conservative", ReconstructClient.SITUATION_SEVERITY["reading"]!! <= 0.6)
    }

    @Test fun severity_default_triggers_neither() {
        val s = ReconstructClient.SITUATION_SEVERITY["default"]!!
        assertFalse("1.0 not aggressive", s >= 1.4)
        assertFalse("1.0 not conservative", s <= 0.6)
    }
}
