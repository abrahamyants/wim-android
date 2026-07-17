package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleTouchTargetTest {
    @Test
    fun touchRadiusShrinksAndGrowsWithBubbleSize() {
        val small = BubbleTouchTarget.circle(540, 540, density = 3f, sizePercent = 70)
        val normal = BubbleTouchTarget.circle(540, 540, density = 3f, sizePercent = 100)
        val large = BubbleTouchTarget.circle(540, 540, density = 3f, sizePercent = 150)

        assertEquals(37.8f, small.radius, 0.001f)
        assertEquals(54f, normal.radius, 0.001f)
        assertEquals(81f, large.radius, 0.001f)
        assertTrue(small.radius < normal.radius)
        assertTrue(normal.radius < large.radius)
    }

    @Test
    fun touchTargetIsCenteredAndCircular() {
        val target = BubbleTouchTarget.circle(540, 480, density = 3f, sizePercent = 100)

        assertEquals(270f, target.centerX, 0.001f)
        assertEquals(240f, target.centerY, 0.001f)
        assertTrue(target.contains(270f, 240f))
        assertTrue(target.contains(324f, 240f))
        assertFalse(target.contains(324.1f, 240f))
        assertFalse(target.contains(0f, 0f))
    }

    @Test
    fun sizePreferenceUsesTheSameBoundsAsTheBubbleUi() {
        val belowMinimum = BubbleTouchTarget.circle(540, 540, 3f, 1)
        val minimum = BubbleTouchTarget.circle(540, 540, 3f, 70)
        val aboveMaximum = BubbleTouchTarget.circle(540, 540, 3f, 999)
        val maximum = BubbleTouchTarget.circle(540, 540, 3f, 150)

        assertEquals(minimum.radius, belowMinimum.radius, 0.001f)
        assertEquals(maximum.radius, aboveMaximum.radius, 0.001f)
    }
}
