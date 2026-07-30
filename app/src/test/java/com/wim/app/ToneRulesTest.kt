package com.wim.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tone rules are prompt text, so a unit test cannot prove the model obeys
 * them. What it can prove is that the instruction is actually present and
 * specific — which is exactly what failed before.
 *
 * Measured on device 2026-07-30: professional returned "Hey, so I'm gonna need
 * the report by Friday afternoon." The rule at the time said "clean, clear
 * business language" and "contractions acceptable but not preferred", which
 * names nothing to remove and explicitly permits the colloquialisms. Vague
 * register instructions lose to the surrounding "preserve the speaker's words"
 * rules every time.
 *
 * These tests pin the concrete parts so a future tidy-up cannot quietly
 * reintroduce the abstract version.
 */
class ToneRulesTest {

    private val professional = ReconstructClient.TONE_RULES["professional"]!!
    private val casual = ReconstructClient.TONE_RULES["casual"]!!
    private val friend = ReconstructClient.TONE_RULES["friend"]!!
    private val formal = ReconstructClient.TONE_RULES["formal"]!!

    // ── Every tone is defined ─────────────────────────────────────

    @Test fun allFourTonesHaveRules() {
        listOf("formal", "professional", "casual", "friend").forEach {
            val rule = ReconstructClient.TONE_RULES[it]
            assertTrue("no rule for $it", !rule.isNullOrBlank())
        }
    }

    // ── Professional must name what to remove ─────────────────────

    @Test fun professionalNamesGreetingsToDrop() {
        assertTrue("greeting not named: $professional", professional.contains("hey man"))
        assertTrue("drop instruction missing", professional.contains("DROP"))
    }

    @Test fun professionalNamesSpokenContractions() {
        assertTrue("gonna not named: $professional", professional.contains("gonna"))
        assertTrue("wanna not named: $professional", professional.contains("wanna"))
    }

    @Test fun professionalNoLongerPermitsColloquialisms() {
        // The exact phrasing that neutered the rule.
        assertTrue(
            "the permissive phrasing is back: $professional",
            !professional.contains("Contractions acceptable but not preferred"),
        )
    }

    @Test fun professionalCarriesAWorkedExample() {
        assertTrue("no worked example: $professional", professional.contains("becomes"))
    }

    @Test fun professionalScopesItselfToRegister() {
        // Without this the rule reads as license to rewrite content, which is
        // the opposite failure — MeaningGuard would then reject every candidate.
        val lower = professional.lowercase()
        assertTrue("register scope missing: $professional", lower.contains("register"))
        assertTrue("content protection missing: $professional", lower.contains("numbers"))
    }

    // ── Casual and friend must PROTECT what professional strips ───

    @Test fun casualKeepsGreetings() {
        assertTrue("casual should keep greetings: $casual", casual.contains("KEEP"))
        assertTrue(casual.lowercase().contains("hey"))
    }

    @Test fun friendKeepsSlang() {
        assertTrue("friend should keep slang: $friend", friend.contains("KEEP"))
        assertTrue(friend.contains("gonna"))
    }

    @Test fun professionalAndCasualDisagreeAboutGreetings() {
        // The whole point of the dial. If both said the same thing about
        // greetings, the two tones would be indistinguishable — which is the
        // bug the operator reported.
        assertTrue(professional.contains("DROP"))
        assertTrue(casual.contains("KEEP"))
    }

    // ── Formal stays the strictest ────────────────────────────────

    @Test fun formalForbidsContractions() {
        assertTrue("formal must forbid contractions: $formal", formal.contains("NO contractions"))
    }

    @Test fun formalAlsoDropsGreetings() {
        assertTrue(formal.lowercase().contains("greeting"))
    }
}
