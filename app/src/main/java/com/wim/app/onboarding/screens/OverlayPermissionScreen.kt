package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wim.app.R
import com.wim.app.onboarding.MrDnaBubble
import com.wim.app.onboarding.MrDnaMood
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus
import com.wim.app.onboarding.theme.LimelightFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily
import kotlinx.coroutines.delay

/**
 * Permission card #1: Overlay (SYSTEM_ALERT_WINDOW).
 *
 * Bubble draws on top of other apps. Without this, WiM is invisible.
 *
 * Two-way wiring:
 *  - "Open settings" → Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 *  - Lifecycle ON_RESUME re-checks status → flips badge to ✓ when granted
 *  - Polling (1s) while screen is foreground catches the case where the user
 *    flips the toggle and the OS doesn't immediately fire RESUME. Mirrors the
 *    BouncerActivity.pollForOverlayGrant pattern — DO NOT REVERT, sideloaded
 *    users uninstall the moment they have to back-button out of Settings.
 */
@Composable
fun OverlayPermissionScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var granted by remember { mutableStateOf(PermissionStatus.overlay(context)) }

    // Lifecycle-driven re-check on RESUME
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = PermissionStatus.overlay(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Poll while not granted — catches the user-flips-toggle-in-Settings case.
    LaunchedEffect(granted) {
        var ticks = 0
        while (!granted && ticks < 60) {
            delay(1000)
            granted = PermissionStatus.overlay(context)
            ticks++
        }
    }

    // Auto-advance the moment the toggle flips
    LaunchedEffect(granted) {
        if (granted) {
            delay(900)  // let the badge animate to ✓
            onContinue()
        }
    }

    PermissionCard(
        heading = "LET WiM DRAW",
        body = "WiM is a bubble. It needs permission to float over your other apps so it can " +
            "sit there when you're texting, emailing, or in any app where you'd rather speak than type.",
        granted = granted,
        primaryCta = "OPEN SETTINGS",
        secondaryCta = "NOT YET",
        onPrimary = { activity?.let { PermissionLauncher.openOverlaySettings(it) } },
        onSecondary = onSkip
    )
}

@Composable
internal fun PermissionCard(
    heading: String,
    body: String,
    granted: Boolean,
    primaryCta: String,
    secondaryCta: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_base))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MrDnaBubble(
                mood = if (granted) MrDnaMood.Satisfied else MrDnaMood.Excited,
                size = 96.dp
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = heading,
                fontFamily = LimelightFamily,
                fontSize = 28.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorResource(R.color.bg_surface))
                    .border(
                        1.dp,
                        colorResource(R.color.border_accent).copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp)
            ) {
                Text(
                    text = body,
                    fontFamily = PlusJakartaFamily,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = colorResource(R.color.text_primary)
                )
            }

            Spacer(Modifier.height(20.dp))

            StatusBadge(granted = granted)

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onPrimary,
                enabled = !granted,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.copper_gold),
                    contentColor = colorResource(R.color.bg_base),
                    disabledContainerColor = colorResource(R.color.green_active),
                    disabledContentColor = colorResource(R.color.bg_base)
                )
            ) {
                Text(
                    text = if (granted) "GRANTED ✓" else primaryCta,
                    fontFamily = PlusJakartaFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onSecondary, enabled = !granted) {
                Text(
                    text = secondaryCta,
                    fontFamily = PlusJakartaFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = colorResource(R.color.copper_gold)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(granted: Boolean) {
    val container = if (granted) colorResource(R.color.green_active) else colorResource(R.color.chip_value)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(container.copy(alpha = 0.15f))
            .border(1.dp, container, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(container)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = if (granted) "READY" else "TAP TO ENABLE",
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            color = container
        )
    }
}
