package com.wim.app.onboarding.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus

/**
 * Permission card: notifications (Android 13+ asks; older phones have it
 * already and this card skips itself through permissionGrantState).
 *
 * Added 2026-10-12 (George's walk-through: "one more permission they usually
 * ask that is not on the list"). The bubble runs without it. What it buys:
 * the one card WiM keeps in the shade — "Voice dictation active" with MENU
 * and STOP — and the "Bubble hidden — tap to show" card after the bubble is
 * dragged down. Without it, a hidden bubble is only reachable by opening
 * the app.
 */
@Composable
fun NotificationsPermissionScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val activity = com.wim.app.onboarding.LocalOnboardingActivity.current

    val granted = permissionGrantState(
        check = { PermissionStatus.notifications(context) },
        onGranted = onContinue
    )

    PermissionCard(
        onBack = onBack,
        heading = "ONE CARD IN YOUR SHADE",
        body = "WiM keeps one small card in your notification shade: \"Voice dictation " +
            "active\", with MENU and STOP on it.\n\n" +
            "When you drag the bubble down to hide it, that card is how you bring it back.",
        granted = granted,
        primaryCta = "ALLOW NOTIFICATIONS",
        secondaryCta = "NOT NOW",
        onPrimary = { activity?.let { PermissionLauncher.requestNotifications(it) } },
        onSecondary = { onSkip() }
    )
}
