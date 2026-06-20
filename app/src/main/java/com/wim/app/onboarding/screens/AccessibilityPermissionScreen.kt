package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus

/**
 * Permission card #2: Accessibility service.
 *
 * The bubble reads the active text field so it can rewrite what you meant —
 * that capability is Android's accessibility service. Settings round-trip is
 * deep-linked straight to WiM's toggle page; WimAccessibilityService.
 * onServiceConnected snaps the user back the instant the service connects
 * (host-aware — returns to this flow, not the Bouncer).
 */
@Composable
fun AccessibilityPermissionScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val granted = permissionGrantState(
        check = { PermissionStatus.accessibility(context) },
        onGranted = onContinue
    )

    PermissionCard(
        heading = "LET WiM LISTEN",
        body = "WiM rewrites what you meant to say in the field you're typing into. " +
            "To do that, it needs to read the field's contents — which is what " +
            "Android's accessibility service is for.",
        caveat = if (PermissionStatus.isSamsung())
            "Samsung: tap \"Installed apps\" → What I Meant → On → Allow. WiM snaps you back here automatically."
        else
            "Tap \"Installed services\" → WiM → On. WiM snaps you back here automatically.",
        granted = granted,
        primaryCta = "OPEN ACCESSIBILITY SETTINGS",
        secondaryCta = "NOT YET",
        onPrimary = { activity?.let { PermissionLauncher.openAccessibilitySettings(it) } },
        onSecondary = {
            android.util.Log.w(
                "WiM.Onboarding",
                "Accessibility skipped — bubble cannot read or rewrite text without it"
            )
            android.widget.Toast.makeText(
                context,
                "Heads up: WiM can't see or fix your text until this is on. Settings → Accessibility → WiM.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onSkip()
        }
    )
}
