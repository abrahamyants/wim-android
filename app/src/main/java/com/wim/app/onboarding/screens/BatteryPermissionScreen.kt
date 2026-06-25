package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus

/**
 * Permission card #4: Battery optimization exemption.
 *
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS shows a system dialog directly —
 * no Settings detour on most OEMs. Grant detection via permissionGrantState.
 */
@Composable
fun BatteryPermissionScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val granted = permissionGrantState(
        check = { PermissionStatus.batteryOptimization(context) },
        onGranted = onContinue
    )

    PermissionCard(
        onBack = onBack,
        heading = "KEEP WiM AWAKE",
        body = "Android tries to put bubbles to sleep after a few minutes. WiM needs " +
            "to be exempt — otherwise it disappears mid-conversation.",
        granted = granted,
        primaryCta = "EXEMPT WiM",
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
