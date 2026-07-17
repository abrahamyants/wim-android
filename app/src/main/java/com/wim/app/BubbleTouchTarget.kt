package com.wim.app

/**
 * Pure geometry for the floating bubble's dedicated touch window.
 *
 * The visual overlay deliberately stays large enough for max-size animations.
 * A second transparent window matches the visible 72dp bezel and scales with
 * the user's size preference, so the surrounding visual window cannot eat
 * touches intended for the app underneath.
 */
object BubbleTouchTarget {
    const val BASE_DIAMETER_DP = 72f
    const val MIN_SIZE_PERCENT = 70
    const val MAX_SIZE_PERCENT = 150

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    fun diameterPx(density: Float, sizePercent: Int): Int {
        val scale = sizePercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT) / 100f
        return (BASE_DIAMETER_DP * density * scale).toInt().coerceAtLeast(1)
    }

    fun bounds(
        visualX: Int,
        visualY: Int,
        visualWidthPx: Int,
        visualHeightPx: Int,
        density: Float,
        sizePercent: Int,
    ): Bounds {
        val diameter = diameterPx(density, sizePercent)
        return Bounds(
            x = visualX + (visualWidthPx - diameter) / 2,
            y = visualY + (visualHeightPx - diameter) / 2,
            width = diameter,
            height = diameter,
        )
    }
}
