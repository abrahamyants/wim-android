package com.wim.app.onboarding.screens

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.TitleFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily
import kotlinx.coroutines.delay

/**
 * Welcome / intro — Calm-dark (rev 2026-06-20, George's intro-page notes, premium pass):
 *  - Slogan ("the first app you won't use") popped above the bubble.
 *  - Bubble (bobbing, WiM logo) hero kept. Redundant "WiM" wordmark removed; "WHAT I MEANT"
 *    promoted (larger, shadowed).
 *  - Demo is a real MESSAGE (a sent-text bubble, Google-Messages style) so it's unmistakably a
 *    text, not raw output. The blurt TYPES IN (live dictation feel): the stream of consciousness
 *    in GREEN with the junk / meta-noise words in RED (what gets cut). It then resolves into the
 *    clean reconstruction in a THIRD color (cream) + a "✓ what you meant". No strikethrough —
 *    reconstruction rewrites everything, so a per-word cross-out would misrepresent it.
 */

private data class DemoExample(
    val to: String,
    val messy: String,
    val junk: List<String>,
    val clean: String
)

private val examples = listOf(
    DemoExample(
        to = "your boss",
        messy = "hey real quick I gotta tell my boss wait what was I gonna say oh right I'm running late traffic is insane ok send that",
        junk = listOf("hey", "real quick", "wait what was I gonna say", "oh right", "ok send that"),
        clean = "Hi — I'm running late, traffic's heavy. I'll be there as soon as I can."
    ),
    DemoExample(
        to = "a friend",
        messy = "text sarah real quick tell her happy birthday and umm that I miss her hope it's a good one",
        junk = listOf("text sarah", "real quick", "tell her", "umm"),
        clean = "Happy birthday, Sarah! Hope it's a great one — miss you."
    ),
    DemoExample(
        to = "the team",
        messy = "ok email the team that the meeting uh what time was it whatever just move it to thursday something came up",
        junk = listOf("ok", "email the team", "uh what time was it", "whatever"),
        clean = "Hi team — let's move our meeting to Thursday. Something came up."
    )
)

private fun buildMessy(messy: String, junk: List<String>, green: Color, red: Color): AnnotatedString =
    buildAnnotatedString {
        pushStyle(SpanStyle(color = green))
        append(messy)
        pop()
        junk.forEach { phrase ->
            var i = messy.indexOf(phrase, ignoreCase = true)
            while (i >= 0) {
                addStyle(SpanStyle(color = red), i, i + phrase.length)
                i = messy.indexOf(phrase, i + phrase.length, ignoreCase = true)
            }
        }
    }

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
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
    ) {
        WelcomeContent(onContinue)
    }
}

@Composable
private fun WelcomeContent(onContinue: () -> Unit) {
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
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.5f))

            Text(
                text = "The first app you won't use.",
                fontFamily = PlusJakartaFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                letterSpacing = 0.3.sp,
                color = colorResource(R.color.copper_gold),
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f))
            )

            Spacer(Modifier.height(28.dp))

            RealBubble(size = 118.dp, label = "WiM")

            Spacer(Modifier.height(18.dp))

            Text(
                text = "WHAT I MEANT",
                fontFamily = TitleFamily,
                fontSize = 30.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 10f))
            )

            Spacer(Modifier.height(30.dp))

            ReconstructDemo()

            Spacer(Modifier.weight(0.85f))

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
                    text = "GET STARTED",
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
private fun ReconstructDemo() {
    val green = colorResource(R.color.green_active)
    val red = colorResource(R.color.red_bright)
    val out = colorResource(R.color.section_header)

    var index by remember { mutableIntStateOf(0) }
    val current = examples[index % examples.size]
    var shown by remember { mutableIntStateOf(0) }
    var reconstructed by remember { mutableStateOf(false) }

    val cleanAlpha by animateFloatAsState(
        targetValue = if (reconstructed) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "cleanAlpha"
    )

    val messyAnno = remember(index, green, red) { buildMessy(current.messy, current.junk, green, red) }

    LaunchedEffect(index) {
        shown = 0
        reconstructed = false
        while (shown < current.messy.length) {
            shown++
            delay(24)
        }
        delay(4000)
        reconstructed = true
        delay(4300)
        index++
    }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 344.dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 8.dp, bottom = 6.dp)
        ) {
            Text(
                text = if (reconstructed) "AFTER" else "BEFORE",
                fontFamily = PlusJakartaFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                color = if (reconstructed) colorResource(R.color.green_active) else colorResource(R.color.red_bright)
            )
            Text(
                text = "  ·  to ${current.to}",
                fontFamily = PlusJakartaFamily,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                color = colorResource(R.color.text_dim)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 5.dp, bottomStart = 18.dp))
                .background(colorResource(R.color.bg_surface))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (!reconstructed) {
                Text(
                    text = buildAnnotatedString {
                        append(messyAnno.subSequence(0, shown.coerceIn(0, current.messy.length)))
                        append("▏")
                    },
                    fontFamily = PlusJakartaFamily,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            } else {
                Text(
                    text = current.clean,
                    fontFamily = PlusJakartaFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = out,
                    modifier = Modifier.alpha(cleanAlpha)
                )
            }
        }

        if (reconstructed) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = "✓ what you meant",
                fontFamily = PlusJakartaFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                color = colorResource(R.color.green_active),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}
