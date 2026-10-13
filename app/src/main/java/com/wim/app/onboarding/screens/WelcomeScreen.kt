package com.wim.app.onboarding.screens

import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.FakeChatBox
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.TitleFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily
import kotlinx.coroutines.delay

/**
 * Welcome / intro — Calm-dark.
 *
 * rev 2026-10-12 (George's walk-through): the before/after card is gone. The
 * demo is the same fake messaging screen the Watch It Work page uses
 * ([FakeChatBox]), with no bubble on it yet, and it says one thing:
 * voice to text. You talk, the words come out any way they come out. Layer 1
 * puts the same words in, cleaned up. Layer 2 and up turns the ramble into a
 * tidy, professional message. That is all it has to convey.
 *
 * The slogan stays ("The first app you won't use." — George 10/12: keep it).
 * The bubble is the real one ([RealBubble]).
 */

private data class DemoExample(
    val messy: String,   // what comes out of the mouth
    val tidy: String,    // layer 1: the same words, cleaned up
    val clean: String    // layer 2 and up: what you meant, said well
)

private val examples = listOf(
    DemoExample(
        messy = "hey real quick I gotta tell my boss wait what was I gonna say oh right I'm running late traffic is insane ok send that",
        tidy = "Hey, real quick, I gotta tell my boss... wait, what was I gonna say? Oh right, I'm running late, traffic is insane. OK, send that.",
        clean = "Hi — I'm running late, traffic's heavy. I'll be there as soon as I can."
    ),
    DemoExample(
        messy = "text sarah real quick tell her happy birthday and umm that I miss her hope it's a good one",
        tidy = "Text Sarah real quick. Tell her happy birthday, and that I miss her. Hope it's a good one.",
        clean = "Happy birthday, Sarah! Hope it's a great one — miss you."
    ),
    DemoExample(
        messy = "ok email the team that the meeting uh what time was it whatever just move it to thursday something came up",
        tidy = "OK, email the team that the meeting, what time was it? Whatever, just move it to Thursday. Something came up.",
        clean = "Hi team — let's move our meeting to Thursday. Something came up."
    )
)

private enum class DemoStep { TALK, TIDY, CLEAN, SENT }

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
            Spacer(Modifier.weight(0.3f))

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

            Spacer(Modifier.height(18.dp))

            RealBubble()

            Spacer(Modifier.height(10.dp))

            Text(
                text = "WHAT I MEANT",
                fontFamily = TitleFamily,
                fontSize = 30.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 10f))
            )

            Spacer(Modifier.height(20.dp))

            TalkDemo()

            Spacer(Modifier.weight(0.6f))

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

/**
 * Voice to text, in the message box. Loops the three examples:
 *   TALK  — the messy words type in, faint and italic, as they are said
 *   TIDY  — layer 1: the same words, cleaned up
 *   CLEAN — layer 2 and up: what you meant, said well
 *   SENT  — it lands in the thread, the box empties
 */
@Composable
private fun TalkDemo() {
    var index by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf(DemoStep.TALK) }
    var shown by remember { mutableIntStateOf(0) }
    val current = examples[index % examples.size]

    LaunchedEffect(index) {
        step = DemoStep.TALK
        shown = 0
        while (shown < current.messy.length) {
            shown++
            delay(26)
        }
        delay(700)
        step = DemoStep.TIDY
        delay(3000)
        step = DemoStep.CLEAN
        delay(3000)
        step = DemoStep.SENT
        delay(1800)
        index++
    }

    val typed = when (step) {
        DemoStep.TALK -> current.messy.take(shown)
        DemoStep.TIDY -> current.tidy
        DemoStep.CLEAN -> current.clean
        DemoStep.SENT -> ""
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FakeChatBox(
            typed = typed,
            typedDim = step == DemoStep.TALK,
            sent = if (step == DemoStep.SENT) listOf(current.clean) else emptyList(),
            height = 270.dp
        )

        Spacer(Modifier.height(10.dp))

        val (chip, line) = when (step) {
            DemoStep.TALK -> "YOU TALK" to "Any way it comes out."
            DemoStep.TIDY -> "LAYER 1" to "The same words, cleaned up."
            DemoStep.CLEAN -> "LAYER 2 AND UP" to "What you meant, said well."
            DemoStep.SENT -> "SENT" to "Into the box, like any text."
        }
        Text(
            text = chip,
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.5.sp,
            color = when (step) {
                DemoStep.TALK -> colorResource(R.color.red_bright)
                DemoStep.TIDY -> colorResource(R.color.copper_gold)
                else -> colorResource(R.color.green_active)
            }
        )
        Text(
            text = line,
            fontFamily = PlusJakartaFamily,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = colorResource(R.color.text_dim),
            textAlign = TextAlign.Center,
            modifier = Modifier.height(26.dp)
        )
    }
}
