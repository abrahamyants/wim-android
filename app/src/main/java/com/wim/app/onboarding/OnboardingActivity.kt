package com.wim.app.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        setContent {
            com.wim.app.onboarding.theme.WimTheme {
                OnboardingNavHost(
                    onComplete = { handleComplete() }
                )
            }
        }
    }

    private fun handleComplete() {
        WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
