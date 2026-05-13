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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BubbleService : Service() {

    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null
    // "Hey WiM" hotword — opt-in continuous mic listener that triggers
    // a recording when it hears the wake phrase. Mutually exclusive with
    // active recording (only one consumer can hold the mic).
    private var hotwordListener: HotwordListener? = null

    // L4 multi-pass decoder needs the recorded WAV after reconstruction
    // finishes so the clarifier check can run cross-pass disagreement
    // analysis. Cleaned up in setBubbleIdle / before next recording.
    // @Volatile because the MultiPassDecoder coroutine reads this on an
    // IO worker while setBubbleIdle (main thread) can null+delete it.
    @Volatile
    private var lastWavFile: java.io.File? = null

    // Patent-clean word-highlight overlay (claim 13) — separate window
    // positioned over the host app's focused text field while a clarifier
    // cycle runs. Read-only on host state (no selection, no cursor move),
    // so the wherein-clause of claim 1 stays satisfied.
    private var wordHighlightView: WordHighlightView? = null
    private var wordHighlightParams: WindowManager.LayoutParams? = null
    private var lastShakeTime = 0L
    private var shakeCount = 0
    // Rising-edge state for the shake detector — true while gForce is
    // sustained above the high threshold; flips back to false only after
    // gForce drops below the release threshold. Each false → true edge
    // counts as one discrete shake event, but only if at least
    // SHAKE_MIN_QUIET_MS of stillness preceded the rising edge — that
    // prevents a single physical shake (which oscillates with brief
    // gForce dips) from registering as two separate events.
    private var inShakeBurst = false
    private var shakeBurstEndTime = 0L
    private val SHAKE_MIN_QUIET_MS = 280L

    private var windowManager: WindowManager? = null
    var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var preJumpX = 0
    private var preJumpY = 0
    private var facehuggerActive = false
    private var audioPlaybackCallback: android.media.AudioManager.AudioPlaybackCallback? = null
    private var scoReceiver: android.content.BroadcastReceiver? = null
    private var isRecording = false
        set(value) {
            if (field != value) {
                field = value
                RecordingStateBus.emit(value)
            }
        }
    private var vibrator: Vibrator? = null
    private var onDeviceRecognizer: OnDeviceRecognizer? = null
    private var moonshineEngine: MoonshineEngine? = null
    private var whisperLocalEngine: WhisperLocalEngine? = null
    private var bubblePersona: BubblePersona? = null
    private var bubbleAffect: BubbleAffect? = null
    /** Active onboarding demo (Phase 7). Tap handler skips it on touch. */
    private var activeDemoScript: BubbleDemoScript? = null
    private var activeDemoFinish: (() -> Unit)? = null
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
    private var liveTextContainer: android.widget.FrameLayout? = null
    private var lastTapTime = 0L
    private val DOUBLE_TAP_TIMEOUT = 300L
    // Press-and-hold threshold — 500ms is well above normal tap durations
    // (100-300ms) so accidental brushes never trigger record. Anything
    // shorter races with toggle-tap. Hold past this threshold = enter
    // hold-mode and start recording; release = stop. Drag during hold cancels.
    private val HOLD_THRESHOLD_MS = 500L
    private var holdModeActive = false
    private var holdStartRunnable: Runnable? = null
    private var singleTapRunnable: Runnable? = null
    // Deferred single-tap → start recording, with the DOUBLE_TAP_TIMEOUT
    // window allowing a second tap to pre-empt and run the double-tap
    // action (open menu). Re-checks isRecording / isDragging so a stale
    // tap that should have been canceled still no-ops cleanly.
    private val deferredSingleTapAction = Runnable {
        if (!isRecording) {
            startPulseRings()
            startListening()
        }
    }
    private val recentTexts = mutableListOf<String>()
    private var redoCount = 0
    private var isWorkMode = false  // false=personal, true=work
    private var isClarifierActive = false
    private var clarifierCallback: ((Boolean?) -> Unit)? = null

    // Regenerate state — set after a successful L2/L3/L4 paste so the user can
    // re-roll the phrasing by tapping the regenerate icon on the lower bezel.
    // Cleared when stale (after timeout) or after the user records again.
    private data class RegenContext(
        val rawText: String,
        val correctedText: String,
        val tone: String,
        val layer: Int,
        val situation: String,
        val precedingContext: String,
        val languageCode: String,
        val rateGapDetected: Boolean,
    )
    private var lastRegenCtx: RegenContext? = null
    private val previousOutputs = mutableListOf<String>()
    private var isRegenAvailable: Boolean = false
    private var regenHideJob: Job? = null
    private var isRegenInFlight: Boolean = false
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
            // Skip writing when SEND-ready is active — that state owns the
            // bubbleTime TextView for its countdown. Without this guard the
            // session timer flickers in between countdown ticks (5 → session
            // time → 4 → session time → 3 …).
            if (isSendReady) {
                timerHandler.postDelayed(this, 1000)
                return
            }
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
        // Two channels: routine foreground-service status (no badge, no sound,
        // user trains to ignore) vs permission-broken alerts (badge ON, user
        // sees a real signal). Single channel previously meant the launcher
        // icon always carried a "1" badge regardless of whether anything
        // actually needed attention.
        private const val CHANNEL_ID = "wim_bubble"            // routine status, no badge
        private const val ALERT_CHANNEL_ID = "wim_bubble_alert" // permission-missing alerts, badge ON
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        var instance: BubbleService? = null
            private set

        /**
         * Screen-space rects of the three dock slots in MainActivity, keyed
         * by theme name. Populated from MainActivity.onResume and cleared on
         * pause. When non-null AND the user releases a bubble drag inside
         * one of these rects, the bubble "parks" — theme swaps to that slot
         * and the bubble animates into the dock then hides.
         */
        var dockSlotRects: Map<String, android.graphics.Rect>? = null

        /**
         * MainActivity subscribes so it can ghost-out the currently-picked-up
         * dock slot. Called on every showBubble/hideBubble transition.
         * Cleared on MainActivity.onPause so we don't leak the activity.
         */
        var onBubbleVisibilityChanged: (() -> Unit)? = null
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    // Public wrappers for onboarding demo script
    fun setBubbleIdlePublic() = setBubbleIdle()
    fun showMenuPublic() = showMenu()
    fun dismissMenuPublic() = dismissMenu()
    fun hideLiveTranscriptPublic() = hideLiveTranscript()

    /**
     * Synthetic clarifier entry — spec §6 Phase 2 EARLIEST DEMO MILESTONE.
     * Called from `WimTestReceiver` via `trigger_clarifier` broadcast.
     * Bypasses ASR + Clarifier.evaluate; the cycle UX is exercised
     * directly with caller-supplied flagged words.
     */
    fun triggerClarifierForTest(flags: List<FlaggedWord>) {
        if (!BuildConfig.DEBUG) return
        // advance() rejects Idle→Injected (forbidden). forceTo() bypasses the
        // matrix so the legal Injected→Clarifying transition inside
        // ClarifierController.enter() can proceed.
        BubbleStateBus.forceTo(BubbleState.Injected(System.currentTimeMillis(), SuccessCue.PASTE))
        enterClarifierCycle(flags, autoSendEligible = false)
    }

    /**
     * Wire-up for the Clarifier cycle. Called from `processText` after a
     * successful inject when [Clarifier.evaluate] returned ≥1 flag.
     * Production-active at L2+; the `trigger_clarifier` test broadcast
     * remains available for dev/QA.
     */
    private fun enterClarifierCycle(flags: List<FlaggedWord>, autoSendEligible: Boolean) {
        // Visual cue marking the transition INTO claim 1's "bidirectional
        // clarification state" — all four bezel arcs flash bright orange
        // for ~320ms, then normal rendering resumes. Strengthens the
        // claim's "entering a bidirectional clarification state" reading.
        bubbleView?.findViewById<BezelStageArcView>(R.id.bubbleStageArc)
            ?.flashAllArcs(durationMs = 320L)
        ClarifierController.enter(
            flaggedWords = flags,
            injectedText = WimAccessibilityService.lastInjectedTextSnapshot(),
            autoSendEligible = autoSendEligible,
            onLabelUpdate = { flagged, idx, total -> paintClarifierLabel(flagged, idx, total) },
            onExit = { eligible ->
                // Release the clarifier's hold on the transcript bar so the
                // next pipeline run can write to it.
                TranscriptBarController.clear(TranscriptBarController.Priority.CLARIFIER)
                // Restore the bubble's normal post-inject UI.
                if (eligible) setBubbleSendReady() else setBubbleIdle()
            },
        )
    }

    /** Spec §2 row 5: clarifier owns bubbleLabel + bubbleTime exclusively
     *  while [BubbleState.Clarifying]. Format: `DUNKEN?` + `1/3`. */
    private fun paintClarifierLabel(flag: FlaggedWord, idx: Int, total: Int) {
        val view = bubbleView ?: return
        val gold = 0xFFC8B48A.toInt()
        view.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "${flag.originalWord.uppercase().take(8)}?"
            setTextColor(gold)
        }
        view.findViewById<TextView>(R.id.bubbleTime)?.apply {
            text = "${idx + 1}/$total"
            setTextColor(gold)
        }
        // Top candidate hint + DEFER affordance via TranscriptBarController
        // — surfaces all three decision modalities (claim 1: approve /
        // reject / defer) in the user-facing hint, with the gesture for
        // each. "← yes" / "× no" map to tap-left / tap-right; "↓ hold = skip"
        // teaches the long-press-to-defer gesture inline.
        val candidate = flag.candidates.firstOrNull() ?: "?"
        val hint = "did you mean → $candidate · ↓ hold = skip"
        TranscriptBarController.show(TranscriptBarController.Priority.CLARIFIER, hint)
    }

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
        if (isRecording) return
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
                // L4 uses whisper-1; L1-cloud uses whisper-1; L2/L3 cloud
                // fallback stays on gpt-4o-transcribe.
                val whisperModel = when {
                    WimApp.layer() >= 4 -> "whisper-1"
                    WimApp.layer() < 2 && WimApp.l1CloudAsr() -> "whisper-1"
                    else -> "gpt-4o-transcribe"
                }
                val l1CloudFastPath = WimApp.layer() < 2 && WimApp.l1CloudAsr()
                val whisperResult = WhisperClient.transcribe(
                    wavFile,
                    patienceMode = WimApp.layer() >= 4,
                    model = whisperModel,
                    verboseSegments = !l1CloudFastPath,
                    language = WimApp.appLanguage(),
                )
                Log.i("WiM", "Test audio Whisper ($whisperModel, ${whisperResult.asrMs}ms): ${whisperResult.text}")
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
        // Restore session start from prefs if present, else start fresh now.
        // MainActivity's Sessions header reads this prefs key for the timer.
        val storedStart = WimApp.prefs.getLong("session_start_time", 0L)
        if (storedStart > 0L) {
            sessionStartTime = storedStart
        } else {
            sessionStartTime = System.currentTimeMillis()
            WimApp.prefs.edit().putLong("session_start_time", sessionStartTime).apply()
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

        // Audio playback monitor — logs ANY sound played on the device so we can detect chirps
        val apCb = object : android.media.AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                if (configs.isNotEmpty()) {
                    Log.w(TAG, "CHIRP_MONITOR: ${configs.size} playback(s) detected")
                    for (c in configs) {
                        Log.w(TAG, "CHIRP_MONITOR: usage=${c.audioAttributes.usage} content=${c.audioAttributes.contentType}")
                    }
                }
            }
        }
        audioPlaybackCallback = apCb
        audioManager?.registerAudioPlaybackCallback(apCb, null)

        onDeviceRecognizer = OnDeviceRecognizer(this)
        moonshineEngine = MoonshineEngine(this)
        whisperLocalEngine = WhisperLocalEngine(this)
        sessionHistory = SessionHistory(this)
        // FSM observer — Phase 1 routes pipeline-stage labels through it.
        // Phase 3 layers the affect catalog on top.
        bubblePersona = BubblePersona().also { it.bind(scope) }
        // Phase 3 affects: RESTING / LEANING_FORWARD / TA_DA / HEAD_TILT
        // + clarifier trigger pulse + approve/reject flashes.
        bubbleAffect = BubbleAffect(
            bubbleViewProvider = { bubbleView },
            vibrator = vibrator,
        ).also { it.bind(scope) }

        // Clarifier voice commands — claim 12(ii). Spec §2 row 3 priority:
        // claim only when state is Clarifying; primary dictation otherwise.
        VoiceCommandRouter.register("clarifier") { utterance ->
            if (BubbleStateBus.current() !is BubbleState.Clarifying) return@register false
            val token = utterance.trim().lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
            when (token) {
                "yes", "approve", "ok", "confirm" -> {
                    ClarifierController.approve(); true
                }
                "no", "reject", "cancel", "nope" -> {
                    ClarifierController.reject(); true
                }
                "next", "skip" -> {
                    ClarifierController.next(); true
                }
                else -> false
            }
        }
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
        scoReceiver = object : android.content.BroadcastReceiver() {
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
        scoReceiver?.let { registerReceiver(it, android.content.IntentFilter(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) }

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
        val accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "shake: no accelerometer sensor available")
            return
        }
        Log.i(TAG, "shake: setupShakeDetector starting, accelerometer=${accelerometer.name}")

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

                if (!WimApp.prefs.getBoolean("shake_enabled", true)) return

                // Rising-edge detection: a "shake event" is one sustained
                // burst of high gForce, regardless of how many oscillation
                // peaks it contains. The previous time-based debounce kept
                // refreshing on every peak inside a continuous shake, so
                // shakeCount never advanced past 1.
                //
                // Asymmetric thresholds — first shake hard enough to
                // prevent accidents (set-down jolt, pocket movement),
                // follow-up easier. Release threshold is comfortably
                // below resting gravity (~1.0) so a brief pause between
                // shakes registers reliably.
                val highThreshold = if (shakeCount == 0) 2.6 else 2.0
                val releaseThreshold = 1.5
                if (gForce > 1.4) Log.i(
                    TAG,
                    "shake gForce=${"%.2f".format(gForce)} count=$shakeCount " +
                            "inBurst=$inShakeBurst high=$highThreshold"
                )

                val now = System.currentTimeMillis()

                if (gForce > highThreshold && !inShakeBurst) {
                    // Reject rising edges that come too soon after the
                    // previous burst ended — that's a mid-shake oscillation
                    // dip, not a discrete second shake.
                    if (shakeBurstEndTime > 0 && now - shakeBurstEndTime < SHAKE_MIN_QUIET_MS) {
                        inShakeBurst = true
                        return
                    }
                    // Rising edge of a new shake event.
                    inShakeBurst = true
                    if (now - lastShakeTime > 1500) {
                        shakeCount = 1
                    } else {
                        shakeCount++
                    }
                    lastShakeTime = now
                    Log.i(TAG, "shake EDGE shakeCount→$shakeCount")

                    if (shakeCount >= 2) {
                        shakeCount = 0
                        try {
                            vibrator?.vibrate(
                                android.os.VibrationEffect.createOneShot(
                                    80,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                )
                            )
                        } catch (_: Exception) {
                        }
                        timerHandler.post {
                            if (bubbleView == null) {
                                showBubble()
                            } else {
                                hideBubble()
                            }
                        }
                    }
                } else if (gForce < releaseThreshold && inShakeBurst) {
                    // Falling edge — shake burst ended; mark the time so the
                    // next rising edge has to wait through a real quiet gap.
                    inShakeBurst = false
                    shakeBurstEndTime = now
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(shakeListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BubbleService onStartCommand: ${intent?.action}")
        when (intent?.action) {
            "SHOW" -> {
                val cx = intent.getIntExtra("spawn_cx", Int.MIN_VALUE)
                val cy = intent.getIntExtra("spawn_cy", Int.MIN_VALUE)
                if (cx != Int.MIN_VALUE && cy != Int.MIN_VALUE) {
                    showBubble(spawnCx = cx, spawnCy = cy)
                } else {
                    showBubble()
                }
            }
            "HIDE" -> hideBubble()
            "OPEN_MENU" -> {
                if (bubbleView == null) showBubble()
                if (menuView != null) dismissMenu() else showMenu()
            }
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
                // Run the bubble's live demo script — the bubble introduces itself.
                // Skip-on-tap (Phase 7 step 43) — cache the script + its onComplete
                // so the tap handler can short-circuit if the user has seen enough.
                timerHandler.postDelayed({
                    val finish: () -> Unit = {
                        activeDemoScript = null
                        activeDemoFinish = null
                        removeOnboardingOverlay()
                        showPipelineStage("🎤", "Close the app — I'm your app now. Tap me whenever you need to talk.")
                        timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
                    }
                    activeDemoFinish = finish
                    activeDemoScript = BubbleDemoScript(finish).also { it.start() }
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

    private fun showBubble(spawnCx: Int? = null, spawnCy: Int? = null) {
        if (bubbleView != null) {
            // Already visible — if a spawn position was requested (dock pickup
            // with a finger-follow expectation), just reposition so the effect
            // is "bubble is already out but jumped to your finger."
            if (spawnCx != null && spawnCy != null) {
                bubbleParams?.let { p ->
                    val view = bubbleView ?: return@let
                    p.x = spawnCx - view.width / 2
                    p.y = spawnCy - view.height / 2
                    try { windowManager?.updateViewLayout(view, p) } catch (_: Exception) {}
                }
            }
            return
        }

        // Guard: overlay permission must be granted before adding window
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show bubble — overlay permission not granted")
            android.widget.Toast.makeText(this, "Grant overlay permission first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Showing bubble")
        updateNotification("Voice dictation active")

        // Check non-blocking permissions (accessibility, mic). Bubble appears
        // either way but inject/record will silently fail if missing — alert
        // loudly so the user knows to fix it instead of thinking the app is
        // broken. Reinstalling the APK wipes accessibility every time.
        timerHandler.postDelayed({ alertOnMissingPermissions() }, 600)
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
            // FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS lets the bubble
            // travel into the status-bar and nav-bar regions (true edge-to-
            // edge movement). Without these, the OS clamps the window to
            // the safe area and the bezel visually stops ~6-10% short of
            // the actual screen edges.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Default spawn: horizontally centered, ~40% from the top.
            // Window is 180dp = ~540px on density 3; computing it here
            // off the live display metrics so it works on any device.
            val spawnDensity = resources.displayMetrics.density
            val spawnWindowPx = (180 * spawnDensity).toInt()
            x = (resources.displayMetrics.widthPixels - spawnWindowPx) / 2
            y = (resources.displayMetrics.heightPixels * 0.4f).toInt()
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
        val DRAG_THRESHOLD = 40

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
                    // Schedule press-and-hold detection. 500ms threshold
                    // sits well above normal tap durations (100-300ms) so
                    // accidental brushes never trigger record. Cancelled
                    // on drag or early release. Haptic cue on activation.
                    holdStartRunnable?.let { timerHandler.removeCallbacks(it) }
                    holdStartRunnable = Runnable {
                        if (!isDragging && !isRecording && bubbleView != null &&
                            BubbleStateBus.current() !is BubbleState.Clarifying) {
                            holdModeActive = true
                            try {
                                vibrator?.vibrate(
                                    android.os.VibrationEffect.createOneShot(
                                        40, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                    )
                                )
                            } catch (_: Exception) {}
                            // Suppress the deferred single-tap so release
                            // doesn't double-toggle.
                            timerHandler.removeCallbacks(deferredSingleTapAction)
                            lastTapTime = 0L
                            startPulseRings()
                            startListening()
                        }
                    }
                    timerHandler.postDelayed(holdStartRunnable!!, HOLD_THRESHOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > DRAG_THRESHOLD || kotlin.math.abs(dy) > DRAG_THRESHOLD) {
                        if (!isDragging) {
                            isDragging = true
                            // Drag pre-empts a queued single-tap action so a
                            // drag-from-rest doesn't fire start-recording on
                            // release. (Recording is now started only via
                            // the deferredSingleTapAction timer in ACTION_UP.)
                            timerHandler.removeCallbacks(deferredSingleTapAction)
                            // Cancel pending hold-mode trigger — user is
                            // moving the bubble, not holding to record.
                            holdStartRunnable?.let { timerHandler.removeCallbacks(it) }
                            holdStartRunnable = null
                            // Reset the double-tap window — a drag is not a tap.
                            lastTapTime = 0L
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy

                        // Magnetic pull toward dock slots — feels like the dock
                        // grabs the bubble when you drag close. Strong inverse
                        // pull, max 80% per frame at point-blank range.
                        val rects = dockSlotRects
                        val viewWMagnet = bubbleView?.width ?: 0
                        val viewHMagnet = bubbleView?.height ?: 0
                        if (rects != null && !rects.isEmpty() && viewWMagnet > 0) {
                            val bubbleCx = params.x + viewWMagnet / 2
                            val bubbleCy = params.y + viewHMagnet / 2
                            val dpDensity = resources.displayMetrics.density
                            val pullRangePx = 400f * dpDensity
                            val hardSnapPx = 140f * dpDensity
                            var nearestDist = Float.MAX_VALUE
                            var nearestSx = 0
                            var nearestSy = 0
                            for ((_, rect) in rects) {
                                val sx = rect.centerX()
                                val sy = rect.centerY()
                                val ddx = (sx - bubbleCx).toFloat()
                                val ddy = (sy - bubbleCy).toFloat()
                                val d = kotlin.math.sqrt(ddx * ddx + ddy * ddy)
                                if (d < pullRangePx && d < nearestDist) {
                                    nearestDist = d
                                    nearestSx = sx
                                    nearestSy = sy
                                }
                            }
                            if (nearestDist < pullRangePx) {
                                val targetX = nearestSx - viewWMagnet / 2
                                val targetY = nearestSy - viewHMagnet / 2
                                val pull: Float = if (nearestDist < hardSnapPx) {
                                    // Within hard-snap radius: full lock onto slot
                                    1f
                                } else {
                                    // Linear falloff from 1.0 at hardSnapPx to 0.35 at pullRangePx
                                    val norm = (pullRangePx - nearestDist) / (pullRangePx - hardSnapPx)
                                    (0.35f + norm * 0.6f).coerceIn(0.35f, 1f)
                                }
                                params.x = (params.x + (targetX - params.x) * pull).toInt()
                                params.y = (params.y + (targetY - params.y) * pull).toInt()
                            }
                        }

                        // Clamp bubble position so the visible bezel can touch
                        // each screen edge but never travel past it. Window is
                        // a fixed 180dp container; the bezel layout is 72dp and
                        // gets scaled by bubbleSize (and briefly by taffy
                        // stretch during drag, up to +15%). Allowing the
                        // WINDOW to extend off-screen by exactly `insetPx`
                        // puts the bezel edge flush with the screen edge.
                        // Taffy headroom (1.15x) is baked in so a fast drag
                        // doesn't briefly punch the bezel past the edge.
                        val clampDensity = resources.displayMetrics.density
                        val sizeScaleClamp = WimApp.bubbleSize().coerceIn(70, 150) / 100f
                        val taffyScaleMax = 1.15f
                        val effectiveBezelDp = 72f * sizeScaleClamp * taffyScaleMax
                        val insetClampPx =
                            ((180f - effectiveBezelDp).coerceAtLeast(0f) / 2f * clampDensity).toInt()
                        val windowClampPx = (180 * clampDensity).toInt()
                        val screenWClamp = resources.displayMetrics.widthPixels
                        val screenHClamp = resources.displayMetrics.heightPixels
                        params.x = params.x.coerceIn(
                            -insetClampPx,
                            screenWClamp - windowClampPx + insetClampPx
                        )
                        params.y = params.y.coerceIn(
                            -insetClampPx,
                            screenHClamp - windowClampPx + insetClampPx
                        )

                        windowManager?.updateViewLayout(bubbleView, params)
                        updateMenuPositionFromBubble()

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
                    // Cancel any pending hold-mode trigger regardless of
                    // release path — once the finger is up, hold detection
                    // is moot whether or not it had fired yet.
                    holdStartRunnable?.let { timerHandler.removeCallbacks(it) }
                    holdStartRunnable = null
                    // Hold-to-record release: stop and process. Bypass the
                    // tap/double-tap toggle since the user already held to
                    // start; release is the natural stop.
                    if (holdModeActive) {
                        holdModeActive = false
                        if (isRecording) stopAndProcess()
                        return@setOnTouchListener true
                    }
                    // Phase 7 step 43 — skip-on-tap: an active onboarding demo
                    // is short-circuited by any tap, so users who don't want
                    // the tour aren't held hostage to it.
                    if (activeDemoScript != null) {
                        activeDemoScript?.cancel()
                        activeDemoScript = null
                        activeDemoFinish?.invoke()
                        activeDemoFinish = null
                        return@setOnTouchListener true
                    }
                    if (isDragging) {
                        // Snap back to normal
                        val scale = WimApp.bubbleSize() / 100f
                        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
                            it.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
                        }

                        // Check if dragged into a dock slot — "pulled him back in".
                        // Only active when MainActivity is visible (it publishes the
                        // rects on resume, clears on pause).
                        val dockSnap = checkDockSnap(params)
                        if (dockSnap != null) {
                            parkIntoDock(dockSnap.first, dockSnap.second, params)
                            return@setOnTouchListener true
                        }

                        // Drag-to-top of screen → open menu; drag-to-bottom →
                        // hide. Triggers fire on the visible BEZEL position
                        // (not the window's outer rect) so they remain
                        // reachable after the bezel-edge clamp above and so
                        // visual feedback matches what the user perceives.
                        val dp = resources.displayMetrics.density
                        val menuZone = (MENU_TRIGGER_DP * dp).toInt()
                        val screenHeight = resources.displayMetrics.heightPixels
                        val hideZone = screenHeight - (HIDE_TRIGGER_DP * dp).toInt()
                        val almostZone = screenHeight - (200 * dp).toInt()
                        // Trigger insets use the AT-REST bezel (no taffy) since
                        // the bubble is already released; taffy doesn't apply.
                        val triggerSizeScale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
                        val triggerInsetPx =
                            ((180f - 72f * triggerSizeScale).coerceAtLeast(0f) / 2f * dp).toInt()
                        val triggerWindowPx = (180 * dp).toInt()
                        val bezelTop = params.y + triggerInsetPx
                        val bezelBottom = params.y + triggerWindowPx - triggerInsetPx
                        if (bezelTop < menuZone) {
                            if (menuView != null) {
                                dismissMenu()
                            } else {
                                snapBubbleForMenuOpen()
                                showMenu()
                            }
                            return@setOnTouchListener true
                        }
                        if (bezelBottom > hideZone) {
                            hideBubble()
                            updateNotification("Bubble hidden — tap to show")
                        } else if (checkCommandModePickup()) {
                            // Bubble dropped on highlighted text — command mode
                            return@setOnTouchListener true
                        } else if (bezelBottom > almostZone) {
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

                    // SEND_READY: any tap within the bubble (bezel OR knob) fires
                    // confirm-and-send. Takes precedence over the bezel-only
                    // work/life toggle because the whole bubble is the affordance
                    // when it reads "SEND".
                    if (isSendReady && !isRecording && dist < outerR && tapDuration < 300) {
                        try { vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        isSendReady = false
                        timerHandler.removeCallbacks(revertSendReadyRunnable)
                        handleSendConfirmTap()
                        return@setOnTouchListener true
                    }

                    // REGEN: tap on the lower-bezel regen icon re-rolls the
                    // last reconstruction with a "different phrasing" hint.
                    // Takes precedence over the work/life toggle so the bottom
                    // half of the bezel is unambiguously the regen affordance
                    // while the icon is visible.
                    if (isRegenAvailable && !isRecording && dist < outerR && ty > outerR * 0.45 && tapDuration < 300) {
                        performRegen()
                        return@setOnTouchListener true
                    }

                    if (!isRecording && dist > innerR && dist < outerR && tapDuration < 300) {
                        try { vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        // FSM clarifier (per-word, spec §6 Phase 2) — left = approve,
                        // right = reject. Wins over the legacy work/life clarifier
                        // because the FSM is the single source of truth.
                        if (BubbleStateBus.current() is BubbleState.Clarifying) {
                            if (tx < 0) {
                                bubbleAffect?.emitApproveFlash()
                                ClarifierController.approve()
                            } else {
                                bubbleAffect?.emitRejectFlash()
                                ClarifierController.reject()
                            }
                            return@setOnTouchListener true
                        }
                        if (isClarifierActive) {
                            // Clarifier mode — left = ✓ yes, right = ✕ no
                            dismissClarifier(tx < 0)
                        } else {
                            // Normal mode — toggle work/life
                            // Stamp manual_tone_set_ms so the accessibility-side
                            // autoSwitchTone observer doesn't ping-pong the user's
                            // explicit choice on the very next host-app event.
                            val nowMs = System.currentTimeMillis()
                            if (tx < 0) {
                                isWorkMode = true
                                WimApp.prefs.edit().putBoolean("work_mode", true).putString("tone", "professional").putLong("manual_tone_set_ms", nowMs).apply()
                            } else {
                                isWorkMode = false
                                WimApp.prefs.edit().putBoolean("work_mode", false).putString("tone", "casual").putLong("manual_tone_set_ms", nowMs).apply()
                            }
                            updateBezelLabels()
                        }
                        return@setOnTouchListener true
                    }

                    try { vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}

                    // Long-press-to-defer during clarifier — the third
                    // user-decision modality named in patent claim 1
                    // ("approve, reject, or defer"). Tap-left = approve,
                    // tap-right = reject, hold = defer (advance without a
                    // decision; same effect as a voice "next" command,
                    // but tap-accessible for users who don't engage the
                    // voice channel for clarifier review).
                    val pressDurationMs = System.currentTimeMillis() - downTime
                    if (pressDurationMs >= 500L &&
                        BubbleStateBus.current() is BubbleState.Clarifying
                    ) {
                        try {
                            vibrator?.vibrate(
                                VibrationEffect.createOneShot(
                                    40, VibrationEffect.DEFAULT_AMPLITUDE
                                )
                            )
                        } catch (_: Exception) {}
                        ClarifierController.next()
                        return@setOnTouchListener true
                    }

                    // Tap routing:
                    //   • If recording is in flight (regardless of how it
                    //     started), tap stops it.
                    //   • Otherwise the tap is the first half of a possible
                    //     double-tap. We DEFER the start-recording action
                    //     by DOUBLE_TAP_TIMEOUT (300 ms) so a second tap
                    //     can pre-empt and run the double-tap action
                    //     instead. The 300 ms latency is the cost of
                    //     supporting double-tap-to-open-menu.
                    if (isRecording) {
                        lastTapTime = 0L
                        timerHandler.removeCallbacks(deferredSingleTapAction)
                        stopAndProcess()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                            // Second tap inside the double-tap window.
                            lastTapTime = 0L
                            timerHandler.removeCallbacks(deferredSingleTapAction)
                            try {
                                vibrator?.vibrate(
                                    android.os.VibrationEffect.createOneShot(
                                        70, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                    )
                                )
                            } catch (_: Exception) {}
                            // Double-tap action: toggle the bubble menu.
                            // Same canonical snap as the drag-to-top path
                            // so menu/tail orientation is identical on both
                            // open paths.
                            if (menuView != null) {
                                dismissMenu()
                            } else {
                                snapBubbleForMenuOpen()
                                showMenu()
                            }
                        } else {
                            // First tap — schedule single-tap action with
                            // a 300 ms deferred runnable. A second tap
                            // arriving inside that window cancels this.
                            lastTapTime = now
                            timerHandler.removeCallbacks(deferredSingleTapAction)
                            timerHandler.postDelayed(deferredSingleTapAction, DOUBLE_TAP_TIMEOUT)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // System pulled the gesture (focus stolen, etc.). Clean
                    // up hold-mode bookkeeping so we don't leave the bubble
                    // in a half-recorded state.
                    holdStartRunnable?.let { timerHandler.removeCallbacks(it) }
                    holdStartRunnable = null
                    if (holdModeActive) {
                        holdModeActive = false
                        if (isRecording) stopAndProcess()
                    }
                    true
                }
                else -> false
            }
        }

        // Apply saved bubble color theme, opacity, and size
        applyBubbleTheme()
        val opacity = WimApp.bubbleOpacity().coerceIn(20, 100) / 100f
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.alpha = opacity

        val scale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
        val density = resources.displayMetrics.density
        // Window held at MAX size (= 120dp * 1.5 = 180dp) so the bezel can scale
        // up freely without clipping. Empty area outside the bezel is invisible
        // (transparent overlay). Resizing now happens entirely via bezel scale.
        val maxWindowDp = 180
        val newWindowPx = (maxWindowDp * density).toInt()
        params.width = newWindowPx
        params.height = newWindowPx
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
            if (it.width > 0) {
                it.pivotX = it.width / 2f
                it.pivotY = it.height / 2f
            }
            it.scaleX = scale
            it.scaleY = scale
        }

        // Spawn-at-finger: if the caller passed a screen center point (dock
        // pickup), position the bubble window so its center lands exactly
        // on that point. Otherwise keep whatever position bubbleParams had.
        if (spawnCx != null && spawnCy != null) {
            params.x = spawnCx - newWindowPx / 2
            params.y = spawnCy - newWindowPx / 2
        }

        windowManager?.addView(bubbleView, params)

        // Start session timer (always running, shows total session time)
        timerHandler.post(timerRunnable)

        // Prime the pre-roll audio buffer so first words aren't lost on tap
        if (WimApp.apiKey().isNotBlank() && WimApp.layer() >= 2) {
            if (audioRecorder == null) audioRecorder = AudioRecorder(this)
            audioRecorder?.primeBuffer()
        }

        // Coat-rack signal: MainActivity's dock listens to this and ghosts
        // the matching slot to show the bubble is "in use."
        onBubbleVisibilityChanged?.invoke()
    }

    private fun hideBubble() {
        // Drop any in-flight send-ready timer + disclaimer so they don't
        // survive the bubble's lifetime.
        isSendReady = false
        timerHandler.removeCallbacks(revertSendReadyRunnable)
        dismissSendDisclaimer()
        hideWordHighlight()
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            bubbleView = null
        }
        // Re-post notification so it stays visible as the recall button
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Bubble hidden — tap to show"))
        // Coat-rack signal: bubble is back on the hanger. Dock un-ghosts.
        onBubbleVisibilityChanged?.invoke()
    }

    /** True iff the floating bubble view is currently attached to the window manager. */
    fun isBubbleVisible(): Boolean = bubbleView != null

    // ── Auto-send state ─────────────────────────────────────────────────
    // Flipped true after a successful injectText when auto-send is enabled
    // and the focused app is whitelisted. Tap the bubble in this state to
    // fire ACTION_CLICK on the app's Send button. Auto-reverts to IDLE
    // after SEND_READY_TIMEOUT_MS.
    private var isSendReady = false
    private val SEND_READY_TIMEOUT_MS = 5000L
    private var sendReadyExpiresAt = 0L
    private val sendReadyTickRunnable = object : Runnable {
        override fun run() {
            if (!isSendReady) return
            val remaining = ((sendReadyExpiresAt - System.currentTimeMillis() + 999) / 1000)
                .coerceAtLeast(0).coerceAtMost(99)
            bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.text = "${remaining}s"
            if (remaining > 0) timerHandler.postDelayed(this, 250)
        }
    }
    private val revertSendReadyRunnable = Runnable {
        if (isSendReady) {
            isSendReady = false
            snapBackFromFacehugger()
            setBubbleIdle()
        }
    }
    private var disclaimerView: View? = null

    /**
     * Called after a successful injectText. Decides whether to rest at IDLE
     * or flip to SEND_READY (green label, tap to auto-send). SEND_READY is
     * gated on user opt-in + per-app whitelist so unsupported apps still
     * behave exactly as today.
     */
    private fun onInjectComplete() {
        // If the user has ALREADY started a new recording by the time inject
        // finished (common when reconstruction takes longer than the user's
        // reaction time), don't override the live recording UI with SEND or
        // IDLE — the fresh recording owns the bubble's UX surface.
        if (isRecording) return
        val autoSendOn = WimApp.autoSendEnabled()
        val supported = WimAccessibilityService.instance?.focusedAppSupportsAutoSend() == true
        if (autoSendOn && supported && bubbleView != null) {
            setBubbleSendReady()
        } else {
            setBubbleIdle()
        }
    }

    private fun showRegenIcon() {
        val view = bubbleView ?: return
        val icon = view.findViewById<android.widget.ImageView>(R.id.bezelRegen) ?: return
        icon.alpha = 0f
        icon.visibility = View.VISIBLE
        icon.animate().alpha(1f).setDuration(200).start()
        isRegenAvailable = true
        regenHideJob?.cancel()
        regenHideJob = scope.launch {
            delay(8000)
            hideRegenIcon()
        }
    }

    private fun hideRegenIcon() {
        val view = bubbleView ?: return
        val icon = view.findViewById<android.widget.ImageView>(R.id.bezelRegen) ?: return
        icon.animate().alpha(0f).setDuration(150).withEndAction {
            icon.visibility = View.GONE
        }.start()
        isRegenAvailable = false
        regenHideJob?.cancel()
        regenHideJob = null
    }

    private fun performRegen() {
        if (isRegenInFlight) return
        val ctx = lastRegenCtx ?: return
        val accessibility = WimAccessibilityService.instance ?: run {
            Toast.makeText(this, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
            return
        }
        isRegenInFlight = true
        hideRegenIcon()
        try { vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        ConsoleLog.log("Regenerating L${ctx.layer}...", "info")

        scope.launch {
            try {
                val profile = WimApp.profile()
                val vocabulary = profile.getVocabulary()
                val corrections = profile.getCorrections()
                val fillerWords = profile.getFillerWords()
                val result = ReconstructClient.reconstruct(
                    rawText = ctx.correctedText,
                    tone = ctx.tone,
                    layer = ctx.layer,
                    situation = ctx.situation,
                    vocabulary = vocabulary,
                    corrections = corrections,
                    fillerWords = fillerWords,
                    triggerWords = profile.getTriggerWords(),
                    onsetWeights = profile.getOnsetWeights(),
                    covertProfile = profile.getCovertProfile(),
                    speechSeverityMod = if (ctx.rateGapDetected) 0.3 else 0.0,
                    precedingContext = ctx.precedingContext,
                    languageCode = ctx.languageCode,
                    previousOutputs = previousOutputs.toList(),
                )
                val newText = result.cleanText.trim()
                if (newText.isBlank() || previousOutputs.contains(newText)) {
                    ConsoleLog.log("Regen returned same/blank — keeping original", "warn")
                    Toast.makeText(this@BubbleService, "No fresh phrasing this time", Toast.LENGTH_SHORT).show()
                    showRegenIcon()
                    return@launch
                }
                // L1 contribution: when the user triggers regen, the previous
                // output was implicitly rejected. Fire-and-forget to the contribution
                // endpoint if the user has opted in and has an L1 set.
                if (profile.contributeL1Data() && profile.profileL1() != null && previousOutputs.isNotEmpty()) {
                    val rejected = previousOutputs.last()
                    val l1 = profile.profileL1()!!
                    val anonId = profile.anonymousId()
                    val rawForContrib = ctx.rawText
                    Thread {
                        try {
                            val payload = org.json.JSONObject().apply {
                                put("raw", rawForContrib.take(2000))
                                put("model_output", rejected.take(2000))
                                put("user_correction", "")
                                put("profile_l1", l1)
                                put("anonymous_id", anonId)
                            }.toString()
                            val url = java.net.URL("https://us-central1-bakers-agent.cloudfunctions.net/wim-l1-contribute")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            conn.outputStream.write(payload.toByteArray())
                            conn.responseCode // trigger request
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }.start()
                }

                accessibility.replaceLastInjected(newText)
                previousOutputs.add(newText)
                ConsoleLog.log("Regen: ${result.reconMs}ms -> \"${newText.take(80)}\"", "out")
                showRegenIcon()
            } catch (e: Exception) {
                Log.e(TAG, "Regen error: ${e.message}", e)
                ConsoleLog.log("Regen failed: ${e.message?.take(60)}", "error")
                showRegenIcon()
            } finally {
                isRegenInFlight = false
            }
        }
    }

    private fun setBubbleSendReady() {
        val view = bubbleView ?: return
        BubbleStateBus.advance(BubbleState.SendReady)
        isSendReady = true
        stopPulseRings()
        hideLiveTranscript()
        view.findViewById<FrameLayout>(R.id.bubbleInner)?.clearAnimation()
        view.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "SEND"
            setTextColor(0xFF5AD15F.toInt())
        }
        // Live countdown under the SEND label — ticks every 250ms, shows "Ns"
        // remaining before auto-cancel.
        view.findViewById<TextView>(R.id.bubbleTime)?.apply {
            visibility = View.VISIBLE
            text = "${SEND_READY_TIMEOUT_MS / 1000}s"
            setTextColor(0xFF5AD15F.toInt())
        }
        sendReadyExpiresAt = System.currentTimeMillis() + SEND_READY_TIMEOUT_MS
        timerHandler.removeCallbacks(sendReadyTickRunnable)
        timerHandler.post(sendReadyTickRunnable)
        timerHandler.removeCallbacks(revertSendReadyRunnable)
        timerHandler.postDelayed(revertSendReadyRunnable, SEND_READY_TIMEOUT_MS)
        ConsoleLog.log("Ready to send — tap bubble or wait to cancel", "info")
        if (WimApp.facehuggerSendEnabled()) {
            WimAccessibilityService.instance?.getSendButtonCenter()?.let { (cx, cy) ->
                val view = bubbleView ?: return@let
                val params = bubbleParams ?: return@let
                preJumpX = params.x + view.width / 2
                preJumpY = params.y + view.height / 2
                facehuggerActive = true
                animateBubbleTo(cx, cy, durationMs = 250)
            }
        }
    }

    private fun handleSendConfirmTap() {
        // Auto-send consent is handled once at onboarding (BouncerActivity's
        // pageAutoSend acknowledgment row sets auto_send_disclaimer_count to
        // 99). No runtime popup — tapping the green bubble is itself the
        // deliberate action. The 3.5 s green-bubble timeout is the cancel path.
        actuallySend()
    }

    private fun actuallySend() {
        val ok = WimAccessibilityService.instance?.clickSendButton() == true
        if (ok) {
            ConsoleLog.log("Auto-send dispatched", "info")
            Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
        } else {
            ConsoleLog.log("Auto-send failed — no Send node", "warn")
            Toast.makeText(this, "Couldn't find Send button", Toast.LENGTH_SHORT).show()
        }
        snapBackFromFacehugger()
        setBubbleIdle()
    }

    private fun showSendDisclaimer(onConfirm: () -> Unit, onCancel: () -> Unit) {
        dismissSendDisclaimer()
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 28)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E1E22.toInt())
                cornerRadius = 28f
                setStroke(2, 0xFFC8B48A.toInt())
            }
        }
        val title = TextView(ctx).apply {
            text = "About to send"
            setTextColor(0xFFC8B48A.toInt())
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 18)
        }
        val body = TextView(ctx).apply {
            text = "Tapping the green bubble sends the text in the compose field through the app that's open — Google Messages, Gmail, WhatsApp, etc. — using your existing signed-in session. " +
                "WiM never sends without a deliberate tap. If the text is wrong, tap outside or wait 3 seconds to cancel and edit."
            setTextColor(0xFFB8B8B0.toInt())
            textSize = 13f
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, 24)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelBtn = TextView(ctx).apply {
            text = "CANCEL"
            setTextColor(0xFF8A7D68.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(36, 22, 28, 22)
            isClickable = true
        }
        val okBtn = TextView(ctx).apply {
            text = "SEND IT"
            setTextColor(0xFF5AD15F.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(28, 22, 36, 22)
            isClickable = true
        }
        row.addView(cancelBtn)
        row.addView(okBtn)
        container.addView(title)
        container.addView(body)
        container.addView(row)
        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.7f
        }
        cancelBtn.setOnClickListener {
            dismissSendDisclaimer()
            onCancel()
        }
        okBtn.setOnClickListener {
            dismissSendDisclaimer()
            onConfirm()
        }
        disclaimerView = container
        try { windowManager?.addView(container, lp) } catch (_: Exception) {}
    }

    private fun dismissSendDisclaimer() {
        disclaimerView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        disclaimerView = null
    }

    /**
     * Remote-drive the bubble's screen position. Called from MainActivity's
     * dock-slot touch listener during drag-from-dock pickup: Android can't
     * hand a touch stream across windows, but MainActivity's listener keeps
     * getting ACTION_MOVE events until the finger lifts, so we pipe those
     * coords here and reposition the overlay in real time. The bubble's
     * notional center lands on (cx, cy).
     *
     * Safe to call every MOVE event — updateViewLayout is cheap relative
     * to touch event cadence (60 Hz). If this becomes a perf concern,
     * throttle at the caller.
     */
    fun moveBubbleTo(cx: Int, cy: Int) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        params.x = cx - view.width / 2
        params.y = cy - view.height / 2
        try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
        updateMenuPositionFromBubble()
    }

    private fun animateBubbleTo(toCx: Int, toCy: Int, durationMs: Long) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val fromCx = params.x + view.width / 2
        val fromCy = params.y + view.height / 2
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                params.x = (fromCx + (toCx - fromCx) * t).toInt() - view.width / 2
                params.y = (fromCy + (toCy - fromCy) * t).toInt() - view.height / 2
                try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun snapBackFromFacehugger() {
        if (!facehuggerActive) return
        facehuggerActive = false
        animateBubbleTo(preJumpX, preJumpY, durationMs = 200)
    }

    private fun startListening() {
        if (isRecording) return

        // Release the hotword listener — it owns the mic exclusively until
        // we hand it back. Safe even if it isn't running.
        stopHotwordListener()

        // Spec §1: Clarifying → Listening is a legal transition (user always
        // wins). Tear down the clarifier cleanly before the new recording
        // claims the bubble's UX surface.
        if (BubbleStateBus.current() is BubbleState.Clarifying) {
            ClarifierController.cancelForNewRecording()
            TranscriptBarController.clear(TranscriptBarController.Priority.CLARIFIER)
        }

        // Stale regen state: a fresh recording invalidates the cached
        // reconstruction context.
        if (isRegenAvailable) hideRegenIcon()
        lastRegenCtx = null
        previousOutputs.clear()

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
        BubbleStateBus.advance(BubbleState.Listening(System.currentTimeMillis()))
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

        // Visual: recording state — only the INNER KNOB lights up, the bezel
        // (the dish around the knob) stays in the user's selected theme color.
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_active)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "REC"
            setTextColor(0xFFFFD580.toInt())  // cream-gold — bright enough to read on red/green knob
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            setTextColor(0xFFFFD580.toInt())
        }
        recordingStartTime = System.currentTimeMillis()
        val pulse = AlphaAnimation(1.0f, 0.6f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.startAnimation(pulse)

        // ASR routing for this session:
        //   L4 always cloud whisper-1 (clinical needs verbose_json segments).
        //   L1 user-toggleable via `l1_cloud_asr`:
        //     - false (default) → local whisper-tiny.en via WhisperLocalEngine.
        //     - true → cloud whisper-1 (audio leaves device).
        //   L2/L3 stays on whisperLocalEngine when ready, falls back to cloud
        //   gpt-4o-transcribe via AudioRecorder when not.
        // AudioRecorder fires when ANY of the cloud paths above will run.
        val isL4 = WimApp.layer() >= 4
        val l1Cloud = WimApp.l1CloudAsr()
        val l1NeedsCloud = l1Cloud && WimApp.layer() < 2
        val wantLocalLive = (whisperLocalEngine?.isReady() == true) && !isL4 && !l1NeedsCloud
        if ((isL4 || l1NeedsCloud || (!wantLocalLive && WimApp.layer() >= 2)) && WimApp.apiKey().isNotBlank()) {
            try {
                if (audioRecorder == null) audioRecorder = AudioRecorder(this)
                audioRecorder?.start(quietMode = WimApp.prefs.getBoolean("quiet_mode", false))
                Log.i(TAG, "L${WimApp.layer()}: AudioRecorder started for cloud-ASR fallback")
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

        // Shared ASR callback logic — same for both engines.
        val handlePartial: (String) -> Unit = { text ->
            Log.i(TAG, "Partial: $text")
            lastRawText = if (finalizedSegments.isBlank()) text else "$finalizedSegments $text"
            updateLiveTranscript(lastRawText)
        }
        val handleFinal: (String) -> Unit = { text ->
            Log.i(TAG, "Final ASR: $text")
            val trimmed = text.trim()
            // Whisper-family ASR hallucinates single chars / pure punctuation
            // on near-silent or very short audio (e.g. "(", ".", "[BLANK_AUDIO]").
            // Discard before they pollute lastRawText / get pasted.
            val isHallucination = trimmed.isBlank() ||
                trimmed.length <= 2 ||
                trimmed in setOf(
                    "(", ")", "[", "]", "...", "(.)", "[.]",
                    "[BLANK_AUDIO]", "[silence]", "[Silence]", "[blank_audio]",
                    "Thank you.", "Thanks for watching.", "you", "You",
                ) ||
                trimmed.all { !it.isLetterOrDigit() }
            if (!isHallucination) {
                finalizedSegments = if (finalizedSegments.isBlank()) text else "$finalizedSegments $text"
                lastRawText = finalizedSegments
                updateLiveTranscript(lastRawText)
            } else {
                Log.i(TAG, "Discarding hallucinated/empty ASR: '$trimmed'")
            }
        }
        val handleSound: (String) -> Unit = { label ->
            updateLiveTranscript("$lastRawText $label")
        }
        val handleError: (Int) -> Unit = { code ->
            Log.e(TAG, "ASR error: $code")
            isRecording = false
            dafPlayer?.stop()
            dafPlayer = null
            setBubbleIdle()
            bubbleView?.scaleX = 1.0f
            bubbleView?.scaleY = 1.0f
        }

        // Pick the engine.
        //   L4: no live engine — AudioRecorder above is capturing WAV; whisper-1
        //       runs post-stop (the clinical prompt needs verbose_json confidence
        //       signals only Whisper-1 provides). Live transcript bar stays empty.
        //   L1 cloud path (l1_cloud_asr=true): WAV captured, whisper-1 on stop.
        //   L1 local / L2 / L3: whisper-tiny.en via WhisperLocalEngine (sherpa-onnx).
        //   Fallback when whisper-tiny model isn't ready: Android SpeechRecognizer.
        // LIVE preview override: whisper-tiny + whisper-1 don't stream partials.
        // If the user has LIVE on at L1 local, route through Android's
        // SpeechRecognizer instead — it streams partials but is less accurate
        // and chirps. Trade-off accepted in exchange for actually-live text.
        val liveOn = WimApp.prefs.getBoolean("live_transcript", false)
        val l1LocalLiveOverride = liveOn && !isL4 && !l1NeedsCloud

        if (isL4) {
            Log.i(TAG, "L4 mode: WAV captured, no live ASR (whisper-1 runs post-stop)")
            updateLiveTranscript("Recording for clinical reconstruction…")
        } else if (l1NeedsCloud) {
            Log.i(TAG, "L1 cloud mode: WAV captured, no live ASR (whisper-1 runs post-stop)")
            updateLiveTranscript("Recording (cloud ASR)…")
        } else if (l1LocalLiveOverride) {
            Log.i(TAG, "LIVE on — routing L1 to Android SpeechRecognizer for real-time partials")
            onDeviceRecognizer?.start(object : OnDeviceRecognizer.ResultListener {
                override fun onPartialResult(text: String) = handlePartial(text)
                override fun onFinalResult(text: String) = handleFinal(text)
                override fun onSoundEvent(label: String) = handleSound(label)
                override fun onError(errorCode: Int) = handleError(errorCode)
            })
        } else if (whisperLocalEngine?.isReady() == true) {
            Log.i(TAG, "Using whisper-tiny.en engine (local, sherpa-onnx)")
            whisperLocalEngine?.start(object : WhisperLocalEngine.ResultListener {
                override fun onPartialResult(text: String) = handlePartial(text)
                override fun onFinalResult(text: String) = handleFinal(text)
                override fun onSoundEvent(label: String) = handleSound(label)
                override fun onError(errorCode: Int) = handleError(errorCode)
            })
        } else {
            Log.w(TAG, "whisper-tiny model not ready — using Android SpeechRecognizer (offline-pinned)")
            // Surface the fallback so the user understands why this recording
            // chirped / is slower. Self-limits — once the ~40 MB model lands,
            // this branch becomes unreachable.
            android.widget.Toast.makeText(
                this,
                "Speech model still downloading — using fallback for now",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            onDeviceRecognizer?.start(object : OnDeviceRecognizer.ResultListener {
                override fun onPartialResult(text: String) = handlePartial(text)
                override fun onFinalResult(text: String) = handleFinal(text)
                override fun onSoundEvent(label: String) = handleSound(label)
                override fun onError(errorCode: Int) = handleError(errorCode)
            })
        }
    }

    private fun stopAndProcess() {
        if (processed) return
        // Reject accidental quick double-taps that capture <200ms of audio.
        // Whisper-tiny hallucinates "(" or similar from such clips. Below this
        // threshold the recording can't be a real intent.
        val recordingDurationMs = System.currentTimeMillis() - recordingStartTime
        if (recordingDurationMs < 200) {
            Log.w(TAG, "Recording too short (${recordingDurationMs}ms) — discarding, no processing")
            processed = true
            isRecording = false
            audioRecorder?.stop(); audioRecorder = null
            onDeviceRecognizer?.stop()
            moonshineEngine?.stop()
            whisperLocalEngine?.stop()
            dafPlayer?.stop(); dafPlayer = null
            setBubbleIdle()
            return
        }
        Log.i(TAG, "Stop requested, processing audio immediately...")
        processed = true
        isRecording = false
        // Immediate visual feedback — bubble transitions to PROC the instant the
        // stop tap is received, not after transcription finishes. Without this,
        // the bubble stays in "recording" red for the full transcription window
        // (3–10s on cloud Whisper, 15–70s on whisper.cpp) and users think the
        // button didn't register.
        setBubbleProcessing()
        BubbleStateBus.advance(BubbleState.Transcribing(System.currentTimeMillis()))
        setStageArc(0)
        dafPlayer?.stop()
        dafPlayer = null

        // Stop audio recorder immediately (captures full audio)
        val wavFile = audioRecorder?.stop()
        audioRecorder = null
        // Track the WAV so the L4 multi-pass decoder can read it after
        // reconstruction completes. Previous file (if any) is cleaned up
        // in setBubbleIdle when the cycle ends.
        lastWavFile?.let { try { it.delete() } catch (_: Exception) {} }
        lastWavFile = wavFile

        // Stop whichever engine was running this session; cheap defensive calls on all.
        onDeviceRecognizer?.stop()
        moonshineEngine?.stop()
        whisperLocalEngine?.stop()

        // Force the bar to appear during pipeline work so the user sees what's
        // happening (transcribe → reconstruct → falcon → inject) even when
        // LIVE toggle is off. Subsequent stages overwrite this.
        showPipelineStage("⏳ Transcribing", "Sending audio to Whisper")

        if (wavFile != null && wavFile.exists() && wavFile.length() > 44) {
            Log.i(TAG, "L${WimApp.layer()}: Sending ${wavFile.length()} bytes to Whisper API")
            scope.launch {
                try {
                    // L4 uses whisper-1; L1-cloud uses whisper-1; L2/L3 cloud
                    // fallback stays on gpt-4o-transcribe (parity gap flagged
                    // for follow-up).
                    val whisperModel = when {
                        WimApp.layer() >= 4 -> "whisper-1"
                        WimApp.layer() < 2 && WimApp.l1CloudAsr() -> "whisper-1"
                        else -> "gpt-4o-transcribe"
                    }
                    val l1CloudFastPath = WimApp.layer() < 2 && WimApp.l1CloudAsr()
                val whisperResult = WhisperClient.transcribe(
                    wavFile,
                    patienceMode = WimApp.layer() >= 4,
                    model = whisperModel,
                    verboseSegments = !l1CloudFastPath,
                    language = WimApp.appLanguage(),
                )
                    Log.i(TAG, "Whisper ($whisperModel, ${whisperResult.asrMs}ms): ${whisperResult.text}")
                    lastWhisperLowConf = whisperResult.lowConfidenceSegments
                    if (whisperResult.lowConfidenceSegments.isNotEmpty()) {
                        Log.i(TAG, "Whisper flagged ${whisperResult.lowConfidenceSegments.size} low-confidence segments (${whisperResult.lowConfidenceSegments.count { it.blockSuspect }} block suspects)")
                    }
                    if (whisperResult.wasFullyHallucinated) {
                        Log.w(TAG, "Whisper fully hallucinated — pasting nothing")
                        BubbleStateBus.advance(BubbleState.Failed(FailReason.HALLUCINATED))
                        showPipelineStage("∅ Silence", "Nothing to transcribe")
                        timerHandler.postDelayed({ setBubbleIdle() }, 1500)
                    } else {
                        val finalText = whisperResult.text
                        if (finalText.isNotBlank()) {
                            processText(finalText)
                        } else {
                            setBubbleIdle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Whisper failed: ${e.message}")
                    BubbleStateBus.advance(BubbleState.Failed(FailReason.NETWORK))
                    showPipelineStage("Error — tap to retry", "Transcription failed — tap bubble to try again")
                    Toast.makeText(this@BubbleService, "Transcription failed — tap bubble to try again", Toast.LENGTH_SHORT).show()
                    sessionHistory?.record(
                        rawText = "",
                        cleanText = "",
                        tone = WimApp.tone(),
                        layer = WimApp.layer(),
                        situation = WimApp.situation(),
                        asrMs = 0,
                        reconMs = 0,
                        totalMs = System.currentTimeMillis() - recordingStartTime,
                        falconApproved = false,
                        riskFlags = listOf("whisper_failed")
                    )
                    SessionUpdateBus.emit()
                    timerHandler.postDelayed({ setBubbleIdle() }, 2000)
                }
                // wavFile cleanup deferred to setBubbleIdle — the L4 multi-pass
                // decoder needs to read it AFTER reconstruction completes.
            }
        } else {
            // No wav — happens when user has no local API key (backend-only tier)
            // or on L1 where audioRecorder is intentionally gated off. Fall back to
            // OnDeviceRecognizer's last result.
            // Wait 400ms before reading lastRawText: the recognizer's onFinalResult
            // callback typically arrives 150-250ms after stop() and overwrites
            // lastRawText with the full utterance. Without this wait we paste a
            // truncated partial (e.g. "this is" instead of "this is a test").
            lastWhisperLowConf = emptyList()
            timerHandler.postDelayed({
                val onDeviceText = lastRawText.trim()
                if (onDeviceText.isNotBlank()) {
                    Log.i(TAG, "No wav, using on-device text: $onDeviceText")
                    processText(onDeviceText)
                } else {
                    Log.w(TAG, "No audio captured and no on-device text")
                    setBubbleIdle()
                }
            }, 400)
        }
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

    /**
     * Public entry point so MainActivity's bubble dock can flip the theme
     * while the user is in Settings — the live floating bubble picks up the
     * new palette immediately.
     */
    fun refreshBubbleTheme() = applyBubbleTheme()

    /**
     * Public entry point for MainActivity's slot drag handler: at the end of
     * a drag-out-from-slot gesture, check the bubble's current position
     * against the published dock rects and park into whichever slot the
     * user released over (if any). Lets the same single-gesture pickup
     * become a re-dock without the user ever lifting and re-grabbing the
     * bubble. Returns true if a park happened.
     */
    fun tryDockSnap(): Boolean {
        val params = bubbleParams ?: return false
        val snap = checkDockSnap(params) ?: return false
        parkIntoDock(snap.first, snap.second, params)
        return true
    }

    /**
     * Returns (theme, slot-center-point) if the bubble's current position
     * lands inside one of MainActivity's dock slot rects, else null. The
     * bubble's notional center is params.x + bubbleWidth/2 / params.y + bubbleHeight/2.
     */
    private fun checkDockSnap(params: WindowManager.LayoutParams): Pair<String, android.graphics.Point>? {
        val rects = dockSlotRects ?: return null
        val view = bubbleView ?: return null
        val cx = params.x + view.width / 2
        val cy = params.y + view.height / 2
        for ((theme, rect) in rects) {
            // Wide release-snap radius — bubble within 80% of slot width on
            // any side counts. Pairs with the active magnet during drag for
            // a "really fucking strong" pull.
            val pad = (rect.width() * 0.8f).toInt()
            if (cx in (rect.left - pad)..(rect.right + pad) &&
                cy in (rect.top - pad)..(rect.bottom + pad)) {
                return theme to android.graphics.Point(rect.centerX(), rect.centerY())
            }
        }
        return null
    }

    /**
     * Animate the bubble toward a dock slot's center, fade + shrink, swap
     * the theme to match the slot, then hide the bubble. Visual effect:
     * "pulled him back in."
     */
    private fun parkIntoDock(
        theme: String,
        slotCenter: android.graphics.Point,
        layoutParams: WindowManager.LayoutParams
    ) {
        WimApp.prefs.edit().putString("bubble_theme", theme).apply()
        applyBubbleTheme()
        val view = bubbleView ?: run { hideBubble(); return }
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: run { hideBubble(); return }
        // Target position: slot center minus bubble half-width/height so the
        // bubble's visual center lands on the slot center.
        val targetX = slotCenter.x - view.width / 2
        val targetY = slotCenter.y - view.height / 2
        val startX = layoutParams.x
        val startY = layoutParams.y
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            // Stronger magnet: faster overall + much sharper end-rush.
            // AccelerateInterpolator(3f) barely moves for the first half and
            // SNAPS into the slot in the last ~30% — reads as "yanked in."
            duration = 180
            interpolator = android.view.animation.AccelerateInterpolator(3.0f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                layoutParams.x = (startX + (targetX - startX) * t).toInt()
                layoutParams.y = (startY + (targetY - startY) * t).toInt()
                try { windowManager?.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
                updateMenuPositionFromBubble()
                bezel.scaleX = 1f - 0.55f * t
                bezel.scaleY = 1f - 0.55f * t
                bezel.alpha = 1f - 0.85f * t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    bezel.scaleX = 1f
                    bezel.scaleY = 1f
                    bezel.alpha = 1f
                    hideBubble()
                    updateNotification("Bubble parked — tap a dock slot or the WiM logo to resummon")
                }
            })
        }
        // Meatier haptic to match the stronger visual snap — registers as
        // a physical "klunk" when the coat hits the hanger.
        try { vibrator?.vibrate(VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        anim.start()
    }

    /** Apply the user's selected bubble color theme (bronze/aluminum/gunmetal). */
    private fun applyBubbleTheme() {
        val theme = WimApp.bubbleTheme()
        val bezelDrawable = when (theme) {
            "aluminum" -> R.drawable.bubble_bezel_aluminum
            "gunmetal" -> R.drawable.bubble_bezel_gunmetal
            else -> R.drawable.bubble_bg_idle
        }
        val knobDrawable = when (theme) {
            "aluminum" -> R.drawable.bubble_knob_aluminum
            "gunmetal" -> R.drawable.bubble_knob_gunmetal
            else -> R.drawable.bubble_inner_knob
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.setBackgroundResource(bezelDrawable)
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.setBackgroundResource(knobDrawable)
        // If the label is currently showing IDLE, refresh its color to match
        // the new theme. Without this, a lingering gunmetal-gold would stay
        // on the aluminum/bronze bezel and become invisible against the
        // lighter metal. REC/PROC labels are left alone — their colors are
        // managed by the recording state path.
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.let { label ->
            if (label.text == "IDLE") {
                val idleColor = when (theme) {
                    "gunmetal" -> 0xFFC8B48A.toInt()  // cream-gold on dark steel
                    "bronze" -> 0xFF1F1208.toInt()    // deep espresso on warm copper — matches palette primaryText
                    else -> 0xFF555560.toInt()         // dark gray on aluminum
                }
                label.setTextColor(idleColor)
            }
        }
    }

    private fun setBubbleIdle() {
        // FSM: drop to Idle (force-set so we don't fight the transition
        // matrix on cancel/cleanup paths from arbitrary prior states).
        BubbleStateBus.reset()
        stopPulseRings()
        hideLiveTranscript()
        // L4 multi-pass wav is no longer needed once we're idle.
        lastWavFile?.let { try { it.delete() } catch (_: Exception) {} }
        lastWavFile = null
        maybeShowTip()
        // Cancel SEND-ready countdown ticker if still running.
        timerHandler.removeCallbacks(sendReadyTickRunnable)
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.clearAnimation()
        applyBubbleTheme()
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "IDLE"
            // Per-theme IDLE color so the label has real contrast on each
            // bezel: cream-gold on gunmetal, deep espresso on bronze (the
            // palette's primaryText), dark gray on aluminum.
            val idleColor = when (WimApp.bubbleTheme()) {
                "gunmetal" -> 0xFFC8B48A.toInt()
                "bronze" -> 0xFF1F1208.toInt()
                else -> 0xFF555560.toInt()
            }
            setTextColor(idleColor)
        }
        // bubbleTime: show anticipatory prediction snippet during Idle if available,
        // otherwise hide. This surfaces the PredictionEngine's context-awareness
        // on the bubble face before the user opens the menu.
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            if (WimApp.predictionsEnabled()) {
                val pkg = WimAccessibilityService.currentForegroundPackage
                val pred = PredictionEngine.predictForContext(pkg)
                if (pred != null) {
                    text = "→ ${pred.tone.take(3).uppercase()}"
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@BubbleService, R.color.copper_gold))
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            } else {
                visibility = View.GONE
            }
            // Note: chip_value color applied only in SEND-ready / clarifier paths.
        }
        // Re-post timer if it was stopped (restart case)
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
        // Pipeline arc resets — new cycle starts cleanly.
        setStageArc(-1)
        // Resume the "Hey WiM" hotword listener now that the mic is free.
        startHotwordIfEnabled()
    }

    /**
     * Start the "Hey WiM" hotword listener if the user has it enabled and
     * we're not currently recording. Idempotent — repeated calls are safe.
     *
     * Critical: release any pre-primed AudioRecorder before starting. The
     * pre-roll buffer (primed in showBubble for cloud-ASR layers) holds
     * the mic exclusively via AudioRecord, which silently blocks
     * SpeechRecognizer from acquiring the same source. Symptoms when
     * skipped: hotword listener starts, never fires partials, never
     * fires errors — a dead-quiet recognizer session.
     */
    private fun startHotwordIfEnabled() {
        if (!WimApp.prefs.getBoolean("hotword_enabled", false)) return
        if (isRecording) return
        if (hotwordListener != null) return
        if (audioRecorder != null) {
            Log.i(TAG, "Releasing AudioRecorder pre-roll buffer to free mic for hotword")
            audioRecorder?.release()
            audioRecorder = null
        }
        hotwordListener = HotwordListener(this) {
            // Hotword fired — vibrate, drop the listener (it stops itself
            // pre-callback), and kick off a normal recording session.
            try {
                vibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        60, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } catch (_: Exception) {}
            hotwordListener = null
            startPulseRings()
            startListening()
        }.also { it.start() }
    }

    private fun stopHotwordListener() {
        hotwordListener?.stop()
        hotwordListener = null
    }

    /** Public alias so MainActivity can flip the listener off when the user
     *  toggles the hotword setting. */
    fun stopHotwordListenerPublic() = stopHotwordListener()

    /**
     * Show the patent-clean word-highlight overlay over the host app's
     * focused text field. Called from `ClarifierController.emitLabelUpdate`
     * on each cycle advance. Reads the field's screen rect from the
     * accessibility service; positions a thin underline-bearing overlay
     * window over it. Touches pass through (FLAG_NOT_TOUCHABLE), so the
     * user's interaction with the host app is unaffected — claim 1
     * application-context invariance preserved.
     *
     * `rangeStart` / `rangeEnd` are reserved for future per-character
     * targeting once we have reliable per-word bounds across hosts; for
     * now the underline spans the full field.
     */
    fun showWordHighlight(rangeStart: Int, rangeEnd: Int) {
        val accessibility = WimAccessibilityService.instance ?: return
        val rect = accessibility.focusedFieldBounds() ?: return
        val wm = windowManager ?: return
        // Lazily create the view + window the first time the cycle fires.
        if (wordHighlightView == null) {
            wordHighlightView = WordHighlightView(this)
            wordHighlightParams = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // FLAG_NOT_TOUCHABLE: touches pass through to the host app.
                // FLAG_NOT_FOCUSABLE: never steals focus from the host.
                // FLAG_LAYOUT_NO_LIMITS: allow positioning into status-bar /
                // nav-bar regions if the field happens to extend there.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rect.left
                y = rect.top
            }
            try {
                wm.addView(wordHighlightView, wordHighlightParams)
                // Fade-in pulse: 0 → 1.0 → 0.65 → 1.0 over ~520ms. Tells
                // the user "the bubble is watching this field" without
                // touching its state. Patent-clean — pure overlay alpha.
                wordHighlightView?.alpha = 0f
                wordHighlightView?.animate()?.alpha(1f)?.setDuration(220)
                    ?.withEndAction {
                        wordHighlightView?.animate()?.alpha(0.65f)?.setDuration(160)
                            ?.withEndAction {
                                wordHighlightView?.animate()?.alpha(1f)?.setDuration(140)
                                    ?.start()
                            }?.start()
                    }?.start()
            } catch (e: Exception) {
                Log.w(TAG, "showWordHighlight: addView failed: ${e.message}")
                wordHighlightView = null
                wordHighlightParams = null
                return
            }
        } else {
            // Re-position to track the current cycle's field rect.
            val params = wordHighlightParams ?: return
            params.x = rect.left
            params.y = rect.top
            params.width = rect.width()
            params.height = rect.height()
            try { wm.updateViewLayout(wordHighlightView, params) } catch (_: Exception) {}
        }
        // Sub-region horizontal estimate: most apps don't expose per-
        // character bounds via accessibility, so we approximate the
        // word's x-position from its character-offset ratio inside the
        // injection. Rough but better than full-field underline; lands
        // the highlight in the right horizontal neighborhood for
        // single-line and most short multi-line cases.
        val injectLen = ClarifierController.injectionTextLength()
        if (injectLen > 0 && rangeStart >= 0 && rangeEnd > rangeStart) {
            val fieldWidthPx = rect.width().toFloat()
            val startFrac = (rangeStart.toFloat() / injectLen).coerceIn(0f, 1f)
            val endFrac = (rangeEnd.toFloat() / injectLen).coerceIn(0f, 1f)
            // Pad each side slightly so a wrapped line that puts the word
            // at the very start of a row still gets a visible underline.
            val pad = fieldWidthPx * 0.04f
            val startPx = (startFrac * fieldWidthPx - pad).coerceAtLeast(0f)
            val endPx = (endFrac * fieldWidthPx + pad).coerceAtMost(fieldWidthPx)
            wordHighlightView?.setSubRange(startPx, endPx)
        } else {
            wordHighlightView?.clearSubRange()
        }
        Log.i(TAG, "showWordHighlight: range=$rangeStart..$rangeEnd field=$rect injectLen=$injectLen")
    }

    fun hideWordHighlight() {
        val view = wordHighlightView ?: return
        val wm = windowManager
        try { wm?.removeView(view) } catch (_: Exception) {}
        wordHighlightView = null
        wordHighlightParams = null
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
        val view = bubbleView
        val params = bubbleParams
        if (view == null || params == null) {
            Log.w(TAG, "updateBubbleAppearance skipped — bubble not visible (view=$view params=$params)")
            return
        }
        val opacityPct = WimApp.bubbleOpacity().coerceIn(20, 100)
        val sizePct = WimApp.bubbleSize().coerceIn(70, 150)
        val opacity = opacityPct / 100f
        val scale = sizePct / 100f

        // Window stays fixed at MAX size (set in showBubble at 180dp = 120 * 1.5).
        // Only the bezel (and its children: knob, label, time) are scaled. This
        // keeps the bezel's visual center anchored to the window center across
        // all sizes — no drift, and no clipping at max scale.
        val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: run {
            Log.w(TAG, "updateBubbleAppearance: bezel view not found")
            return
        }
        bezel.alpha = opacity
        // Pivot at the bezel's measured center so scaling grows/shrinks
        // around the visual center, not the top-left corner.
        if (bezel.width > 0) {
            bezel.pivotX = bezel.width / 2f
            bezel.pivotY = bezel.height / 2f
        }
        // Cancel any in-flight bezel animations (taffy stretch from a prior
        // drag, snap-back interpolator) — those keep running and overwrite
        // the scale we set here, which masks the slider as "broken."
        bezel.animate().cancel()
        bezel.scaleX = scale
        bezel.scaleY = scale
        bezel.invalidate()
        Log.i(TAG, "updateBubbleAppearance: size=${sizePct}% opacity=${opacityPct}% bezelScale=$scale bezelW=${bezel.width} pivot=(${bezel.pivotX},${bezel.pivotY})")
    }

    // Trigger thresholds for drag-to-top (open menu) and drag-to-bottom
    // (hide bubble). 40dp top keeps the menu trigger from firing during
    // normal repositioning.
    private val MENU_TRIGGER_DP = 40
    private val HIDE_TRIGGER_DP = 80

    private fun showMenu() {
        if (menuView != null) return
        dismissMenu()

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_WhatIMeant)
        val inflater = LayoutInflater.from(themedContext)
        menuView = inflater.inflate(R.layout.bubble_menu, null)

        // Per-theme palette: comment-bubble fill/stroke + matched text colors
        // so the menu reads as the bubble's own speech.
        val palette = CommentBubbleDrawable.paletteFor(WimApp.bubbleTheme())

        // Speech-bubble background with downward tail centered at bottom — tail
        // tip lands on the floating bubble's top edge.
        menuView!!.background = CommentBubbleDrawable(
            fillColor = palette.fill,
            strokeColor = palette.stroke,
            displayMetrics = resources.displayMetrics,
            backgroundStyle = CommentBubbleDrawable.backgroundStyleFor(WimApp.bubbleTheme())
        )

        // On-FILL text color (section labels, slider labels/values, Reset, Settings)
        // adapts per theme so it stays readable against the menu surface:
        //   Bronze hex / Silver hex → dark grey
        //   Gunmetal carbon weave   → brand cream
        // Chips (engraved nameplates) have their own dark background, so chip
        // text stays brand cream universally regardless of theme.
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
            menuView!!.findViewById<TextView>(id)?.setTextColor(color)
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

        // Anticipatory prediction chip — populated at menu-open time so
        // it reflects whichever app the user is currently in. Hidden when
        // PredictionEngine has nothing high-confidence to say.
        menuView!!.findViewById<TextView>(R.id.menuPredictionChip)?.let { chip ->
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
            // Real x/y are set by updateMenuPositionFromBubble() once measured
            x = 0
            y = 0
        }
        menuParams = mParams

        // Load current values
        val opacity = WimApp.bubbleOpacity().coerceIn(20, 100)
        val size = WimApp.bubbleSize().coerceIn(70, 150)
        val liveEnabled = WimApp.prefs.getBoolean("live_transcript", false)

        val accentTint = android.content.res.ColorStateList.valueOf(palette.accent)
        val trackTint = android.content.res.ColorStateList.valueOf(palette.sliderTrack)

        val opacitySeek = menuView!!.findViewById<SeekBar>(R.id.menuOpacitySeek)
        val opacityValue = menuView!!.findViewById<TextView>(R.id.menuOpacityValue)
        opacitySeek.progress = opacity
        opacityValue.text = "$opacity"
        opacitySeek.thumbTintList = accentTint
        opacitySeek.progressTintList = accentTint
        opacitySeek.progressBackgroundTintList = trackTint

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(20, 100)
                opacityValue.text = "$pct"
                WimApp.prefs.edit().putInt("bubble_opacity", pct).apply()
                updateBubbleAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val sizeSeek = menuView!!.findViewById<SeekBar>(R.id.menuSizeSeek)
        val sizeValue = menuView!!.findViewById<TextView>(R.id.menuSizeValue)
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
                updateBubbleAppearance()
                // Bubble grew/shrank — re-anchor menu tail to new bubble top
                updateMenuPositionFromBubble()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val liveToggle = menuView!!.findViewById<MaterialSwitch>(R.id.menuLiveToggle)
        liveToggle.isChecked = liveEnabled
        liveToggle.thumbTintList = accentTint
        liveToggle.trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.stroke, palette.sliderTrack)
        )
        liveToggle.scaleX = 0.75f
        liveToggle.scaleY = 0.75f
        liveToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("live_transcript", checked).apply()
        }

        // Layer selector — 3 buttons mapped to layers 1/2/4. Profile (L3) is
        // dropped from the menu by design; access via Settings.
        val layerEntries = listOf(
            R.id.menuLayer1 to 1,
            R.id.menuLayer2 to 2,
            R.id.menuLayer4 to 4
        ).map { (id, layerVal) -> menuView!!.findViewById<TextView>(id) to layerVal }
        val currentLayer = WimApp.layer()
        // Engraved nameplate chips use the state_selected layer of nameplate_bg
        // for the active visual (maroon fill + cream highlight border). Text
        // brightens to brandCreamBright when active.
        layerEntries.forEach { (btn, layerVal) ->
            val isActive = layerVal == currentLayer
            btn.isSelected = isActive
            btn.setTextColor(if (isActive) brandCreamBright else brandCream)
            btn.setOnClickListener {
                WimApp.prefs.edit().putInt("layer", layerVal).apply()
                layerEntries.forEach { (b, _) ->
                    b.isSelected = false
                    b.setTextColor(brandCream)
                }
                btn.isSelected = true
                btn.setTextColor(brandCreamBright)
                updateIdleDisplay()
            }
        }

        // L1 source sub-row — LOCAL vs CLOUD, indented under TRANSCRIPTION.
        // Active source is reflected via nameplate_bg state_selected (maroon
        // fill + cream border) so the user sees the current setting at a
        // glance whenever the menu is open.
        val l1LocalBtn = menuView!!.findViewById<TextView>(R.id.menuL1Local)
        val l1CloudBtn = menuView!!.findViewById<TextView>(R.id.menuL1Cloud)
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
            WimApp.prefs.edit().putBoolean("l1_cloud_asr", true).apply()
            paintL1Source()
        }

        // Bubble color picker — three tiny replicas of the bubble bezel itself.
        // Selecting one literally swaps the floating bubble's metal. The selector
        // drawables flip to a cream glow ring on state_selected.
        val themeBtns = mapOf(
            "bronze" to menuView!!.findViewById<View>(R.id.menuThemeBronze),
            "aluminum" to menuView!!.findViewById<View>(R.id.menuThemeAluminum),
            "gunmetal" to menuView!!.findViewById<View>(R.id.menuThemeGunmetal)
        )
        val currentTheme = WimApp.bubbleTheme()
        themeBtns.forEach { (themeKey, btn) ->
            btn.isSelected = (themeKey == currentTheme)
            btn.setOnClickListener {
                WimApp.prefs.edit().putString("bubble_theme", themeKey).apply()
                applyBubbleTheme()
                dismissMenu()
                showMenu()
            }
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

        // Measure the menu synchronously so initial x/y land on the bubble's
        // top-center before the first frame renders. With OVERLAY windows
        // post-layout positioning fires unreliably; without this the menu
        // ends up at (0,0) — behind the status bar — and looks like it
        // didn't open at all.
        menuView!!.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mW0 = menuView!!.measuredWidth
        val mH0 = menuView!!.measuredHeight
        val bp0 = bubbleParams
        val bv0 = bubbleView
        if (bp0 != null && bv0 != null && mW0 > 0 && mH0 > 0) {
            val bubbleWidth = if (bp0.width > 0) bp0.width else bv0.width
            val bubbleHeight = if (bp0.height > 0) bp0.height else bv0.height
            val bezelTopOffset = (bubbleHeight * 0.20).toInt()
            val tailOverlap = 0
            mParams.x = bp0.x + bubbleWidth / 2 - mW0 / 2
            mParams.y = bp0.y - mH0 + bezelTopOffset + tailOverlap
        }

        windowManager?.addView(menuView, mParams)
        // Refine after final layout in case wrap_content settled differently.
        menuView!!.post { updateMenuPositionFromBubble() }
    }

    /**
     * Snap the floating bubble to the canonical menu-anchor position
     * (centered horizontally, 340dp from top) over 220ms. Called from
     * BOTH menu-open paths — drag-to-top and double-tap — so the
     * comment-bubble orientation, tail-on-bezel landing, and animation
     * feel are identical regardless of how the user opens the menu.
     * Caller invokes showMenu() right after; the per-frame
     * updateMenuPositionFromBubble() call inside the animator keeps the
     * menu's tail tip glued to the bezel as the bubble glides into place.
     */
    private fun snapBubbleForMenuOpen() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val dp = resources.displayMetrics.density
        val targetX = (resources.displayMetrics.widthPixels - view.width) / 2
        val targetY = (340 * dp).toInt()
        val startX = params.x
        val startY = params.y
        if (startX == targetX && startY == targetY) return
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                updateMenuPositionFromBubble()
            }
        }
        anim.start()
    }

    /**
     * Reposition the menu so its downward-tail tip lands on the floating
     * bubble's top center. Called from showMenu() (initial), the bubble drag
     * MOVE handler, moveBubbleTo() (dock-pickup pipe), and the parkIntoDock
     * animation update listener — every place the bubble's window position
     * can change while the menu is visible.
     */
    private fun updateMenuPositionFromBubble() {
        val mView = menuView ?: return
        val mParams = menuParams ?: return
        val bp = bubbleParams ?: return
        val bv = bubbleView ?: return
        val mWidth = if (mView.width > 0) mView.width else mView.measuredWidth
        val mHeight = if (mView.height > 0) mView.height else mView.measuredHeight
        if (mWidth == 0 || mHeight == 0) return
        // Prefer params.width — bv.width can lag a frame after a size change
        val bubbleWidth = if (bp.width > 0) bp.width else bv.width
        val bubbleHeight = if (bp.height > 0) bp.height else bv.height
        val bubbleCenterX = bp.x + bubbleWidth / 2
        val bubbleTop = bp.y
        // Bubble window is 120dp; visible bezel inside is 72dp centered → ~20% top
        // padding before the bezel. Push menu down by that padding so the tail tip
        // lands ON the bezel circle, not the invisible window edge above it. Plus
        // a small overlap so the tail visually merges into the bezel.
        val bezelTopOffset = (bubbleHeight * 0.20).toInt()
        val tailOverlap = 0
        mParams.x = bubbleCenterX - mWidth / 2
        mParams.y = bubbleTop - mHeight + bezelTopOffset + tailOverlap
        try { windowManager?.updateViewLayout(mView, mParams) } catch (_: Exception) {}
    }

    private fun dismissMenu() {
        menuView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            menuView = null
            menuParams = null
        }
    }

    /**
     * Build the comment-bubble shaped transcript bar (theme-tinted, solid,
     * borderless, anchored above the floating bubble with tail tip on the
     * bezel top). Used by BOTH showLiveTranscript (live partials) and
     * showPipelineStage (post-stop pipeline status). Single visual surface
     * for everything that overlays the bubble.
     */
    private fun ensureTranscriptBar() {
        if (liveTextView != null) return
        val dp = resources.displayMetrics.density
        val barWidthPx = (220 * dp).toInt()
        val palette = CommentBubbleDrawable.paletteFor(WimApp.bubbleTheme())
        val styleFor = CommentBubbleDrawable.backgroundStyleFor(WimApp.bubbleTheme())
        val opaqueFill = (palette.fill or 0xFF000000.toInt())
        val bgDrawable = CommentBubbleDrawable(
            fillColor = opaqueFill,
            strokeColor = palette.stroke,
            displayMetrics = resources.displayMetrics,
            backgroundStyle = styleFor,
            drawOutline = false
        )
        val tv = TextView(this).apply {
            setTextColor(palette.primaryText)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (22 * dp).toInt())
            minLines = 2
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = ""
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        liveTextView = tv
        TranscriptBarController.bind { text -> liveTextView?.text = text }

        val container = android.widget.FrameLayout(this).apply {
            background = bgDrawable
            addView(tv)
        }
        liveTextContainer = container

        val bp = bubbleParams ?: run {
            Log.w(TAG, "ensureTranscriptBar: bubbleParams null — cannot position")
            return
        }
        val bubbleHeight = if (bp.height > 0) bp.height else (120 * dp).toInt()
        val bubbleTop = bp.y
        val bubbleCenterX = bp.x + (if (bp.width > 0) bp.width else 360) / 2
        val screenW = resources.displayMetrics.widthPixels
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
        liveTextParams = ltParams
        try { windowManager?.addView(container, ltParams) } catch (_: Exception) {}

        val bezelTopOffset = (bubbleHeight * 0.20).toInt()
        val anchor: () -> Unit = {
            val measuredH = container.height.coerceAtLeast(1)
            val newY = (bubbleTop - measuredH + bezelTopOffset).coerceAtLeast(0)
            if (ltParams.y != newY) {
                ltParams.y = newY
                try { windowManager?.updateViewLayout(container, ltParams) } catch (_: Exception) {}
            }
        }
        container.post(anchor)
        container.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((bottom - top) != (oldBottom - oldTop)) anchor()
        }
    }

    private fun showLiveTranscript() {
        val enabled = WimApp.prefs.getBoolean("live_transcript", false)
        if (!enabled) return
        ensureTranscriptBar()
        // Restore growable mode for live partials (vs pipeline-stage's locked 3 lines)
        liveTextView?.minLines = 2
        liveTextView?.maxLines = 6
        stopStagePulse()
        liveTextView?.text = "● Recording…"
    }

    private fun updateLiveTranscript(text: String) {
        liveTextView?.text = text
    }

    // Pipeline-stage state — premium typewriter rendering. Title is
    // static (no flickering glyph, no breathing alpha — those read as
    // "epileptic" per operator). Body reveals char-by-char at 22ms/char.
    // After body finishes typing, a single ▌ cursor blinks at 500ms cycle.
    private var stageLabel: String = ""
    private var stageDetail: String = ""
    private var typewriterIdx = 0
    private var cursorVisible = true
    private val stageAnimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val TYPEWRITER_INTERVAL_MS = 22L
    private val CURSOR_BLINK_MS = 500L

    fun showPipelineStage(stage: String, detail: String) {
        // L1 cloud (whisper-1 API) is the silent fast path. The bubble's
        // own PROC color + bezel arc are enough feedback — narrating
        // "Transcribing → Cleaning up → Cleaned" via the comment bubble
        // adds no latency but reads as new slowness on a path the user
        // expects to feel instant. Demo script overrides via activeDemoScript.
        val isL1Cloud = WimApp.layer() < 2 && WimApp.l1CloudAsr()
        if (isL1Cloud && activeDemoScript == null) return
        // Reuse the comment-bubble shaped transcript bar. Pipeline state lives
        // in the SAME surface as live partials. Locked to 3 lines so the bar
        // doesn't jump between transcribe/recon/falcon calls. Cross-fades on
        // each change. Three loading animations stack: rotating tape-reel glyph,
        // breathing color on the stage label, typewriter dots after it.
        ensureTranscriptBar()
        val tv = liveTextView ?: return
        tv.minLines = 3
        tv.maxLines = 3
        tv.ellipsize = android.text.TextUtils.TruncateAt.END

        stageLabel = stage
        stageDetail = detail
        startStageAnimations()

        // Cross-fade: dim → settle → restore
        tv.animate().alpha(0.3f).setDuration(110)
            .withEndAction {
                renderStageFrame()
                tv.animate().alpha(1f).setDuration(180).start()
            }.start()
    }

    private fun renderStageFrame() {
        val tv = liveTextView ?: return

        // Theme-aware palette — bronze/aluminum have LIGHT fills (need dark
        // text), gunmetal has DARK fill (needs cream-gold text). Single source
        // of truth as the live-transcript surface.
        val palette = CommentBubbleDrawable.paletteFor(WimApp.bubbleTheme())
        val labelRgb = palette.primaryText and 0x00FFFFFF
        val detailColor = palette.secondaryText
        val labelColor = 0xFF000000.toInt() or labelRgb

        // Body text reveals char-by-char per typewriterIdx. Trailing cursor
        // is visible while typing OR blinking after body completes.
        val typedBody = if (typewriterIdx >= stageDetail.length) stageDetail
                        else stageDetail.substring(0, typewriterIdx)
        val cursorChar = if (cursorVisible) "▌" else " "

        val styled = android.text.SpannableStringBuilder().apply {
            val titleStart = length
            append(stageLabel)
            setSpan(android.text.style.ForegroundColorSpan(labelColor),
                titleStart, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                titleStart, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            val detailStart = length
            append(typedBody)
            append(cursorChar)
            setSpan(android.text.style.RelativeSizeSpan(0.85f),
                detailStart, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(detailColor),
                detailStart, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = styled
    }

    // Two tickers — typewriter advance (22ms/char while body still has
    // un-revealed characters) and cursor blink (500ms toggle). Title is
    // static. No glyph rotation, no breathing alpha — those read as
    // "epileptic" per operator and added zero information.
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

    private fun hideLiveTranscript() {
        stopStagePulse()
        liveTextContainer?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        liveTextContainer = null
        liveTextView = null
        liveTextParams = null
        TranscriptBarController.unbind()
    }

    /**
     * Drive the bezel's stage arc — 0=Transcribing, 1=Reconstructing,
     * 2=Verifying (Falcon), 3=Injecting. -1 resets to all dim.
     * Theme color follows the current bubble theme so the arcs harmonize
     * with bronze/aluminum/gunmetal palettes.
     */
    private fun setStageArc(stage: Int) {
        val arc = bubbleView?.findViewById<BezelStageArcView>(R.id.bubbleStageArc) ?: return
        // Per-theme arc color so all four quadrants read as "quarter
        // separation" on every bezel. Aluminum's light silver bezel needs
        // a much darker arc than bronze's warm copper — `#7A1F1F` at 40%
        // alpha washed out on silver. `#2A0606` (near-black with a maroon
        // undertone) stays in the brand-maroon family but reads dark-on-
        // light. Bronze keeps the deep maroon that harmonizes with the
        // warm metal. Gunmetal stays cream-gold on dark steel.
        val arcColor = when (WimApp.bubbleTheme()) {
            "gunmetal" -> 0xFFC8B48A.toInt()
            "aluminum" -> 0xFF2A0606.toInt()
            else -> 0xFF7A1F1F.toInt()
        }
        arc.setArcColor(arcColor)
        if (stage < 0) {
            arc.reset()
            return
        }
        arc.setActiveStage(stage)
    }

    /** Public wrapper for BubbleDemoScript to drive the stage arc. */
    fun setStageArcPublic(stage: Int) = setStageArc(stage)

    /**
     * Replay the bubble's demo parade. Bound to long-press on footer
     * Profile in MainActivity. Idempotent — no-op if a demo is already
     * running. Same finish callback as the onboarding entry path so the
     * end state is identical.
     */
    fun runFeatureParade() {
        if (activeDemoScript != null) return
        if (bubbleView == null) showBubble()
        val finish: () -> Unit = {
            activeDemoScript = null
            activeDemoFinish = null
            removeOnboardingOverlay()
            showPipelineStage("🎤", "Close the app — I'm your app now. Tap me whenever you need to talk.")
            timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
        }
        activeDemoFinish = finish
        activeDemoScript = BubbleDemoScript(finish).also { it.start() }
    }

    private fun setBubbleProcessing() {
        // Bezel stays in theme color. Knob and label both stay in copper_gold
        // — the bubble's brand color across recording AND processing — so the
        // visual reads as one continuous "bubble is busy" affordance. The
        // REC → PROC label change carries the state distinction; color
        // doesn't need to do double duty.
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_processing)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "PROC"
            setTextColor(0xFFFFD580.toInt())  // cream-gold — bright enough to read on red/green knob
        }
    }

    /**
     * Walk a reconstructed output, locate sentence boundaries, and record
     * each (last-3-words-before-period, next-word) pair into PredictionEngine.
     * Quiet on edge cases (single-sentence output, ending punctuation,
     * blank tokens) — we just want clean trigrams when they exist.
     */
    private fun recordPredictionsFromOutput(pkg: String, text: String) {
        if (text.isBlank()) return
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in 0 until tokens.size - 1) {
            val tok = tokens[i]
            if (tok.lastOrNull() in setOf('.', '?', '!')) {
                val priorStart = (i - 2).coerceAtLeast(0)
                val priorWords = tokens.subList(priorStart, i + 1)
                val rawNext = tokens[i + 1]
                // Strip any leading/trailing punctuation off the next word
                // so "Maybe." doesn't end up as a different trigram seed
                // than "maybe".
                val nextClean = rawNext.trim('"', '\'', '(', ')', ',', '.', '!', '?', ';', ':')
                if (nextClean.isNotBlank()) {
                    PredictionEngine.recordCompletion(pkg, priorWords, nextClean)
                }
            }
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
                val apiKey = WimApp.apiKey()
                val idToken = AuthManager.getIdToken()

                // Signed-in path: backend handles OpenAI key + tier + rate
                // limiting (action=command on wim-reconstruct). Fall back to
                // direct OpenAI only when no Firebase auth is present (dev).
                val result: String? = if (idToken != null) {
                    callBackendCommandTransform(idToken, source, command)
                } else if (apiKey.isNotBlank()) {
                    val systemPrompt = "You are a text transformation assistant. The user highlighted some text and spoke a command to modify it. Apply the command and return ONLY the transformed text, nothing else. Preserve the meaning. Do not add explanations, quotes, or prefixes."
                    val userContent = "TEXT:\n$source\n\nCOMMAND: $command"
                    callOpenAiTransform(apiKey, systemPrompt, userContent)
                } else {
                    null
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

    /**
     * Signed-in Command Mode path: POST {action: "command", source, command}
     * to the wim-reconstruct Cloud Function. Backend uses its own OpenAI key,
     * applies tier + daily-rate limits, returns {"transformed": "..."} or
     * {"error": "..."}. Returns null on any non-200 / network failure so
     * the caller can show "✕ Failed".
     */
    private suspend fun callBackendCommandTransform(
        idToken: String,
        source: String,
        command: String
    ): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val http = okhttp3.OkHttpClient.Builder()
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = org.json.JSONObject().apply {
                    put("action", "command")
                    put("source", source)
                    put("command", command)
                }
                val req = okhttp3.Request.Builder()
                    .url("https://us-central1-bakers-agent.cloudfunctions.net/wim-reconstruct")
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = http.newCall(req).execute()
                val respBody = resp.body?.string() ?: return@withContext null
                if (!resp.isSuccessful) {
                    Log.w(TAG, "CMD backend ${resp.code}: ${respBody.take(200)}")
                    return@withContext null
                }
                org.json.JSONObject(respBody).optString("transformed").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "CMD backend exception: ${e.message}")
                null
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
            onInjectComplete()
            return
        }

        val layer = WimApp.layer()
        val tone = WimApp.tone()
        val situation = WimApp.situation()
        ConsoleLog.log("Processing... [L$layer ${tone.uppercase()} $situation]", "info")
        val profile = WimApp.profile()

        // Rate-gap analysis: does the word count match the audio duration vs the
        // user's historical baseline? Low ratio = ASR dropped content (Samsung
        // pre-smooths "go go go go go" to "go go go"). Information-only; signals
        // downstream reconstruction that something may need recovery.
        val audioDurationSec = asrMs / 1000.0
        val rawWordCountForGap = rawText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val rateGap = RateGapDetector.analyze(rawWordCountForGap, audioDurationSec, profile.baselineWps())
        if (rateGap.wps > 0) profile.recordWps(rateGap.wps)
        if (rateGap.gapDetected) {
            Log.w(TAG, "Rate-gap: wps=${"%.2f".format(rateGap.wps)} baseline=${"%.2f".format(rateGap.baselineWps ?: 0.0)} ratio=${"%.2f".format(rateGap.ratio ?: 0.0)}")
            ConsoleLog.log("Rate-gap: ${((rateGap.ratio ?: 0.0) * 100).toInt()}% of baseline WPS — ASR likely dropped content", "warn")
        }

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

        // FSM: ASR text in hand, entering disfluency-strip phase.
        BubbleStateBus.advance(BubbleState.Stripping(System.currentTimeMillis()))

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
        // Merge user profile fillers with language-pack fillers (empty for English).
        val fillerWords = profile.getFillerWords() + LangPack.fillers()
        showPipelineStage("\u29BB Cleaning up...", "Removing disfluencies & fillers")
        val preCleaned = if (layer >= 4) deHallucinated else DisfluencyFilter.filter(
            deHallucinated,
            fillerWords,
            LangPack.naturalRepeats()
        )
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
            // FSM: L1 still goes through Reconstructing → Verified → Injected;
            // the "reconstruction" is deterministic (PhoneticMatcher + Repunctuate).
            BubbleStateBus.advance(BubbleState.Reconstructing(System.currentTimeMillis(), layer = 1))
            setStageArc(1)
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
            // PhoneticMatcher intentionally NOT called at L1 (Lav parity —
            // lavrentiy.py phonetic_match gate `2 <= layer <= 3`). At L1 the
            // swap goes straight to paste with no validator, so a wrong-
            // direction Metaphone collision lands directly in the user's
            // text field. The swap pays off only when GPT sees the cleaner
            // input, which is L2/L3.
            // Deterministic capitalization + terminal punctuation. Idempotent
            // on already-punctuated text. Mirrors lavrentiy.py repunctuate().
            l1Text = Repunctuate.apply(l1Text)
            // FSM: L1 has no Falcon — the deterministic pass is "trusted by
            // construction." Verified → Injected before the actual paste so
            // observers see the right transition order.
            BubbleStateBus.advance(BubbleState.Verified(System.currentTimeMillis()))
            setStageArc(2)
            if (stripped > 0 || l1Text != preCleaned) {
                val corrCount = if (l1Text != preCleaned) " + corrections" else ""
                showPipelineStage("\u2713 Cleaned", "$stripped words stripped$corrCount \u00B7 ${rawWordCount}w \u2192 ${l1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}w")
            }
            ConsoleLog.log("-> \"${l1Text.take(80)}${if (l1Text.length > 80) "..." else ""}\"  [${l1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}w]", "out")
            if (WimAccessibilityService.instance == null) {
                Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
                setBubbleIdle()
                return
            }
            WimAccessibilityService.instance!!.injectText(l1Text)
            BubbleStateBus.advance(BubbleState.Injected(System.currentTimeMillis(), SuccessCue.PASTE))
            setStageArc(3)
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
            onInjectComplete()
            return
        }

        // L2+: reconstruct via GPT (with pre-cleaned input)
        // FSM: enter ReadingContext (audience-context detection runs while
        // the precedingContext is gathered via accessibility look-behind).
        BubbleStateBus.advance(BubbleState.ReadingContext(System.currentTimeMillis()))
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
        // Phonetic-neighbor correction before GPT — gives the reconstruction
        // prompt a cleaner input, reduces round-trip cost of "fix this word"
        // when the fix is just a phonetic neighbor of vocabulary. Gated to
        // L2/L3 only (Lav parity); at L4 Sonnet ext-think reasons through
        // phonetic context with the rich onset_weights prompt block instead.
        if (layer in 2..3) {
            correctedText = PhoneticMatcher.correct(correctedText, profile.getVocabulary(), profile.getOnsetWeights())
        }

        // L3+ gets profile vocabulary and corrections injected into the prompt
        val vocabulary = if (layer >= 3) profile.getVocabulary() else emptyList()
        val corrections = if (layer >= 3) profile.getCorrections() else emptyMap()

        scope.launch {
            // Hoisted so the catch block below can cancel the L4 stage-animation
            // loop on reconstruction failure — Kotlin catch is a sibling scope.
            var turboTaxJob: Job? = null
            // L4 reconstruction can run 60-70s on Sonnet ET; without a wake-lock
            // the screen turning off mid-call suspends the CPU and the round-trip
            // dies. PARTIAL_WAKE_LOCK keeps the CPU alive without the screen on.
            val wakeLock: android.os.PowerManager.WakeLock? = if (layer >= 4) {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                try {
                    pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WiM:L4Recon").apply {
                        setReferenceCounted(false)
                        acquire(120_000L)
                    }
                } catch (_: Exception) { null }
            } else null
            try {
                val layerLabel = when (layer) {
                    2 -> "generic cleanup"
                    3 -> "+ your vocabulary"
                    4 -> "stutter-aware"
                    else -> "processing"
                }
                showPipelineStage("\u29BB Reconstructing intent...", "$tone tone \u00B7 L$layer $layerLabel")

                // TurboTax engagement: animated stage messages for L4 (long processing)
                turboTaxJob = if (layer >= 4) {
                    scope.launch {
                        val stages = listOf(
                            "\u29BB Analyzing speech patterns..." to "Mapping phoneme difficulty",
                            "\u29BB Detecting disfluencies..." to "Blocks, prolongations, repetitions",
                            "\u29BB Building phoneme context..." to "Your personal speech profile",
                            "\u29BB Reconstructing intent..." to "Speech disfluency AI processing",
                            "\u29BB Validating meaning..." to "Making sure nothing was lost"
                        )
                        for ((title, sub) in stages) {
                            showPipelineStage(title, sub)
                            kotlinx.coroutines.delay(1200)
                        }
                        // Stages done but cloud call still running — pulse elapsed time
                        // so the user knows it hasn't frozen.
                        var elapsedSec = (stages.size * 1200L) / 1000
                        while (true) {
                            showPipelineStage("⌛ Still working...", "Deep reconstruction — ${elapsedSec}s")
                            kotlinx.coroutines.delay(2000)
                            elapsedSec += 2
                        }
                    }
                } else null

                // Look-behind: scrape text already in the target field so the reconstructor
                // can disambiguate using conversational context. Skipped for password fields.
                val precedingContext = WimAccessibilityService.instance?.getPreCursorContext() ?: ""
                if (precedingContext.isNotBlank()) {
                    Log.i(TAG, "Look-behind context: ${precedingContext.length} chars")
                }

                // L2/L3/L4: cloud GPT-4o reconstruction. No local Gemma path —
                // reconstruction is the product's "shtick" and quality matters more
                // than cloud cost. (L4 will get its own dedicated setup later.)
                ConsoleLog.log("Reconstructing... [L$layer $tone gpt-4o]", "info")
                Log.i(TAG, "Calling cloud reconstruction (L$layer, $tone, sit=$situation)...")
                val primaryLanguage = WimApp.appLanguage()
                // Format the rate-gap signal as a model-visible prompt note.
                // Includes the actual numbers (wps, baseline, ratio percent)
                // so the model sees the diagnostic, not just the severity bump.
                val compressionRatioNote = if (rateGap.gapDetected) {
                    val wpsStr = "%.2f".format(rateGap.wps)
                    val baselineStr = rateGap.baselineWps?.let { "%.2f".format(it) } ?: "?"
                    val ratioPct = ((rateGap.ratio ?: 0.0) * 100).toInt()
                    "Speaker produced $wpsStr words/sec vs their baseline of $baselineStr wps " +
                        "($ratioPct% of baseline). Low ratio = ASR likely dropped content during " +
                        "blocks, extended pauses, or pre-smoothing where intended words did not " +
                        "surface in the transcription. Reconstruct with the assumption that some " +
                        "intended words are MISSING from the input — supply them from semantic context."
                } else ""
                BubbleStateBus.advance(BubbleState.Reconstructing(System.currentTimeMillis(), layer))
                setStageArc(1)
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
                    speechSeverityMod = if (rateGap.gapDetected) 0.3 else 0.0,
                    precedingContext = precedingContext,
                    languageCode = primaryLanguage,
                    compressionRatioNote = compressionRatioNote,
                    audiencePackage = WimAccessibilityService.currentForegroundPackage,
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
                BubbleStateBus.advance(BubbleState.Verified(System.currentTimeMillis()))
                setStageArc(2)
                showPipelineStage(falconLabel, "${rawWordCount}w \u2192 ${finalWordCount}w \u00B7 ${reconResult.reconMs}ms")

                // Brief pause so user can see the final stage
                kotlinx.coroutines.delay(800)

                if (WimAccessibilityService.instance != null) {
                    WimAccessibilityService.instance!!.injectText(outputText)
                    val injectCue = if (previousOutputs.isNotEmpty()) SuccessCue.REGEN_SUCCESS else SuccessCue.PASTE
                    BubbleStateBus.advance(BubbleState.Injected(System.currentTimeMillis(), injectCue))
                    setStageArc(3)
                    // Cache so the user can re-roll on the bezel without re-recording.
                    lastRegenCtx = RegenContext(
                        rawText = correctedText,
                        correctedText = correctedText,
                        tone = tone,
                        layer = layer,
                        situation = situation,
                        precedingContext = precedingContext,
                        languageCode = primaryLanguage,
                        rateGapDetected = rateGap.gapDetected,
                    )
                    previousOutputs.clear()
                    previousOutputs.add(outputText)
                    showRegenIcon()
                } else {
                    Log.e(TAG, "No accessibility service instance!")
                    Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
                }

                // L2+: learn from reconstruction results (skip if leak was filtered)
                if (layer >= 2 && !leakDetected) {
                    profile.learnFromReconstruction(preCleaned, outputText)
                }

                // L1 auto-detect: if the user has no L1 set and hasn't been prompted
                // yet, count reconstructions and after 5 fire a background L1-guess
                // call. On confidence >= 0.7 show a one-time prompt.
                if (layer >= 2 && profile.profileL1() == null && !profile.wasL1DetectPrompted()) {
                    profile.incrementL1DetectCount()
                    if (profile.l1DetectCount() >= 5) {
                        val textForGuess = outputText
                        val profileRef = profile
                        // Mark prompted BEFORE launching the thread so a network
                        // failure or exception doesn't leave wasL1DetectPrompted()
                        // false and spawn a new detection thread on every subsequent
                        // reconstruction (thread-per-call leak).
                        profileRef.markL1DetectPrompted()
                        Thread {
                            try {
                                val payload = org.json.JSONObject().apply {
                                    put("text", textForGuess.take(3000))
                                }.toString()
                                val url = java.net.URL("https://us-central1-bakers-agent.cloudfunctions.net/wim-l1-guess")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("Content-Type", "application/json")
                                conn.doOutput = true
                                conn.connectTimeout = 5000
                                conn.readTimeout = 5000
                                conn.outputStream.write(payload.toByteArray())
                                val responseCode = conn.responseCode
                                if (responseCode == 200) {
                                    val responseText = conn.inputStream.bufferedReader().readText()
                                    conn.disconnect()
                                    val responseJson = org.json.JSONObject(responseText)
                                    val topL1 = responseJson.optString("top_l1", "")
                                    val confidence = responseJson.optDouble("confidence", 0.0)
                                    if (topL1.isNotBlank() && confidence >= 0.7) {
                                        val displayName = topL1.replaceFirstChar { it.uppercaseChar() }
                                        Log.i(TAG, "L1 detected: $topL1 (confidence=$confidence)")
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            try {
                                                // Informational only — no auto-apply. User opens Profile
                                                // and sets their first language manually.
                                                val msg = "Detected $displayName-pattern English. Open Profile to set your first language."
                                                Toast.makeText(this@BubbleService, msg, Toast.LENGTH_LONG).show()
                                            } catch (_: Exception) {}
                                        }
                                    } else {
                                        Log.i(TAG, "L1 detection low confidence ($confidence) — skipping prompt")
                                        conn.disconnect()
                                    }
                                } else {
                                    Log.w(TAG, "L1 detection request failed (HTTP $responseCode)")
                                    conn.disconnect()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "L1 detection network error: ${e.message}")
                            }
                        }.start()
                    }
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

                // Adaptive learning: record sentence-boundary completions for
                // PredictionEngine. Each (last 3 words before a sentence-end
                // → first word of the next sentence) tuple gets stored per
                // foreground package. Powers `candidatesForPosition` for the
                // clarifier (claim 14: "your bubble learned how you talk to
                // your boss in Slack") plus future on-bubble prediction chips.
                val predPkg = WimAccessibilityService.currentForegroundPackage
                if (WimApp.predictionsEnabled() && !predPkg.isNullOrBlank()) {
                    recordPredictionsFromOutput(predPkg, outputText)
                }
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

                // Clarifier check on the just-completed reconstruction.
                // Multi-pass runs on every reconstruction layer (L2 / L3 / L4)
                // — the clarifier is the differentiating feature most users
                // will see, so gating it cost-conservatively defeats the
                // point. L1 has no reconstruction step, so this branch never
                // fires for L1 anyway.
                val clarifierPkg = WimAccessibilityService.currentForegroundPackage
                if (WimApp.isClarifierAllowed(clarifierPkg)) {
                    var wordConfidences: List<Clarifier.WordConfidence> = emptyList()
                    val wav = lastWavFile
                    if (layer >= 2 && wav != null && wav.exists() && wav.length() > 44) {
                        try {
                            showPipelineStage("⟳ Reviewing", "Cross-checking unsure words")
                            val recognizer = WhisperApiL1(
                                model = "whisper-1",
                                language = primaryLanguage,
                                patienceMode = true,
                            )
                            val multi = MultiPassDecoder(recognizer).run(wav)
                            wordConfidences = multi.wordConfidences
                            Log.i(TAG, "MultiPass (L$layer): ${multi.disagreementSummary.size} disagreement positions, ${multi.wordConfidences.count { it.multiPassDisagreements > 0 }} flagged words")
                        } catch (e: Exception) {
                            Log.w(TAG, "MultiPassDecoder failed: ${e.message} — clarifier proceeds with empty confidences")
                        }
                    }
                    val flags = Clarifier.evaluate(
                        finalText = outputText,
                        wordConfidences = wordConfidences,
                        vocabulary = vocabulary,
                        onsetWeights = profile.getOnsetWeights(),
                        recentSwaps = ClarifierController.recentSwapsSnapshot(),
                        candidateProvider = if (WimApp.predictionsEnabled()) PredictionEngine else null,
                        contextPkg = clarifierPkg,
                        threshold = WimApp.clarifierSensitivityThreshold(),
                    )
                    if (flags.isNotEmpty()) {
                        val supported = WimAccessibilityService.instance?.focusedAppSupportsAutoSend() == true
                        val autoSendOn = WimApp.autoSendEnabled()
                        enterClarifierCycle(
                            flags = flags,
                            autoSendEligible = autoSendOn && supported,
                        )
                        return@launch  // Cycle owns post-inject UX; onExit fires
                                       // setBubbleSendReady or setBubbleIdle.
                    }
                }

                onInjectComplete()
            } catch (e: Exception) {
                turboTaxJob?.cancel()
                BubbleStateBus.advance(BubbleState.Failed(FailReason.NETWORK))
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
                try {
                    Toast.makeText(this@BubbleService, "Reconstruction failed — raw text used", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                setBubbleIdle()
            } finally {
                try { wakeLock?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Routine status — required for the foreground service to exist, but
        // suppressed at the launcher (no badge, no sound, low importance).
        val statusChannel = NotificationChannel(
            CHANNEL_ID,
            "WiM Voice Dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the voice dictation bubble active"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        // Permission/error alerts — badge ON, higher importance so the user
        // notices when accessibility / mic / overlay permission is missing.
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "WiM Permission Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shown when WiM needs a permission to function"
            setShowBadge(true)
        }
        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun buildNotification(text: String = "Voice dictation active"): Notification {
        val showIntent = Intent(this, BubbleService::class.java).apply { action = "SHOW" }
        val pendingIntent = android.app.PendingIntent.getService(
            this, 0, showIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Notification-shade quick action — opens the bubble menu without
        // having to interact with the bubble itself. Conflict-free with
        // every gesture on the bubble.
        val menuIntent = Intent(this, BubbleService::class.java).apply { action = "OPEN_MENU" }
        val menuPending = android.app.PendingIntent.getService(
            this, 1, menuIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("What I Meant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setBadgeIconType(Notification.BADGE_ICON_NONE)
            .setNumber(0)
            .addAction(android.R.drawable.ic_menu_preferences, "MENU", menuPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Permission-broken alert. Routes through the high-importance alert
     * channel so the launcher icon shows a real badge — the user sees a
     * signal that's distinct from the routine "service is running" state.
     * Cleared by [clearPermissionAlert] once permissions are restored.
     */
    private fun showPermissionAlert(missingLabel: String) {
        val openSettings = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 100, openSettings,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("WiM needs a permission")
            .setContentText("Missing $missingLabel — tap to fix")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setNumber(1)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notif)
    }

    private fun clearPermissionAlert() {
        getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
    }

    /**
     * True if WiM's accessibility service is enabled. Reinstalling the APK
     * silently revokes accessibility on Samsung — without this check the
     * bubble appears normally but inject and auto-send fail invisibly.
     */
    private fun isAccessibilityEnabled(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val ours = "$packageName/${WimAccessibilityService::class.java.name}"
        val oursShort = "$packageName/.WimAccessibilityService"
        return flat.split(":").any { it.equals(ours, ignoreCase = true) || it.equals(oursShort, ignoreCase = true) }
    }

    private fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Surfaces missing-permission state so the user is never confused about
     * why the bubble "looks fine but does nothing." Toast + label badge on
     * the bubble + notification text update.
     */
    private fun alertOnMissingPermissions() {
        val missing = mutableListOf<String>()
        if (!isAccessibilityEnabled()) missing += "Accessibility"
        if (!hasMicPermission()) missing += "Mic"
        if (missing.isEmpty()) {
            // All permissions granted — clear any stale alert so the launcher
            // badge goes away the moment the user fixes the permission.
            clearPermissionAlert()
            return
        }

        val label = missing.joinToString(" + ")
        val msg = "WiM needs $label permission — settings → accessibility → What I Meant"
        Log.w(TAG, "Permissions missing: $label")
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "FIX"
            setTextColor(0xFFE05050.toInt())
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            text = "PERM"
            setTextColor(0xFFE05050.toInt())
        }
        // Route through the high-importance alert channel — launcher badge ON,
        // distinct from the routine status notification.
        showPermissionAlert(label)
    }

    override fun onDestroy() {
        instance = null
        sensorManager?.unregisterListener(shakeListener)
        stopHotwordListener()
        dismissMenu()
        hideLiveTranscript()
        dafPlayer?.stop()
        dafPlayer = null
        audioRecorder?.release()
        audioRecorder = null
        hideBubble()
        onDeviceRecognizer?.destroy()
        moonshineEngine?.destroy()
        whisperLocalEngine?.destroy()
        bubblePersona?.unbind()
        bubblePersona = null
        bubbleAffect?.unbind()
        bubbleAffect = null
        TranscriptBarController.unbind()
        IdleTimer.cancelAll()
        VoiceCommandRouter.unregister("clarifier")
        ClarifierController.reset()
        timerHandler.removeCallbacksAndMessages(null)
        audioPlaybackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        try { scoReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }
}
