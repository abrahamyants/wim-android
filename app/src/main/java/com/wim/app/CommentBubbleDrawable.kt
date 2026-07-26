package com.wim.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DiscretePathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

/**
 * Hand-drawn comic-book speech bubble: rounded rect with a downward-pointing
 * tail at the bottom-center. Outline uses two wobbly strokes (DiscretePathEffect
 * with different segment seeds) for the cartoonist double-pencil look.
 * Used as the floating bubble menu's background.
 *
 * Background fill can be a flat color (default) or a textured Shader matching
 * the active theme: bronze gradient, brushed silver gradient, or carbon-fiber
 * weave for the gunmetal/gold theme.
 */
class CommentBubbleDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val displayMetrics: DisplayMetrics,
    private val backgroundStyle: BackgroundStyle = BackgroundStyle.FLAT,
    cornerRadiusDp: Float = 18f,
    tailWidthDp: Float = 44f,
    tailHeightDp: Float = 22f,
    strokeWidthDp: Float = 2.2f,
    /**
     * Draw the dual wobble outline (ghost + main stroke). Default OFF —
     * the bubble menu wants borderless. The live transcript bar enables
     * this for the hand-drawn "two lines" look.
     */
    private val drawOutline: Boolean = false,
) : Drawable() {

    enum class BackgroundStyle { FLAT, BRONZE, SILVER, CARBON_FIBER }

    private val density = displayMetrics.density
    private val cornerRadiusPx = cornerRadiusDp * density
    private val tailWidthPx = tailWidthDp * density
    val tailHeightPx = tailHeightDp * density
    private val strokeWidthPx = strokeWidthDp * density

    // Two wobble effects with different segment lengths produce different random
    // patterns, simulating two passes of an unsteady pencil. Longer segments +
    // smaller deviation give a subtle hand-drawn feel rather than torn-paper
    // ragged edges.
    private val mainWobble: PathEffect = DiscretePathEffect(9f * density, 0.4f * density)
    private val ghostWobble: PathEffect = DiscretePathEffect(13f * density, 0.6f * density)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        pathEffect = mainWobble
    }
    // Thinner secondary stroke at reduced opacity, different wobble seed —
    // bounces alongside the main outline for the hand-drawn double-line feel.
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx * 0.45f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        alpha = 70
        pathEffect = ghostWobble
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }
    private val path = Path()

    // Shader cache — rebuild only when bounds change. Saves rebuilding the
    // carbon fiber bitmap on every draw call.
    private var cachedShaderKey: Int = 0
    private var cachedShader: Shader? = null
    private var cachedFiberTile: Bitmap? = null

    private fun shaderForBounds(w: Int, h: Int): Shader? {
        // Per George 2026-04-30: menu background is now solid color matching
        // the bubble bezel — no hex/weave pattern overlay. The fillColor
        // passed via constructor (palette.fill) is the per-theme color.
        return null
    }

    // Honeycomb hex tile — pointy-top hexes in offset rows. Background fill +
    // colored stroke per call so the same generator serves bronze and silver.
    // The hexes get a 1px inner highlight so each cell reads as raised metal.
    private fun hexShader(bgColor: Int, hexStroke: Int, hexHighlight: Int): Shader {
        val r = 7f * density
        val hexW = (Math.sqrt(3.0) * r).toFloat()
        val tileW = hexW.toInt().coerceAtLeast(18)
        val tileH = (3f * r).toInt().coerceAtLeast(28)

        val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        c.drawRect(0f, 0f, tileW.toFloat(), tileH.toFloat(), bg)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hexStroke
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
            strokeJoin = Paint.Join.ROUND
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hexHighlight
            style = Paint.Style.STROKE
            strokeWidth = 0.5f * density
        }

        val hexPath = Path()
        // Draw hexes in a 3x3 neighborhood around tile so partial cells fill
        // edges naturally and REPEAT tiling looks seamless.
        for (row in -1..2) {
            for (col in -1..1) {
                val cx = col * hexW + if (Math.abs(row) % 2 == 1) hexW / 2f else 0f
                val cy = row * 1.5f * r
                hexPath.rewind()
                for (i in 0..5) {
                    val angle = (Math.PI / 3.0 * i - Math.PI / 6.0).toFloat()
                    val px = cx + r * Math.cos(angle.toDouble()).toFloat()
                    val py = cy + r * Math.sin(angle.toDouble()).toFloat()
                    if (i == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
                }
                hexPath.close()
                c.drawPath(hexPath, strokePaint)
                // Inner highlight — shrunk hex 1px inside, gives the cell depth
                hexPath.rewind()
                val rIn = r - 1.4f * density
                for (i in 0..5) {
                    val angle = (Math.PI / 3.0 * i - Math.PI / 6.0).toFloat()
                    val px = cx + rIn * Math.cos(angle.toDouble()).toFloat()
                    val py = cy + rIn * Math.sin(angle.toDouble()).toFloat()
                    if (i == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
                }
                hexPath.close()
                c.drawPath(hexPath, highlightPaint)
            }
        }
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // Carbon fiber rectangular brick weave — matches the reference's stacked
    // rectangle pattern (vertical bars in offset rows) rather than diagonal twill.
    private fun carbonWeaveShader(): Shader {
        val tile = cachedFiberTile ?: generateCarbonWeaveTile().also { cachedFiberTile = it }
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun generateCarbonWeaveTile(): Bitmap {
        // Brick width ~10dp, height ~6dp. Two rows offset by half-brick → tile
        // = 1 brick wide × 2 bricks tall.
        val brickW = (10 * density).toInt().coerceAtLeast(8)
        val brickH = (6 * density).toInt().coerceAtLeast(5)
        val tileW = brickW
        val tileH = brickH * 2
        val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Base near-black wash
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0E0E12.toInt()
            style = Paint.Style.FILL
        }
        c.drawRect(0f, 0f, tileW.toFloat(), tileH.toFloat(), p)

        // Brick fill — slightly lighter dark grey
        p.color = 0xFF1F1F26.toInt()
        // Top row brick (full width)
        val gap = 0.8f * density
        c.drawRect(gap, gap, tileW - gap, brickH - gap, p)
        // Bottom row brick — offset, drawn as two halves (left + right) due to wrap
        c.drawRect(gap - brickW / 2f, brickH + gap, brickW / 2f - gap, tileH - gap, p)
        c.drawRect(brickW / 2f + gap, brickH + gap, tileW + brickW / 2f - gap, tileH - gap, p)

        // Top highlight per brick
        p.color = 0x44FFFFFF.toInt()
        c.drawRect(gap, gap, tileW - gap, gap + 0.6f * density, p)
        c.drawRect(gap - brickW / 2f, brickH + gap, brickW / 2f - gap, brickH + gap + 0.6f * density, p)
        c.drawRect(brickW / 2f + gap, brickH + gap, tileW + brickW / 2f - gap, brickH + gap + 0.6f * density, p)

        // Bottom shadow per brick — depth
        p.color = 0x44000000.toInt()
        c.drawRect(gap, brickH - gap - 0.6f * density, tileW - gap, brickH - gap, p)
        c.drawRect(gap - brickW / 2f, tileH - gap - 0.6f * density, brickW / 2f - gap, tileH - gap, p)
        c.drawRect(brickW / 2f + gap, tileH - gap - 0.6f * density, tileW + brickW / 2f - gap, tileH - gap, p)

        return bmp
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // Lazy: build shader only after bounds are known.
        val shader = shaderForBounds(b.width(), b.height())
        if (fillPaint.shader !== shader) {
            fillPaint.shader = shader
            // When using a shader, color is ignored but alpha still applies.
            if (shader == null) fillPaint.color = fillColor
        }

        val inset = strokeWidthPx
        val tipY = h - inset
        val rectBottom = tipY - tailHeightPx
        val rectTop = inset
        val rectLeft = inset
        val rectRight = w - inset
        val cx = w / 2f
        val halfTail = tailWidthPx / 2f

        path.rewind()
        path.moveTo(rectLeft + cornerRadiusPx, rectTop)
        path.lineTo(rectRight - cornerRadiusPx, rectTop)
        path.quadTo(rectRight, rectTop, rectRight, rectTop + cornerRadiusPx)
        path.lineTo(rectRight, rectBottom - cornerRadiusPx)
        path.quadTo(rectRight, rectBottom, rectRight - cornerRadiusPx, rectBottom)
        path.lineTo(cx + halfTail, rectBottom)
        path.cubicTo(
            cx + halfTail * 0.55f, rectBottom + tailHeightPx * 0.35f,
            cx + halfTail * 0.18f, tipY - tailHeightPx * 0.05f,
            cx, tipY
        )
        path.cubicTo(
            cx - halfTail * 0.18f, tipY - tailHeightPx * 0.05f,
            cx - halfTail * 0.55f, rectBottom + tailHeightPx * 0.35f,
            cx - halfTail, rectBottom
        )
        path.lineTo(rectLeft + cornerRadiusPx, rectBottom)
        path.quadTo(rectLeft, rectBottom, rectLeft, rectBottom - cornerRadiusPx)
        path.lineTo(rectLeft, rectTop + cornerRadiusPx)
        path.quadTo(rectLeft, rectTop, rectLeft + cornerRadiusPx, rectTop)
        path.close()

        canvas.save()
        canvas.translate(0f, 3f * density)
        canvas.drawPath(path, shadowPaint)
        canvas.restore()

        canvas.drawPath(path, fillPaint)
        if (drawOutline) {
            canvas.drawPath(path, ghostPaint)
            canvas.drawPath(path, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        ghostPaint.alpha = (70 * alpha / 255)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        ghostPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Per-bubble-theme palette for the menu's comment bubble background.
         * Each entry: fill color, stroke color, primary text color (slider values,
         * active labels), secondary text color (section labels), accent color
         * (active layer / Settings). Picked to match the floating bubble's
         * own bezel palette (bubble_bg_idle / bubble_bezel_aluminum /
         * bubble_bezel_gunmetal).
         */
        data class Palette(
            val fill: Int,
            val stroke: Int,
            val primaryText: Int,
            val secondaryText: Int,
            val accent: Int,
            val sliderTrack: Int,
        )

        // Menu background fill is FULLY OPAQUE (0xFF). It was 0x80, then 0xC0 —
        // a hardcoded translucency the bubble's opacity slider never reached,
        // because the slider sets alpha on the bezel VIEW and this is a
        // drawable. At OP 100 the operator could still read the app through
        // the menu and no setting did anything about it. The slider now drives
        // this drawable's alpha (see BubbleMenu.applyMenuOpacity), so 100 is
        // genuinely solid and anything less is see-through on purpose. Baking
        // the base at 0xFF means that holds even if the runtime call is ever
        // missed on some path.
        // background only, the floating bubble itself stays opaque (user controls
        // bubble opacity via the OP slider).
        val BRONZE = Palette(
            fill = 0xFFC4A574.toInt(),       // mid-warm copper, opaque
            stroke = 0xFFB55050.toInt(),     // maroon (matches bubble bezel stroke)
            primaryText = 0xFF1F1208.toInt(),
            secondaryText = 0xFF4A2F18.toInt(),
            accent = 0xFF8B0000.toInt(),     // dark red on bronze
            sliderTrack = 0x335C3A1F,
        )

        val ALUMINUM = Palette(
            fill = 0xFFC8C8CE.toInt(),       // brushed silver, opaque
            stroke = 0xFFB55050.toInt(),
            primaryText = 0xFF1A1A20.toInt(),
            secondaryText = 0xFF45454F.toInt(),
            accent = 0xFFB55050.toInt(),     // maroon (matches stroke)
            sliderTrack = 0x33555560,
        )

        val GUNMETAL = Palette(
            fill = 0xFF2E2E36.toInt(),       // dark brushed steel, opaque
            stroke = 0xFFB55050.toInt(),
            primaryText = 0xFFF0E8D8.toInt(),
            secondaryText = 0xFFC8B48A.toInt(), // brand cream
            accent = 0xFFD4AF37.toInt(),     // gold (matches "Gold" chip label)
            sliderTrack = 0x44C8B48A,
        )

        fun paletteFor(theme: String): Palette = when (theme) {
            "aluminum" -> ALUMINUM
            "gunmetal" -> GUNMETAL
            else -> BRONZE
        }

        fun backgroundStyleFor(theme: String): BackgroundStyle = when (theme) {
            "aluminum" -> BackgroundStyle.SILVER
            "gunmetal" -> BackgroundStyle.CARBON_FIBER
            else -> BackgroundStyle.BRONZE
        }
    }
}
