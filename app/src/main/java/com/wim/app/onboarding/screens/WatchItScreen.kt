package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.CHAT_H
import com.wim.app.onboarding.CHAT_W
import com.wim.app.onboarding.FakeChatBox
import com.wim.app.onboarding.REAL_BUBBLE_BOX
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.RealBubbleState
import com.wim.app.onboarding.SEND_BTN
import com.wim.app.onboarding.theme.PlusJakartaFamily
import com.wim.app.onboarding.theme.TitleFamily

/**
 * Watch-it slide (George 2026-10-12): the onboarding told the user what the
 * bubble does in words; this slide SHOWS one whole take, looping, over the
 * fake chat screen ([FakeChatBox]), with the real bubble ([RealBubble]).
 *
 * The story inside the box (George 10/12, "tell the story better"): while the
 * bubble is red the spoken words show up faint and italic in the message box,
 * exactly as they come out of the mouth. Orange: they get worked on. Then the
 * clean line types itself over them. Green: one tap sends.
 *
 * Timeline (one loop, ms):
 *   0      gold IDLE, resting up by the incoming message
 *   900    finger tap → RED "REC"; the messy words appear as "you talk"
 *   5400   finger tap → ORANGE "PROC", arcs light 12→3→6→9 o'clock
 *   7400   clean text types itself into the box
 *   9400   GREEN "SEND" + count, bubble slides onto the Send arrow
 *   10800  finger tap → the message pops into the thread, box empties
 *   11100  bubble slides home, back to gold IDLE
 *   13300  loop
 */
private const val LOOP_MS = 13_300L
private const val T_TAP1 = 900L
private const val T_TAP2 = 5_400L
private const val T_TYPE = 7_400L
private const val T_SEND = 9_400L
private const val T_TAP3 = 10_800L
private const val T_HOME = 11_100L

private const val MESSY_TEXT = "hey real quick I gotta tell my boss wait what was I gonna say oh right I'm running late traffic is insane ok send that"
private const val CLEAN_TEXT = "Hi — I'm running late, traffic's heavy. I'll be there as soon as I can."

private enum class Phase { IDLE, REC, PROC, TYPING, SEND, SENT, HOME }

private fun phaseAt(t: Long): Phase = when {
    t < T_TAP1 -> Phase.IDLE
    t < T_TAP2 -> Phase.REC
    t < T_TYPE -> Phase.PROC
    t < T_SEND -> Phase.TYPING
    t < T_TAP3 -> Phase.SEND
    t < T_HOME -> Phase.SENT
    else -> Phase.HOME
}

private fun captionFor(p: Phase): String = when (p) {
    Phase.IDLE -> "Tap the bubble."
    Phase.REC -> "Red. Talk, any way it comes out."
    Phase.PROC -> "Orange. It works on your words."
    Phase.TYPING -> "Your clean text lands in the box."
    Phase.SEND -> "Green means you may send. One tap."
    Phase.SENT, Phase.HOME -> "Sent. Nothing goes without your tap."
}

@Composable
fun WatchItScreen(onBack: () -> Unit, onContinue: () -> Unit) = CalmDark {
    // One clock drives everything; every frame derives its look from `t`.
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> t = (now - start) % LOOP_MS }
        }
    }
    val phase = phaseAt(t)

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
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.15f))

            Text(
                text = "WATCH IT WORK",
                fontFamily = TitleFamily,
                fontSize = 26.sp,
                color = colorResource(R.color.section_header),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            FakeChat(t = t, phase = phase)

            Spacer(Modifier.height(14.dp))

            Text(
                text = captionFor(phase),
                fontFamily = PlusJakartaFamily,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = colorResource(R.color.text_dim),
                textAlign = TextAlign.Center,
                modifier = Modifier.height(56.dp)
            )

            Spacer(Modifier.weight(0.5f))

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

/* ---------- the take, frame by frame ---------- */

private fun ease(x: Float): Float = x * x * (3f - 2f * x)

@Composable
private fun FakeChat(t: Long, phase: Phase) {
    // What sits in the message box.
    val (typed, dim) = when (phase) {
        Phase.REC -> {
            val f = ((t - T_TAP1).toFloat() / (T_TAP2 - T_TAP1 - 400L)).coerceIn(0f, 1f)
            MESSY_TEXT.take((MESSY_TEXT.length * f).toInt()) to true
        }
        Phase.PROC -> MESSY_TEXT to true
        Phase.TYPING -> {
            val f = ((t - T_TYPE).toFloat() / (T_SEND - T_TYPE - 300L)).coerceIn(0f, 1f)
            CLEAN_TEXT.take((CLEAN_TEXT.length * f).toInt()) to false
        }
        Phase.SEND -> CLEAN_TEXT to false
        else -> "" to false
    }
    val sentVisible = phase == Phase.SENT || phase == Phase.HOME

    // Bubble rest spot: upper right, level with the incoming message.
    // Send spot: centered on the Send arrow (bottom-right corner of the box).
    val restX = CHAT_W - REAL_BUBBLE_BOX - 14.dp
    val restY = 34.dp
    val sendX = CHAT_W - 12.dp - SEND_BTN / 2 - REAL_BUBBLE_BOX / 2
    val sendY = CHAT_H - 12.dp - SEND_BTN / 2 - REAL_BUBBLE_BOX / 2
    val slide = when (phase) {
        Phase.SEND, Phase.SENT -> ease(((t - T_SEND).toFloat() / 350f).coerceIn(0f, 1f))
        Phase.HOME -> 1f - ease(((t - T_HOME).toFloat() / 250f).coerceIn(0f, 1f))
        else -> 0f
    }
    val bx = restX + (sendX - restX) * slide
    val by = restY + (sendY - restY) * slide

    // Finger: a soft disc that lands on the bubble at each tap.
    val tapAt = listOf(T_TAP1, T_TAP2, T_TAP3)
    val fingerAlpha = tapAt.maxOf { tap ->
        val d = t - tap
        when {
            d < -250 || d > 300 -> 0f
            d < 0 -> (d + 250) / 250f
            else -> 1f - d / 300f
        }
    }.coerceIn(0f, 1f)

    // The bubble's look this frame.
    val breathe = if (phase == Phase.REC) {
        1f + 0.04f * kotlin.math.sin(((t - T_TAP1) % 1200L) / 1200f * 2f * Math.PI.toFloat())
    } else 1f
    val stage = when (phase) {
        Phase.PROC -> (((t - T_TAP2) / 500L).toInt()).coerceIn(0, 3)
        Phase.TYPING -> 3
        else -> -1
    }
    val sendLeft = if (phase == Phase.SEND) (5 - (t - T_SEND) / 1000L).coerceIn(1, 5) else 0L
    val state = when (phase) {
        Phase.REC -> RealBubbleState.RECORDING
        Phase.PROC, Phase.TYPING -> RealBubbleState.PROCESSING
        Phase.SEND -> RealBubbleState.SEND_READY
        else -> RealBubbleState.IDLE
    }

    FakeChatBox(
        typed = typed,
        typedDim = dim,
        sent = if (sentVisible) listOf(CLEAN_TEXT) else emptyList()
    ) {
        RealBubble(
            state = state,
            breathe = breathe,
            stage = stage,
            sendLeft = sendLeft,
            modifier = Modifier.offset(x = bx, y = by)
        )
        Box(
            modifier = Modifier
                .offset(x = bx + REAL_BUBBLE_BOX / 2 - 16.dp, y = by + REAL_BUBBLE_BOX / 2 - 10.dp)
                .size(32.dp)
                .alpha(fingerAlpha * 0.55f)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
