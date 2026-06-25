package com.wim.app.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * The REAL bubble (gunmetal theme) rendered in Compose — replaces the flat MrDnaBubble
 * stand-in so the onboarding shows the actual asset the user gets. Layers the live app's own
 * drawables (no hex, no vectors, no Activity context needed; confirmed safe outside an Activity):
 *   bubble_bezel_gunmetal (coral-ringed metal bezel) + bubble_knob_gunmetal (raised knob) +
 *   knob_tick (gold tick at 12 o'clock) + label.
 * Keeps the bob George liked (spring breathe — brand-guard requires spring-only, no tween).
 * Ratios mirror bubble.xml (root 120 : bezel 72 : knob 44 : tick 3×8 @ ~16 from top).
 *
 * @param ringColor optional state ring drawn over the bezel (red/amber/green); null = stock coral.
 */
@Composable
fun RealBubble(
    size: Dp = 96.dp,
    label: String = "WiM",
    ringColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val breath = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val s = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
        while (true) {
            breath.animateTo(1f, s)
            breath.animateTo(0f, s)
        }
    }

    val knobFraction = 44f / 72f

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { translationY = -(breath.value * 4f * density) },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.bubble_bezel_gunmetal),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )

        if (ringColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(width = size * (2.5f / 72f), color = ringColor, shape = CircleShape)
            )
        }

        Image(
            painter = painterResource(R.drawable.bubble_knob_gunmetal),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(knobFraction)
        )

        Image(
            painter = painterResource(R.drawable.knob_tick),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = size * (15f / 72f))
                .size(width = size * (3.5f / 72f), height = size * (9f / 72f))
        )

        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = colorResource(R.color.section_header),
                fontFamily = PlusJakartaFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.135f).sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
