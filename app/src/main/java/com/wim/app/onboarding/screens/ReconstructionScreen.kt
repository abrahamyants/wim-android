package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.wim.app.onboarding.MrDnaBubble
import com.wim.app.onboarding.MrDnaMood
import com.wim.app.onboarding.theme.LimelightFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Reconstruction pitch — Calm-dark. The differentiator slide: WiM gauges INTENT,
 * not words (voice-to-intent, "Grammarly for voice"), and names the two layers plus
 * the "local transcription is free" point. Sits right after Welcome, before permissions
 * — sell the value, then ask for access. Content per George briefing 2026-06-20
 * (project_wim_onboarding_spec).
 */
@Composable
fun ReconstructionScreen(onContinue: () -> Unit) = CalmDark {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.4f))

            MrDnaBubble(mood = MrDnaMood.Idle, size = 92.dp)

            Spacer(Modifier.height(26.dp))

            Text(
                text = "IT GETS WHAT YOU MEANT",
                fontFamily = LimelightFamily,
                fontSize = 27.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Other voice apps race to type your exact words — and you still proofread " +
                    "every one. WiM is different: you ramble, half-finish, talk like you think — " +
                    "and it rewrites it into what you meant to say. Like handing Grammarly a draft.",
                fontFamily = PlusJakartaFamily,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = colorResource(R.color.text_secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 330.dp)
            )

            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorResource(R.color.bg_surface))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LayerRow("TRANSCRIBE", "Your words, tidied up. Runs free, right on your phone.")
                LayerRow("RECONSTRUCT", "Your intent, in your tone — Casual or Professional.")
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
                    text = "NEXT",
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
private fun LayerRow(title: String, desc: String) {
    Column {
        Text(
            text = title,
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 1.5.sp,
            color = colorResource(R.color.copper_gold)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = desc,
            fontFamily = PlusJakartaFamily,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colorResource(R.color.text_dim)
        )
    }
}
