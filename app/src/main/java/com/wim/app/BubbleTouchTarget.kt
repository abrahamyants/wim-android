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

    /** The bezel window now DRAWS the bezel (window alpha 1.0 = truly solid),
     *  so it needs headroom for animations that briefly scale past the bezel:
     *  taffy drag stretch (1.15x) and the affect nudges. Taps landing in the
     *  headroom ring miss the [hitRadiusPx] test and are ignored. */
    const val ANIMATION_HEADROOM = 1.18f
    const val EDGE_PAD_DP = 4f

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    fun diameterPx(density: Float, sizePercent: Int): Int {
        val scale = sizePercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT) / 100f
        return (BASE_DIAMETER_DP * density * scale).toInt().coerceAtLeast(1)
    }

    fun windowSizePx(density: Float, sizePercent: Int): Int =
        (diameterPx(density, sizePercent) * ANIMATION_HEADROOM + EDGE_PAD_DP * density)
            .toInt().coerceAtLeast(1)

    /** Taps farther than this from the window center are not on the visible
     *  bezel — ignore them so the headroom ring never acts like a button. */
    fun hitRadiusPx(density: Float, sizePercent: Int): Float =
        diameterPx(density, sizePercent) / 2f

    fun bounds(
        visualX: Int,
        visualY: Int,
        visualWidthPx: Int,
        visualHeightPx: Int,
        density: Float,
        sizePercent: Int,
    ): Bounds {
        val side = windowSizePx(density, sizePercent)
        return Bounds(
            x = visualX + (visualWidthPx - side) / 2,
            y = visualY + (visualHeightPx - side) / 2,
            width = side,
            height = side,
        )
    }
}
