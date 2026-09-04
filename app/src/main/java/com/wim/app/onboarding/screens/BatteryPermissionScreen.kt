package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus

/**
 * Permission card #4: Battery optimization exemption.
 *
 * Opens Android's battery-optimization list (see
 * PermissionLauncher.openBatteryOptimizationSettings for why the one-tap
 * dialog was dropped). The user picks WiM from the list and chooses "Don't
 * optimize", so the copy has to say that. Grant detection is unchanged —
 * permissionGrantState polls isIgnoringBatteryOptimizations and advances
 * automatically once the exemption lands.
 */
@Composable
fun BatteryPermissionScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val activity = com.wim.app.onboarding.LocalOnboardingActivity.current

    val granted = permissionGrantState(
        check = { PermissionStatus.batteryOptimization(context) },
        onGranted = onContinue
    )

    PermissionCard(
        onBack = onBack,
        heading = "KEEP WiM AWAKE",
        body = "Android tries to put bubbles to sleep after a few minutes. WiM needs " +
            "to be exempt — otherwise it disappears mid-conversation.\n\n" +
            "Tap below, find What I Meant in the list, and choose \"Don't optimize\"." +
            (com.wim.app.OemKillers.hint()?.let { "\n\nOn this phone, also: $it" } ?: ""),
        granted = granted,
        primaryCta = "OPEN BATTERY SETTINGS",
        secondaryCta = "NOT YET",
        onPrimary = { activity?.let { PermissionLauncher.openBatteryOptimizationSettings(it) } },
        onSecondary = {
            android.util.Log.w(
                "WiM.Onboarding",
                "Battery exemption skipped — OS may kill the bubble in background"
            )
            android.widget.Toast.makeText(
                context,
                "Heads up: Android may kill the bubble after a few idle minutes until WiM is exempt.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onSkip()
        }
    )
}
