package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleLiveStateVisualsTest {
    @Test
    // 2026-09-04: one espresso ink on every live word (operator's call);
    // the FACE carries the color, the label does not.
    fun recording_redFace_espressoInk() {
        val spec = BubbleLiveStateVisuals.specFor(BubbleLiveStateVisuals.State.RECORDING)

        assertEquals("REC", spec.label)
        assertEquals(R.drawable.bubble_inner_active, spec.innerDrawable)
        assertEquals(R.color.espresso, spec.labelColor)
    }

    @Test
    fun processing_amberFace_espressoInk() {
        val spec = BubbleLiveStateVisuals.specFor(BubbleLiveStateVisuals.State.PROCESSING)

        assertEquals("PROC", spec.label)
        assertEquals(R.drawable.bubble_inner_processing, spec.innerDrawable)
        assertEquals(R.color.espresso, spec.labelColor)
    }

    @Test
    fun sendReady_greenFace_espressoInk() {
        val spec = BubbleLiveStateVisuals.specFor(BubbleLiveStateVisuals.State.SEND_READY)

        assertEquals("SEND", spec.label)
        assertEquals(R.drawable.bubble_inner_send, spec.innerDrawable)
        assertEquals(R.color.espresso, spec.labelColor)
    }
}
