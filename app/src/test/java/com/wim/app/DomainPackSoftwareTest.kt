package com.wim.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The software pack, and the rule that separates it from the correction list
 * that once rewrote a quarter of every plain transcription.
 *
 * A phonetic alias repairs a non-word the recogniser produced, so applying it
 * cannot destroy meaning. A preferred term swaps a real English word the
 * speaker used correctly, so it is a judgement and belongs in the prompt with
 * its condition attached — never in a find-and-replace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DomainPackSoftwareTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun pack() = DomainPackHelper.loadPack(ctx(), "software")

    @Test fun packLoadsWithAllThreeSections() {
        val p = pack()
        assertNotNull("software pack must load from assets", p)
        assertTrue("needs real vocabulary", p!!.vocab.size > 100)
        assertTrue("needs phonetic repairs", p.phoneticAliases.isNotEmpty())
        assertTrue("needs preferred terms", p.preferredTerms.isNotEmpty())
    }

    /** The engineer's pack, not the salesperson's. */
    @Test fun techIndustryGetsTheSoftwarePack() {
        WimApp.prefs.edit().putString("profile_industry", "tech").apply()
        assertEquals("software", DomainPackHelper.activePack(ctx())?.key)
        WimApp.prefs.edit().putString("profile_industry", "sales").apply()
        assertEquals("sf_pro", DomainPackHelper.activePack(ctx())?.key)
    }

    /** Every upgrade must carry the condition that makes it correct. */
    @Test fun everyPreferredTermStatesWhenItApplies() {
        pack()!!.preferredTerms.forEach {
            assertTrue(
                "preferred term '${it.loose}' → '${it.precise}' has no condition — " +
                    "without one it is a blind swap",
                it.whenTo.isNotBlank()
            )
            assertNotEquals(it.loose, it.precise)
        }
    }

    /**
     * A preferred term is a real word used correctly, so it must never appear
     * as a phonetic alias, which IS applied mechanically. "code" landing in
     * the alias map would rewrite "write some code" on every take.
     */
    @Test fun noPreferredTermIsAlsoAMechanicalAlias() {
        val p = pack()!!
        val aliasKeys = p.phoneticAliases.keys.map { it.lowercase() }.toSet()
        p.preferredTerms.forEach {
            assertFalse(
                "'${it.loose}' is a judgement call and must not be in the alias map",
                it.loose.lowercase() in aliasKeys
            )
        }
    }

    /** The prompt must carry the condition, not just the arrow. */
    @Test fun promptInjectionCarriesTheConditions() {
        WimApp.prefs.edit().putString("profile_industry", "tech").apply()
        val text = DomainPackHelper.promptInjection(ctx())
        assertTrue(text.contains("codebase"))
        assertTrue("the injection must explain when an upgrade applies",
            text.contains("only when"))
    }

    /** The transcriber hint stays a hint. */
    @Test fun asrHintIsCappedAndNonEmptyForTech() {
        WimApp.prefs.edit().putString("profile_industry", "tech").apply()
        val hint = DomainPackHelper.asrHint(ctx())
        assertTrue("tech profile should seed the transcriber", hint.isNotBlank())
        assertTrue("hint must stay short enough not to steer content", hint.length <= 900)

        WimApp.prefs.edit().putString("profile_industry", "").apply()
        assertEquals("no industry, no hint", "", DomainPackHelper.asrHint(ctx()))
    }
}
