package com.wim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View

/**
 * Two parallax-responsive amber orbs sitting behind the app content.
 *
 * Upper-right: bright champagne-gold. Lower-left: deeper warm bronze. Both are
 * soft radial gradients that bleed into transparency at the edges, giving a
 * bokeh feel — like two distant warm lights floating in a dark room.
 *
 * When the phone tilts the orbs translate INVERSELY to gravity — so the
 * effect reads as if the orbs live in a fixed 3D space and the phone is the
 * window moving around them. Same trick iOS uses for wallpaper parallax.
 *
 * Accelerometer values go through a low-pass filter so the motion feels
 * floaty, not jittery. Sensor is registered only while attached + visible to
 * avoid draining battery when the app is backgrounded.
 */
class AmbientOrbsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private data class Orb(
        val fx: Float,           // center x as fraction of view width
        val fy: Float,           // center y as fraction of view height
        val coreColor: Int,      // center color (solid)
        val radiusFrac: Float    // radius as fraction of min(w, h)
    )

    // Three orbs positioned around the canvas:
    //   upper-right = deep teal  (complements — but does NOT repeat — the gold
    //                             dials rendered by the UI in that corner)
    //   center      = warm champagne-gold (anchor light, pulled in from the
    //                                      old upper-right slot)
    //   lower-left  = bronze (deep warm counterweight, unchanged from prior)
    // Corner orbs sit slightly past the canvas edge so the visible arc shifts
    // into and out of frame as you tilt; the center orb stays centered so
    // parallax wiggles it rather than slides it off.
    private val orbs = listOf(
        Orb(fx = 0.92f, fy = 0.08f, coreColor = 0xFF4A9898.toInt(), radiusFrac = 0.45f),
        Orb(fx = 0.50f, fy = 0.50f, coreColor = 0xFFF2CF7A.toInt(), radiusFrac = 0.45f),
        Orb(fx = 0.08f, fy = 0.92f, coreColor = 0xFFD98A4A.toInt(), radiusFrac = 0.60f)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // RadialGradient cache. Without this, onDraw built three brand-new
    // RadialGradient objects per frame (at sensor rate on the home screen)
    // — pure GC churn for shaders whose only changing input was screen
    // position. Build the gradient ONCE at origin per orb, then translate
    // via the shader's local matrix each frame; only rebuild when the
    // view resizes (radius changes). Audit finding 2026-06-06.
    private val orbShaders = arrayOfNulls<android.graphics.RadialGradient>(3)
    private val cachedRadiusPx = IntArray(3) { -1 }
    private val shaderMatrix = android.graphics.Matrix()

    // Raw sensor readings (accelerometer x, y in m/s^2)
    private var rawTiltX = 0f
    private var rawTiltY = 0f
    // Low-pass-filtered values (what onDraw reads)
    private var smoothedTiltX = 0f
    private var smoothedTiltY = 0f

    // How much the orbs translate per unit of tilt. Max parallax offset is
    // capped at maxOffsetFrac of view width so they don't slide off-screen.
    // Tuned for visible-but-tasteful motion — iOS wallpaper parallax is in this
    // same ballpark.
    private val maxOffsetFrac = 0.18f
    // Low-pass smoothing coefficient — higher = more responsive, lower = more floaty.
    // 0.22 gives noticeable snap while still feeling dampened, not jittery.
    private val smoothingAlpha = 0.22f
    // Divisor scaling raw accelerometer readings (m/s²) into a roughly -1..1
    // range the offset math expects. Reduced from 9.8 → 5.0 so gentle,
    // everyday hand-movement tilts (2-4 m/s²) move the orbs a meaningful amount.
    private val tiltNormalization = 5.0f

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private var accelerometer: Sensor? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager.unregisterListener(this)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Pause sensor when backgrounded to save battery.
        if (visibility != VISIBLE) {
            sensorManager.unregisterListener(this)
        } else {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        rawTiltX = event.values[0]
        rawTiltY = event.values[1]
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        // Low-pass filter — smooth the raw accelerometer toward zero jitter.
        smoothedTiltX += (rawTiltX - smoothedTiltX) * smoothingAlpha
        smoothedTiltY += (rawTiltY - smoothedTiltY) * smoothingAlpha

        val maxOffsetPx = width * maxOffsetFrac
        // Invert X so that tilting the phone right (positive x-accel when
        // landscape-right lifts) makes the orbs appear to slide left — they
        // stay put in world space while the phone rotates around them.
        val offsetX = -(smoothedTiltX / tiltNormalization) * maxOffsetPx
        val offsetY = (smoothedTiltY / tiltNormalization) * maxOffsetPx

        val minDim = minOf(width, height).toFloat()

        for ((i, orb) in orbs.withIndex()) {
            val cx = width * orb.fx + offsetX
            val cy = height * orb.fy + offsetY
            val radius = minDim * orb.radiusFrac
            val radiusRounded = radius.toInt()

            // Cached gradient: built once at origin, translated each frame.
            // Only rebuilt when the view resizes (radius changes). Saves
            // ~300 RadialGradient allocations/sec at sensor rate.
            var shader = orbShaders[i]
            if (shader == null || cachedRadiusPx[i] != radiusRounded) {
                // RadialGradient: core color at 30% alpha, fading to transparent.
                // 30% core keeps the orb visible on a dark base but won't blow
                // out the content layered above it.
                val coreWithAlpha = (orb.coreColor and 0x00FFFFFF) or 0x4C000000
                shader = RadialGradient(
                    0f, 0f, radius,
                    intArrayOf(coreWithAlpha, orb.coreColor and 0x00FFFFFF),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                orbShaders[i] = shader
                cachedRadiusPx[i] = radiusRounded
            }
            shaderMatrix.setTranslate(cx, cy)
            shader.setLocalMatrix(shaderMatrix)
            paint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
