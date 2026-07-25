package com.wim.app

/**
 * Decides which tone a host application should get — and, crucially, when
 * WiM should stop guessing and ask.
 *
 * Pure Kotlin, no Android imports, so the decision is unit-testable without
 * Robolectric. [WimAccessibilityService.autoSwitchTone] is the only caller.
 *
 * Before 2026-07-25 the router had two outcomes: a whitelist hit set the
 * tone, and anything else silently left the previous app's tone in place —
 * so dictating into an unlisted app inherited whatever tone the last listed
 * app had set. The [Decision.Ask] branch restores the "Quick question — is
 * this for work or casual?" prompt that shipped in April, and the answer is
 * remembered per package so a given app is only ever asked about once.
 *
 * Note: [AudienceContext] keeps its own, slightly wider copy of these sets
 * for the READER prompt block. Deliberately not merged here — that module
 * maps packages to a richer profile (medium, formality, brevity), not to a
 * two-value tone, and collapsing them would lose that. If they drift far
 * enough to matter, unify on AudienceContext's profile and derive tone from
 * it rather than adding a third copy.
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
        /** Unknown app and nothing learned — surface the work/casual question. */
        object Ask : Decision()
    }

    /**
     * @param packageName foreground app.
     * @param learnedTone the tone the user previously chose for THIS package,
     *   or null if they've never been asked. A learned answer outranks the
     *   built-in lists: the user correcting WiM is the strongest signal there
     *   is, and it must survive a later change to the shipped whitelists.
     */
    fun decide(packageName: String, learnedTone: String?): Decision = when {
        learnedTone == PROFESSIONAL || learnedTone == CASUAL -> Decision.Use(learnedTone)
        WORK_APPS.contains(packageName) -> Decision.Use(PROFESSIONAL)
        PERSONAL_APPS.contains(packageName) -> Decision.Use(CASUAL)
        // Substring match, not the sets — catches the long tail of mail
        // clients (fairemail, bluemail, k9mail, protonmail…) without an
        // unbounded whitelist.
        packageName.contains("mail") || packageName.contains("email") ->
            Decision.Use(PROFESSIONAL)
        else -> Decision.Ask
    }

    /** SharedPreferences key holding the user's answer for [packageName]. */
    fun learnedKey(packageName: String): String = "app_tone_$packageName"
}
