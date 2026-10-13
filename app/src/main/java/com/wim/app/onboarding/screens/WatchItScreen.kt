package com.wim.app.onboarding.screens

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.wim.app.BezelStageArcView
import com.wim.app.BubbleAppearance
import com.wim.app.BubbleLiveStateVisuals
import com.wim.app.R
import com.wim.app.onboarding.theme.PlusJakartaFamily
import com.wim.app.onboarding.theme.TitleFamily

/**
 * Watch-it slide (George 2026-10-12): the onboarding told the user what the
 * bubble does in words; this slide SHOWS one whole take, looping, over a
 * fake chat screen.
 *
 * The bubble here is not a drawing of the bubble. It is the live bubble's own
 * layout (bubble_bezel.xml) inflated inside Compose, painted by the same
 * [BubbleLiveStateVisuals.apply] the service calls, with the same
 * [BezelStageArcView] lighting the same four arcs. Colors cannot drift from
 * production because they are production.
 *
 * Timeline (one loop, ms):
 *   0      gold IDLE, resting above the text box
 *   900    finger tap → RED "REC", bubble breathes while "you talk"
 *   3600   finger tap → ORANGE "PROC", arcs light 12→3→6→9 o'clock
 *   5600   clean text types itself into the box
 *   7600   GREEN "SEND" + 5s count, bubble slides onto the Send arrow
 *   9000   finger tap → the message pops into the thread, box empties
 *   9300   bubble slides home, back to gold IDLE
 *   11500  loop
 */
private const val LOOP_MS = 11_500L
private const val T_TAP1 = 900L
private const val T_TAP2 = 3_600L
private const val T_TYPE = 5_600L
private const val T_SEND = 7_600L
private const val T_TAP3 = 9_000L
private const val T_HOME = 9_300L

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
    Phase.REC -> "Red. Talk."
    Phase.PROC -> "Tap again. Orange. It works on your words."
    Phase.TYPING -> "Your clean text lands in the box."
    Phase.SEND -> "Green. Tap it, and that tap sends."
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
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = colorResource(R.color.text_dim),
                textAlign = TextAlign.Center,
                modifier = Modifier.height(40.dp)
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

/* ---------- the fake chat screen ---------- */

private val CHAT_W = 330.dp
private val CHAT_H = 470.dp
private val BUBBLE_SCALE = 0.9f             // 72dp bezel → 65dp: the whole bubble is the size the old knob was (George 10/12)
private val BUBBLE_BOX = 72.dp
private val SEND_BTN = 40.dp

