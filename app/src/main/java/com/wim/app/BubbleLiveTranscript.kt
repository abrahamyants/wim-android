package com.wim.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Comment-bubble shaped overlay above the bubble — shared surface for live
 * recording partials and post-stop pipeline state. Single visual surface
 * for everything that overlays the bubble.
 *
 * Extracted from BubbleService 2026-05-25 as the second BubbleWindow slice
 * (after BubbleWordHighlight). Owns: the overlay view + container, the
 * WindowManager params, the typewriter + cursor-blink animations, the
 * stage-pulse cancellation, and the TranscriptBarController bind/unbind.
 *
 * Dependencies injected via constructor so this class never reaches into
 * BubbleService state:
 *  - [getBubbleParams] reads the current bubble position so the bar can
 *    anchor itself relative to the bubble at show time.
 *  - [isDemoActive] gates the L1-cloud-silent-path skip (demo script
 *    overrides the silent treatment for onboarding screenshots).
 */
class BubbleLiveTranscript(
    private val context: Context,
    private val windowManager: WindowManager,
    private val getBubbleParams: () -> WindowManager.LayoutParams?,
    private val isDemoActive: () -> Boolean
) {
    private companion object {
        private const val TAG = "WiM"
        private const val TYPEWRITER_INTERVAL_MS = 22L
        private const val CURSOR_BLINK_MS = 500L
    }

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var container: FrameLayout? = null

    // Pipeline-stage state — premium typewriter rendering. Title is static
    // (no flickering glyph, no breathing alpha — those read as "epileptic"
    // per operator). Body reveals char-by-char at 22ms/char. After body
    // finishes typing, a single ▌ cursor blinks at 500ms cycle.
    private var stageLabel: String = ""
    private var stageDetail: String = ""
    private var typewriterIdx = 0
    private var cursorVisible = true
    private val stageAnimHandler = Handler(Looper.getMainLooper())

    private val typewriterTick = object : Runnable {
        override fun run() {
            if (typewriterIdx < stageDetail.length) {
                typewriterIdx += 1
                renderStageFrame()
                stageAnimHandler.postDelayed(this, TYPEWRITER_INTERVAL_MS)
            }
        }
    }
    private val cursorBlinkTick = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            renderStageFrame()
            stageAnimHandler.postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    private fun ensureBar() {
        if (view != null) return
        val dp = context.resources.displayMetrics.density
        val barWidthPx = (220 * dp).toInt()
        val palette = CommentBubbleDrawable.paletteFor(WimApp.bubbleTheme())
        val styleFor = CommentBubbleDrawable.backgroundStyleFor(WimApp.bubbleTheme())
        val opaqueFill = (palette.fill or 0xFF000000.toInt())
        val bgDrawable = CommentBubbleDrawable(
            fillColor = opaqueFill,
            strokeColor = palette.stroke,
            displayMetrics = context.resources.displayMetrics,
            backgroundStyle = styleFor,
            drawOutline = false
        )
        val tv = TextView(context).apply {
            setTextColor(palette.primaryText)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (22 * dp).toInt())
            minLines = 2
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            text = ""
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        view = tv
        TranscriptBarController.bind { text -> view?.text = text }

        val box = FrameLayout(context).apply {
            background = bgDrawable
            addView(tv)
        }
        container = box

        val bp = getBubbleParams() ?: run {
            Log.w(TAG, "ensureTranscriptBar: bubbleParams null — cannot position")
            return
        }
        val bubbleHeight = if (bp.height > 0) bp.height else (120 * dp).toInt()
        val bubbleTop = bp.y
        val bubbleCenterX = bp.x + (if (bp.width > 0) bp.width else 360) / 2
        val screenW = context.resources.displayMetrics.widthPixels
        val ltParams = WindowManager.LayoutParams(
            barWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleCenterX - barWidthPx / 2).coerceIn(0, (screenW - barWidthPx).coerceAtLeast(0))
            y = (bubbleTop - (90 * dp).toInt()).coerceAtLeast(0)
        }
        params = ltParams
        try { windowManager.addView(box, ltParams) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }

        val bezelTopOffset = (bubbleHeight * 0.20).toInt()
        val anchor: () -> Unit = {
            val measuredH = box.height.coerceAtLeast(1)
            val newY = (bubbleTop - measuredH + bezelTopOffset).coerceAtLeast(0)
            if (ltParams.y != newY) {
                ltParams.y = newY
                try { windowManager.updateViewLayout(box, ltParams) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
            }
        }
        box.post(anchor)
        box.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((bottom - top) != (oldBottom - oldTop)) anchor()
        }
    }

    /** Show "● Recording…" placeholder; subsequent partials arrive via
     *  TranscriptBarController.bind below. Returns silently if the
     *  `live_transcript` pref is off. */
    fun showLive() {
        if (!WimApp.prefs.getBoolean("live_transcript", false)) return
        ensureBar()
        view?.minLines = 2
        view?.maxLines = 6
        stopStagePulse()
        view?.text = "● Recording…"
    }

    /** Direct write of live partial text. The TranscriptBarController bind
     *  hooked up in ensureBar() is the usual path; this stays for callers
     *  that hold a direct reference. */
    fun updateLive(text: String) {
        view?.text = text
    }

    /** Show a pipeline-stage update (Transcribing → Cleaning → Reconstructing
     *  → Falcon → Inject). L1 cloud (whisper-1) path is silenced unless a
     *  demo script is active — the bubble's own PROC color + bezel arc are
     *  enough feedback on the fast path. */
    fun showStage(stage: String, detail: String) {
        val isL1Cloud = WimApp.layer() < 2 && WimApp.l1CloudAsr()
        if (isL1Cloud && !isDemoActive()) return
        ensureBar()
        val tv = view ?: return
        tv.minLines = 3
        tv.maxLines = 3
        tv.ellipsize = TextUtils.TruncateAt.END

        stageLabel = stage
        stageDetail = detail
        startStageAnimations()

        // Cross-fade: dim → settle → restore.
        tv.animate().alpha(0.3f).setDuration(110)
            .withEndAction {
                renderStageFrame()
                tv.animate().alpha(1f).setDuration(180).start()
            }.start()
    }

    private fun renderStageFrame() {
        val tv = view ?: return

        // Theme-aware palette — bronze/aluminum have LIGHT fills (need dark
        // text), gunmetal has DARK fill (needs cream-gold text). Single
        // source of truth as the live-transcript surface.
        val palette = CommentBubbleDrawable.paletteFor(WimApp.bubbleTheme())
        val labelRgb = palette.primaryText and 0x00FFFFFF
        val detailColor = palette.secondaryText
        val labelColor = 0xFF000000.toInt() or labelRgb

        val typedBody = if (typewriterIdx >= stageDetail.length) stageDetail
                        else stageDetail.substring(0, typewriterIdx)
        val cursorChar = if (cursorVisible) "▌" else " "

        val styled = SpannableStringBuilder().apply {
            val titleStart = length
            append(stageLabel)
            setSpan(ForegroundColorSpan(labelColor),
                titleStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD),
                titleStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            val detailStart = length
            append(typedBody)
            append(cursorChar)
            setSpan(RelativeSizeSpan(0.85f),
                detailStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(detailColor),
                detailStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = styled
    }

    private fun startStageAnimations() {
        stopStagePulse()
        typewriterIdx = 0
        cursorVisible = true
        stageAnimHandler.postDelayed(typewriterTick, TYPEWRITER_INTERVAL_MS)
        stageAnimHandler.postDelayed(cursorBlinkTick, CURSOR_BLINK_MS)
    }

    private fun stopStagePulse() {
        stageAnimHandler.removeCallbacks(typewriterTick)
        stageAnimHandler.removeCallbacks(cursorBlinkTick)
    }

    fun hide() {
        stopStagePulse()
        container?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
        }
        container = null
        view = null
        params = null
        TranscriptBarController.unbind()
    }

    /** Cancel every pending tick so onDestroy doesn't leave the typewriter
     *  / cursor blink callbacks firing into a dead view forever. */
    fun destroy() {
        stageAnimHandler.removeCallbacksAndMessages(null)
    }
}
