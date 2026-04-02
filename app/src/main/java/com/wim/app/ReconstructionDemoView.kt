package com.wim.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * 9-second animated cartoon demonstrating reconstruction.
 * Woman driving, dictates messy → phone cleans it → two different tones.
 */
class ReconstructionDemoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var startTime = 0L
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    // Paints
    private val skyPaint = Paint().apply { color = 0xFF2D1B4E.toInt() } // night sky
    private val roadPaint = Paint().apply { color = 0xFF333340.toInt() }
    private val roadLinePaint = Paint().apply { color = 0xFFCCCC44.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val carPaint = Paint().apply { color = 0xFF4A4A5A.toInt() }
    private val windowPaint = Paint().apply { color = 0xFF6688AA.toInt() }
    private val skinPaint = Paint().apply { color = 0xFFDEB887.toInt() }
    private val hairPaint = Paint().apply { color = 0xFF3A2A1A.toInt() }
    private val phonePaint = Paint().apply { color = 0xFF1A1A2E.toInt() }
    private val phoneScreenPaint = Paint().apply { color = 0xFF2A2A3E.toInt() }
    private val bubblePaint = Paint().apply { color = 0xFFEE5A24.toInt() }
    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 28f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
    }
    private val messyPaint = Paint().apply {
        color = 0xFFFF6666.toInt(); textSize = 22f; typeface = Typeface.MONOSPACE; isAntiAlias = true
    }
    private val cleanPaint = Paint().apply {
        color = 0xFF66FF66.toInt(); textSize = 22f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply {
        color = 0x88FFFFFF.toInt(); textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val starPaint = Paint().apply { color = 0xFFFFFFDD.toInt(); style = Paint.Style.FILL; isAntiAlias = true }

    // Stars positions (random-ish)
    private val stars = listOf(
        Pair(0.1f, 0.05f), Pair(0.3f, 0.08f), Pair(0.5f, 0.03f), Pair(0.7f, 0.07f), Pair(0.9f, 0.04f),
        Pair(0.15f, 0.12f), Pair(0.45f, 0.1f), Pair(0.65f, 0.14f), Pair(0.85f, 0.11f), Pair(0.25f, 0.02f)
    )

    fun startAnimation() {
        startTime = System.currentTimeMillis()
        running = true
        invalidate()
    }

    fun stopAnimation() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val elapsed = if (running) (System.currentTimeMillis() - startTime) else 0L
        val sec = elapsed / 1000f

        // Background: night sky
        canvas.drawRect(0f, 0f, w, h * 0.55f, skyPaint)

        // Stars (twinkling)
        for ((sx, sy) in stars) {
            val twinkle = (Math.sin((sec * 3 + sx * 10).toDouble()) * 0.5 + 0.5).toFloat()
            starPaint.alpha = (twinkle * 255).toInt().coerceIn(50, 255)
            canvas.drawCircle(sx * w, sy * h, 2f + twinkle, starPaint)
        }

        // Road
        canvas.drawRect(0f, h * 0.55f, w, h, roadPaint)

        // Road lines (moving)
        val lineOffset = (sec * 100) % 80
        roadLinePaint.pathEffect = DashPathEffect(floatArrayOf(30f, 50f), lineOffset)
        canvas.drawLine(0f, h * 0.72f, w, h * 0.72f, roadLinePaint)

        // Car body (from behind, centered)
        val carL = w * 0.15f
        val carR = w * 0.85f
        val carTop = h * 0.3f
        val carBot = h * 0.85f
        val carRect = RectF(carL, carTop, carR, carBot)
        carPaint.color = 0xFF4A4A5A.toInt()
        canvas.drawRoundRect(carRect, 30f, 30f, carPaint)

        // Roof
        val roofRect = RectF(w * 0.22f, h * 0.2f, w * 0.78f, h * 0.42f)
        canvas.drawRoundRect(roofRect, 20f, 20f, carPaint)

        // Rear window
        val winRect = RectF(w * 0.25f, h * 0.22f, w * 0.75f, h * 0.38f)
        canvas.drawRoundRect(winRect, 15f, 15f, windowPaint)

        // Tail lights
        val tlPaint = Paint().apply { color = 0xFFFF2222.toInt() }
        canvas.drawRoundRect(RectF(carL + 10, h * 0.7f, carL + 40, h * 0.75f), 5f, 5f, tlPaint)
        canvas.drawRoundRect(RectF(carR - 40, h * 0.7f, carR - 10, h * 0.75f), 5f, 5f, tlPaint)

        // Woman silhouette in rear window (head + hair)
        val headX = w * 0.55f
        val headY = h * 0.28f
        canvas.drawCircle(headX, headY, 18f, skinPaint)
        // Hair
        canvas.drawArc(RectF(headX - 20, headY - 22, headX + 14, headY + 5), 180f, 180f, true, hairPaint)
        canvas.drawRect(headX - 18, headY - 5, headX - 10, headY + 15, hairPaint)

        // Phone mounted on dash (visible through window)
        val phoneX = w * 0.4f
        val phoneY = h * 0.28f
        val phoneW = 28f
        val phoneH = 42f
        canvas.drawRoundRect(RectF(phoneX - phoneW/2, phoneY - phoneH/2, phoneX + phoneW/2, phoneY + phoneH/2), 5f, 5f, phonePaint)
        canvas.drawRoundRect(RectF(phoneX - phoneW/2 + 3, phoneY - phoneH/2 + 3, phoneX + phoneW/2 - 3, phoneY + phoneH/2 - 6), 3f, 3f, phoneScreenPaint)

        // WiM bubble on phone (pulsing)
        if (sec > 0.5f) {
            val pulseScale = 1f + 0.15f * Math.sin((sec * 4).toDouble()).toFloat()
            val bubbleR = 5f * pulseScale
            bubblePaint.alpha = 255
            canvas.drawCircle(phoneX, phoneY - 5, bubbleR, bubblePaint)
        }

        // ── Text overlay area (below car) ──
        val textY = h * 0.88f

        if (sec < 3f) {
            // Phase 1: Dictating to boss — messy text appears
            val alpha = ((sec / 0.5f).coerceIn(0f, 1f) * 255).toInt()
            messyPaint.alpha = alpha
            val messy = "so um tell him the the\nmeeting is is moved to uh Thursday"
            val lines = messy.split("\n")
            lines.forEachIndexed { i, line ->
                val charCount = ((sec * 15).toInt()).coerceAtMost(line.length)
                val visible = line.substring(0, charCount)
                messyPaint.textSize = 18f
                canvas.drawText(visible, w * 0.05f, textY + i * 24f, messyPaint)
            }

            // Label
            if (sec > 0.3f) {
                labelPaint.alpha = ((sec / 1f).coerceIn(0f, 1f) * 255).toInt()
                canvas.drawText("🎙 Dictating to boss...", w / 2, h * 0.5f, labelPaint)
            }

        } else if (sec < 5.5f) {
            // Phase 2: Sparkle transform — messy becomes clean
            val progress = ((sec - 3f) / 1.5f).coerceIn(0f, 1f)

            if (progress < 0.5f) {
                // Messy fading out
                messyPaint.alpha = ((1f - progress * 2) * 255).toInt()
                messyPaint.textSize = 18f
                canvas.drawText("so um tell him the the", w * 0.05f, textY, messyPaint)
                canvas.drawText("meeting is is moved to uh Thursday", w * 0.05f, textY + 24f, messyPaint)
            }

            if (progress > 0.3f) {
                // Clean fading in
                val cleanAlpha = (((progress - 0.3f) / 0.7f) * 255).toInt()
                cleanPaint.alpha = cleanAlpha
                cleanPaint.textSize = 20f
                canvas.drawText("\"The meeting has been moved", w * 0.05f, textY, cleanPaint)
                canvas.drawText("  to Thursday.\"", w * 0.05f, textY + 26f, cleanPaint)
            }

            // Sparkle particles
            if (progress > 0.1f && progress < 0.8f) {
                val sparkPaint = Paint().apply { color = 0xFFFFDD44.toInt(); isAntiAlias = true }
                for (i in 0..5) {
                    val sx = w * 0.1f + (w * 0.8f * ((i * 0.17f + progress * 2) % 1f))
                    val sy = textY - 10 + (20 * Math.sin((sec * 5 + i).toDouble())).toFloat()
                    sparkPaint.alpha = ((1f - progress) * 200).toInt()
                    canvas.drawCircle(sx, sy, 3f, sparkPaint)
                }
            }

            // Label
            labelPaint.alpha = 255
            val badge = Paint().apply { color = 0xFF2E7D32.toInt(); isAntiAlias = true }
            canvas.drawRoundRect(RectF(w/2 - 80, h * 0.48f, w/2 + 80, h * 0.54f), 10f, 10f, badge)
            dimPaint.color = 0xFFFFFFFF.toInt()
            dimPaint.textSize = 16f
            canvas.drawText("✨ PROFESSIONAL", w / 2, h * 0.53f, dimPaint)

        } else if (sec < 9f) {
            // Phase 3: Now texting friend — same speech, casual output
            val phase3Sec = sec - 5.5f

            if (phase3Sec < 2f) {
                val alpha = ((phase3Sec / 0.5f).coerceIn(0f, 1f) * 255).toInt()
                messyPaint.alpha = alpha
                messyPaint.textSize = 18f
                val charCount = ((phase3Sec * 20).toInt()).coerceAtMost(40)
                val messy2 = "lol the meeting got pushed"
                val visible = messy2.substring(0, charCount.coerceAtMost(messy2.length))
                canvas.drawText(visible, w * 0.05f, textY, messyPaint)

                labelPaint.alpha = alpha
                canvas.drawText("🎙 Now texting a friend...", w / 2, h * 0.5f, labelPaint)
            }

            if (phase3Sec > 1.5f) {
                val cleanAlpha = (((phase3Sec - 1.5f) / 1f).coerceIn(0f, 1f) * 255).toInt()
                cleanPaint.alpha = cleanAlpha
                cleanPaint.textSize = 20f
                canvas.drawText("\"lol meeting got pushed to", w * 0.05f, textY, cleanPaint)
                canvas.drawText("  Thursday, classic 😂\"", w * 0.05f, textY + 26f, cleanPaint)

                val badge = Paint().apply { color = 0xFF1565C0.toInt(); isAntiAlias = true }
                canvas.drawRoundRect(RectF(w/2 - 60, h * 0.48f, w/2 + 60, h * 0.54f), 10f, 10f, badge)
                dimPaint.color = 0xFFFFFFFF.toInt()
                dimPaint.textSize = 16f
                dimPaint.alpha = cleanAlpha
                canvas.drawText("😎 CASUAL", w / 2, h * 0.53f, dimPaint)
            }
        }

        // Final tagline
        if (sec > 8f) {
            val alpha = (((sec - 8f) / 0.5f).coerceIn(0f, 1f) * 255).toInt()
            labelPaint.alpha = alpha
            labelPaint.textSize = 22f
            canvas.drawText("Same voice. Right tone. Every time.", w / 2, h * 0.96f, labelPaint)
        }

        // Loop or continue
        if (running && sec < 10f) {
            handler.postDelayed({ invalidate() }, 16) // ~60fps
        } else if (running && sec >= 10f) {
            // Restart loop
            startTime = System.currentTimeMillis()
            invalidate()
        }
    }
}
