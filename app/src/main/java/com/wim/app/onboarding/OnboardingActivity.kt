package com.wim.app.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.wim.app.BubbleService
import com.wim.app.MainActivity
import com.wim.app.WimApp

/** Survives the CalmDark LocalContext override — all permission screens read this. */
val LocalOnboardingActivity = staticCompositionLocalOf<Activity?> { null }

/**
 * Compose-based onboarding host. Replaces the View/XML BouncerActivity.
 *
 * Current status (2026-06-26): this Compose flow IS the canonical first-run
 * route — MainActivity routes first-run here (the old "flip MainActivity" step
 * is done). BouncerActivity is kept as a fallback, not yet deleted. Remaining:
 *   1. Delete BouncerActivity.kt + activity_bouncer.xml + onboarding.html
 *   2. Update brand.local.md to declare Compose canonical
 *
 * Until then this can be launched manually for testing via:
 *   adb shell am start -n com.wimlabs.wim/com.wim.app.onboarding.OnboardingActivity
 */
class OnboardingActivity : ComponentActivity() {

    /**
     * Onboarding is always the Calm-dark palette by design. This is a plain
     * ComponentActivity, so the app-wide AppCompatDelegate night mode never
     * reaches it — on a phone in light mode, colorResource() resolved the
     * cream values/ palette and text rendered near-invisible. Force night on
     * this activity's own Configuration so every colorResource/painterResource
     * resolves values-night/ regardless of the system setting.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val night = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_YES or
                (newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        }
        applyOverrideConfiguration(night)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tell the accessibility snap-back which onboarding host is active —
        // WimAccessibilityService.onServiceConnected returns the user here
        // (not BouncerActivity) while the Compose flow is running.
        WimApp.prefs.edit().putString("onboarding_host", "compose").apply()
        setContent {
            com.wim.app.onboarding.theme.WimTheme {
                // Provide the Activity before CalmDark overwrites LocalContext with a
                // ContextWrapper.  Permission screens read LocalOnboardingActivity.current
                // (a separate CompositionLocal unaffected by that override) so their
                // Settings launchers actually fire.
                CompositionLocalProvider(LocalOnboardingActivity provides this@OnboardingActivity) {
                    com.wim.app.onboarding.screens.CalmDark {
                        OnboardingNavHost(
                            onComplete = { handleComplete() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Hide the live floating bubble while onboarding is on screen so it doesn't draw
        // over the flow. Starting our own (non-exported) service from in-app is allowed;
        // only external uids are blocked. Restored on completion (below).
        startService(Intent(this, BubbleService::class.java).apply { action = "HIDE" })
    }

    private fun handleComplete() {
        WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()
        startService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
