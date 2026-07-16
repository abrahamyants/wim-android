package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleLiveStateVisualsTest {
    @Test
    fun recording_isRed() {
        val spec = BubbleLiveStateVisuals.specFor(BubbleLiveStateVisuals.State.RECORDING)

        assertEquals("REC", spec.label)
        assertEquals(R.drawable.bubble_inner_active, spec.innerDrawable)
        assertEquals(R.color.red_hot, spec.labelColor)
    }

    @Test
    fun processing_isAmber() {
        val spec = BubbleLiveStateVisuals.specFor(BubbleLiveStateVisuals.State.PROCESSING)

        assertEquals("PROC", spec.label)
        assertEquals(R.drawable.bubble_inner_processing, spec.innerDrawable)
        assertEquals(R.color.orange_amber, spec.labelColor)
    }

    @Test
    fun sendReady_isGreen() {
        val spec = BubbleLiveStateVisuals.specFor(BubbleLiveStateVisuals.State.SEND_READY)

        assertEquals("SEND", spec.label)
        assertEquals(R.drawable.bubble_inner_send, spec.innerDrawable)
        assertEquals(R.color.green_bright, spec.labelColor)
    }
}
