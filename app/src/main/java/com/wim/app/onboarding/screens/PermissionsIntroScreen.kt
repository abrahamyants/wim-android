package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.PermissionStatus
import com.wim.app.onboarding.theme.LimelightFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Permissions intro — Calm-dark. The trust-framing beat between Welcome and the gauntlet.
 * Names every switch up front with a live status dot per row (granted rows show green on
 * entry, so a returning user sees progress instead of a wall).
 */
@Composable
fun PermissionsIntroScreen(onBack: () -> Unit, onContinue: () -> Unit) = CalmDark {
    val context = LocalContext.current
    val samsung = PermissionStatus.isSamsung()

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.4f))

            RealBubble(size = 118.dp, label = "WiM")

            Spacer(Modifier.height(26.dp))

            Text(
                text = if (samsung) "FIVE QUICK THINGS" else "FOUR QUICK THINGS",
                fontFamily = LimelightFamily,
                fontSize = 30.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "The bubble lives on top of your other apps — that takes a few Android " +
                    "switches. Each one unlocks a piece of WiM. Grant them as they come; WiM " +
                    "snaps you back here after each.",
                fontFamily = PlusJakartaFamily,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = colorResource(R.color.text_secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )

            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorResource(R.color.bg_surface))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ManifestRow("OVERLAY", "so the bubble can appear", PermissionStatus.overlay(context))
                ManifestRow("ACCESSIBILITY", "so it can fix your text", PermissionStatus.accessibility(context))
                ManifestRow("MICROPHONE", "so it can hear you", PermissionStatus.microphone(context))
                ManifestRow("BATTERY", "so it survives the background", PermissionStatus.batteryOptimization(context))
                if (samsung) {
                    ManifestRow("SAMSUNG SLEEP", "so One UI doesn't kill it", false)
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onContinue,
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
                    text = "LET'S GO",
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

@Composable
private fun ManifestRow(label: String, why: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (granted) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.green_active))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, colorResource(R.color.copper_gold), CircleShape)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                fontFamily = PlusJakartaFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 1.4.sp,
                color = colorResource(R.color.text_bright)
            )
            Text(
                text = why,
                fontFamily = PlusJakartaFamily,
                fontSize = 11.sp,
                color = colorResource(R.color.text_dim)
            )
        }
    }
}
