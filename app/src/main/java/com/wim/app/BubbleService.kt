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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BubbleService : Service() {

    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeTime = 0L
    private var shakeCount = 0

    private var windowManager: WindowManager? = null
    var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isRecording = false
        set(value) {
            if (field != value) {
                field = value
                RecordingStateBus.emit(value)
            }
        }
    private var vibrator: Vibrator? = null
    private var onDeviceRecognizer: OnDeviceRecognizer? = null
    private var sessionHistory: SessionHistory? = null
    private var dafPlayer: DafPlayer? = null
    private var audioRecorder: AudioRecorder? = null
    private var lastWhisperLowConf: List<WhisperClient.LowConfidenceSegment> = emptyList()

    // Command Mode state — when armed, the next recording is a GPT transform command
    private var commandModeArmed: Boolean = false
    private var commandSourceText: String = ""
    private var commandSelectionStart: Int = -1
    private var commandSelectionEnd: Int = -1
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
    private val clarifierTimeout = Runnable {
        if (clarifierRetryCount < 1) {
            // First timeout — retry
            clarifierRetryCount++
            pendingClarifierCallback?.let { cb ->
                showClarifier(pendingClarifierWord, pendingClarifierQuestion, cb)
            }
        } else {
            // Second timeout — give up
            clarifierRetryCount = 0
            dismissClarifier(null)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingStartTime = 0L
    private var sessionStartTime = 0L
    private var pulseAnimators = mutableListOf<AnimatorSet>()
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            // Timer tracks full session duration; caps at 99:59 to prevent 3-digit overflow
            val elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000
            val timeView = bubbleView?.findViewById<TextView>(R.id.bubbleTime)
            if (elapsed >= 5999) {
                timeView?.text = "99:59"
                // Stop re-posting — user must reset session to resume counting
            } else {
                val min = elapsed / 60
                val sec = elapsed % 60
                timeView?.text = "$min:${"%02d".format(sec)}"
                timerHandler.postDelayed(this, 1000)
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

    fun isCurrentlyRecording(): Boolean = isRecording

    // Public wrappers for onboarding demo script
    fun setBubbleIdlePublic() = setBubbleIdle()
    fun showMenuPublic() = showMenu()
    fun dismissMenuPublic() = dismissMenu()
    fun hideLiveTranscriptPublic() = hideLiveTranscript()

    // ── Test automation entry points (WimTestReceiver) ─────────────────────

    /**
     * Feed raw text directly into the pipeline, bypassing mic/ASR.
     * Runs the full chain: voice shortcuts → block hallucination strip →
     * disfluency filter → reconstruction → Falcon → inject → session record.
     *
     * Result written to WimTestReceiver.lastResult when complete.
     */
    fun processTextForTest(rawText: String) {
        if (!BuildConfig.DEBUG) return
        lastWhisperLowConf = emptyList()
        recordingStartTime = System.currentTimeMillis()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            processText(rawText)
        }
    }

    /**
     * Feed a WAV file into the Whisper → pipeline chain, bypassing mic.
     * Runs: Whisper transcription → disfluency filter → reconstruction → inject.
     *
     * Result written to WimTestReceiver.lastResult when complete.
     */
    fun processAudioForTest(wavFile: java.io.File) {
        if (!BuildConfig.DEBUG) return
        recordingStartTime = System.currentTimeMillis()
        scope.launch {
            try {
                val whisperResult = WhisperClient.transcribe(wavFile, patienceMode = WimApp.layer() >= 4)
                Log.i("WiM", "Test audio Whisper (${whisperResult.asrMs}ms): ${whisperResult.text}")
                lastWhisperLowConf = whisperResult.lowConfidenceSegments
                if (whisperResult.wasFullyHallucinated) {
                    WimTestReceiver.lastResult = org.json.JSONObject().apply {
                        put("status", "hallucinated")
                        put("note", "Whisper fully hallucinated — no real speech detected")
                    }
                    return@launch
                }
                val finalText = whisperResult.text
                if (finalText.isNotBlank()) {
                    processText(finalText)
                } else {
                    WimTestReceiver.lastResult = org.json.JSONObject().apply {
                        put("status", "empty")
                        put("note", "Whisper returned empty transcription")
                    }
                }
            } catch (e: Exception) {
                Log.e("WiM", "Test audio processing failed: ${e.message}", e)
                WimTestReceiver.lastResult = org.json.JSONObject().apply {
                    put("status", "error")
                    put("error", e.message)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var audioManager: android.media.AudioManager? = null
    private var bluetoothScoActive = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BubbleService created")
        instance = this
        sessionStartTime = System.currentTimeMillis()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

        // Audio playback monitor — logs ANY sound played on the device so we can detect chirps
        audioManager?.registerAudioPlaybackCallback(object : android.media.AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                if (configs.isNotEmpty()) {
                    Log.w(TAG, "CHIRP_MONITOR: ${configs.size} playback(s) detected")
                    for (c in configs) {
                        Log.w(TAG, "CHIRP_MONITOR: usage=${c.audioAttributes.usage} content=${c.audioAttributes.contentType}")
                    }
                }
            }
        }, null)

        onDeviceRecognizer = OnDeviceRecognizer(this)
        sessionHistory = SessionHistory(this)
        createNotificationChannel()
        setupBluetoothSco()
        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fgsType = if (hasMic) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIFICATION_ID, buildNotification(), fgsType)
        setupShakeDetector()
    }

    private fun setupBluetoothSco() {
        val am = audioManager ?: return
        // Listen for Bluetooth SCO state changes
        val scoReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val state = intent?.getIntExtra(android.media.AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (state) {
                    android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO connected — using BT mic")
                        bluetoothScoActive = true
                    }
                    android.media.AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO disconnected — using default mic")
                        bluetoothScoActive = false
                    }
                }
            }
        }
        registerReceiver(scoReceiver, android.content.IntentFilter(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        // If Bluetooth headset is already connected, start SCO
        if (am.isBluetoothScoAvailableOffCall) {
            try {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                Log.i(TAG, "Bluetooth SCO started")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start Bluetooth SCO: ${e.message}")
            }
        }
    }

    private fun setupShakeDetector() {
        sensorManager = getSystemService(android.hardware.SensorManager::class.java)
        val accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return

        shakeListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                // Only respond to shakes when screen is on and phone is unlocked
                val powerManager = getSystemService(android.os.PowerManager::class.java)
                val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
                if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()) / android.hardware.SensorManager.GRAVITY_EARTH

                if (gForce > 2.5 && WimApp.prefs.getBoolean("shake_enabled", true)) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeTime > 500) {
                        shakeCount = 1
                    } else {
                        shakeCount++
                    }
                    lastShakeTime = now

                    if (shakeCount >= 2) {
                        shakeCount = 0
                        // Double-shake: toggle bubble visibility
                        if (bubbleView != null) {
                            hideBubble()
                            try { vibrator?.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        } else {
                            showBubble()
                            try { vibrator?.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(shakeListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BubbleService onStartCommand: ${intent?.action}")
        when (intent?.action) {
            "SHOW" -> showBubble()
            "HIDE" -> hideBubble()
            "TOGGLE_RECORDING" -> {
                if (bubbleView == null) showBubble()
                if (isRecording) stopAndProcess() else startListening()
            }
            "TEST_CLARIFIER" -> {
                val q = intent.getStringExtra("question") ?: "WORK?"
                showClarifier(q) { answer ->
                    Log.i(TAG, "Clarifier answer: $answer")
                }
            }
            "ONBOARDING" -> {
                showOnboardingOverlay()
                showBubble()
                // Run the bubble's live demo script — the bubble introduces itself
                timerHandler.postDelayed({
                    BubbleDemoScript {
                        // Demo complete — remove overlay, bubble is idle
                        removeOnboardingOverlay()
                        showPipelineStage("🎤", "Close the app — I'm your app now. Tap me whenever you need to talk.")
                        timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
                    }.start()
                }, 800)
            }
        }
        return START_STICKY
    }

    // ═══════════════════════════════════════════════════════════
    // ONBOARDING OVERLAY — dark background behind bubble demo
    // ═══════════════════════════════════════════════════════════

    private var onboardingOverlay: View? = null

    private fun showOnboardingOverlay() {
        if (onboardingOverlay != null) return
        val overlay = View(this).apply {
            setBackgroundColor(0xF01A1A1E.toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(overlay, params)
            onboardingOverlay = overlay
        } catch (_: Exception) {}
    }

    private fun removeOnboardingOverlay() {
        onboardingOverlay?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            onboardingOverlay = null
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return

        // Guard: overlay permission must be granted before adding window
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show bubble — overlay permission not granted")
            android.widget.Toast.makeText(this, "Grant overlay permission first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Showing bubble")
        updateNotification("Voice dictation active")
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble, null)
        // Kill touch sounds on every view in the bubble hierarchy
        fun disableSounds(v: View) {
            v.isSoundEffectsEnabled = false
            v.isHapticFeedbackEnabled = false
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) disableSounds(v.getChildAt(i))
            }
        }
        bubbleView?.let { disableSounds(it) }

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
        var prevMoveX = 0f
        var prevMoveY = 0f
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
            // TWIST mode — route all touch events to twist handler, bypass normal gestures
            if (twistMode != TwistMode.NONE) {
                return@setOnTouchListener handleTwistTouch(event, v)
            }

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
                    prevMoveX = event.rawX
                    prevMoveY = event.rawY
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

                        // Taffy: stretch in drag direction
                        val speed = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                        val baseScale = WimApp.bubbleSize() / 100f
                        val stretch = baseScale * (1f + (speed / 800f).coerceAtMost(0.15f))
                        val squeeze = baseScale * (1f - (speed / 1600f).coerceAtMost(0.08f))
                        val bezel = bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            bezel?.scaleX = stretch
                            bezel?.scaleY = squeeze
                        } else {
                            bezel?.scaleY = stretch
                            bezel?.scaleX = squeeze
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    timerHandler.removeCallbacks(startRecordingRunnable)
                    if (isDragging) {
                        // Snap back to normal
                        val scale = WimApp.bubbleSize() / 100f
                        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
                            it.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
                        }

                        // Check if dragged to bottom edge — hide bubble
                        val screenHeight = resources.displayMetrics.heightPixels
                        val dp = resources.displayMetrics.density
                        val hideZone = screenHeight - (80 * dp).toInt()
                        val almostZone = screenHeight - (200 * dp).toInt()
                        if (params.y > hideZone) {
                            hideBubble()
                            updateNotification("Bubble hidden — tap to show")
                        } else if (checkCommandModePickup()) {
                            // Bubble dropped on highlighted text — command mode
                            return@setOnTouchListener true
                        } else if (params.y > almostZone) {
                            // Close but not enough — coach the user
                            val nudges = listOf(
                                Pair("Hey!", "Put your back into it! Drag me all the way down."),
                                Pair("So close!", "Almost there — just a little further south."),
                                Pair("Nice try!", "You gotta commit. All the way to the bottom.")
                            )
                            val nudge = nudges[(System.currentTimeMillis() % nudges.size).toInt()]
                            showPipelineStage(nudge.first, nudge.second)
                            timerHandler.postDelayed({ hideLiveTranscript() }, 2500)
                        }
                        return@setOnTouchListener true
                    }

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

        // Start session timer (always running, shows total session time)
        timerHandler.post(timerRunnable)

        // Prime the pre-roll audio buffer so first words aren't lost on tap
        if (WimApp.apiKey().isNotBlank() && WimApp.layer() >= 2) {
            if (audioRecorder == null) audioRecorder = AudioRecorder(this)
            audioRecorder?.primeBuffer()
        }
    }

    private fun hideBubble() {
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            bubbleView = null
        }
        // Re-post notification so it stays visible as the recall button
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Bubble hidden — tap to show"))
    }

    private fun startListening() {
        if (isRecording) return

        // Safety: refuse to record into password/sensitive fields
        if (WimAccessibilityService.isPasswordField) {
            Toast.makeText(this, "Password field — dictation blocked", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Blocked: password field focused")
            // Brief visual flash on bubble
            bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.apply {
                animate().alpha(0.4f).setDuration(100).withEndAction {
                    animate().alpha(1f).setDuration(200).start()
                }.start()
            }
            return
        }

        isRecording = true
        lastRawText = ""
        finalizedSegments = ""
        processed = false
        ConsoleLog.log("Recording...", "rec")

        // Suppress SpeechRecognizer beep — grab exclusive audio focus
        val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build()
        audioManager?.requestAudioFocus(focusRequest)
        timerHandler.postDelayed({
            audioManager?.abandonAudioFocusRequest(focusRequest)
        }, 600)

        Log.i(TAG, "Starting on-device recognition")
        dismissMenu()
        showLiveTranscript()
        Toast.makeText(this, getString(R.string.toast_listening), Toast.LENGTH_SHORT).show()

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
        val pulse = AlphaAnimation(1.0f, 0.6f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.startAnimation(pulse)

        // L2+: record audio for Whisper API transcription (better quality than on-device ASR)
        if (WimApp.layer() >= 2 && WimApp.apiKey().isNotBlank()) {
            try {
                if (audioRecorder == null) audioRecorder = AudioRecorder(this)
                audioRecorder?.start(quietMode = WimApp.prefs.getBoolean("quiet_mode", false))
                Log.i(TAG, "L${WimApp.layer()}: AudioRecorder started for Whisper pipeline")
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

            override fun onSoundEvent(label: String) {
                updateLiveTranscript("$lastRawText $label")
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
            // Suppress SpeechRecognizer stop beep — grab exclusive audio focus
            val stopFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
            audioManager?.requestAudioFocus(stopFocusRequest)
            timerHandler.postDelayed({
                audioManager?.abandonAudioFocusRequest(stopFocusRequest)
            }, 600)

            val textBeforeStop = lastRawText
            onDeviceRecognizer?.stop()

            // Wait another 250ms for the final result callback
            timerHandler.postDelayed({
                val onDeviceText = if (lastRawText.length > textBeforeStop.length) lastRawText else textBeforeStop

                // L4 with recorded audio: use Whisper API for better stutter transcription
                if (wavFile != null && wavFile.exists() && wavFile.length() > 44) {
                    Log.i(TAG, "L${WimApp.layer()}: Sending ${wavFile.length()} bytes to Whisper API")
                    scope.launch {
                        try {
                            val whisperResult = WhisperClient.transcribe(wavFile, patienceMode = WimApp.layer() >= 4)
                            Log.i(TAG, "Whisper (${whisperResult.asrMs}ms): ${whisperResult.text}")
                            lastWhisperLowConf = whisperResult.lowConfidenceSegments
                            if (whisperResult.lowConfidenceSegments.isNotEmpty()) {
                                Log.i(TAG, "Whisper flagged ${whisperResult.lowConfidenceSegments.size} low-confidence segments (${whisperResult.lowConfidenceSegments.count { it.blockSuspect }} block suspects)")
                            }
                            // If Whisper was 100% hallucination, don't fall back — bail.
                            // On-device ASR during silence is garbage too, and pasting garbage is worse than pasting nothing.
                            if (whisperResult.wasFullyHallucinated) {
                                Log.w(TAG, "Whisper fully hallucinated — pasting nothing")
                                showPipelineStage("∅ Silence", "Nothing to transcribe")
                                timerHandler.postDelayed({ setBubbleIdle() }, 1500)
                            } else {
                                val finalText = whisperResult.text.ifBlank { onDeviceText }
                                if (finalText.isNotBlank()) {
                                    processText(finalText)
                                } else {
                                    setBubbleIdle()
                                }
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
                    // L1 or no audio: use on-device result
                    lastWhisperLowConf = emptyList()
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


    private val tips = listOf(
        Pair("Did you know?", "Tap the ? in the app for a quick guide to all features."),
        Pair("Light or dark?", "Tap the bulb icon in the app to switch between light and dark mode."),
        Pair("Hide me!", "Drag the bubble to the bottom edge to hide. Shake to bring me back."),
        Pair("Double-tap me", "Double-tap the bubble for quick settings — size, opacity, live text.")
    )

    private fun maybeShowTip() {
        if (!WimApp.prefs.getBoolean("tips_enabled", true)) return
        val lastTip = WimApp.prefs.getLong("last_tip_time", 0L)
        val now = System.currentTimeMillis()
        // Show a tip at most once every 24 hours
        if (now - lastTip < 24 * 60 * 60 * 1000) return
        val tipIndex = WimApp.prefs.getInt("tip_index", 0) % tips.size
        val tip = tips[tipIndex]
        timerHandler.postDelayed({
            showPipelineStage(tip.first, tip.second)
            timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
        }, 2000) // 2s delay after idle so it doesn't flash during quick interactions
        WimApp.prefs.edit()
            .putLong("last_tip_time", now)
            .putInt("tip_index", tipIndex + 1)
            .apply()
    }

    private fun setBubbleIdle() {
        stopPulseRings()
        hideLiveTranscript()
        maybeShowTip()
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.apply {
            clearAnimation()
            setBackgroundResource(R.drawable.bubble_inner_knob)
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            ?.setBackgroundResource(R.drawable.bubble_bg_idle)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "IDLE"
            setTextColor(0xFF555560.toInt())
        }
        // Keep session timer ticking in idle (maroon)
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            setTextColor(0xFF9A4040.toInt())
        }
        // Re-post timer if it was stopped (restart case)
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    fun updateIdleDisplay() {
        if (isRecording || isClarifierActive) return
        setBubbleIdle()
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
    private var preClarifierSize = -1
    private var preClarifierOpacity = -1
    private var clarifierRetryCount = 0
    private var pendingClarifierWord = ""
    private var pendingClarifierQuestion = ""
    private var pendingClarifierCallback: ((Boolean?) -> Unit)? = null

    // Twist mode — voice-triggered knob rotation to adjust size or opacity
    private enum class TwistMode { NONE, SIZE, OPACITY }
    private var twistMode: TwistMode = TwistMode.NONE
    private var preTwistSize = -1
    private var preTwistOpacity = -1
    private var twistConfirmShown = false
    private val twistTimeoutRunnable = Runnable { if (twistMode != TwistMode.NONE) exitTwistMode(commit = false) }

    /**
     * Check if the bubble was dropped on a focused text field with highlighted text.
     * If yes, pick up the selection for Command Mode.
     */
    private fun checkCommandModePickup(): Boolean {
        val node = WimAccessibilityService.activeTextField ?: return false
        try {
            if (!node.refresh()) return false
        } catch (_: Exception) { return false }

        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end <= start) return false

        val fullText = node.text?.toString() ?: return false
        if (fullText.isEmpty() || end > fullText.length) return false

        val selectedText = fullText.substring(start.coerceAtLeast(0), end.coerceAtMost(fullText.length))
        if (selectedText.isBlank()) return false

        // Check bubble center is over the field's bounds
        val fieldRect = android.graphics.Rect()
        node.getBoundsInScreen(fieldRect)
        val params = bubbleParams ?: return false
        val bubbleCenterX = params.x + (bubbleView?.width ?: 0) / 2
        val bubbleCenterY = params.y + (bubbleView?.height ?: 0) / 2
        if (!fieldRect.contains(bubbleCenterX, bubbleCenterY)) return false

        // Pickup! Store selection and show in live transcript, then clarify
        commandSourceText = selectedText
        commandSelectionStart = start
        commandSelectionEnd = end

        // Show picked-up text (truncated to 3 lines ~= ~120 chars)
        val preview = if (selectedText.length > 120) selectedText.substring(0, 117) + "..." else selectedText
        showPipelineStage("📋 Picked up", preview)

        // Ask user to confirm
        showClarifier("?", "Rewrite this text?") { answer ->
            if (answer == true) {
                armCommandMode()
            } else {
                clearCommandPickup()
            }
        }
        return true
    }

    private fun armCommandMode() {
        commandModeArmed = true
        showPipelineStage("🎤 Say the command", "formal · professional · casual · friend · shorter · fix · translate")
        // Start listening immediately after clarifier dismisses
        timerHandler.postDelayed({
            if (commandModeArmed && !isRecording) startListening()
        }, 600)
    }

    private fun clearCommandPickup() {
        commandModeArmed = false
        commandSourceText = ""
        commandSelectionStart = -1
        commandSelectionEnd = -1
        hideLiveTranscript()
    }

    /**
     * Replace the stored selection in the active text field with new text.
     */
    private fun replaceSelection(newText: String): Boolean {
        val node = WimAccessibilityService.activeTextField ?: return false
        try {
            if (!node.refresh()) return false
        } catch (_: Exception) { return false }

        val fullText = node.text?.toString() ?: return false
        val start = commandSelectionStart
        val end = commandSelectionEnd
        if (start < 0 || end < start || end > fullText.length) return false

        val replaced = fullText.substring(0, start) + newText + fullText.substring(end)
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replaced)
        }
        return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun showClarifier(questionWord: String, contextQuestion: String = "", onAnswer: (Boolean?) -> Unit) {
        isClarifierActive = true
        clarifierCallback = { answer -> onAnswer(answer) }
        pendingClarifierWord = questionWord
        pendingClarifierQuestion = contextQuestion
        pendingClarifierCallback = onAnswer

        // Save current size/opacity and max out for visibility
        if (preClarifierSize < 0) {
            preClarifierSize = WimApp.bubbleSize()
            preClarifierOpacity = WimApp.bubbleOpacity()
        }
        WimApp.prefs.edit().putInt("bubble_size", 150).putInt("bubble_opacity", 100).apply()
        updateBubbleAppearance()

        // Show question on the knob — auto-size to fill, dark gray, bold
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = questionWord
            setTextColor(0xFF444450.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.0f
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            setAutoSizeTextTypeUniformWithConfiguration(8, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE

        // Show full question on live transcript bar
        if (contextQuestion.isNotBlank()) {
            val retryLabel = if (clarifierRetryCount > 0) "Still waiting..." else "Quick question"
            showPipelineStage(retryLabel, contextQuestion)
        }

        // Show split bezel (green/red) and ✓/✕
        bubbleView?.findViewById<View>(R.id.clarifierBgYes)?.visibility = View.VISIBLE
        bubbleView?.findViewById<View>(R.id.clarifierBgNo)?.visibility = View.VISIBLE
        bubbleView?.findViewById<TextView>(R.id.bezelYes)?.visibility = View.VISIBLE
        bubbleView?.findViewById<TextView>(R.id.bezelNo)?.visibility = View.VISIBLE

        // Auto-retry or dismiss after 10 seconds
        timerHandler.removeCallbacks(clarifierTimeout)
        timerHandler.postDelayed(clarifierTimeout, 10000)
    }

    private fun dismissClarifier(answer: Boolean?) {
        timerHandler.removeCallbacks(clarifierTimeout)
        isClarifierActive = false
        clarifierRetryCount = 0

        // Hide split bezel and ✓/✕
        bubbleView?.findViewById<View>(R.id.clarifierBgYes)?.visibility = View.GONE
        bubbleView?.findViewById<View>(R.id.clarifierBgNo)?.visibility = View.GONE
        bubbleView?.findViewById<TextView>(R.id.bezelYes)?.visibility = View.GONE
        bubbleView?.findViewById<TextView>(R.id.bezelNo)?.visibility = View.GONE

        // Restore normal label sizing and style
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.05f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        // Restore original size and opacity
        if (preClarifierSize > 0) {
            WimApp.prefs.edit().putInt("bubble_size", preClarifierSize).putInt("bubble_opacity", preClarifierOpacity).apply()
            updateBubbleAppearance()
            preClarifierSize = -1
            preClarifierOpacity = -1
        }

        // Show acknowledgment if user answered (not timeout)
        if (answer != null) {
            val label = bubbleView?.findViewById<TextView>(R.id.bubbleLabel)
            val bezel = bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            // Flash the checkmark, then cycle sparkle sequence
            label?.apply {
                text = if (answer == true) "✓" else "✕"
                setTextColor(if (answer == true) getColor(R.color.orange_label) else 0xFFCC4444.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
                setShadowLayer(16f, 0f, 0f, if (answer == true) 0x80FFFFFF.toInt() else 0x80CC4444.toInt())
            }
            // Quick scale-pulse animation on the bezel
            bezel?.let { v ->
                val scaleUp = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f, 1f),
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f, 1f))
                scaleUp.duration = 350
                scaleUp.interpolator = android.view.animation.OvershootInterpolator()
                scaleUp.start()
            }
            // Sparkle sequence: ✓ → ✦ → ✧ → idle
            timerHandler.postDelayed({
                label?.text = "✦"
                label?.setTextColor(getColor(R.color.orange_label)) // copper gold
            }, 400)
            timerHandler.postDelayed({
                label?.text = "✧"
                label?.setTextColor(0x88FFFFFF.toInt())
            }, 650)
            timerHandler.postDelayed({
                bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE
                setBubbleIdle()
            }, 900)
        } else {
            bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE
            setBubbleIdle()
        }

        // Fire callback
        clarifierCallback?.invoke(answer)
        clarifierCallback = null
    }

    // ═══════════════════════════════════════════════════════════
    // TWIST MODE — voice-triggered knob rotation for size/opacity
    // ═══════════════════════════════════════════════════════════

    private fun detectTwistTrigger(rawText: String): TwistMode? {
        val lower = rawText.trim().lowercase().trimEnd('.', '!', '?', ',')
        // Strict prefix match — must start with trigger phrase to avoid false positives
        if (lower.startsWith("settings size") ||
            lower.startsWith("knob size")) {
            return TwistMode.SIZE
        }
        if (lower.startsWith("settings opacity") ||
            lower.startsWith("knob opacity")) {
            return TwistMode.OPACITY
        }
        return null
    }

    private fun enterTwistMode(mode: TwistMode) {
        twistMode = mode
        twistConfirmShown = false

        // Save current size/opacity for revert
        preTwistSize = WimApp.bubbleSize()
        preTwistOpacity = WimApp.bubbleOpacity()

        // Enlarge bubble to max (clarifier size) so twist gesture is ergonomic
        WimApp.prefs.edit().putInt("bubble_size", 150).putInt("bubble_opacity", 100).apply()
        updateBubbleAppearance()

        // Swap label to mode indicator
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = if (mode == TwistMode.SIZE) "SIZE" else "DIM"
            setTextColor(0xFFC8B48A.toInt())  // cream, matches logo
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        }

        // Hide timer during twist
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.GONE
        // Ensure tick is visible (might've been hidden by confirm state)
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.visibility = View.VISIBLE

        // Rotate tick to match current value
        val currentValue = if (mode == TwistMode.SIZE) preTwistSize else preTwistOpacity
        val min = if (mode == TwistMode.SIZE) 70 else 20
        val max = if (mode == TwistMode.SIZE) 150 else 100
        val normalized = (currentValue - min).toFloat() / (max - min).toFloat()
        val angle = -90f + normalized.coerceIn(0f, 1f) * 180f
        rotateTick(angle)

        // Timeout: 6s of no touch interaction cancels
        timerHandler.removeCallbacks(twistTimeoutRunnable)
        timerHandler.postDelayed(twistTimeoutRunnable, 6000)

        Log.i(TAG, "Entered twist mode: $mode, from=$currentValue")
    }

    private fun exitTwistMode(commit: Boolean) {
        if (twistMode == TwistMode.NONE) return
        val mode = twistMode

        timerHandler.removeCallbacks(twistTimeoutRunnable)

        if (!commit) {
            // Revert to pre-twist values
            WimApp.prefs.edit()
                .putInt("bubble_size", preTwistSize)
                .putInt("bubble_opacity", preTwistOpacity)
                .apply()
            updateBubbleAppearance()
        }
        // If commit: current prefs already hold the new twisted value

        // Hide checkmark, reset tick
        bubbleView?.findViewById<TextView>(R.id.twistConfirmCheck)?.visibility = View.GONE
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.visibility = View.VISIBLE
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.rotation = 0f

        // Restore timer
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.visibility = View.VISIBLE

        twistMode = TwistMode.NONE
        twistConfirmShown = false
        preTwistSize = -1
        preTwistOpacity = -1

        Log.i(TAG, "Exited twist mode: $mode, commit=$commit")
        setBubbleIdle()
    }

    private fun rotateTick(degrees: Float) {
        val tick = bubbleView?.findViewById<View>(R.id.bubbleTick) ?: return
        tick.post {
            // Pivot is 20dp below the tick's top edge — that's the knob's center
            // (tick is 8dp tall with 2dp marginTop from 44dp knob top → knob center at 22dp)
            val dp = resources.displayMetrics.density
            tick.pivotX = tick.width / 2f
            tick.pivotY = (tick.height / 2f) + (16f * dp)
            tick.rotation = degrees
        }
    }

    /**
     * Called from the bubble's onTouchListener when twistMode is active.
     * Returns true if the event was consumed (skip normal touch handling).
     */
    private fun handleTwistTouch(event: MotionEvent, view: View): Boolean {
        if (twistMode == TwistMode.NONE) return false

        if (twistConfirmShown) {
            return handleTwistConfirmTouch(event, view)
        }

        val cx = view.width / 2f
        val cy = view.height / 2f
        val dx = event.x - cx
        val dy = event.y - cy

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Reset timeout while finger is interacting
                timerHandler.removeCallbacks(twistTimeoutRunnable)
                timerHandler.postDelayed(twistTimeoutRunnable, 6000)

                // atan2(dx, -dy): 0° = 12 o'clock, -90° = 9 o'clock, +90° = 3 o'clock
                val angleRad = kotlin.math.atan2(dx.toDouble(), (-dy).toDouble())
                val angleDeg = Math.toDegrees(angleRad).toFloat().coerceIn(-90f, 90f)

                rotateTick(angleDeg)

                // Map to value
                val normalized = (angleDeg + 90f) / 180f
                val min = if (twistMode == TwistMode.SIZE) 70 else 20
                val max = if (twistMode == TwistMode.SIZE) 150 else 100
                val value = (min + normalized * (max - min)).toInt()

                val prefKey = if (twistMode == TwistMode.SIZE) "bubble_size" else "bubble_opacity"
                WimApp.prefs.edit().putInt(prefKey, value).apply()
                updateBubbleAppearance()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                showTwistConfirm()
                return true
            }
        }
        return true  // always consume during twist mode
    }

    private fun showTwistConfirm() {
        twistConfirmShown = true
        bubbleView?.findViewById<TextView>(R.id.twistConfirmCheck)?.apply {
            visibility = View.VISIBLE
        }
        // Hide tick so the checkmark is clearly the only action
        bubbleView?.findViewById<View>(R.id.bubbleTick)?.visibility = View.INVISIBLE
        // Also hide the SIZE/DIM label while confirm is shown
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.visibility = View.GONE

        timerHandler.removeCallbacks(twistTimeoutRunnable)
        timerHandler.postDelayed(twistTimeoutRunnable, 5000)
    }

    private fun handleTwistConfirmTouch(event: MotionEvent, view: View): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true  // consume DOWN/MOVE

        val cx = view.width / 2f
        val cy = view.height / 2f
        val dx = event.x - cx
        val dy = event.y - cy
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        // Inner knob is ~44dp on 120dp view = 36% width, radius ~18% of view width
        val knobRadius = view.width * 0.20f

        // Restore label visibility (exit path resets everything)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.visibility = View.VISIBLE

        if (dist <= knobRadius) {
            exitTwistMode(commit = true)
        } else {
            exitTwistMode(commit = false)
        }
        return true
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
        // Match WiM app toggles: copper thumb, warm dark track, 0.75 scale
        liveToggle.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFC49A6C.toInt())
        liveToggle.trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF3A3530.toInt(), 0xFF2A2A2F.toInt())
        )
        liveToggle.scaleX = 0.75f
        liveToggle.scaleY = 0.75f
        liveToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("live_transcript", checked).apply()
        }

        // Layer selector
        val layerBtns = listOf(
            menuView!!.findViewById<TextView>(R.id.menuLayer1),
            menuView!!.findViewById<TextView>(R.id.menuLayer2),
            menuView!!.findViewById<TextView>(R.id.menuLayer3),
            menuView!!.findViewById<TextView>(R.id.menuLayer4)
        )
        val currentLayer = WimApp.layer()
        layerBtns.forEachIndexed { i, btn ->
            val layerVal = i + 1
            btn.setTextColor(if (layerVal == currentLayer) getColor(R.color.orange_label) else 0xFF888890.toInt())
            btn.setOnClickListener {
                WimApp.prefs.edit().putInt("layer", layerVal).apply()
                layerBtns.forEach { b -> b.setTextColor(0xFF888890.toInt()) }
                btn.setTextColor(getColor(R.color.orange_label))
                updateIdleDisplay()
            }
        }

        menuView!!.findViewById<TextView>(R.id.menuResetTimer).setOnClickListener {
            sessionStartTime = System.currentTimeMillis()
            bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.text = "0:00"
            dismissMenu()
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
        if (!enabled) return
        if (liveTextView != null) return

        val tv = TextView(this).apply {
            setBackgroundColor(0xFF222226.toInt())
            setTextColor(0xFFD4D0C8.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(20, 12, 20, 12)
            maxLines = 3
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

    fun showPipelineStage(stage: String, detail: String) {
        if (liveTextView == null) {
            // Force-show the transcript bar for pipeline display
            val bp = bubbleParams ?: return
            val tv = TextView(this).apply {
                setBackgroundColor(0xFF222226.toInt())
                setTextColor(0xFFD4D0C8.toInt())
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(20, 12, 20, 12)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            liveTextView = tv
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
            try { windowManager?.addView(tv, ltParams) } catch (_: Exception) {}
        }
        val styled = android.text.SpannableStringBuilder()
        styled.append(stage, android.text.style.ForegroundColorSpan(0xFFC8B48A.toInt()), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.append("\n")
        styled.append(detail)
        liveTextView?.text = styled
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
                Toast.makeText(this, getString(R.string.toast_redo_warning), Toast.LENGTH_LONG).show()
                redoCount = 0
            }
        } else {
            redoCount = 0
        }
        recentTexts.add(text)
        if (recentTexts.size > 5) recentTexts.removeAt(0)
    }

    /**
     * Process Command Mode voice input: send source text + command to GPT,
     * replace the original selection with the result.
     */
    private fun processCommand(command: String) {
        val source = commandSourceText
        Log.i(TAG, "CMD: transform '$source' with command: '$command'")
        showPipelineStage("⟳ Transforming...", command)

        scope.launch {
            try {
                val systemPrompt = "You are a text transformation assistant. The user highlighted some text and spoke a command to modify it. Apply the command and return ONLY the transformed text, nothing else. Preserve the meaning. Do not add explanations, quotes, or prefixes."
                val userContent = "TEXT:\n$source\n\nCOMMAND: $command"

                val apiKey = WimApp.apiKey()
                val idToken = AuthManager.getIdToken()

                val result: String? = if (idToken != null) {
                    // Use backend proxy — we'll repurpose reconstruct endpoint with raw=source, a synthetic layer
                    // Simpler: go direct through OpenAI with the user's key, or build a proper cmd endpoint later
                    if (apiKey.isNotBlank()) callOpenAiTransform(apiKey, systemPrompt, userContent) else null
                } else {
                    if (apiKey.isNotBlank()) callOpenAiTransform(apiKey, systemPrompt, userContent) else null
                }

                if (result.isNullOrBlank()) {
                    Log.e(TAG, "CMD: transform failed")
                    showPipelineStage("✕ Failed", "Try again")
                    timerHandler.postDelayed({ setBubbleIdle() }, 2000)
                    clearCommandPickup()
                    return@launch
                }

                val replaced = replaceSelection(result)
                if (replaced) {
                    Log.i(TAG, "CMD: replaced selection with '$result'")
                    val wordCount = result.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                    showPipelineStage("✓ Rewrote $wordCount words", result.take(80))
                } else {
                    Log.e(TAG, "CMD: replaceSelection failed, copying to clipboard")
                    val clip = android.content.ClipData.newPlainText("wim", result)
                    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    showPipelineStage("✓ Copied", "Paste manually")
                }
                timerHandler.postDelayed({ setBubbleIdle() }, 1800)
            } catch (e: Exception) {
                Log.e(TAG, "CMD: exception: ${e.message}")
                showPipelineStage("✕ Error", e.message ?: "")
                timerHandler.postDelayed({ setBubbleIdle() }, 2000)
            } finally {
                clearCommandPickup()
            }
        }
    }

    private suspend fun callOpenAiTransform(apiKey: String, systemPrompt: String, userContent: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val http = okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                val messages = org.json.JSONArray().apply {
                    put(org.json.JSONObject().put("role", "system").put("content", systemPrompt))
                    put(org.json.JSONObject().put("role", "user").put("content", userContent))
                }
                val body = org.json.JSONObject().apply {
                    put("model", "gpt-4o")
                    put("messages", messages)
                    put("temperature", 0.3)
                }
                val req = okhttp3.Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = http.newCall(req).execute()
                val respBody = resp.body?.string() ?: return@withContext null
                if (!resp.isSuccessful) return@withContext null
                org.json.JSONObject(respBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun processText(rawText: String) {
        val pipelineStart = System.currentTimeMillis()
        val asrMs = pipelineStart - recordingStartTime
        Log.i(TAG, "Processing (asr=${asrMs}ms): $rawText")
        checkRedo(rawText)
        setBubbleProcessing()

        // TWIST mode voice triggers — the transcript is a knob control command, not text
        val triggered = detectTwistTrigger(rawText)
        if (triggered != null) {
            Log.i(TAG, "Twist trigger detected: $triggered")
            enterTwistMode(triggered)
            return
        }

        // COMMAND mode: the voice is a transform command, not text to dictate
        if (commandModeArmed) {
            processCommand(rawText)
            return
        }

        // INSTANT mode: bypass everything, paste raw ASR text directly
        if (WimApp.prefs.getBoolean("instant_mode", false)) {
            Log.i(TAG, "INSTANT mode: pasting raw text, no processing")
            WimAccessibilityService.instance?.injectText(rawText)
            setBubbleIdle()
            return
        }

        val layer = WimApp.layer()
        val tone = WimApp.tone()
        val situation = WimApp.situation()
        ConsoleLog.log("Processing... [L$layer ${tone.uppercase()} $situation]", "info")
        val profile = WimApp.profile()

        // Voice shortcuts — expand triggers before processing
        var expandedText = rawText
        val shortcuts = WimApp.prefs.getString("voice_shortcuts", null)
        if (!shortcuts.isNullOrBlank()) {
            for (line in shortcuts.split("\n")) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val trigger = parts[0].trim()
                    val replacement = parts[1].trim()
                    if (trigger.isNotBlank() && expandedText.lowercase().contains(trigger.lowercase())) {
                        expandedText = expandedText.replace(Regex("(?i)${Regex.escape(trigger)}"), replacement)
                        Log.i(TAG, "Voice shortcut: '$trigger' → '$replacement'")
                    }
                }
            }
        }

        // Strip Whisper block hallucinations — applies to ALL layers
        val deHallucinated = DisfluencyFilter.stripBlockHallucinations(expandedText, lastWhisperLowConf)
        if (deHallucinated != expandedText) {
            Log.i(TAG, "Block hallucination stripped: ${expandedText.length - deHallucinated.length} chars")
            ConsoleLog.log("Block hallucination stripped: ${expandedText.length - deHallucinated.length} chars", "warn")
        }

        // Pre-clean: strip disfluencies from raw ASR text before GPT sees it.
        // L4 stutter-aware path needs to SEE the disfluencies to reason about them,
        // so we skip the pre-filter at L4 and let the clinical prompt handle it.
        val rawWordCount = deHallucinated.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val fillerWords = profile.getFillerWords()
        showPipelineStage("\u29BB Cleaning up...", "Removing disfluencies & fillers")
        val preCleaned = if (layer >= 4) deHallucinated else DisfluencyFilter.filter(deHallucinated, fillerWords)
        val cleanWordCount = preCleaned.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val stripped = rawWordCount - cleanWordCount
        // Track filler count for VU meter — resets at 150
        if (stripped > 0) {
            val current = WimApp.prefs.getInt("filler_count", 0) + stripped
            WimApp.prefs.edit().putInt("filler_count", if (current >= 150) 0 else current).apply()
        }
        Log.i(TAG, "Pre-cleaned: $preCleaned")
        ConsoleLog.log("Raw: \"${rawText.take(80)}${if (rawText.length > 80) "..." else ""}\"", "raw")
        if (stripped > 0) {
            ConsoleLog.log("Disfluency filter: $stripped removed (${rawWordCount}w -> ${cleanWordCount}w)", "info")
        }

        if (layer < 2) {
            // L1: disfluency-filtered text + profile corrections (no GPT needed)
            Log.i(TAG, "L1 mode, applying profile corrections then injecting")
            val corrections = profile.getCorrections()
            val sessionNum = sessionHistory?.count() ?: 0
            var l1Text = preCleaned
            for ((wrong, right) in corrections) {
                val pattern = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(l1Text)) {
                    l1Text = pattern.replace(l1Text, right)
                    profile.markCorrectionUsed(wrong, sessionNum)
                    Log.i(TAG, "L1 correction: \"$wrong\" → \"$right\"")
                }
            }
            // Decay sweep every 30 sessions
            if (sessionNum > 0 && sessionNum % 30 == 0) {
                profile.decayStaleCorrections(sessionNum)
            }
            if (stripped > 0 || l1Text != preCleaned) {
                val corrCount = if (l1Text != preCleaned) " + corrections" else ""
                showPipelineStage("\u2713 Cleaned", "$stripped words stripped$corrCount \u00B7 ${rawWordCount}w \u2192 ${l1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}w")
            }
            ConsoleLog.log("-> \"${l1Text.take(80)}${if (l1Text.length > 80) "..." else ""}\"  [${l1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}w]", "out")
            WimAccessibilityService.instance?.injectText(l1Text)
            val totalMs = System.currentTimeMillis() - recordingStartTime
            ConsoleLog.log("[L1] paste_clean | ${totalMs}ms (asr:${asrMs})", "info")
            sessionHistory?.record(
                rawText = rawText,
                cleanText = l1Text,
                tone = tone,
                layer = 1,
                situation = situation,
                asrMs = asrMs,
                reconMs = 0,
                totalMs = totalMs,
                falconApproved = true,
                riskFlags = emptyList()
            )
            SessionUpdateBus.emit()
            // Test automation: capture result
            if (BuildConfig.DEBUG) {
                WimTestReceiver.lastResult = org.json.JSONObject().apply {
                    put("status", "ok")
                    put("raw_text", rawText)
                    put("clean_text", l1Text)
                    put("tone", tone)
                    put("layer", 1)
                    put("situation", situation)
                    put("asr_ms", asrMs)
                    put("recon_ms", 0)
                    put("total_ms", totalMs)
                    put("falcon_approved", true)
                    put("risk_flags", org.json.JSONArray())
                    put("wer", (sessionHistory?.computeWer(rawText, l1Text) ?: 0f).toDouble())
                }
            }
            setBubbleIdle()
            return
        }

        // L2+: reconstruct via GPT (with pre-cleaned input)
        if (stripped > 0) {
            showPipelineStage("\u2713 $stripped disfluencies removed", "${rawWordCount}w \u2192 ${cleanWordCount}w")
        }

        // Apply profile corrections at L2+ client-side BEFORE GPT sees the text.
        // Fixes the Lavrentiy bug where corrections were only applied at L3+.
        val allCorrections = profile.getCorrections()
        var correctedText = preCleaned
        val sessionNum = sessionHistory?.count() ?: 0
        for ((wrong, right) in allCorrections) {
            val pattern = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(correctedText)) {
                correctedText = pattern.replace(correctedText, right)
                profile.markCorrectionUsed(wrong, sessionNum)
                Log.i(TAG, "L$layer correction: \"$wrong\" → \"$right\"")
            }
        }

        // L3+ gets profile vocabulary and corrections injected into the prompt
        val vocabulary = if (layer >= 3) profile.getVocabulary() else emptyList()
        val corrections = if (layer >= 3) profile.getCorrections() else emptyMap()

        scope.launch {
            try {
                val layerLabel = when (layer) {
                    2 -> "generic cleanup"
                    3 -> "+ your vocabulary"
                    4 -> "stutter-aware"
                    else -> "processing"
                }
                showPipelineStage("\u29BB Reconstructing intent...", "$tone tone \u00B7 L$layer $layerLabel")

                // TurboTax engagement: animated stage messages for L4 (long processing)
                val turboTaxJob = if (layer >= 4) {
                    scope.launch {
                        val stages = listOf(
                            "\u29BB Analyzing speech patterns..." to "Mapping phoneme difficulty",
                            "\u29BB Detecting disfluencies..." to "Blocks, prolongations, repetitions",
                            "\u29BB Building phoneme context..." to "Your personal speech profile",
                            "\u29BB Reconstructing intent..." to "Stutter-aware AI processing",
                            "\u29BB Validating meaning..." to "Making sure nothing was lost"
                        )
                        for ((title, sub) in stages) {
                            showPipelineStage(title, sub)
                            kotlinx.coroutines.delay(1200)
                        }
                    }
                } else null

                // Look-behind: scrape text already in the target field so the reconstructor
                // can disambiguate using conversational context. Skipped for password fields.
                val precedingContext = WimAccessibilityService.instance?.getPreCursorContext() ?: ""
                if (precedingContext.isNotBlank()) {
                    Log.i(TAG, "Look-behind context: ${precedingContext.length} chars")
                }

                ConsoleLog.log("Reconstructing... [L$layer $tone gpt-4o]", "info")
                Log.i(TAG, "Calling reconstruction (L$layer, $tone, sit=$situation)...")
                val reconResult = ReconstructClient.reconstruct(
                    rawText = correctedText,
                    tone = tone,
                    layer = layer,
                    situation = situation,
                    vocabulary = vocabulary,
                    corrections = corrections,
                    fillerWords = fillerWords,
                    triggerWords = profile.getTriggerWords(),
                    onsetWeights = profile.getOnsetWeights(),
                    covertProfile = profile.getCovertProfile(),
                    whisperLowConf = lastWhisperLowConf,
                    whisperDisagreements = emptyList(),
                    speechSeverityMod = 0.0,
                    precedingContext = precedingContext
                )
                turboTaxJob?.cancel()
                Log.i(TAG, "Reconstruction (${reconResult.reconMs}ms): ${reconResult.cleanText}")
                ConsoleLog.log("Recon: ${reconResult.reconMs}ms | Falcon: ${if (reconResult.falconApproved) "OK" else "REJECTED"}", "info")

                // Post-reconstruction LLM leak filter (ported from Лаврентий)
                // Catches chatbot responses / meta-commentary that GPT sometimes emits
                var outputText = reconResult.cleanText
                var leakDetected = false
                val reconLower = outputText.trim().lowercase()
                val llmLeakPatterns = listOf(
                    "i'm sorry", "i apologize", "i didn't catch that",
                    "could you please clarify", "could you please repeat",
                    "could you say that again", "i'm not sure what you meant",
                    "please clarify", "please provide more context",
                    "i cannot assist", "i can't assist", "i can't help",
                    "as an ai", "as a language model",
                    "please ensure that you", "ensure that you construct",
                    "i'd be happy to help",
                    "no output", "identified as hallucinated", "hallucinated filler",
                    "no transcription", "unable to transcribe", "no speech detected",
                    "[no output", "[hallucin", "[unable to"
                )
                for (pattern in llmLeakPatterns) {
                    if (reconLower.startsWith(pattern) || pattern in reconLower) {
                        Log.w(TAG, "LLM leak detected: \"${outputText.take(80)}\" — using raw")
                        ConsoleLog.log("LLM leak detected — using raw", "error")
                        leakDetected = true
                        break
                    }
                }

                // Length explosion guard: if reconstruction is >2.5x longer than input,
                // GPT fabricated content instead of cleaning speech
                if (!leakDetected && cleanWordCount >= 3) {
                    val reconWordCount = outputText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                    val lenRatio = reconWordCount.toFloat() / cleanWordCount.toFloat()
                    if (lenRatio > 2.5f) {
                        Log.w(TAG, "LLM length explosion: ${cleanWordCount}w → ${reconWordCount}w (${lenRatio}x) — using raw")
                        ConsoleLog.log("Length explosion: ${cleanWordCount}w -> ${reconWordCount}w (${lenRatio}x) — using raw", "error")
                        leakDetected = true
                    }
                }

                if (leakDetected) {
                    outputText = correctedText
                }

                val finalWordCount = outputText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                val falconLabel = if (leakDetected) "\u26A0 LLM leak filtered" else if (reconResult.falconApproved) "\u2713 Meaning verified" else "\u26A0 Using original"
                showPipelineStage(falconLabel, "${rawWordCount}w \u2192 ${finalWordCount}w \u00B7 ${reconResult.reconMs}ms")

                // Brief pause so user can see the final stage
                kotlinx.coroutines.delay(800)

                if (WimAccessibilityService.instance != null) {
                    WimAccessibilityService.instance!!.injectText(outputText)
                } else {
                    Log.e(TAG, "No accessibility service instance!")
                    Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
                }

                // L3+: learn from reconstruction results (skip if leak was filtered)
                if (layer >= 3 && !leakDetected) {
                    profile.learnFromReconstruction(preCleaned, outputText)
                }

                val outWords = outputText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                ConsoleLog.log("-> \"${outputText.take(80)}${if (outputText.length > 80) "..." else ""}\"  [${outWords}w]", "out")
                // Record session
                val totalMs = System.currentTimeMillis() - recordingStartTime
                sessionHistory?.record(
                    rawText = preCleaned,
                    cleanText = outputText,
                    tone = tone,
                    layer = layer,
                    situation = situation,
                    asrMs = asrMs,
                    reconMs = reconResult.reconMs,
                    totalMs = totalMs,
                    falconApproved = reconResult.falconApproved,
                    riskFlags = reconResult.riskFlags
                )
                SessionUpdateBus.emit()
                val flagStr = if (reconResult.riskFlags.isNotEmpty()) " flags:[${reconResult.riskFlags.joinToString(",")}]" else ""
                ConsoleLog.log("[L$layer] paste_clean | ${totalMs}ms (asr:${asrMs} recon:${reconResult.reconMs})$flagStr", "info")

                // Test automation: capture result
                if (BuildConfig.DEBUG) {
                    WimTestReceiver.lastResult = org.json.JSONObject().apply {
                        put("status", "ok")
                        put("raw_text", rawText)
                        put("pre_cleaned", preCleaned)
                        put("clean_text", outputText)
                        put("tone", tone)
                        put("layer", layer)
                        put("situation", situation)
                        put("asr_ms", asrMs)
                        put("recon_ms", reconResult.reconMs)
                        put("total_ms", totalMs)
                        put("falcon_approved", reconResult.falconApproved)
                        put("leak_filtered", leakDetected)
                        put("risk_flags", org.json.JSONArray(reconResult.riskFlags))
                        put("wer", (sessionHistory?.computeWer(rawText, outputText) ?: 0f).toDouble())
                        put("disfluencies_stripped", stripped)
                    }
                }

                setBubbleIdle()
            } catch (e: Exception) {
                Log.e(TAG, "Reconstruction error: ${e.message}", e)
                ConsoleLog.log("Reconstruction failed: ${e.message?.take(60)}", "error")
                // Fallback: inject pre-cleaned text
                WimAccessibilityService.instance?.injectText(preCleaned)
                // Still record the session even on API failure
                val totalMs = System.currentTimeMillis() - recordingStartTime
                sessionHistory?.record(
                    rawText = preCleaned,
                    cleanText = preCleaned,
                    tone = tone,
                    layer = layer,
                    situation = situation,
                    asrMs = asrMs,
                    reconMs = 0,
                    totalMs = totalMs,
                    falconApproved = false,
                    riskFlags = listOf("reconstruction_failed")
                )
                SessionUpdateBus.emit()
                // Test automation: capture error
                if (BuildConfig.DEBUG) {
                    WimTestReceiver.lastResult = org.json.JSONObject().apply {
                        put("status", "error")
                        put("error", e.message)
                        put("raw_text", rawText)
                        put("pre_cleaned", preCleaned)
                        put("clean_text", preCleaned)
                        put("tone", tone)
                        put("layer", layer)
                        put("situation", situation)
                        put("total_ms", totalMs)
                        put("falcon_approved", false)
                        put("risk_flags", org.json.JSONArray(listOf("reconstruction_failed")))
                    }
                }
                setBubbleIdle()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiM Voice Dictation",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Keeps the voice dictation bubble active"
            setSound(null, null)
            enableVibration(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String = "Voice dictation active"): Notification {
        val showIntent = Intent(this, BubbleService::class.java).apply { action = "SHOW" }
        val pendingIntent = android.app.PendingIntent.getService(
            this, 0, showIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("What I Meant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        instance = null
        sensorManager?.unregisterListener(shakeListener)
        dismissMenu()
        hideLiveTranscript()
        dafPlayer?.stop()
        dafPlayer = null
        audioRecorder?.release()
        audioRecorder = null
        hideBubble()
        onDeviceRecognizer?.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
