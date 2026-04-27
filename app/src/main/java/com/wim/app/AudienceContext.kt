package com.wim.app

/**
 * Audience-Specific Reconstruction — patent claim line:
 * "Voice transcription post-processor that conditions reconstruction style
 *  on real-time foreground-app + IME-field metadata."
 *
 * Tone (formal/professional/casual/friend) is a weak proxy for the real
 * signal that should drive reconstruction style: **recipient + medium +
 * thread context**. The same words read very differently in Slack
 * #engineering at 11am Tuesday vs iMessage to your spouse at 11pm Saturday
 * vs email to a foundation at 9am Monday.
 *
 * WiM has unique access to this signal because it's a system-wide IME +
 * Accessibility service — it can read the foreground app's package name
 * the moment dictation starts. Lavrentiy can't (system-wide engine, no
 * app context), and other voice-dictation tools (Wispr, Google Voice
 * Typing, Apple Dictation, Dragon) don't condition on it either.
 *
 * This module formats the captured app metadata into a structured READER
 * block that gets appended to the L2/L3 reconstruction prompt. The model
 * uses it to tune brevity, formality, and structure to the actual reader
 * and medium — not just an abstract tone label.
 *
 * v1 captures only the foreground app package (via
 * WimAccessibilityService.currentForegroundPackage). Future expansions:
 * thread-context scraping (last N messages from visible UI), recipient
 * inference (contact name from window title), composer-mode detection
 * (reply vs new vs edit). Each one gets layered onto the same READER
 * block as additional bullets.
 */
object AudienceContext {

