package com.wim.app.onboarding.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.MrDnaBubble
import com.wim.app.onboarding.MrDnaMood
import com.wim.app.onboarding.theme.LimelightFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily
import kotlinx.coroutines.delay

/**
 * Welcome / demo. The pitch in 8 seconds:
 *   1. Big WiM logo lands
 *   2. Mini console types a disfluent sentence
 *   3. Cleaned output materializes underneath
 *   4. Cycle through 4 examples
 *   5. "Get Started" button always-on
 *
 * Port of the orphaned WebView tile 1 (assets/onboarding.html), restructured
 * around Compose primitives. Disfluent input examples are kept verbatim — they
 * are depiction of real speech disfluency patterns, not the forbidden term.
 */

private data class DemoExample(
    val messy: String,
    val clean: String,
    val tone: String,
    val junkWords: Set<String> = emptySet(),
    val repeatRanges: List<IntRange> = emptyList()
)

private val examples = listOf(
    DemoExample(
        messy = "I I I w-want to c-c-call my my d-doctor about the the appointment",
        clean = "I want to call my doctor about the appointment.",
        tone  = "casual",
        junkWords = setOf("w-want", "c-c-call", "d-doctor"),
        repeatRanges = listOf(0..2, 7..8, 10..11)
    ),
    DemoExample(
        messy = "So um I I was trying to to tell him the the project is done",
        clean = "I was trying to tell him the project is done.",
        tone  = "casual",
        junkWords = setOf("so", "um"),
        repeatRanges = listOf(3..4, 6..7, 10..11)
    ),
    DemoExample(
        messy = "Can you uh send me the the report by by Friday please",
        clean = "Can you send me the report by Friday?",
        tone  = "professional",
        junkWords = setOf("uh"),
        repeatRanges = listOf(5..6, 8..9)
    ),
    DemoExample(
        messy = "I think we should we should probably like move the meeting",
        clean = "I think we should move the meeting.",
        tone  = "formal",
        junkWords = setOf("like"),
        repeatRanges = listOf(3..6)
    )
)

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_base))
    ) {
        // Subtle vertical paper texture
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to colorResource(R.color.bg_base),
                        0.6f to colorResource(R.color.bg_surface),
                        1f to colorResource(R.color.bg_base)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: persona + WiM logo
            Row(verticalAlignment = Alignment.CenterVertically) {
                MrDnaBubble(mood = MrDnaMood.Excited, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "WiM",
                        fontFamily = LimelightFamily,
                        fontSize = 56.sp,
                        color = colorResource(R.color.section_header)
                    )
                    Text(
                        text = "WHAT I MEANT",
                        fontFamily = PlusJakartaFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        letterSpacing = 4.sp,
                        color = colorResource(R.color.text_secondary)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(24.dp))

            // The console — typewriter demo cycling examples
            DemoConsole()

            Spacer(Modifier.weight(1f))

            Text(
                text = "Voice in. Meaning out. Speak naturally — WiM rewrites what you meant to say.",
                fontFamily = PlusJakartaFamily,
                fontSize = 15.sp,
                color = colorResource(R.color.text_primary),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.copper_gold),
                    contentColor = colorResource(R.color.bg_base)
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

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(2.dp)
            .background(colorResource(R.color.copper_gold))
    )
}

@Composable
private fun DemoConsole() {
    var index by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var showClean by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }

    val current = examples[index % examples.size]

    LaunchedEffect(index) {
        typed = ""
        showHighlights = false
        showClean = false
        val full = current.messy
        for (i in 1..full.length) {
            typed = full.substring(0, i)
            delay(28L + (i % 5) * 4L)
        }
        delay(450)
        showHighlights = true
        delay(450)
        showClean = true
        delay(3800)
        index = (index + 1) % examples.size
    }

    val borderColor = colorResource(R.color.border_accent)
    val sunken = colorResource(R.color.bg_sunken)
    val raised = colorResource(R.color.bg_elevated)
    val raisedHi = colorResource(R.color.card_bg_base)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(0f to raisedHi, 1f to raised)
            )
            .border(1.dp, borderColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
    ) {
        Column {
            // Title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(0f to raisedHi, 1f to raised)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WiM",
                    fontFamily = LimelightFamily,
                    fontSize = 14.sp,
                    color = colorResource(R.color.section_header)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "VOICE RECONSTRUCTION",
                    fontFamily = PlusJakartaFamily,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    color = colorResource(R.color.text_dim)
                )
                Spacer(Modifier.weight(1f))
                LiveDot()
            }

            // Body — input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sunken)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(
                        text = "INPUT",
                        fontFamily = PlusJakartaFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp,
                        color = colorResource(R.color.copper_gold)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (!showHighlights) annotateTyped(typed)
                               else annotateWithJunk(typed, current, junkColor = colorResource(R.color.chip_value)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = colorResource(R.color.text_primary),
                        lineHeight = 22.sp
                    )

                    AnimatedVisibility(
                        visible = showClean,
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "↓",
                                fontSize = 14.sp,
                                color = colorResource(R.color.copper_gold),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "OUTPUT",
                                fontFamily = PlusJakartaFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 9.sp,
                                letterSpacing = 1.5.sp,
                                color = colorResource(R.color.copper_gold)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = current.clean,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = colorResource(R.color.green_active),
                                lineHeight = 22.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "tone: ${current.tone}",
                                fontFamily = PlusJakartaFamily,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                                color = colorResource(R.color.copper_gold),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            // Stats strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Stat("156", "WPM", Modifier.weight(1f))
                Stat("1.2s", "RECON", Modifier.weight(1f))
                Stat("✓", "FALCON", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LiveDot() {
    val pulse = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        val s = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness    = Spring.StiffnessLow
        )
        while (true) {
            pulse.animateTo(1f, s); pulse.animateTo(0.4f, s)
        }
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colorResource(R.color.green_active))
            .graphicsLayer { alpha = pulse.value }
    )
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colorResource(R.color.bg_sunken))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontFamily = LimelightFamily,
            fontSize = 14.sp,
            color = colorResource(R.color.copper_gold)
        )
        Text(
            text = label,
            fontFamily = PlusJakartaFamily,
            fontSize = 8.sp,
            letterSpacing = 0.6.sp,
            color = colorResource(R.color.text_dim)
        )
    }
}

private fun annotateTyped(s: String): AnnotatedString =
    buildAnnotatedString { append(s) }

private fun annotateWithJunk(typed: String, ex: DemoExample, junkColor: Color): AnnotatedString {
    val words = typed.split(' ')
    return buildAnnotatedString {
        words.forEachIndexed { i, w ->
            val isJunk = ex.junkWords.any { it.equals(w, ignoreCase = true) }
            val isRepeat = ex.repeatRanges.any { i in it && i != it.last }
            if (isJunk || isRepeat) {
                pushStyle(
                    SpanStyle(
                        color = junkColor,
                        textDecoration = TextDecoration.LineThrough
                    )
                )
                append(w)
                pop()
            } else {
                append(w)
            }
            if (i < words.size - 1) append(' ')
        }
    }
}
