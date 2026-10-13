package com.wim.app.onboarding.screens

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.wim.app.onboarding.PermissionLauncher
import com.wim.app.onboarding.PermissionStatus
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.TitleFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Final screen — Calm-dark. The payoff beat: send the user OFF the app and onto
 * the bubble. This is the spine of WiM's positioning ("the first app you're not
 * going to use") — onboarding ends by telling them to close it, and names the
 * notification as the recall button if the bubble ever vanishes.
 *
 * Visual-reskin pass (2026-06-20): copy + brand only, matched to Welcome/PermissionCard.
 * The live interactive bubble performance and the profile questionnaire are
 * intentionally NOT here — pinned (see project_wim_onboarding_spec memory).
 *
 * Skipped-permission safety net (2026-06-25): the permission cards let the user
 * skip with a warning (deliberate — a hard wall makes sideloaded testers
 * uninstall; see OverlayPermissionScreen's DO-NOT-REVERT note). The cost was a
 * dead-end: skip mic/overlay/accessibility and onboarding still says "YOU'RE SET"
 * onto a bubble that can't draw, hear, or type. This screen now re-checks those
 * three on arrival and, if any are still off, shows a non-blocking notice with a
 * one-tap GRANT route. DONE is never disabled — the no-wall decision stands.
 */
@Composable
fun FinalScreen(onBack: () -> Unit, onDone: () -> Unit) {
    // Capture the Activity OUTSIDE CalmDark — inside it LocalContext is the
    // night-wrapped config context, which is not the Activity, so the grant
    // launchers (which need an Activity) would get null.
    val context = LocalContext.current
    val activity = com.wim.app.onboarding.LocalOnboardingActivity.current

    // Re-check the bubble's load-bearing permissions on every ON_RESUME, so the
    // notice clears the moment the user grants one and returns from Settings.
    var missing by remember { mutableStateOf(criticalMissing(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) missing = criticalMissing(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val allGranted = missing.isEmpty()

    CalmDark {
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
            BackArrow(onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.7f))

                RealBubble()

                Spacer(Modifier.height(30.dp))

                Text(
                    text = if (allGranted) "YOU'RE SET" else "ALMOST THERE",
                    fontFamily = TitleFamily,
                    fontSize = 34.sp,
                    color = colorResource(R.color.section_header),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "Now close the app. The bubble is your app — it lives on top of " +
                        "everything else. Tap it in any text field and just talk.",
                    fontFamily = PlusJakartaFamily,
                    fontSize = 16.sp,
                    lineHeight = 25.sp,
                    color = colorResource(R.color.text_secondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "If it ever disappears: swipe down from the top of your phone and " +
                        "tap WiM right there — it comes back. Leave that one on.",
                    fontFamily = PlusJakartaFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colorResource(R.color.text_dim),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )

                if (!allGranted) {
                    Spacer(Modifier.height(22.dp))
                    MissingPermsNotice(
                        missing = missing,
                        onGrant = { activity?.let { act -> missing.firstOrNull()?.grant?.invoke(act) } }
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(R.color.copper_gold),
                        contentColor = colorResource(R.color.bg_sunken)
                    )
                ) {
                    Text(
                        text = if (allGranted) "DONE" else "FINISH ANYWAY",
                        fontFamily = PlusJakartaFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** One load-bearing permission the bubble needs to function, plus how to grant it. */
private data class MissingPerm(val label: String, val grant: (Activity) -> Unit)

/**
 * The three permissions without which the bubble can't do its job: draw (overlay),
 * type (accessibility), and hear (microphone). Battery optimization is deliberately
 * NOT here — it affects reliability, not basic function, so it stays a soft skip.
 */
private fun criticalMissing(c: Context): List<MissingPerm> = buildList {
    if (!PermissionStatus.overlay(c))
        add(MissingPerm("Floating bubble") { PermissionLauncher.openOverlaySettings(it) })
    if (!PermissionStatus.accessibility(c))
        add(MissingPerm("Type for you") { PermissionLauncher.openAccessibilitySettings(it) })
    if (!PermissionStatus.microphone(c))
        add(MissingPerm("Microphone") { PermissionLauncher.requestMicrophone(it) })
}

@Composable
private fun MissingPermsNotice(missing: List<MissingPerm>, onGrant: () -> Unit) {
    val amber = colorResource(R.color.orange_label)
    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(amber.copy(alpha = 0.10f))
            .border(1.dp, amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Still off: " + missing.joinToString(", ") { it.label },
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = amber,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "WiM can't work until these are on. You can still finish — grant them " +
                "now, or later from Settings → WiM.",
            fontFamily = PlusJakartaFamily,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colorResource(R.color.text_dim),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "GRANT NOW ›",
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 1.4.sp,
            color = amber,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onGrant)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
