package com.wim.app.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.theme.LimelightFamily

enum class MrDnaMood { Idle, Excited, Curious, Satisfied }

/**
 * Mr. DNA persona bubble. Replaces the WebView .wim-bubble.
 *
 * Motion contract (enforced by wim-brand-guard hook):
 *  - Spring physics ONLY. No tween anywhere in this file.
 *  - Damping ratio MediumBouncy, stiffness Low — the "signature wobble" per brand.
 *  - Infinite breathing implemented as an Animatable bounce between 0 and 1, each
 *    leg driven by a spring(). This produces real organic motion, not eased fakery.
 *
 * Lottie JSON assets (res/raw/mrdna_*.json) are not yet designed. When they exist,
 * route this composable through rememberLottieComposition and fall back here only
 * if the asset returns null.
 */
@Composable
fun MrDnaBubble(
    mood: MrDnaMood = MrDnaMood.Idle,
    size: Dp = 96.dp,
    label: String = "WiM",
    modifier: Modifier = Modifier
) {
    val breath = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }

    val (offsetMax, tiltMax) = when (mood) {
        MrDnaMood.Idle      -> 4f to 0f
        MrDnaMood.Curious   -> 3f to 5f
        MrDnaMood.Excited   -> 8f to 3f
        MrDnaMood.Satisfied -> 2f to 1.5f
    }

    LaunchedEffect(mood) {
        val s = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        )
        while (true) {
            breath.animateTo(1f, s)
            breath.animateTo(0f, s)
        }
    }

    LaunchedEffect(mood) {
        if (tiltMax == 0f) return@LaunchedEffect
        val s = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        )
        while (true) {
            tilt.animateTo(1f, s)
            tilt.animateTo(-1f, s)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                translationY = -(breath.value * offsetMax * density)
                rotationZ    = tilt.value * tiltMax
            }
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colorResource(R.color.copper_gold),
                        colorResource(R.color.brand_dark)
                    ),
                    center = Offset(size.value * 1.05f, size.value * 0.85f),
                    radius = size.value * 2.2f
                )
            )
            .border(
                width = 2.dp,
                color = colorResource(R.color.copper_gold),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = colorResource(R.color.section_header),
            fontFamily = LimelightFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (size.value * 0.32f).sp
        )
    }
}
