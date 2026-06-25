package com.wim.app.onboarding.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import com.wim.app.R
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus
import com.wim.app.onboarding.theme.TitleFamily
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
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val activity = com.wim.app.onboarding.LocalOnboardingActivity.current
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
        onBack = onBack,
        heading = "LET WiM DRAW",
        body = "WiM is a bubble. It needs permission to float over your other apps so it can " +
            "sit there when you're texting, emailing, or in any app where you'd rather speak than type.",
        granted = granted,
        primaryCta = "OPEN SETTINGS",
        secondaryCta = "NOT YET",
        onPrimary = { activity?.let { PermissionLauncher.openOverlaySettings(it) } },
        // Soft skip — "NOT YET" was wired to the same destination as
        // "Granted," letting the user advance past a permission the
        // bubble fundamentally requires. Per audit 2026-06-06: warn
        // explicitly on skip so the consequence is visible, then let
        // the user proceed if they really want to. Better than a hard
        // block (sideloaded testers uninstall if they hit a wall) and
        // better than the silent pass-through this used to be.
        onSecondary = {
            android.util.Log.w(
                "WiM.Onboarding",
                "Overlay skipped — bubble cannot draw without this permission"
            )
            android.widget.Toast.makeText(
                context,
                "Heads up: the bubble can't appear until you grant this. You can come back from Settings → WiM.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onSkip()
        }
    )
}

/** Forces the approved night palette to resolve regardless of the device light/dark
 *  setting, so every onboarding surface stays Calm-dark. */
@Composable
internal fun CalmDark(content: @Composable () -> Unit) {
    val baseCtx = LocalContext.current
    val nightCtx = remember(baseCtx) {
        val cfg = Configuration(baseCtx.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        baseCtx.createConfigurationContext(cfg)
    }
    CompositionLocalProvider(
        LocalContext provides nightCtx,
        LocalConfiguration provides nightCtx.resources.configuration
    ) { content() }
}

/** Shared onboarding back arrow — top-left, copper. Place inside a CalmDark Box. */
@Composable
internal fun BoxScope.BackArrow(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, top = 4.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "‹",
            fontFamily = PlusJakartaFamily,
            fontSize = 30.sp,
            color = colorResource(R.color.copper_gold)
        )
    }
}

@Composable
internal fun PermissionCard(
    heading: String,
    body: String,
    granted: Boolean,
    primaryCta: String,
    secondaryCta: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    caveat: String? = null,
    showBadge: Boolean = true,
    onBack: (() -> Unit)? = null
) = CalmDark {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to colorResource(R.color.brand_dark_top),
                    0.45f to colorResource(R.color.bg_base),
                    1f to colorResource(R.color.bg_sunken)
                )
            )
    ) {
        if (onBack != null) BackArrow(onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.5f))

            RealBubble(
                size = 118.dp,
                label = if (granted) "✓" else "WiM"
            )

            Spacer(Modifier.height(30.dp))

            Text(
                text = heading,
                fontFamily = TitleFamily,
                fontSize = 30.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = body,
                fontFamily = PlusJakartaFamily,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = colorResource(R.color.text_secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )

            if (caveat != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = caveat,
                    fontFamily = PlusJakartaFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colorResource(R.color.text_dim),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            if (showBadge) {
                StatusBadge(granted = granted)
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onPrimary,
                enabled = !granted,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.copper_gold),
                    contentColor = colorResource(R.color.bg_sunken),
                    disabledContainerColor = colorResource(R.color.green_active),
                    disabledContentColor = colorResource(R.color.bg_sunken)
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

            Spacer(Modifier.height(6.dp))

            TextButton(onClick = onSecondary, enabled = !granted) {
                Text(
                    text = secondaryCta,
                    fontFamily = PlusJakartaFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = colorResource(R.color.text_dim)
                )
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun StatusBadge(granted: Boolean) {
    val tint = if (granted) colorResource(R.color.green_active) else colorResource(R.color.copper_gold)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tint)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = if (granted) "READY" else "TAP TO ENABLE",
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            color = tint
        )
    }
}
