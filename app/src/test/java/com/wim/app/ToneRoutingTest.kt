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

    // ── seedTone: onboarding's "MOSTLY FOR" answer ──────────────────
    //
    // The regression these cover: the answer was written to work_setup and read
    // only by decide(), decide() lost its caller when per-app routing was
    // retired, and someone who answered "work" still started on casual.

    @Test fun seedTone_workStartsProfessional() {
        assertEquals(ToneRouting.PROFESSIONAL, ToneRouting.seedTone("work"))
    }

    @Test fun seedTone_lifeStartsCasual() {
        assertEquals(ToneRouting.CASUAL, ToneRouting.seedTone("life"))
    }

    @Test fun seedTone_bothAndUnansweredLeaveTheDefaultAlone() {
        assertEquals(null, ToneRouting.seedTone("both"))
        assertEquals(null, ToneRouting.seedTone(null))
        assertEquals(null, ToneRouting.seedTone(""))
    }

    // The dashboard Profile overlay stores the same answer under a different
    // key with a different spelling, so a user who set it there reached none of
    // the consumers keyed on work_setup.

    @Test fun seedTone_acceptsTheDashboardVocabulary() {
        assertEquals(ToneRouting.PROFESSIONAL, ToneRouting.seedTone("work_only"))
        assertEquals(ToneRouting.CASUAL, ToneRouting.seedTone("life_only"))
    }

    @Test fun normalizeUsage_translatesDashboardToOnboarding() {
        assertEquals("work", ToneRouting.normalizeUsage("work_only"))
        assertEquals("life", ToneRouting.normalizeUsage("life_only"))
        assertEquals("both", ToneRouting.normalizeUsage("both"))
    }

    @Test fun normalizeUsage_leavesOnboardingValuesAlone() {
        assertEquals("work", ToneRouting.normalizeUsage("work"))
        assertEquals("life", ToneRouting.normalizeUsage("life"))
        assertEquals(null, ToneRouting.normalizeUsage(null))
    }

    @Test fun theTwoKeyPairsAreDistinct() {
        assertEquals("work_setup", ToneRouting.USAGE_KEY)
        assertEquals("profile_usage", ToneRouting.LEGACY_USAGE_KEY)
        assertEquals("work_hours", ToneRouting.HOURS_KEY)
        assertEquals("profile_hours", ToneRouting.LEGACY_HOURS_KEY)
    }

    @Test fun seedTone_ignoresBouncerLocationVocabulary() {
        // office/hybrid/remote/mobile answer "where do you work", not "what do
        // you use this for". Installs from that era still hold these values.
        for (v in listOf("office", "hybrid", "remote", "mobile")) {
            assertEquals("'$v' is a location answer, not a register signal", null, ToneRouting.seedTone(v))
        }
    }
}
