package com.wim.app.onboarding

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.wim.app.BezelStageArcView
import com.wim.app.BubbleAppearance
import com.wim.app.BubbleLiveStateVisuals
import com.wim.app.R

/**
 * THE bubble, in the onboarding. Not a drawing of it (George 10/12: "the
 * bubble design through all the rest of the onboarding slides are incorrect"):
 * this is the live bubble's own layout, bubble_bezel.xml, inflated inside
 * Compose and painted by the same [BubbleLiveStateVisuals.apply] the service
 * calls, with the same [BezelStageArcView] lighting the same four arcs. The
 * default bronze dish + knob, IDLE in espresso. Colors cannot drift from
 * production because they are production.
 *
 * One size everywhere: the 72 dp bezel at [REAL_BUBBLE_SCALE], the size the
 * Watch It Work page settled on.
 */
enum class RealBubbleState { IDLE, RECORDING, PROCESSING, SEND_READY }

val REAL_BUBBLE_BOX = 72.dp
const val REAL_BUBBLE_SCALE = 0.9f

@Composable
fun RealBubble(
    state: RealBubbleState = RealBubbleState.IDLE,
    modifier: Modifier = Modifier,
    scale: Float = REAL_BUBBLE_SCALE,
    /** Extra multiplier on top of [scale] — the recording breathe. */
    breathe: Float = 1f,
    /** Lit stage arcs 0..3 while processing; -1 = none. */
    stage: Int = -1,
    /** Seconds left on the green bubble; 0 = no timer shown. */
    sendLeft: Long = 0L,
) {
    AndroidView(
        modifier = modifier
            .size(REAL_BUBBLE_BOX)
            .graphicsLayer {
                scaleX = scale * breathe
                scaleY = scale * breathe
            },
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.bubble_bezel, null, false).also { paintIdle(it) }
        },
        update = { root ->
            when (state) {
                RealBubbleState.IDLE -> paintIdle(root)
                RealBubbleState.RECORDING -> {
                    BubbleLiveStateVisuals.apply(root, BubbleLiveStateVisuals.State.RECORDING)
                    root.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
                }
                RealBubbleState.PROCESSING -> {
                    BubbleLiveStateVisuals.apply(root, BubbleLiveStateVisuals.State.PROCESSING)
                    root.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
                }
                RealBubbleState.SEND_READY -> {
                    BubbleLiveStateVisuals.apply(root, BubbleLiveStateVisuals.State.SEND_READY)
                    root.findViewById<TextView>(R.id.bubbleTime)?.apply {
                        if (sendLeft > 0L) {
                            visibility = View.VISIBLE
                            text = "${sendLeft}s"
                            setTextColor(ContextCompat.getColor(context, R.color.espresso))
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        } else {
                            visibility = View.GONE
                        }
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