    private val workApps = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.slack", "com.microsoft.teams", "com.linkedin.android",
        "com.google.android.apps.docs", "com.google.android.apps.docs.editors.sheets",
        "com.asana.app", "com.trello", "com.atlassian.android.jira.core",
        "com.atlassian.android.confluence", "com.notion.id", "notion.id"
    )
    private val personalApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.facebook.orca", "com.instagram.android", "com.snapchat.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.discord", "com.reddit.frontpage"
    )

    data class Profile(
        val category: String,
        val audience: String,
        val mediumExpectations: String
    )

    /**
     * Map a package name to a structured audience profile. Specific apps
     * get tailored profiles; everything else falls back to category-level
     * defaults via [workApps] / [personalApps]; truly unknown apps get
     * a no-tuning fallback.
     */
    fun profileForPackage(packageName: String?): Profile? {
        if (packageName.isNullOrBlank()) return null
        return when (packageName) {
            "com.google.android.gm", "com.microsoft.office.outlook" -> Profile(
                category = "email",
                audience = "professional contacts (recipient explicitly addressed)",
                mediumExpectations = "asynchronous; expects greeting and sign-off; longer-form OK; full sentences; no Slack-style fragments"
            )
            "com.slack" -> Profile(
                category = "work chat",
                audience = "team peers (technical or business)",
                mediumExpectations = "async; expects brevity; jargon and acronyms welcome; no greeting needed; threading is normal; one short paragraph beats five sentences"
            )
            "com.microsoft.teams" -> Profile(
                category = "work chat",
                audience = "colleagues (more formal than Slack)",
                mediumExpectations = "async; brief but professional; minimal slang; topic-first sentences"
            )
            "com.linkedin.android" -> Profile(
                category = "professional networking",
                audience = "weak ties or new contacts; first impression",
                mediumExpectations = "polite-formal; careful tone; brand-aware; avoid overly casual language and inside-baseball jargon"
            )
            "com.google.android.apps.messaging", "com.samsung.android.messaging" -> Profile(
                category = "SMS",
                audience = "personal contact (one-to-one)",
                mediumExpectations = "casual; brief; no formal structure; sentence fragments OK; emoji acceptable but not required"
            )
            "com.whatsapp", "com.whatsapp.w4b" -> Profile(
                category = "personal chat",
                audience = "friends or family (group or 1:1)",
                mediumExpectations = "casual; conversational; slang and profanity preserved as-spoken; very short messages preferred over long blocks"
            )
            "org.telegram.messenger", "com.facebook.orca" -> Profile(
                category = "personal chat",
                audience = "friends, family, or casual contacts",
                mediumExpectations = "casual; conversational; slang OK; brief"
            )
            "com.instagram.android", "com.snapchat.android" -> Profile(
                category = "social DM",
                audience = "friends or mutuals",
                mediumExpectations = "very casual; internet shorthand and slang preserved; very brief; lowercase first letters acceptable; emoji frequent"
            )
            "com.discord" -> Profile(
                category = "community chat",
                audience = "channel peers (often gaming, tech, or hobby community)",
                mediumExpectations = "casual; in-group jargon and emoji preserved; very brief; lowercase normal"
            )
            "com.reddit.frontpage" -> Profile(
                category = "forum post or comment",
                audience = "broad anonymous audience",
                mediumExpectations = "structured arguments; can be longer; jargon explained or avoided; tone matches subreddit norms"
            )
            "com.google.android.apps.docs", "com.google.android.apps.docs.editors.sheets" -> Profile(
                category = "document",
                audience = "future readers (often colleagues)",
                mediumExpectations = "polished prose; full sentences; structured paragraphs; no chat-style fragments"
            )
            "com.notion.id", "notion.id" -> Profile(
                category = "notes / docs",
                audience = "self or small team",
                mediumExpectations = "structured; bulletable; can be terse but must be parseable later"
            )
            "com.asana.app", "com.trello", "com.atlassian.android.jira.core" -> Profile(
                category = "task tracker",
                audience = "task assignees / project peers",
                mediumExpectations = "imperative voice OK; brief; action-oriented; no greetings"
            )
            else -> when {
                workApps.contains(packageName) -> Profile(
                    category = "work tool",
                    audience = "colleagues",
                    mediumExpectations = "professional; concise; can use domain jargon"
                )
                personalApps.contains(packageName) -> Profile(
                    category = "personal app",
                    audience = "friends or family",
                    mediumExpectations = "casual; brief; conversational"
                )
                packageName.contains("mail", ignoreCase = true) ||
                packageName.contains("email", ignoreCase = true) -> Profile(
                    category = "email-like",
                    audience = "asynchronous correspondent",
                    mediumExpectations = "professional; full sentences; greeting and sign-off"
                )
                packageName.contains("chat", ignoreCase = true) ||
                packageName.contains("messag", ignoreCase = true) -> Profile(
                    category = "chat-like",
                    audience = "synchronous correspondent",
                    mediumExpectations = "casual; brief; conversational"
                )
                else -> null  // truly unknown app — no tuning, default to user's chosen tone
            }
        }
    }

    /**
     * Format the READER block for the L2/L3 reconstruction system prompt.
     * Returns empty string when no profile is available so callers can
     * append unconditionally.
     *
     * The block is INFORMATIONAL — the model uses it to tune style. The
     * NEVER-ECHO directive at the end keeps the captured metadata from
     * leaking into the user-visible output.
     */
    fun formatReaderBlock(packageName: String?): String {
        val profile = profileForPackage(packageName) ?: return ""
        return buildString {
            append("\n\nREADER CONTEXT (system-detected from foreground app — use to tune style only, NEVER echo back, NEVER mention):")
            append("\n- App package: $packageName")
            append("\n- Category: ${profile.category}")
            append("\n- Implied audience: ${profile.audience}")
            append("\n- Medium expectations: ${profile.mediumExpectations}")
            append("\n\nTune output for this audience and medium. Specifically:")
            append("\n- DO NOT add greetings ('Hi', 'Hey', 'Dear ...') or sign-offs ('Thanks', 'Best, ...') unless the medium expects them.")
            append("\n- DO NOT over-formalize peer-to-peer chat or under-formalize email to weak ties.")
            append("\n- MATCH the medium's brevity convention: chat = short, email = full sentences, document = polished prose.")
            append("\n- PRESERVE slang and informality when the audience expects it; do not 'professionalize' a message to a friend.")
        }
    }
}
