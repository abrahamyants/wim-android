package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus

/**
 * Permission card #3: Microphone (RECORD_AUDIO runtime permission).
 *
 * The only card with no skip — see secondaryCta below.
 *
 * The system dialog grants in place — ON_RESUME + the poll in
 * permissionGrantState pick the flip up either way. After two dialog
 * attempts Android stops showing the dialog ("don't ask again"), so the
 * primary CTA reroutes to the app-details page where the toggle lives.
 */
@Composable
fun MicPermissionScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val activity = com.wim.app.onboarding.LocalOnboardingActivity.current
    var attempts by remember { mutableStateOf(0) }

    val granted = permissionGrantState(
        check = { PermissionStatus.microphone(context) },
        onGranted = onContinue
    )

    val exhausted = attempts >= 2 && !granted

    PermissionCard(
        onBack = onBack,
        heading = "VOICE IN",
        body = "WiM takes voice and turns it into clean text. That requires a microphone.",
        caveat = if (exhausted)
            "Android has stopped showing the dialog — flip Microphone on under Permissions instead."
        else null,
        granted = granted,
        primaryCta = if (exhausted) "OPEN SETTINGS" else "ALLOW MICROPHONE",
        // No skip. Overlay and accessibility change how WiM reaches you and can
        // wait; the microphone is the input itself, and the level measurement on
        // the next screen cannot run without it. "Not yet" here produced an app
        // that opens, looks fine, and does nothing.
        secondaryCta = "WHY IT IS NEEDED",
        onPrimary = {
            activity?.let {
                if (exhausted) {
                    PermissionLauncher.openAppDetails(it)
                } else {
                    PermissionLauncher.requestMicrophone(it)
                }
                attempts++
            }
        },
        onSecondary = {
            android.widget.Toast.makeText(
                context,
                "WiM turns your voice into text. Without the microphone there is " +
                    "nothing for it to work from, so this is the one permission it " +
                    "cannot start without.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    )
}
