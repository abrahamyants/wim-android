package com.wim.app

/**
 * Pure geometry for the floating bubble's accepted touch area.
 *
 * The overlay window deliberately stays large enough for max-size animations,
 * but only this centered circle should be touchable. Keeping the math here lets
 * BubbleService use one source of truth for both Android's window-level
 * touchable region and its gesture hit test.
 */
object BubbleTouchTarget {
    const val BASE_DIAMETER_DP = 36f
    const val MIN_SIZE_PERCENT = 70
    const val MAX_SIZE_PERCENT = 150

    data class Circle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
    ) {
        fun contains(x: Float, y: Float): Boolean {
            val dx = x - centerX
            val dy = y - centerY
            return dx * dx + dy * dy <= radius * radius
        }
    }

    fun circle(
        viewWidthPx: Int,
        viewHeightPx: Int,
        density: Float,
        sizePercent: Int,
    ): Circle {
        val scale = sizePercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT) / 100f
        val radiusPx = BASE_DIAMETER_DP * density * scale / 2f
        return Circle(
            centerX = viewWidthPx / 2f,
            centerY = viewHeightPx / 2f,
            radius = radiusPx,
        )
    }
}
