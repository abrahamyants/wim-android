package com.wim.app.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wim.app.BubbleService
import com.wim.app.MainActivity
import com.wim.app.WimApp

/**
 * Compose-based onboarding host. Replaces the View/XML BouncerActivity.
 *
 * Current status (2026-05-16): registered in AndroidManifest as a parallel route.
 * MainActivity still routes first-run to BouncerActivity. Once the screens here
 * reach feature parity and ship-quality, we will:
 *   1. Flip MainActivity.kt:95 to startActivity(OnboardingActivity::class.java)
 *   2. Delete BouncerActivity.kt + activity_bouncer.xml + onboarding.html
 *   3. Update brand.local.md to declare Compose canonical
 *
 * Until then this can be launched manually for testing via:
 *   adb shell am start -n com.wim.app/.onboarding.OnboardingActivity
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tell the accessibility snap-back which onboarding host is active —
        // WimAccessibilityService.onServiceConnected returns the user here
        // (not BouncerActivity) while the Compose flow is running.
        WimApp.prefs.edit().putString("onboarding_host", "compose").apply()
        setContent {
            com.wim.app.onboarding.theme.WimTheme {
                // Force the Calm-dark palette across EVERY screen regardless of the
                // device light/dark setting. Most screens wrap CalmDark themselves
                // (via PermissionCard or directly), but Welcome and any future screen
                // that doesn't would resolve light-mode colorResources and render
                // low-contrast. Wrapping the whole graph once here is the backstop.
                com.wim.app.onboarding.screens.CalmDark {
                    OnboardingNavHost(
                        onComplete = { handleComplete() }
                    )
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
