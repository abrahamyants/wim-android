package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ToneRouting] — the work/casual decision behind the
 * "Quick question — is this for work or casual?" prompt.
 *
 * The behaviour that matters and had no coverage before: an unknown app must
 * produce [ToneRouting.Decision.Ask], not silently inherit the previous app's
 * tone, and a user's learned answer must outrank the shipped whitelists.
 */
class ToneRoutingTest {

    @Test fun workApp_usesProfessional() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide("com.slack", learnedTone = null),
        )
    }

    @Test fun personalApp_usesCasual() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide("com.whatsapp", learnedTone = null),
        )
    }

    @Test fun mailSubstring_usesProfessional() {
        // The long tail of mail clients that will never be in the set.
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide("eu.faircode.email", learnedTone = null),
        )
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide("ch.protonmail.android", learnedTone = null),
        )
    }

    @Test fun unknownApp_asksInsteadOfInheritingPreviousTone() {
        assertEquals(
            ToneRouting.Decision.Ask,
            ToneRouting.decide("com.some.unlisted.notes", learnedTone = null),
        )
    }

    @Test fun learnedAnswer_isUsedForAnUnknownApp() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide("com.some.unlisted.notes", learnedTone = ToneRouting.CASUAL),
        )
    }

    @Test fun learnedAnswer_outranksTheShippedWhitelist() {
        // The user told us Slack is casual for them. The built-in list says
        // professional. The user wins, permanently — otherwise the bezel tap
        // is undone the moment the 30s manual lockout lapses.
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide("com.slack", learnedTone = ToneRouting.CASUAL),
        )
    }

    @Test fun learnedAnswer_outranksTheMailSubstringRule() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide("ch.protonmail.android", learnedTone = ToneRouting.CASUAL),
        )
    }

    @Test fun garbageLearnedValue_fallsBackToTheLists() {
        // A corrupt or stale pref must not become a tone. "work" is not a tone
        // string; "professional" and "casual" are.
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide("com.slack", learnedTone = "work"),
        )
        assertEquals(
            ToneRouting.Decision.Ask,
            ToneRouting.decide("com.some.unlisted.notes", learnedTone = ""),
        )
    }

    @Test fun learnedKey_isNamespacedPerPackage() {
        assertEquals("app_tone_com.slack", ToneRouting.learnedKey("com.slack"))
    }
}
