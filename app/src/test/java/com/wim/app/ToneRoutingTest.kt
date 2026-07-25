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

    @Test fun unknownApp_withNoProfileSignal_asks() {
        assertEquals(
            ToneRouting.Decision.Ask,
            ToneRouting.decide("com.some.unlisted.notes", learnedTone = null),
        )
    }

    // ── The onboarding profile answers, which used to be write-only ──

    @Test fun mostlyForLife_neverAsks_andStaysCasual() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "life", workHours = "nine_to_five",
                hourOfDay = 11, isWeekend = false,
            ),
        )
    }

    @Test fun mostlyForWork_neverAsks_andStaysProfessional() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "work", hourOfDay = 23, isWeekend = true,
            ),
        )
    }

    @Test fun both_insideStatedHours_isProfessional() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "nine_to_five",
                hourOfDay = 14, isWeekend = false,
            ),
        )
    }

    @Test fun both_outsideStatedHours_isCasual() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "nine_to_five",
                hourOfDay = 21, isWeekend = false,
            ),
        )
    }

    @Test fun both_weekend_isCasualEvenInsideTheWindow() {
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "nine_to_five",
                hourOfDay = 14, isWeekend = true,
            ),
        )
    }

    @Test fun flexibleHours_asks_ratherThanInventingAWindow() {
        // "Flexible" is a real answer meaning the clock predicts nothing.
        // Guessing from it would be worse than asking once.
        assertEquals(
            ToneRouting.Decision.Ask,
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "flexible",
                hourOfDay = 14, isWeekend = false,
            ),
        )
    }

    @Test fun legacyBouncerHourValues_areNormalised() {
        // Old first-run screen wrote a different vocabulary into the same key.
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "9to5",
                hourOfDay = 10, isWeekend = false,
            ),
        )
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "night_owl",
                hourOfDay = 20, isWeekend = false,
            ),
        )
        // "shifts" and "off_clock" carry no window — ask, don't guess.
        assertEquals(
            ToneRouting.Decision.Ask,
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "shifts",
                hourOfDay = 10, isWeekend = false,
            ),
        )
    }

    @Test fun legacyLocationAnswerInWorkSetup_isNotTreatedAsATone() {
        // Bouncer used to write office/hybrid/remote/mobile into work_setup.
        // That answers "where do you work", not "what do you use this for",
        // so it must fall through to the clock rather than imply professional.
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.CASUAL),
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "office", workHours = "nine_to_five",
                hourOfDay = 22, isWeekend = false,
            ),
        )
    }

    @Test fun appListStillOutranksTheProfile() {
        // Slack is work even for someone who said "mostly life".
        assertEquals(
            ToneRouting.Decision.Use(ToneRouting.PROFESSIONAL),
            ToneRouting.decide(
                "com.slack", learnedTone = null,
                workSetup = "life", hourOfDay = 22, isWeekend = true,
            ),
        )
    }

    @Test fun outOfRangeHour_asksRatherThanGuessing() {
        assertEquals(
            ToneRouting.Decision.Ask,
            ToneRouting.decide(
                "com.some.unlisted.notes", learnedTone = null,
                workSetup = "both", workHours = "nine_to_five",
                hourOfDay = -1, isWeekend = false,
            ),
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
