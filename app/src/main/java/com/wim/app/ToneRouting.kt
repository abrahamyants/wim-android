package com.wim.app

/**
 * Decides which tone a host application should get — and, crucially, when
 * WiM should stop guessing and ask.
 *
 * Pure Kotlin, no Android imports, so the decision is unit-testable without
 * Robolectric.
 *
 * ## Status, 2026-08-02
 *
 * [decide] currently has no production caller. `autoSwitchTone` became a no-op
 * stub when per-app routing was retired on 2026-07-29, and
 * `autoSwitchToneLegacy` — the only code that ever called [decide] — was left
 * behind with nothing invoking it. The constants, [learnedKey] and [seedTone]
 * are live; the router below is kept because the retirement was a behavior
 * decision, not a verdict on the code.
 *
 * ## Why this exists
 *
 * Before 2026-07-25 the router had two outcomes: a whitelist hit set the tone,
 * and anything else silently left the previous app's tone in place — so
 * dictating into an unlisted app inherited whatever formality the last listed
 * app had set. Seventeen packages is not the world.
 *
 * The onboarding profile already asks the user two questions specifically to
 * answer this — "MOSTLY FOR" (work / life / both) and "YOUR HOURS" — and
 * ProfileScreen's own KDoc claims they "calibrate the tone engine from message
 * one". They did not: `work_setup` and `work_hours` were written by two
 * different onboarding screens and read by nothing. This router is what makes
 * that claim true, and the "Quick question — is this for work or casual?"
 * prompt is now the last resort rather than the only mechanism.
 *
 * ## Signal order (strongest first)
 *
 *  1. What the user answered for THIS app — a correction outranks everything.
 *  2. The shipped app lists.
 *  3. "MOSTLY FOR" — an explicit statement about what they use WiM for.
 *  4. "YOUR HOURS" + the clock, but only when (3) is "both"/unset.
 *  5. Ask.
 *
 * ## Legacy values
 *
 * BouncerActivity (the pre-Compose first-run path, still the fallback) wrote a
 * different vocabulary into the same keys: `9to5`/`night_owl`/`shifts`/
 * `off_clock` for hours, and `office`/`hybrid`/`remote`/`mobile` for setup —
 * the latter answering "where do you work", an entirely different question.
 * Installs from that era still hold those values, so hours are normalised
 * below and the location answers are treated as no signal rather than being
 * bent into a tone they were never about.
 *
 * Note: [AudienceContext] keeps its own, slightly wider copy of the app sets
 * for the READER prompt block. Deliberately not merged — that module maps
 * packages to a richer profile (medium, formality, brevity), not to a
 * two-value tone.
 */
object ToneRouting {

    const val PROFESSIONAL = "professional"
    const val CASUAL = "casual"

    val WORK_APPS = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.slack", "com.microsoft.teams", "com.linkedin.android",
        "com.google.android.apps.docs", "com.google.android.apps.docs.editors.sheets",
        "com.asana.app", "com.trello", "com.atlassian.android.jira.core",
    )

    val PERSONAL_APPS = setOf(
        "com.whatsapp", "org.telegram.messenger", "com.facebook.orca",
        "com.instagram.android", "com.snapchat.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
    )

    sealed class Decision {
        /** Apply this tone. */
        data class Use(val tone: String) : Decision()
        /** Nothing available resolves it — surface the work/casual question. */
        object Ask : Decision()
    }

    /** Half-open hour window [from, until) in local 24h time. */
    private data class Window(val from: Int, val until: Int)

    /**
     * Normalise `work_hours` across both onboarding vocabularies.
     * Returns null when the answer carries no usable window — "flexible",
     * "shifts" and "off_clock" all mean "the clock does not predict this",
     * which is a real answer and must not be forced into one.
     */
    private fun hoursWindow(workHours: String?): Window? = when (workHours) {
        "morning" -> Window(5, 12)
        "nine_to_five", "9to5" -> Window(9, 17)
        "evening", "night_owl" -> Window(17, 23)
        else -> null // flexible / shifts / off_clock / unset
    }

    /**
     * @param packageName foreground app.
     * @param learnedTone the tone the user previously chose for THIS package,
     *   or null if they've never been asked.
     * @param workSetup the `work_setup` pref — "work", "life" or "both".
     *   Legacy location answers (office/hybrid/remote/mobile) are ignored.
     * @param workHours the `work_hours` pref, either vocabulary.
     * @param hourOfDay local hour, 0-23.
     * @param isWeekend true for Saturday/Sunday in the user's own timezone.
     */
    fun decide(
        packageName: String,
        learnedTone: String?,
        workSetup: String? = null,
        workHours: String? = null,
        hourOfDay: Int = -1,
        isWeekend: Boolean = false,
    ): Decision = when {
        learnedTone == PROFESSIONAL || learnedTone == CASUAL -> Decision.Use(learnedTone)

        WORK_APPS.contains(packageName) -> Decision.Use(PROFESSIONAL)
        PERSONAL_APPS.contains(packageName) -> Decision.Use(CASUAL)
        // Substring match, not the set — catches the long tail of mail clients
        // (fairemail, bluemail, k9mail, protonmail…) without an endless list.
        packageName.contains("mail") || packageName.contains("email") ->
            Decision.Use(PROFESSIONAL)

        // The user told us outright what they use WiM for. Believe them, and
        // don't interrupt to re-ask something already answered in onboarding.
        workSetup == "life" -> Decision.Use(CASUAL)
        workSetup == "work" -> Decision.Use(PROFESSIONAL)

        // "Both" (or never answered): the clock is the only remaining signal,
        // and only when they gave a window worth trusting. Weekends fall out
        // of every work window by definition.
        else -> {
            val window = hoursWindow(workHours)
            when {
                window == null || hourOfDay !in 0..23 -> Decision.Ask
                isWeekend -> Decision.Use(CASUAL)
                hourOfDay >= window.from && hourOfDay < window.until ->
                    Decision.Use(PROFESSIONAL)
                else -> Decision.Use(CASUAL)
            }
        }
    }

    /** SharedPreferences key holding the user's answer for [packageName]. */
    fun learnedKey(packageName: String): String = "app_tone_$packageName"

    /**
     * The tone a fresh install starts on, from the onboarding "MOSTLY FOR"
     * answer. This is the only remaining live consumer of `work_setup`.
     *
     * Onboarding has asked this question since 2026-06-20 and ProfileScreen's
     * KDoc has claimed since then that it "calibrates the tone engine from
     * message one". It did not. The answer reached [decide], [decide] lost its
     * caller when per-app routing was retired, and the pref has been written
     * and read by nothing ever since — so a user who said "work" still got the
     * `casual` default on their first dictation.
     *
     * Returns null when the answer carries no register signal: "both", never
     * answered, and BouncerActivity's legacy location vocabulary
     * (office/hybrid/remote/mobile), which answered "where do you work" — a
     * different question that must not be bent into a tone.
     */
    fun seedTone(workSetup: String?): String? = when (workSetup) {
        "work" -> PROFESSIONAL
        "life" -> CASUAL
        else -> null
    }
}
