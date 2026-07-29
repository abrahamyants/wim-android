package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleTouchTargetTest {
    @Test
    fun touchWindowShrinksAndGrowsWithBubbleSize() {
        val small = BubbleTouchTarget.diameterPx(density = 3f, sizePercent = 70)
        val normal = BubbleTouchTarget.diameterPx(density = 3f, sizePercent = 100)
        val large = BubbleTouchTarget.diameterPx(density = 3f, sizePercent = 150)

        assertEquals(151, small)
        assertEquals(216, normal)
        assertEquals(324, large)
        assertTrue(small < normal)
        assertTrue(normal < large)
    }

    @Test
    fun touchWindowStaysCenteredOnVisualWindow() {
        val target = BubbleTouchTarget.bounds(
            visualX = 100,
            visualY = 200,
            visualWidthPx = 540,
            visualHeightPx = 480,
            density = 3f,
            sizePercent = 100,
        )

        // Window = bezel diameter (216) * 1.18 headroom + 4dp*3 pad = 266.
        val side = BubbleTouchTarget.windowSizePx(density = 3f, sizePercent = 100)
        assertEquals(266, side)
        assertEquals(side, target.width)
        assertEquals(side, target.height)
        // Center must stay pinned to the visual window's center regardless
        // of headroom.
        assertEquals(370, target.x + target.width / 2)
        assertEquals(440, target.y + target.height / 2)
    }

    @Test
    fun hitRadiusIgnoresTheHeadroomRing() {
        // The tappable radius stays the visible bezel's radius even though
        // the window itself is larger.
        assertEquals(108f, BubbleTouchTarget.hitRadiusPx(density = 3f, sizePercent = 100))
        assertTrue(
            BubbleTouchTarget.hitRadiusPx(3f, 100) * 2 <
                BubbleTouchTarget.windowSizePx(3f, 100)
        )
    }

    @Test
    fun sizePreferenceUsesTheSameBoundsAsTheBubbleUi() {
        assertEquals(
            BubbleTouchTarget.diameterPx(3f, 70),
            BubbleTouchTarget.diameterPx(3f, 1),
        )
        assertEquals(
            BubbleTouchTarget.diameterPx(3f, 150),
            BubbleTouchTarget.diameterPx(3f, 999),
        )
    }
}
