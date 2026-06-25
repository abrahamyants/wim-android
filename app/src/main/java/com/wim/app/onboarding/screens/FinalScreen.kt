package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
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
 * Final screen — Calm-dark. The payoff beat: send the user OFF the app and onto
 * the bubble. This is the spine of WiM's positioning ("the first app you're not
 * going to use") — onboarding ends by telling them to close it, and names the
 * notification as the recall button if the bubble ever vanishes.
 *
 * Visual-reskin pass (2026-06-20): copy + brand only, matched to Welcome/PermissionCard.
 * The live interactive bubble performance and the profile questionnaire are
 * intentionally NOT here — pinned (see project_wim_onboarding_spec memory).
 */
@Composable
fun FinalScreen(onBack: () -> Unit, onDone: () -> Unit) = CalmDark {
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

            RealBubble(size = 118.dp, label = "✓")

            Spacer(Modifier.height(30.dp))

            Text(
                text = "YOU'RE SET",
                fontFamily = LimelightFamily,
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
                text = "If it ever disappears, the WiM notification in your tray brings it " +
                    "back — leave that one on.",
                fontFamily = PlusJakartaFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colorResource(R.color.text_dim),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )

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
                    text = "DONE",
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
