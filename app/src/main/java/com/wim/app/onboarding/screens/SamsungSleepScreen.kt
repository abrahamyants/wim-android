package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wim.app.onboarding.PermissionLauncher

/**
 * Permission card #5: Samsung deep sleep — shown only when
 * PermissionStatus.isSamsung() (OnboardingNav handles the branch).
 *
 * There is no API to query the "Deep sleeping apps" list, so this card has
 * no grant detection and no status badge — primary opens Samsung's battery
 * UI (with generic app-details fallback), DONE advances on the user's word.
 * Mirrors BouncerActivity's Samsung page, where Done and Skip both advance.
 */
@Composable
fun SamsungSleepScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    PermissionCard(
        onBack = onBack,
        heading = "SAMSUNG ONE MORE THING",
        body = "Samsung overrides Android's battery exemption with a \"Deep sleeping apps\" " +
            "list. If WiM lands on that list, it dies anyway. Tap below and make sure WiM " +
            "isn't on it — Battery → Background usage limits → Deep sleeping apps.",
        granted = false,
        showBadge = false,
        primaryCta = "OPEN SAMSUNG BATTERY SETTINGS",
        secondaryCta = "DONE",
        onPrimary = { activity?.let { PermissionLauncher.openSamsungDeepSleepSettings(it) } },
        onSecondary = { onContinue() }
    )
}