@Composable
private fun FakeChat(t: Long, phase: Phase) {
    val typed = when (phase) {
        Phase.TYPING -> {
            val f = ((t - T_TYPE).toFloat() / (T_SEND - T_TYPE - 300L)).coerceIn(0f, 1f)
            CLEAN_TEXT.take((CLEAN_TEXT.length * f).toInt())
        }
        Phase.SEND -> CLEAN_TEXT
        else -> ""
    }
    val sentVisible = phase == Phase.SENT || phase == Phase.HOME

    // Bubble rest spot: upper right, level with the incoming message —
    // not down by the Send arrow (George 10/12). The slide to Send is the
    // visible move at the end.
    // Send spot: centered on the Send arrow (bottom-right corner of the box).
    val restX = CHAT_W - BUBBLE_BOX - 14.dp
    val restY = 34.dp
    val sendX = CHAT_W - 12.dp - SEND_BTN / 2 - BUBBLE_BOX / 2
    val sendY = CHAT_H - 12.dp - SEND_BTN / 2 - BUBBLE_BOX / 2
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

    Box(modifier = Modifier.size(CHAT_W, CHAT_H)) {
      Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(colorResource(R.color.bg_surface))
            .border(1.dp, colorResource(R.color.copper_gold).copy(alpha = 0.35f), RoundedCornerShape(22.dp))
      ) {
        // Thread.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Boss",
                fontFamily = PlusJakartaFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = colorResource(R.color.copper_gold)
            )
            ChatLine("Where are you?", mine = false)
            if (sentVisible) ChatLine(CLEAN_TEXT, mine = true)
        }

        // Text box + Send arrow.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colorResource(R.color.bg_base))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = if (typed.isEmpty()) "Message" else typed,
                    fontFamily = PlusJakartaFamily,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    color = if (typed.isEmpty()) colorResource(R.color.text_dim).copy(alpha = 0.6f)
                    else colorResource(R.color.section_header)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(SEND_BTN)
                    .clip(CircleShape)
                    .background(colorResource(R.color.copper_gold)),
                contentAlignment = Alignment.Center
            ) {
                Text("➤", fontSize = 16.sp, color = colorResource(R.color.bg_sunken))
            }
        }

      }

        // The real bubble — outside the clipped card so it can hang over the edge.
        LiveBubble(
            phase = phase,
            t = t,
            modifier = Modifier
                .offset(x = bx, y = by)
                .size(BUBBLE_BOX)
        )

        // The finger.
        Box(
            modifier = Modifier
                .offset(x = bx + BUBBLE_BOX / 2 - 16.dp, y = by + BUBBLE_BOX / 2 - 10.dp)
                .size(32.dp)
                .alpha(fingerAlpha * 0.55f)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun ChatLine(text: String, mine: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text = text,
            fontFamily = PlusJakartaFamily,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = if (mine) colorResource(R.color.bg_sunken) else colorResource(R.color.section_header),
            modifier = Modifier
                .widthIn(max = 200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (mine) colorResource(R.color.copper_gold)
                    else colorResource(R.color.bg_base)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/* ---------- the live bubble, inflated from the service's own layout ---------- */

private fun ease(x: Float): Float = x * x * (3f - 2f * x)

@Composable
private fun LiveBubble(phase: Phase, t: Long, modifier: Modifier = Modifier) {
    // Breathe while recording — the same lift-and-breathe feel the live
    // bubble gets from BubbleAffect; a 1.2 s cycle, 4 % amplitude.
    val breathe = if (phase == Phase.REC) {
        1f + 0.04f * kotlin.math.sin(((t - T_TAP1) % 1200L) / 1200f * 2f * Math.PI.toFloat())
    } else 1f
    val stage = when (phase) {
        Phase.PROC -> (((t - T_TAP2) / 500L).toInt()).coerceIn(0, 3)
        Phase.TYPING -> 3
        else -> -1
    }
    val sendLeft = if (phase == Phase.SEND) (5 - (t - T_SEND) / 1000L).coerceIn(1, 5) else 0L

    AndroidView(
        modifier = modifier.graphicsLayer {
            scaleX = BUBBLE_SCALE * breathe
            scaleY = BUBBLE_SCALE * breathe
        },
        factory = { ctx ->
            // bubble_bezel.xml: a match_parent host with the 72 dp bezel centered.
            LayoutInflater.from(ctx).inflate(R.layout.bubble_bezel, null, false).also { root ->
                paintIdle(root)
            }
        },
        update = { root ->
            when (phase) {
                Phase.IDLE, Phase.HOME, Phase.SENT -> paintIdle(root)
                Phase.REC -> {
                    BubbleLiveStateVisuals.apply(root, BubbleLiveStateVisuals.State.RECORDING)
                    root.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
                }
                Phase.PROC, Phase.TYPING -> {
                    BubbleLiveStateVisuals.apply(root, BubbleLiveStateVisuals.State.PROCESSING)
                    root.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
                }
                Phase.SEND -> {
                    BubbleLiveStateVisuals.apply(root, BubbleLiveStateVisuals.State.SEND_READY)
                    root.findViewById<TextView>(R.id.bubbleTime)?.apply {
                        visibility = View.VISIBLE
                        text = "${sendLeft}s"
                        setTextColor(ContextCompat.getColor(context, R.color.espresso))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    }
                }
            }
            root.findViewById<BezelStageArcView>(R.id.bubbleStageArc)?.let { arc ->
                arc.setArcColor(0xFF7A1F1F.toInt())   // bronze bezel's arc ink (BubbleStageArc)
                if (stage < 0) arc.reset() else arc.setActiveStage(stage)
            }
        }
    )
}

/** Gold at rest: the default bronze dish + knob, IDLE in espresso, no timer. */
private fun paintIdle(root: View) {
    root.findViewById<FrameLayout>(R.id.bubbleBezel)
        ?.setBackgroundResource(BubbleAppearance.bezelDrawableFor("bronze"))
    root.findViewById<FrameLayout>(R.id.bubbleInner)?.apply {
        backgroundTintList = null
        setBackgroundResource(BubbleAppearance.knobDrawableFor("bronze"))
    }
    root.findViewById<TextView>(R.id.bubbleLabel)?.apply {
        text = "IDLE"
        setTextColor(BubbleAppearance.idleLabelColorFor("bronze"))
    }
    root.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
}
