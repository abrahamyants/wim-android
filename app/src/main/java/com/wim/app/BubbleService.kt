package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isRecording = false
    private var vibrator: Vibrator? = null
    private var onDeviceRecognizer: OnDeviceRecognizer? = null
    private var sessionHistory: SessionHistory? = null
    private var dafPlayer: DafPlayer? = null
    private var audioRecorder: AudioRecorder? = null
    private var lastRawText = ""
    private var finalizedSegments = ""
    private var processed = false
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var liveTextView: TextView? = null
    private var liveTextParams: WindowManager.LayoutParams? = null
    private var lastTapTime = 0L
    private val DOUBLE_TAP_TIMEOUT = 300L
    private var singleTapRunnable: Runnable? = null
    private val recentTexts = mutableListOf<String>()
    private var redoCount = 0
    private var isWorkMode = false  // false=personal, true=work
    private var isClarifierActive = false
    private var clarifierCallback: ((Boolean?) -> Unit)? = null
    private val clarifierTimeout = Runnable { dismissClarifier(null) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingStartTime = 0L
    private var pulseAnimators = mutableListOf<AnimatorSet>()
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val min = elapsed / 60
                val sec = elapsed % 60
                bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.text = "$min:${"%02d".format(sec)}"
                timerHandler.postDelayed(this, 500)
            }
        }
    }

    companion object {
        private const val TAG = "WiM"
        private const val CHANNEL_ID = "wim_bubble"
        private const val NOTIFICATION_ID = 1
        var instance: BubbleService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BubbleService created")
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        onDeviceRecognizer = OnDeviceRecognizer(this)
        sessionHistory = SessionHistory(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BubbleService onStartCommand: ${intent?.action}")
        when (intent?.action) {
            "SHOW" -> showBubble()
            "HIDE" -> hideBubble()
            "TEST_CLARIFIER" -> {
                val q = intent.getStringExtra("question") ?: "WORK?"
                showClarifier(q) { answer ->
                    Log.i(TAG, "Clarifier answer: $answer")
                }
            }
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubbleView != null) return

        Log.i(TAG, "Showing bubble")
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble, null)

        val params = bubbleParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 900
            y = 1000
        }
        bubbleParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L
        var startedOnThisPress = false
        val DRAG_THRESHOLD = 40
        val startRecordingRunnable = Runnable {
            if (!isDragging && !isRecording) {
                try { vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                startPulseRings()
                startListening()
                startedOnThisPress = true
            }
        }

        // Initialize work/personal from prefs
        isWorkMode = WimApp.prefs.getBoolean("work_mode", false)
        updateBezelLabels()

        bubbleView?.setOnTouchListener { v, event ->
            // Accept touches on the bezel area (up to 50% radius) — covers both knob and bezel ring
            if (event.action == MotionEvent.ACTION_DOWN) {
                val cx = v.width / 2f
                val cy = v.height / 2f
                val tx = event.x - cx
                val ty = event.y - cy
                val r = v.width * 0.5f
                if (tx * tx + ty * ty > r * r) return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()
                    startedOnThisPress = false

                    if (!isRecording) {
                        // Delay recording start by 150ms — if finger moves, it's a drag
                        timerHandler.postDelayed(startRecordingRunnable, 150)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > DRAG_THRESHOLD || kotlin.math.abs(dy) > DRAG_THRESHOLD) {
                        if (!isDragging) {
                            isDragging = true
                            // Cancel pending recording start
                            timerHandler.removeCallbacks(startRecordingRunnable)
                            // If recording somehow started, cancel it
                            if (startedOnThisPress && isRecording) {
                                isRecording = false
                                onDeviceRecognizer?.stop()
                                audioRecorder?.stop()
                                audioRecorder = null
                                dafPlayer?.stop()
                                dafPlayer = null
                                stopPulseRings()
                                setBubbleIdle()
                                startedOnThisPress = false
                            }
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    timerHandler.removeCallbacks(startRecordingRunnable)
                    if (isDragging) return@setOnTouchListener true

                    // Check if tap was in the bezel ring (not the inner knob)
                    val cx = v.width / 2f
                    val cy = v.height / 2f
                    val tx = event.x - cx
                    val ty = event.y - cy
                    val dist = kotlin.math.sqrt(tx * tx + ty * ty)
                    val innerR = v.width * 0.18f   // inner knob edge
                    val outerR = v.width * 0.40f    // bezel outer edge
                    val tapDuration = System.currentTimeMillis() - downTime

                    if (!isRecording && dist > innerR && dist < outerR && tapDuration < 300) {
                        try { vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        if (isClarifierActive) {
                            // Clarifier mode — left = ✓ yes, right = ✕ no
                            dismissClarifier(tx < 0)
                        } else {
                            // Normal mode — toggle work/life
                            if (tx < 0) {
                                isWorkMode = true
                                WimApp.prefs.edit().putBoolean("work_mode", true).putString("tone", "professional").apply()
                            } else {
                                isWorkMode = false
                                WimApp.prefs.edit().putBoolean("work_mode", false).putString("tone", "casual").apply()
                            }
                            updateBezelLabels()
                        }
                        return@setOnTouchListener true
                    }

                    try { vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}

                    if (startedOnThisPress && isRecording) {
                        // Hold mode: was recording from this press, release = done
                        lastTapTime = 0L
                        stopAndProcess()
                    } else if (!startedOnThisPress && isRecording) {
                        // Second tap: recording was running from before, stop it
                        lastTapTime = 0L
                        stopAndProcess()
                    } else if (!isRecording) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                            // Double-tap: cancel pending single-tap, show/hide menu
                            lastTapTime = 0L
                            singleTapRunnable?.let { timerHandler.removeCallbacks(it) }
                            singleTapRunnable = null
                            if (menuView != null) dismissMenu() else showMenu()
                        } else {
                            // First tap: wait for possible second tap before starting
                            lastTapTime = now
                            singleTapRunnable = Runnable {
                                if (!isRecording) {
                                    startPulseRings()
                                    startListening()
                                }
                            }
                            timerHandler.postDelayed(singleTapRunnable!!, DOUBLE_TAP_TIMEOUT)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Apply saved bubble opacity and size
        val opacity = WimApp.bubbleOpacity().coerceIn(20, 100) / 100f
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.alpha = opacity

        val scale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
        val density = resources.displayMetrics.density
        val baseWindowDp = 120
        val newWindowPx = (baseWindowDp * density * scale).toInt()
        params.width = newWindowPx
        params.height = newWindowPx
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
            it.scaleX = scale
            it.scaleY = scale
        }

        windowManager?.addView(bubbleView, params)
    }

    private fun hideBubble() {
        bubbleView?.let {
            windowManager?.removeView(it)
            bubbleView = null
        }
    }

    private fun startListening() {
        if (isRecording) return
        isRecording = true
        lastRawText = ""
        finalizedSegments = ""
        processed = false

        Log.i(TAG, "Starting on-device recognition")
        dismissMenu()
        showLiveTranscript()
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()

        // Visual: recording state — bezel glows red, inner well lights up
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            ?.setBackgroundResource(R.drawable.bubble_bg_recording)
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_active)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "REC"
            setTextColor(0xFFEF4444.toInt())
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            setTextColor(0xFFFF6B35.toInt())
        }
        recordingStartTime = System.currentTimeMillis()
        timerHandler.post(timerRunnable)
        val pulse = AlphaAnimation(1.0f, 0.6f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.startAnimation(pulse)

        // L4: also record audio for Whisper API transcription
        if (WimApp.layer() >= 4 && WimApp.apiKey().isNotBlank()) {
            try {
                audioRecorder = AudioRecorder(this)
                audioRecorder?.start()
                Log.i(TAG, "L4: AudioRecorder started for Whisper pipeline")
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecorder failed, will use on-device only: ${e.message}")
                audioRecorder = null
            }
        }

        // Start DAF playback if enabled
        if (WimApp.dafEnabled()) {
            dafPlayer = DafPlayer()
            dafPlayer?.start()
        }

        onDeviceRecognizer?.start(object : OnDeviceRecognizer.ResultListener {
            override fun onPartialResult(text: String) {
                Log.i(TAG, "Partial: $text")
                // Partial = current segment only; combine with finalized segments
                lastRawText = if (finalizedSegments.isBlank()) text else "$finalizedSegments $text"
                updateLiveTranscript(lastRawText)
            }

            override fun onFinalResult(text: String) {
                Log.i(TAG, "Final ASR: $text")
                if (text.isNotBlank()) {
                    finalizedSegments = if (finalizedSegments.isBlank()) text else "$finalizedSegments $text"
                    lastRawText = finalizedSegments
                    updateLiveTranscript(lastRawText)
                }
            }

            override fun onError(errorCode: Int) {
                Log.e(TAG, "ASR error: $errorCode")
                isRecording = false
                dafPlayer?.stop()
                dafPlayer = null
                setBubbleIdle()
                bubbleView?.scaleX = 1.0f
                bubbleView?.scaleY = 1.0f
            }
        })
    }

    private fun stopAndProcess() {
        if (processed) return
        Log.i(TAG, "Stop requested, waiting 750ms for final words...")
        processed = true
        isRecording = false
        dafPlayer?.stop()
        dafPlayer = null

        // Stop audio recorder immediately (captures full audio)
        val wavFile = audioRecorder?.stop()
        audioRecorder = null

        // Delay stopping the recognizer — let it finish processing the last audio buffer
        timerHandler.postDelayed({
            val textBeforeStop = lastRawText
            onDeviceRecognizer?.stop()

            // Wait another 250ms for the final result callback
            timerHandler.postDelayed({
                val onDeviceText = if (lastRawText.length > textBeforeStop.length) lastRawText else textBeforeStop

                // L4 with recorded audio: use Whisper API for better stutter transcription
                if (wavFile != null && wavFile.exists() && wavFile.length() > 44) {
                    Log.i(TAG, "L4: Sending ${wavFile.length()} bytes to Whisper API")
                    scope.launch {
                        try {
                            val whisperResult = WhisperClient.transcribe(wavFile)
                            Log.i(TAG, "Whisper (${whisperResult.asrMs}ms): ${whisperResult.text}")
                            val finalText = whisperResult.text.ifBlank { onDeviceText }
                            if (finalText.isNotBlank()) {
                                processText(finalText)
                            } else {
                                setBubbleIdle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Whisper failed, falling back to on-device: ${e.message}")
                            if (onDeviceText.isNotBlank()) {
                                processText(onDeviceText)
                            } else {
                                setBubbleIdle()
                            }
                        } finally {
                            wavFile.delete()
                        }
                    }
                } else {
                    // L1-3 or no audio: use on-device result
                    Log.i(TAG, "Final text after delay: $onDeviceText")
                    if (onDeviceText.isNotBlank()) {
                        processText(onDeviceText)
                    } else {
                        setBubbleIdle()
                    }
                }
            }, 250)
        }, 750)
    }

    private fun startPulseRings() {
        stopPulseRings()
        val rings = listOf(
            bubbleView?.findViewById<View>(R.id.ring1),
            bubbleView?.findViewById<View>(R.id.ring2),
            bubbleView?.findViewById<View>(R.id.ring3)
        )

        rings.forEachIndexed { i, ring ->
            ring ?: return@forEachIndexed
            val delay = (i * 300).toLong()
            val maxScale = 1.4f + (i * 0.3f) // 1.4, 1.7, 2.0 — fits in 120dp window

            val sx = ObjectAnimator.ofFloat(ring, "scaleX", 1.0f, maxScale).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART; startDelay = delay
            }
            val sy = ObjectAnimator.ofFloat(ring, "scaleY", 1.0f, maxScale).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART; startDelay = delay
            }
            val a = ObjectAnimator.ofFloat(ring, "alpha", 0.9f, 0.0f).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART; startDelay = delay
            }

            val set = AnimatorSet()
            set.playTogether(sx, sy, a)
            set.start()
            pulseAnimators.add(set)
        }
    }

    private fun stopPulseRings() {
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators.clear()
        listOf(R.id.ring1, R.id.ring2, R.id.ring3).forEach { id ->
            bubbleView?.findViewById<View>(id)?.apply {
                scaleX = 1f; scaleY = 1f; alpha = 0f
            }
        }
    }

    private fun setBubbleIdle() {
        timerHandler.removeCallbacks(timerRunnable)
        stopPulseRings()
        hideLiveTranscript()
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.apply {
            clearAnimation()
            setBackgroundResource(R.drawable.bubble_inner_knob)
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            ?.setBackgroundResource(R.drawable.bubble_bg_idle)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "IDLE"
            setTextColor(0xFFFFFFFF.toInt())
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            text = "0:00"
            setTextColor(0xFFEE5A24.toInt())
        }
    }

    private fun updateBezelLabels() {
        // No-op in normal state — knob shows IDLE/REC/PROC
        // WORK/LIFE only shown during clarifier
    }

    /**
     * Show a clarifier question on the knob with ✓ and ✕ on the bezel.
     * @param questionWord Short word shown on the knob (e.g. "WORK?" or "VENT?")
     * @param onAnswer Callback with true=✓ false=✕ null=timeout
     */
    fun showClarifier(questionWord: String, onAnswer: (Boolean?) -> Unit) {
        isClarifierActive = true
        clarifierCallback = { answer -> onAnswer(answer) }

        // Show question on the knob — auto-size to fill available space
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = questionWord
            setTextColor(0xFFFFFFFF.toInt())
            letterSpacing = 0.02f
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            setAutoSizeTextTypeUniformWithConfiguration(5, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE

        // Show split bezel (green/red) and ✓/✕
        bubbleView?.findViewById<View>(R.id.clarifierBgYes)?.visibility = View.VISIBLE
        bubbleView?.findViewById<View>(R.id.clarifierBgNo)?.visibility = View.VISIBLE
        bubbleView?.findViewById<TextView>(R.id.bezelYes)?.visibility = View.VISIBLE
        bubbleView?.findViewById<TextView>(R.id.bezelNo)?.visibility = View.VISIBLE

        // Auto-dismiss after 5 seconds
        timerHandler.postDelayed(clarifierTimeout, 10000)
    }

    private fun dismissClarifier(answer: Boolean?) {
        timerHandler.removeCallbacks(clarifierTimeout)
        isClarifierActive = false

        // Hide split bezel and ✓/✕
        bubbleView?.findViewById<View>(R.id.clarifierBgYes)?.visibility = View.GONE
        bubbleView?.findViewById<View>(R.id.clarifierBgNo)?.visibility = View.GONE
        bubbleView?.findViewById<TextView>(R.id.bezelYes)?.visibility = View.GONE
        bubbleView?.findViewById<TextView>(R.id.bezelNo)?.visibility = View.GONE

        // Restore normal label sizing
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 7f)
            letterSpacing = 0.15f
        }

        // Show acknowledgment if user answered (not timeout)
        if (answer != null) {
            bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
                text = "\uD83D\uDC4D"
                setTextColor(0xFFFFFFFF.toInt())
            }
            timerHandler.postDelayed({
                bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE
                setBubbleIdle()
            }, 800)
        } else {
            bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE
            setBubbleIdle()
        }

        // Fire callback
        clarifierCallback?.invoke(answer)
        clarifierCallback = null
    }

    fun updateBubbleAppearance() {
        val opacity = WimApp.bubbleOpacity().coerceIn(20, 100) / 100f
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.alpha = opacity

        val scale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
        val density = resources.displayMetrics.density
        val baseWindowDp = 120
        val newWindowPx = (baseWindowDp * density * scale).toInt()

        bubbleParams?.let { params ->
            params.width = newWindowPx
            params.height = newWindowPx
            try {
                windowManager?.updateViewLayout(bubbleView, params)
            } catch (_: Exception) {}
        }

        // Scale the inner views to match
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
            it.scaleX = scale
            it.scaleY = scale
        }
    }

    private fun showMenu() {
        if (menuView != null) return
        dismissMenu()

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_WhatIMeant)
        val inflater = LayoutInflater.from(themedContext)
        menuView = inflater.inflate(R.layout.bubble_menu, null)

        // Position above the bubble
        val bp = bubbleParams ?: return
        val mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bp.x - 40
            y = bp.y - 260
        }
        menuParams = mParams

        // Load current values
        val opacity = WimApp.bubbleOpacity().coerceIn(20, 100)
        val size = WimApp.bubbleSize().coerceIn(70, 150)
        val liveEnabled = WimApp.prefs.getBoolean("live_transcript", false)

        val opacitySeek = menuView!!.findViewById<SeekBar>(R.id.menuOpacitySeek)
        val opacityValue = menuView!!.findViewById<TextView>(R.id.menuOpacityValue)
        opacitySeek.progress = opacity
        opacityValue.text = "$opacity%"

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(20, 100)
                opacityValue.text = "$pct%"
                WimApp.prefs.edit().putInt("bubble_opacity", pct).apply()
                updateBubbleAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val sizeSeek = menuView!!.findViewById<SeekBar>(R.id.menuSizeSeek)
        val sizeValue = menuView!!.findViewById<TextView>(R.id.menuSizeValue)
        sizeSeek.progress = size
        sizeValue.text = "$size%"

        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(70, 150)
                sizeValue.text = "$pct%"
                WimApp.prefs.edit().putInt("bubble_size", pct).apply()
                updateBubbleAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val liveToggle = menuView!!.findViewById<MaterialSwitch>(R.id.menuLiveToggle)
        liveToggle.isChecked = liveEnabled
        liveToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("live_transcript", checked).apply()
        }

        menuView!!.findViewById<TextView>(R.id.menuOpenSettings).setOnClickListener {
            dismissMenu()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // Dismiss on tap outside menu
        menuView!!.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else {
                v.performClick()
                false
            }
        }

        windowManager?.addView(menuView, mParams)
    }

    private fun dismissMenu() {
        menuView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            menuView = null
            menuParams = null
        }
    }

    private fun showLiveTranscript() {
        val enabled = WimApp.prefs.getBoolean("live_transcript", false)
        Log.i(TAG, "showLiveTranscript: enabled=$enabled, existing=${liveTextView != null}, params=${bubbleParams != null}")
        if (!enabled) return
        if (liveTextView != null) return

        val tv = TextView(this).apply {
            setBackgroundColor(0xDD1A1A2E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(20, 10, 20, 10)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "..."
        }
        liveTextView = tv

        val bp = bubbleParams ?: return
        val ltParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = (220 * resources.displayMetrics.density).toInt()
            x = bp.x - 40
            y = bp.y - 70
        }
        liveTextParams = ltParams

        windowManager?.addView(tv, ltParams)
    }

    private fun updateLiveTranscript(text: String) {
        liveTextView?.text = text.ifBlank { "..." }
    }

    private fun hideLiveTranscript() {
        liveTextView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            liveTextView = null
            liveTextParams = null
        }
    }

    private fun setBubbleProcessing() {
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            ?.setBackgroundResource(R.drawable.bubble_bg_processing)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "PROC"
            setTextColor(0xFFFFA000.toInt())
        }
    }

    /** Jaccard similarity between two strings (word-level). */
    private fun jaccard(a: String, b: String): Float {
        val setA = a.lowercase().split("\\s+".toRegex()).toSet()
        val setB = b.lowercase().split("\\s+".toRegex()).toSet()
        if (setA.isEmpty() && setB.isEmpty()) return 1f
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun checkRedo(text: String) {
        if (recentTexts.isNotEmpty() && jaccard(text, recentTexts.last()) > 0.7) {
            redoCount++
            if (redoCount >= 3) {
                Toast.makeText(this, "Recording similar content repeatedly — take a breath", Toast.LENGTH_LONG).show()
                redoCount = 0
            }
        } else {
            redoCount = 0
        }
        recentTexts.add(text)
        if (recentTexts.size > 5) recentTexts.removeAt(0)
    }

    private fun processText(rawText: String) {
        val pipelineStart = System.currentTimeMillis()
        val asrMs = pipelineStart - recordingStartTime
        Log.i(TAG, "Processing (asr=${asrMs}ms): $rawText")
        checkRedo(rawText)
        setBubbleProcessing()

        val layer = WimApp.layer()
        val tone = WimApp.tone()
        val situation = WimApp.situation()
        val profile = WimApp.profile()

        // Pre-clean: strip disfluencies from raw ASR text before any further processing
        val fillerWords = profile.getFillerWords()
        val preCleaned = DisfluencyFilter.filter(rawText, fillerWords)
        Log.i(TAG, "Pre-cleaned: $preCleaned")

        if (layer < 2) {
            // L1: disfluency-filtered text IS the cleanup — inject directly
            Log.i(TAG, "L1 mode, injecting pre-cleaned text")
            WimAccessibilityService.instance?.injectText(preCleaned)
            val totalMs = System.currentTimeMillis() - recordingStartTime
            sessionHistory?.record(
                rawText = rawText,
                cleanText = preCleaned,
                tone = tone,
                layer = 1,
                situation = situation,
                asrMs = asrMs,
                reconMs = 0,
                totalMs = totalMs,
                falconApproved = true,
                riskFlags = emptyList()
            )
            setBubbleIdle()
            return
        }

        // L2+: reconstruct via GPT (with pre-cleaned input)
        Toast.makeText(this, "Reconstructing...", Toast.LENGTH_SHORT).show()

        // L3+ gets profile vocabulary and corrections injected into the prompt
        val vocabulary = if (layer >= 3) profile.getVocabulary() else emptyList()
        val corrections = if (layer >= 3) profile.getCorrections() else emptyMap()

        scope.launch {
            try {
                Log.i(TAG, "Calling reconstruction (L$layer, $tone, sit=$situation)...")
                val reconResult = ReconstructClient.reconstruct(
                    rawText = preCleaned,
                    tone = tone,
                    layer = layer,
                    situation = situation,
                    vocabulary = vocabulary,
                    corrections = corrections,
                    fillerWords = fillerWords,
                    triggerWords = profile.getTriggerWords(),
                    onsetWeights = profile.getOnsetWeights(),
                    covertProfile = profile.getCovertProfile()
                )
                Log.i(TAG, "Reconstruction (${reconResult.reconMs}ms): ${reconResult.cleanText}")

                if (WimAccessibilityService.instance != null) {
                    WimAccessibilityService.instance!!.injectText(reconResult.cleanText)
                } else {
                    Log.e(TAG, "No accessibility service instance!")
                    Toast.makeText(this@BubbleService, "Enable accessibility service to paste text", Toast.LENGTH_SHORT).show()
                }

                // L3+: learn from reconstruction results
                if (layer >= 3) {
                    profile.learnFromReconstruction(preCleaned, reconResult.cleanText)
                }

                // Record session
                val totalMs = System.currentTimeMillis() - recordingStartTime
                sessionHistory?.record(
                    rawText = preCleaned,
                    cleanText = reconResult.cleanText,
                    tone = tone,
                    layer = layer,
                    situation = situation,
                    asrMs = asrMs,
                    reconMs = reconResult.reconMs,
                    totalMs = totalMs,
                    falconApproved = reconResult.falconApproved,
                    riskFlags = reconResult.riskFlags
                )

                setBubbleIdle()
            } catch (e: Exception) {
                Log.e(TAG, "Reconstruction error: ${e.message}", e)
                // Fallback: inject pre-cleaned text
                WimAccessibilityService.instance?.injectText(preCleaned)
                setBubbleIdle()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiM Voice Dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the voice dictation bubble active"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("What I Meant")
            .setContentText("Voice dictation active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

    override fun onDestroy() {
        instance = null
        dismissMenu()
        hideLiveTranscript()
        dafPlayer?.stop()
        dafPlayer = null
        audioRecorder?.stop()
        audioRecorder = null
        hideBubble()
        onDeviceRecognizer?.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
