package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.TitleFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Settings-menu slide — Calm-dark (George 2026-06-20). Conveys "the bubble IS the app":
 * close the app, and double-tap the bubble any time for the settings that matter — mirrors
 * the real double-tap menu contents (opacity, size, layer, bubble color, live transcript).
 * Clean recreation of the menu's *contents*, not the raw home-screen screenshot (kept on-brand).
 */
@Composable
fun SettingsMenuScreen(onBack: () -> Unit, onContinue: () -> Unit) = CalmDark {
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
            Text("‹", fontFamily = PlusJakartaFamily, fontSize = 30.sp, color = colorResource(R.color.copper_gold))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.35f))

            RealBubble(size = 118.dp, label = "WiM")

            Spacer(Modifier.height(20.dp))

            Text(
                text = "IT'S ALL ON THE BUBBLE",
                fontFamily = TitleFamily,
                fontSize = 26.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Close the app — the bubble is all you need. Double-tap it — or drag it to " +
                    "the top of your screen — any time for the settings that matter:",
                fontFamily = PlusJakartaFamily,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = colorResource(R.color.text_secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 330.dp)
            )

            Spacer(Modifier.height(26.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 350.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorResource(R.color.bg_surface))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                MenuRow("OPACITY", "how see-through it floats")
                MenuRow("SIZE", "how big it sits on screen")
                MenuRow("LAYER", "Transcribe · Reconstruct · Disfluency")
                MenuRow("COLOR", "bronze, aluminum, or gunmetal")
                MenuRow("LIVE", "watch the words land as you talk")
            }

            Spacer(Modifier.weight(0.7f))

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
                    text = "NEXT",
                    fontFamily = PlusJakartaFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MenuRow(name: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
            color = colorResource(R.color.copper_gold),
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = desc,
            fontFamily = PlusJakartaFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = colorResource(R.color.text_dim)
        )
    }
}
