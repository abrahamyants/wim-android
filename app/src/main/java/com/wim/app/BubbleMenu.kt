package com.wim.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Comment-bubble menu overlay — opens above the floating bubble on long
 * tap or drag-to-top. Hosts the L1/L2/L4 layer selector, L1 source toggle
 * (LOCAL ↔ CLOUD), opacity + size sliders, live-transcript toggle, theme
 * picker, anticipatory prediction chip, and Settings shortcut.
 *
 * Extracted from BubbleService 2026-05-25 as the third BubbleWindow slice
 * (after ShakeDetector, WordHighlight, LiveTranscript). Owns the inflation
 * + theming + listener wiring + tail-anchored positioning. Callbacks bring
 * the side effects (appearance / layer / theme application) back to the
 * service so this class never reaches into bubble state.
 */
class BubbleMenu(
    private val context: Context,
    private val windowManager: WindowManager,
    private val getBubbleView: () -> View?,
    private val getBubbleParams: () -> WindowManager.LayoutParams?,
    private val onAppearanceChanged: () -> Unit,
    private val onLayerChanged: () -> Unit,
    private val onThemeChanged: () -> Unit
) {
    private companion object {
        private const val TAG = "WiM"
    }

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun isVisible(): Boolean = view != null

    fun show() {
        if (view != null) return
        dismiss()

        val themedContext = ContextThemeWrapper(context, R.style.Theme_WhatIMeant)
        val inflater = LayoutInflater.from(themedContext)
        val v = inflater.inflate(R.layout.bubble_menu, null)
        view = v

        // Per-theme palette: comment-bubble fill/stroke + matched text
        // colors so the menu reads as the bubble's own speech.
        val palette = CommentBubbleDrawable.paletteFor(WimApp.bubbleTheme())

        // Speech-bubble background with downward tail centered at bottom —
        // tail tip lands on the floating bubble's top edge.
        v.background = CommentBubbleDrawable(
            fillColor = palette.fill,
            strokeColor = palette.stroke,
            displayMetrics = context.resources.displayMetrics,
            backgroundStyle = CommentBubbleDrawable.backgroundStyleFor(WimApp.bubbleTheme())
        )
        // The bubble's opacity slider never governed this panel. It sets alpha on
        // the bezel VIEW; the menu's surface is a drawable whose palette fill
        // carries its own hardcoded 0xC0 (75%) alpha, so at OP 100 the user could
        // still read the app underneath through the menu and had no control that
        // did anything about it. Drive the drawable from the same pref: 100 is
        // genuinely solid, lower values are as see-through as asked for.
        applyMenuOpacity(v)

        // On-FILL text color (section labels, slider labels/values, Reset,
        // Settings) adapts per theme so it stays readable against the menu
        // surface:
        //   Bronze hex / Silver hex → dark grey
        //   Gunmetal carbon weave   → brand cream
        // Chips (engraved nameplates) have their own dark background, so
        // chip text stays brand cream universally regardless of theme.
        val brandCream = 0xFFC8B48A.toInt()
        val brandCreamBright = 0xFFE6D4B0.toInt()
        val onFillText = when (WimApp.bubbleTheme()) {
            "gunmetal" -> brandCream
            "bronze" -> 0xFF1F1208.toInt()    // deep brown — strong contrast on bronze hex
            else -> 0xFF0A0A12.toInt()        // near-black — strong contrast on silver hex
        }
        val onFillTextBright = when (WimApp.bubbleTheme()) {
            "gunmetal" -> brandCreamBright
            else -> 0xFF000000.toInt()        // pure black for slider values
        }
        fun colorAs(id: Int, color: Int) {
            v.findViewById<TextView>(id)?.setTextColor(color)
        }
        // Section labels — sit on the menu fill
        colorAs(R.id.menuLiveLabel, onFillText)
        colorAs(R.id.menuLayerLabel, onFillText)
        colorAs(R.id.menuColorLabel, onFillText)
        // Slider mini-labels (OP / SZ) + bottom value readouts
        colorAs(R.id.menuOpacityLabel, onFillText)
        colorAs(R.id.menuOpacityValue, onFillTextBright)
        colorAs(R.id.menuSizeLabel, onFillText)
        colorAs(R.id.menuSizeValue, onFillTextBright)
        // Settings — wrapper-less, sits on the menu fill
        colorAs(R.id.menuOpenSettings, onFillTextBright)

        // Anticipatory prediction chip — populated at menu-open time so it
        // reflects whichever app the user is currently in. Hidden when
        // PredictionEngine has nothing high-confidence to say.
        v.findViewById<TextView>(R.id.menuPredictionChip)?.let { chip ->
            chip.setTextColor(palette.secondaryText)
            val pkg = WimAccessibilityService.currentForegroundPackage
            val pred = if (WimApp.predictionsEnabled()) PredictionEngine.predictForContext(pkg) else null
            if (pred != null) {
                chip.text = "→ ${pred.tone.uppercase()} · ${pred.recipient}"
                chip.visibility = View.VISIBLE
            } else {
                chip.visibility = View.GONE
            }
        }

        val mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Real x/y are set by updatePositionFromBubble() once measured.
            x = 0
            y = 0
        }
        params = mParams

        // Load current values
        val opacity = WimApp.bubbleOpacity().coerceIn(0, 100)
        val size = WimApp.bubbleSize().coerceIn(70, 150)
        val liveEnabled = WimApp.prefs.getBoolean("live_transcript", false)

        val accentTint = ColorStateList.valueOf(palette.accent)
        val trackTint = ColorStateList.valueOf(palette.sliderTrack)

        val opacitySeek = v.findViewById<SeekBar>(R.id.menuOpacitySeek)
        val opacityValue = v.findViewById<TextView>(R.id.menuOpacityValue)
        opacitySeek.progress = opacity
        opacityValue.text = "$opacity"
        opacitySeek.thumbTintList = accentTint
        opacitySeek.progressTintList = accentTint
        opacitySeek.progressBackgroundTintList = trackTint

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(0, 100)
                opacityValue.text = "$pct"
                WimApp.prefs.edit().putInt("bubble_opacity", pct).apply()
                // Repaint the panel itself as the slider moves, not just the
                // bubble — otherwise the control appears to do nothing to the
                // very surface the user is looking at while dragging it.
                applyMenuOpacity(v)
                onAppearanceChanged()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val sizeSeek = v.findViewById<SeekBar>(R.id.menuSizeSeek)
        val sizeValue = v.findViewById<TextView>(R.id.menuSizeValue)
        sizeSeek.progress = size
        sizeValue.text = "$size"
        sizeSeek.thumbTintList = accentTint
        sizeSeek.progressTintList = accentTint
        sizeSeek.progressBackgroundTintList = trackTint

        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(70, 150)
                sizeValue.text = "$pct"
                WimApp.prefs.edit().putInt("bubble_size", pct).apply()
                onAppearanceChanged()
                // Bubble grew/shrank — re-anchor menu tail to new bubble top.
                updatePositionFromBubble()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val liveToggle = v.findViewById<MaterialSwitch>(R.id.menuLiveToggle)
        liveToggle.isChecked = liveEnabled
        liveToggle.thumbTintList = accentTint
        liveToggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.stroke, palette.sliderTrack)
        )
        liveToggle.scaleX = 0.75f
        liveToggle.scaleY = 0.75f
        liveToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("live_transcript", checked).apply()
        }

        // Layer selector — 3 buttons mapped to layers 1/2/4. Profile (L3)
        // is dropped from the menu by design; access via Settings.
        val layerEntries = listOf(
            R.id.menuLayer1 to 1,
            R.id.menuLayer2 to 2,
            R.id.menuLayer3 to 3,
            R.id.menuLayer4 to 4
        ).map { (id, layerVal) -> v.findViewById<TextView>(id) to layerVal }
        val currentLayer = WimApp.layer()
        // Engraved nameplate chips use the state_selected layer of
        // nameplate_bg for the active visual (maroon fill + cream highlight
        // border). Text brightens to brandCreamBright when active.
        layerEntries.forEach { (btn, layerVal) ->
            val isActive = layerVal == currentLayer
            btn.isSelected = isActive
            btn.setTextColor(if (isActive) brandCreamBright else brandCream)
            btn.setOnClickListener {
                if (layerVal > 1 && !WimApp.cloudUnlocked()) {
                    openCloudUnlock()
                    return@setOnClickListener
                }
                WimApp.prefs.edit().putInt("layer", layerVal).apply()
                layerEntries.forEach { (b, _) ->
                    b.isSelected = false
                    b.setTextColor(brandCream)
                }
                btn.isSelected = true
                btn.setTextColor(brandCreamBright)
                onLayerChanged()
            }
        }

        // L1 source sub-row — LOCAL vs CLOUD, indented under TRANSCRIPTION.
        // Active source reflected via nameplate_bg state_selected (maroon
        // fill + cream border) so the user sees the current setting at a
        // glance whenever the menu is open.
        val l1LocalBtn = v.findViewById<TextView>(R.id.menuL1Local)
        val l1CloudBtn = v.findViewById<TextView>(R.id.menuL1Cloud)
        fun paintL1Source() {
            val cloud = WimApp.l1CloudAsr()
            l1LocalBtn.isSelected = !cloud
            l1CloudBtn.isSelected = cloud
            l1LocalBtn.setTextColor(if (!cloud) brandCreamBright else brandCream)
            l1CloudBtn.setTextColor(if (cloud) brandCreamBright else brandCream)
        }
        paintL1Source()
        l1LocalBtn.setOnClickListener {
            WimApp.prefs.edit().putBoolean("l1_cloud_asr", false).apply()
            paintL1Source()
        }
        l1CloudBtn.setOnClickListener {
            if (!WimApp.cloudUnlocked()) {
                openCloudUnlock()
                return@setOnClickListener
            }
            WimApp.prefs.edit().putBoolean("l1_cloud_asr", true).apply()
            paintL1Source()
        }

        // Bubble color picker — three tiny replicas of the bubble bezel
        // itself. Selecting one literally swaps the floating bubble's
        // metal. The selector drawables flip to a cream glow ring on
        // state_selected.
        val themeBtns = mapOf(
            "bronze" to v.findViewById<View>(R.id.menuThemeBronze),
            "aluminum" to v.findViewById<View>(R.id.menuThemeAluminum),
            "gunmetal" to v.findViewById<View>(R.id.menuThemeGunmetal)
        )
        val currentTheme = WimApp.bubbleTheme()
        themeBtns.forEach { (themeKey, btn) ->
            btn.isSelected = (themeKey == currentTheme)
            btn.setOnClickListener {
                WimApp.prefs.edit().putString("bubble_theme", themeKey).apply()
                onThemeChanged()
                // Re-open so the menu picks up the new palette.
                dismiss()
                show()
            }
        }

        v.findViewById<TextView>(R.id.menuOpenSettings).setOnClickListener {
            dismiss()
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        // Dismiss on tap outside menu.
        v.setOnTouchListener { tv, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true
            } else {
                tv.performClick()
                false
            }
        }

        // Measure the menu synchronously so initial x/y land on the
        // bubble's top-center before the first frame renders. With OVERLAY
        // windows post-layout positioning fires unreliably; without this
        // the menu ends up at (0,0) — behind the status bar — and looks
        // like it didn't open at all.
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mW0 = v.measuredWidth
        val mH0 = v.measuredHeight
        val bp0 = getBubbleParams()
        val bv0 = getBubbleView()
        if (bp0 != null && bv0 != null && mW0 > 0 && mH0 > 0) {
            val bubbleWidth = if (bp0.width > 0) bp0.width else bv0.width
            val bubbleHeight = if (bp0.height > 0) bp0.height else bv0.height
            val bezelTopOffset = (bubbleHeight * 0.20).toInt()
            val tailOverlap = 0
            mParams.x = bp0.x + bubbleWidth / 2 - mW0 / 2
            mParams.y = bp0.y - mH0 + bezelTopOffset + tailOverlap
        }

        try { windowManager.addView(v, mParams) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
        // Refine after final layout in case wrap_content settled differently.
        v.post { updatePositionFromBubble() }
    }

    /**
     * Reposition the menu so its downward-tail tip lands on the floating
     * bubble's top center. Called from show() (initial), the bubble drag
     * MOVE handler, moveBubbleTo() (dock-pickup pipe), the parkIntoDock
     * animator update listener, and the size-slider listener inside this
     * class — every place the bubble's window position can change while
     * the menu is visible.
     */
    fun updatePositionFromBubble() {
        val mView = view ?: return
        val mParams = params ?: return
        val bp = getBubbleParams() ?: return
        val bv = getBubbleView() ?: return
        val mWidth = if (mView.width > 0) mView.width else mView.measuredWidth
        val mHeight = if (mView.height > 0) mView.height else mView.measuredHeight
        if (mWidth == 0 || mHeight == 0) return
        // Prefer params.width — bv.width can lag a frame after a size change.
        val bubbleWidth = if (bp.width > 0) bp.width else bv.width
        val bubbleHeight = if (bp.height > 0) bp.height else bv.height
        val bubbleCenterX = bp.x + bubbleWidth / 2
        val bubbleTop = bp.y
        // Bubble window is 120dp; visible bezel inside is 72dp centered →
        // ~20% top padding before the bezel. Push menu down by that padding
        // so the tail tip lands ON the bezel circle, not the invisible
        // window edge above it. Plus a small overlap so the tail visually
        // merges into the bezel.
        val bezelTopOffset = (bubbleHeight * 0.20).toInt()
        val tailOverlap = 0
        mParams.x = bubbleCenterX - mWidth / 2
        mParams.y = bubbleTop - mHeight + bezelTopOffset + tailOverlap
        try { windowManager.updateViewLayout(mView, mParams) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
    }

    /**
     * Push the user's bubble-opacity preference into the menu panel's own
     * background drawable.
     *
     * [CommentBubbleDrawable.setAlpha] overwrites the paint alpha rather than
     * multiplying it, so 255 fully cancels the palette fill's baked-in 0xC0 and
     * the panel becomes genuinely opaque at OP 100.
     */
    private fun applyMenuOpacity(root: View) {
        // The bubble may go to true 0 (invisible), but the menu panel floors at
        // 25 — an invisible panel would hide the very slider needed to recover.
        val pct = WimApp.bubbleOpacity().coerceIn(25, 100)
        root.background?.alpha = pct * 255 / 100
        root.invalidate()
    }

    private fun openCloudUnlock() {
        dismiss()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("open_cloud_unlock", true)
        }
        context.startActivity(intent)
    }

    fun dismiss() {
        view?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
            view = null
            params = null
        }
    }
}
