package com.wim.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.theme.LimelightFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Placeholder destination. Used by every route that hasn't been built yet.
 * Renders the brand background + Mr. DNA so visual scaffolding is testable
 * end-to-end even before the real content lands.
 */
@Composable
fun StubScreen(
    label: String,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_base))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MrDnaBubble(mood = MrDnaMood.Curious, size = 88.dp)
        Spacer(Modifier.size(32.dp))
        Text(
            text = "TODO",
            fontFamily = LimelightFamily,
            fontSize = 36.sp,
            color = colorResource(R.color.section_header)
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "route: $label",
            fontFamily = PlusJakartaFamily,
            fontSize = 14.sp,
            color = colorResource(R.color.text_dim)
        )
        Spacer(Modifier.size(48.dp))
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(R.color.copper_gold),
                contentColor = colorResource(R.color.bg_base)
            )
        ) {
            Text(
                text = "Next →",
                fontFamily = PlusJakartaFamily,
                fontSize = 16.sp
            )
        }
    }
}
