package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.LimelightFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Auto-Send teaching — Calm-dark. Teaches the crown jewel statically (no motion):
 * the bubble's RED → AMBER → GREEN-SEND sequence, and that it sends ON YOUR BEHALF so you
 * never reach for the host app's own send button. State dots reuse the approved tokens
 * (red_hot / orange_amber / green_active). Sits right before the final "close the app"
 * beat. Per George briefing 2026-06-20 (project_wim_onboarding_spec / project_wim_bubble_spec).
 */
@Composable
fun AutoSendScreen(onBack: () -> Unit, onContinue: () -> Unit) = CalmDark {
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
            Spacer(Modifier.weight(0.4f))

            RealBubble(size = 118.dp, label = "WiM")

            Spacer(Modifier.height(26.dp))

            Text(
                text = "SEND WITHOUT LEAVING",
                fontFamily = LimelightFamily,
                fontSize = 26.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Speak, and WiM drops the clean text right into your app. Then the bubble " +
                    "walks you all the way to sent:",
                fontFamily = PlusJakartaFamily,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = colorResource(R.color.text_secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 330.dp)
            )

            Spacer(Modifier.height(26.dp))

            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StepRow(R.color.red_hot, "Tap the bubble — and talk.")
                StepRow(R.color.orange_amber, "Tap again — it cleans up what you meant.")
                StepRow(R.color.green_active, "It turns green: SEND. Tap it — the bubble sends, for you.")
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "You never reach for the app's own send button. It's your call — flip it on in settings.",
                fontFamily = PlusJakartaFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colorResource(R.color.text_dim),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )

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
private fun StepRow(colorRes: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RealBubble(
            size = 46.dp,
            label = "",
            ringColor = colorResource(colorRes)
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = text,
            fontFamily = PlusJakartaFamily,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = colorResource(R.color.text_secondary)
        )
    }
}
