package com.wim.app

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * One visual contract for the bubble's four live states:
 * RED recording -> AMBER processing -> GREEN send-ready -> INDIGO redo-ready.
 *
 * Production and the built-in demo both use this renderer so their colors
 * cannot silently drift apart again.
 */
object BubbleLiveStateVisuals {
    enum class State {
        RECORDING,
        PROCESSING,
        SEND_READY,
        REDO_READY,
    }

    data class Spec(
        val label: String,
        @DrawableRes val innerDrawable: Int,
        @ColorRes val labelColor: Int,
    )

    fun specFor(state: State): Spec = when (state) {
        State.RECORDING -> Spec(
            label = "REC",
            innerDrawable = R.drawable.bubble_inner_active,
            labelColor = R.color.red_hot,
        )
        State.PROCESSING -> Spec(
            label = "PROC",
            innerDrawable = R.drawable.bubble_inner_processing,
            labelColor = R.color.orange_amber,
        )
        State.SEND_READY -> Spec(
            label = "SEND",
            innerDrawable = R.drawable.bubble_inner_send,
            labelColor = R.color.green_bright,
        )
        // Reached when the SEND window lapses with a re-rollable reconstruction
        // still held. The text is sitting unsent in the user's field, which is
        // exactly when a re-roll is worth offering — and the only window where
        // it is, since a sent message cannot be taken back.
        State.REDO_READY -> Spec(
            label = "REDO",
            innerDrawable = R.drawable.bubble_inner_redo,
            labelColor = R.color.indigo_bright,
        )
    }

    fun apply(root: View?, state: State) {
        root ?: return
        val spec = specFor(state)
        root.findViewById<FrameLayout>(R.id.bubbleInner)?.apply {
            // Remove any tint left by older builds or demo runs before applying
            // the state-specific drawable.
            backgroundTintList = null
            setBackgroundResource(spec.innerDrawable)
        }
        root.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = spec.label
            setTextColor(ContextCompat.getColor(context, spec.labelColor))
        }
    }
}
