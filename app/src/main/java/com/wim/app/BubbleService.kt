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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BubbleService : Service() {

    // Double-shake gesture extracted to BubbleShakeDetector (2026-05-25,
    // first slice of the god-object split). Owns sensor lifecycle, rising-
    // edge state, and the prefs toggle. The action fires via the callback
    // wired at start().
    private var shakeDetector: BubbleShakeDetector? = null

    // "Hey WiM" hotword — opt-in continuous mic listener that triggers
    // a recording when it hears the wake phrase. Mutually exclusive with
    // active recording (only one consumer can hold the mic).
    // "Hey WiM" hotword wiring extracted to BubbleHotword 2026-05-25 —
    // fifteenth slice. Owns the recognizer lifecycle + mic-handoff +
    // pref auto-disable on unavailable device.
    private var hotword: BubbleHotword? = null

    // Notification channels + foreground notification + permission alerts
    // extracted to BubbleStatusUI 2026-05-25 — sixteenth slice. Channel IDs
    // and notification IDs stay as service constants.
    private var statusUI: BubbleStatusUI? = null

    // Bezel 4-quadrant stage arc + PROC processing-label paint extracted
    // to BubbleStageArc 2026-05-25 — eighteenth slice.
    private var stageArc: BubbleStageArc? = null

    // L4 multi-pass decoder needs the recorded WAV after reconstruction
    // finishes so the clarifier check can run cross-pass disagreement
    // analysis. Cleaned up in setBubbleIdle / before next recording.
    // @Volatile because the MultiPassDecoder coroutine reads this on an
    // IO worker while setBubbleIdle (main thread) can null+delete it.
    @Volatile
    private var lastWavFile: java.io.File? = null

    // Tracks the in-flight reconstruction coroutine so a fresh startListening()
    // can cancel it. Architecture critic's RecordingSession value (closes the
    // stale-paste-into-new-recording race + orphaned turboTaxJob loop via one
    // cancel) without the full ~600 LOC extraction.
    private var currentReconJob: Job? = null

    // One-time Toast guard: fires at most once per service lifetime when
    // Sonnet L4 throws and the GPT-4o fallback runs.
    @Volatile
    private var l4DowngradeNotified = false

    // Patent-clean word-highlight overlay (claim 13) extracted to
    // BubbleWordHighlight 2026-05-25 — first leaf-overlay slice of the
    // BubbleWindow cluster. This class owns the wrapper that fetches the
    // field rect + injection length; the overlay itself is the new file.
    private var wordHighlight: BubbleWordHighlight? = null

    private var windowManager: WindowManager? = null
    var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var preJumpX = 0
    private var preJumpY = 0
    private var facehuggerActive = false
    private var audioPlaybackCallback: android.media.AudioManager.AudioPlaybackCallback? = null
    // Bluetooth SCO bring-up + state tracking extracted to BubbleBluetoothSco
    // 2026-05-25 — sixth slice. Owns the SCO receiver + routing call.
    private var bluetoothSco: BubbleBluetoothSco? = null
    private var isRecording = false
        set(value) {
            if (field != value) {
                field = value
                RecordingStateBus.emit(value)
            }
        }
    private var vibrator: Vibrator? = null
    private var onDeviceRecognizer: OnDeviceRecognizer? = null
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

    // Command Mode cluster extracted to BubbleCommandMode 2026-05-25 —
    // ninth slice. State (armed flag + selection) lives in the new class.
    private var commandMode: BubbleCommandMode? = null

    // Dock snap + park-into-dock animation extracted to BubbleDock
    // 2026-05-25 — tenth slice. tryDockSnap() stays as a thin public
    // wrapper so MainActivity's existing BubbleService.instance call
    // continues to work without changes.
    private var bubbleDock: BubbleDock? = null

    // Bubble visual properties (theme + size + opacity) extracted to
    // BubbleAppearance 2026-05-25 — eleventh slice. applyBubbleTheme()
    // + updateBubbleAppearance() become thin wrappers; refreshBubbleTheme()
    // public alias stays.
    private var bubbleAppearance: BubbleAppearance? = null
    private var lastRawText = ""
    private var finalizedSegments = ""
    private var processed = false
    // Auto Quiet Mode: set when the last take was boosted as whisper-soft, so
    // setBubbleIdle() can flash the amber confirmation on the bubble.
    private var pendingQuietAutoFlash = false
    // Set true when stopAndProcess fell to the "no wav, use on-device text"
    // branch and is waiting for the Final ASR callback. handleFinal checks
    // this and kicks off processText if it arrives after the fallback timer
    // already gave up (common with whisper-tiny.en sherpa-onnx local — decode
    // + thread-hop latency can exceed the timer window).
    @Volatile private var awaitingPostStopAsr = false
    private val postStopAsrFallback = Runnable {
        if (!awaitingPostStopAsr) return@Runnable
        val onDeviceText = lastRawText.trim()
        if (onDeviceText.isNotBlank()) {
            // Text already arrived (via partial or finalized segment) — process it.
            awaitingPostStopAsr = false
            Log.i(TAG, "No wav, using on-device text: $onDeviceText")
            processText(onDeviceText)
        } else {
            // No text yet — DO NOT disable handleFinal's recovery path.
            // whisper-tiny decode can take 5-15 seconds for long audio; the
            // Final ASR may still arrive after this timer fired. Keeping
            // awaitingPostStopAsr=true lets handleFinal recover then. A
            // separate outer safety cap (postStopAsrOuterTimeout) fires at
            // 30s as a real bailout for engine-genuinely-died cases.
            Log.w(TAG, "1500ms passed with no on-device text yet — waiting for late Final ASR (cap at 30s)")
            timerHandler.removeCallbacks(postStopAsrOuterTimeout)
            timerHandler.postDelayed(postStopAsrOuterTimeout, 30_000L - 1500L)
        }
    }
    private val postStopAsrOuterTimeout = Runnable {
        if (!awaitingPostStopAsr) return@Runnable
        awaitingPostStopAsr = false
        val onDeviceText = lastRawText.trim()
        if (onDeviceText.isNotBlank()) {
            Log.i(TAG, "30s outer cap reached but text now available — processing: $onDeviceText")
            processText(onDeviceText)
        } else {
            Log.w(TAG, "30s outer cap reached, no audio captured and no on-device text — giving up")
            setBubbleIdle()
        }
    }
    // Comment-bubble menu overlay extracted to BubbleMenu 2026-05-25 —
    // third BubbleWindow slice. Owns the menu view + params + inflation +
    // listeners + tail-anchored positioning.
    private var bubbleMenu: BubbleMenu? = null
    // Live transcript + pipeline-stage comment-bubble overlay extracted to
    // BubbleLiveTranscript 2026-05-25 — second BubbleWindow slice. Owns the
    // overlay view, container, params, typewriter/cursor animations, and
    // TranscriptBarController bind/unbind.
    private var liveTranscript: BubbleLiveTranscript? = null
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
            pulseRings?.start()
            startListening()
        }
    }
    private val recentTexts = mutableListOf<String>()
    private var redoCount = 0
    private var isWorkMode = false  // false=personal, true=work
    // Clarifier UI extracted to BubbleClarifierUI 2026-05-25 — eighth slice.
    // BubbleService still owns enterClarifierCycle/paintClarifierLabel since
    // they wrap ClarifierController, not the bubble's visual state.
    private var clarifierUI: BubbleClarifierUI? = null

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
    // Regen icon (lower-bezel re-roll hint) extracted to BubbleRegenIcon
    // 2026-05-25 — fourteenth slice. isAvailable getter still used by the
    // touch handler tap-zone test.
    private var regenIcon: BubbleRegenIcon? = null
    private var isRegenInFlight: Boolean = false
    // Clarifier retry/timeout state moved to BubbleClarifierUI.
    // SupervisorJob: child coroutine failures don't cancel siblings.
    // CoroutineExceptionHandler: any throw in a launched coroutine that's
    // NOT caught by an inner try/catch ends up here — without this handler
    // the exception is silently swallowed by SupervisorJob, leaving the
    // failure invisible. Surfaces as logcat + Crashlytics non-fatal so
    // every dangling exception is at least recorded.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled coroutine exception: ${e.message}", e)
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                    .recordException(e)
            } catch (_: Throwable) { /* Crashlytics not initialised in tests */ }
        }
    )
    private var recordingStartTime = 0L
    private var sessionStartTime = 0L
    // Pulse rings (3 concentric loops during recording) extracted to
    // BubblePulseRings 2026-05-25 — twelfth slice.
    private var pulseRings: BubblePulseRings? = null

    // "Did you know?" rotating tip surfaced once-per-24h after idle —
    // extracted to BubbleTipRotator 2026-05-25 — thirteenth slice.
    private var tipRotator: BubbleTipRotator? = null
    private val timerHandler = Handler(Looper.getMainLooper())

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
    fun showMenuPublic() { bubbleMenu?.show() }
    fun dismissMenuPublic() { bubbleMenu?.dismiss() }
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
                    // OpenAI's gpt-4o-transcribe-api-ev3 rev (rolled out
                    // late May 2026) rejects `verbose_json` with HTTP 400.
                    // Only whisper-1 accepts verbose_json; everything else
                    // must use plain `json` (loses per-segment confidence,
                    // which is fine for L1-cloud + L2/L3 — only L4's
                    // MultiPassDecoder consumes the per-segment signal).
                    verboseSegments = (whisperModel == "whisper-1"),
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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BubbleService created")
        instance = this

        // CRITICAL: startForeground MUST run within ~5 seconds of onCreate
        // or Android throws ForegroundServiceDidNotStartInTimeException and
        // bricks the app on launch. Previously this call lived at the
        // *bottom* of onCreate — after ~50 component constructions, any of
        // which throwing would skip the foreground call and brick launch.
        // Now: minimal channel + notification + foreground call happen
        // FIRST, before any of the heavy wiring. The statusUI-backed
        // notification rebuild happens later in onCreate as a no-op update.
        run {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "WiM Bubble", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            nm?.createNotificationChannel(ch)
            val initialNotif = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WiM")
                .setContentText("Voice dictation active")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
            val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasMic) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, initialNotif, fgsType)
        }

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
        wordHighlight = BubbleWordHighlight(this, windowManager!!)
        liveTranscript = BubbleLiveTranscript(
            context = this,
            windowManager = windowManager!!,
            getBubbleParams = { bubbleParams },
            isDemoActive = { activeDemoScript != null }
        )
        bubbleMenu = BubbleMenu(
            context = this,
            windowManager = windowManager!!,
            getBubbleView = { bubbleView },
            getBubbleParams = { bubbleParams },
            onAppearanceChanged = { updateBubbleAppearance() },
            onLayerChanged = { updateIdleDisplay() },
            onThemeChanged = { applyBubbleTheme() }
        )
        onboardingOverlay = BubbleOnboardingOverlay(this, windowManager!!)
        sendDisclaimer = BubbleSendDisclaimer(this, windowManager!!)
        twistController = BubbleTwistController(
            context = this,
            getBubbleView = { bubbleView },
            timerHandler = timerHandler,
            onAppearanceChanged = { updateBubbleAppearance() },
            onExit = { setBubbleIdle() },
        )
        clarifierUI = BubbleClarifierUI(
            context = this,
            getBubbleView = { bubbleView },
            timerHandler = timerHandler,
            onAppearanceChanged = { updateBubbleAppearance() },
            onPipelineStage = { stage, detail -> showPipelineStage(stage, detail) },
            onAcknowledged = { setBubbleIdle() },
        )
        commandMode = BubbleCommandMode(
            context = this,
            getBubbleView = { bubbleView },
            getBubbleParams = { bubbleParams },
            scope = scope,
            timerHandler = timerHandler,
            onPipelineStage = { stage, detail -> showPipelineStage(stage, detail) },
            onConfirmPrompt = { q, ctx, cb -> clarifierUI?.show(q, ctx, cb) },
            onStartListening = { startListening() },
            isRecording = { isRecording },
            onHideLiveTranscript = { hideLiveTranscript() },
            onIdle = { setBubbleIdle() },
        )
        bubbleDock = BubbleDock(
            windowManager = windowManager!!,
            getBubbleView = { bubbleView },
            getSlotRects = { dockSlotRects },
            getVibrator = { vibrator },
            onApplyTheme = { applyBubbleTheme() },
            onMenuRepositioned = { bubbleMenu?.updatePositionFromBubble() },
            onHideBubble = { hideBubble() },
            onNotificationText = { msg -> updateNotification(msg) },
        )
        bubbleAppearance = BubbleAppearance(getBubbleView = { bubbleView })
        pulseRings = BubblePulseRings(getBubbleView = { bubbleView })
        tipRotator = BubbleTipRotator(
            timerHandler = timerHandler,
            onPipelineStage = { title, body -> showPipelineStage(title, body) },
            onHideLiveTranscript = { hideLiveTranscript() },
        )
        regenIcon = BubbleRegenIcon(
            getBubbleView = { bubbleView },
            scope = scope,
        )
        statusUI = BubbleStatusUI(service = this, getBubbleView = { bubbleView })
        stageArc = BubbleStageArc(getBubbleView = { bubbleView })
        hotword = BubbleHotword(
            context = this,
            getVibrator = { vibrator },
            isRecording = { isRecording },
            releaseAudioRecorder = {
                if (audioRecorder != null) {
                    Log.i(TAG, "Releasing AudioRecorder pre-roll buffer to free mic for hotword")
                    audioRecorder?.release()
                    audioRecorder = null
                }
            },
            onHotwordFired = {
                pulseRings?.start()
                startListening()
            },
        )
        // VIBRATOR_SERVICE was deprecated in API 31 (Android 12). On API 31+
        // use VibratorManager.defaultVibrator; older devices keep the old
        // constant. App minSdk=26 so both branches are reachable.
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (getSystemService(android.os.VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

        // Audio playback monitor — diagnostic for the Session 9-10 chirp saga.
        // Gated to debug builds so release users don't pay for an unused
        // device-wide audio-playback callback (audit finding 2026-06-06).
        if (BuildConfig.DEBUG) {
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
        }

        onDeviceRecognizer = OnDeviceRecognizer(this)
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
        audioManager?.let {
            bluetoothSco = BubbleBluetoothSco(this, it).also { sco -> sco.start() }
        }
        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fgsType = if (hasMic) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIFICATION_ID, buildNotification(), fgsType)
        shakeDetector = BubbleShakeDetector(this, WimApp.prefs) {
            timerHandler.post {
                try {
                    vibrator?.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            80, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } catch (_: Exception) {}
                if (bubbleView == null) showBubble() else hideBubble()
            }
        }.also { it.start() }
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
                if (bubbleMenu?.isVisible() == true) bubbleMenu?.dismiss() else bubbleMenu?.show()
            }
            "TOGGLE_RECORDING" -> {
                if (bubbleView == null) showBubble()
                if (isRecording) stopAndProcess() else startListening()
            }
            "TEST_CLARIFIER" -> {
                val q = intent.getStringExtra("question") ?: "WORK?"
                clarifierUI?.show(q) { answer ->
                    Log.i(TAG, "Clarifier answer: $answer")
                }
            }
            "ONBOARDING" -> {
                onboardingOverlay?.show()
                showBubble()
                // Run the bubble's live demo script — the bubble introduces itself.
                // Skip-on-tap (Phase 7 step 43) — cache the script + its onComplete
                // so the tap handler can short-circuit if the user has seen enough.
                timerHandler.postDelayed({
                    val finish: () -> Unit = {
                        activeDemoScript = null
                        activeDemoFinish = null
                        onboardingOverlay?.remove()
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

    // Onboarding dim overlay extracted to BubbleOnboardingOverlay
    // 2026-05-25 — fourth BubbleWindow slice.
    private var onboardingOverlay: BubbleOnboardingOverlay? = null

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
                    try { windowManager?.updateViewLayout(view, p) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
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
            if (twistController?.isActive() == true) {
                return@setOnTouchListener twistController?.handleTouch(event, v) ?: false
            }

            // Hit-test the touch against the ACTUAL visible bronze surface
            // of the bezel, not the FrameLayout bounds. bubble_bg_idle.xml
            // layer 1 is the outer ring at 72dp container size, but layer 2
            // (the inner bronze face) is inset 5dp on each side and sized
            // 50dp — that's what the operator perceives as the "tappable"
            // metal surface. Operator 2026-06-06 (second pass): even the
            // 72dp version still registered taps in the outer rim, so drop
            // to 50dp. Scales with bubble-size pref.
            if (event.action == MotionEvent.ACTION_DOWN) {
                val cx = v.width / 2f
                val cy = v.height / 2f
                val tx = event.x - cx
                val ty = event.y - cy
                val density = resources.displayMetrics.density
                val bezelBaseDp = 50f
                val scale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
                val r = (bezelBaseDp * density * scale) / 2f
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
                            pulseRings?.start()
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
                        bubbleMenu?.updatePositionFromBubble()
                        liveTranscript?.repositionToBubble()

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
                        if (bubbleDock?.trySnap(params) == true) {
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
                            if (bubbleMenu?.isVisible() == true) {
                                bubbleMenu?.dismiss()
                            } else {
                                snapBubbleForMenuOpen()
                                bubbleMenu?.show()
                            }
                            return@setOnTouchListener true
                        }
                        if (bezelBottom > hideZone) {
                            hideBubble()
                            updateNotification("Bubble hidden — tap to show")
                        } else if (commandMode?.checkPickup() == true) {
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
                    if ((regenIcon?.isAvailable == true) && !isRecording && dist < outerR && ty > outerR * 0.45 && tapDuration < 300) {
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
                        if (clarifierUI?.isActive == true) {
                            // Clarifier mode — left = ✓ yes, right = ✕ no
                            clarifierUI?.dismiss(tx < 0)
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
                            if (bubbleMenu?.isVisible() == true) {
                                bubbleMenu?.dismiss()
                            } else {
                                snapBubbleForMenuOpen()
                                bubbleMenu?.show()
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

        try {
            windowManager?.addView(bubbleView, params)
        } catch (e: Exception) {
            // addView throws WindowManager.BadTokenException / SecurityException
            // when the SYSTEM_ALERT_WINDOW permission was revoked between
            // service start and now. Without this catch the service crashes
            // and the bubble vanishes mid-use.
            Log.e("BubbleService", "addView failed (overlay permission revoked?): ${e.message}")
            Toast.makeText(this, "Overlay permission lost — re-enable in Settings", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try { startActivity(intent) } catch (_: Exception) {}
            return
        }

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
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
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
    // Send disclaimer overlay extracted to BubbleSendDisclaimer 2026-05-25
    // — fifth BubbleWindow slice. Show path is dormant (auto-send confirm
    // flow is not currently wired) but the dismiss path is called
    // defensively from hideBubble.
    private var sendDisclaimer: BubbleSendDisclaimer? = null

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
        if (isRecording) {
            Log.i(TAG, "onInjectComplete: isRecording=true → skip (fresh recording owns UI)")
            return
        }
        val autoSendOn = WimApp.autoSendEnabled()
        val supported = WimAccessibilityService.instance?.focusedAppSupportsAutoSend() == true
        val bv = bubbleView != null
        Log.i(TAG, "onInjectComplete: autoSend=$autoSendOn focusedAppSupports=$supported bubbleView=$bv → ${if (autoSendOn && supported && bv) "SEND_READY" else "Idle"}")
        if (autoSendOn && supported && bv) {
            setBubbleSendReady()
        } else {
            setBubbleIdle()
        }
    }

    private fun showRegenIcon() { regenIcon?.show() }
    private fun hideRegenIcon() { regenIcon?.hide() }

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
                if (result.downgradedFromL4 && !l4DowngradeNotified) {
                    l4DowngradeNotified = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(this@BubbleService, "L4 unavailable — using L3 this session.", Toast.LENGTH_SHORT).show()
                    }
                }
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
                    WimApp.instance.appScope.launch {
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
                            // Samsung's OEM HttpURLConnection stalls TCP if the
                            // output stream isn't closed before reading the
                            // response — burns a socket fd until readTimeout.
                            conn.outputStream.use { it.write(payload.toByteArray()) }
                            conn.responseCode // trigger request
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }
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
        pulseRings?.stop()
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
        val facehuggerOn = WimApp.facehuggerSendEnabled()
        if (facehuggerOn) {
            val sendCenter = WimAccessibilityService.instance?.getSendButtonCenter()
            if (sendCenter == null) {
                Log.w(TAG, "facehugger: pref=on but getSendButtonCenter()=null — no Send node detected in active window")
            } else {
                val (cx, cy) = sendCenter
                val view = bubbleView
                val params = bubbleParams
                if (view == null || params == null) {
                    Log.w(TAG, "facehugger: bubbleView/Params null — cannot jump")
                } else {
                    preJumpX = params.x + view.width / 2
                    preJumpY = params.y + view.height / 2
                    facehuggerActive = true
                    Log.i(TAG, "facehugger: jumping bubble to Send button at ($cx,$cy)")
                    animateBubbleTo(cx, cy, durationMs = 250)
                }
            }
        } else {
            Log.i(TAG, "facehugger: pref=off → bubble stays put on SEND_READY")
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
        sendDisclaimer?.show(onConfirm, onCancel)
    }

    private fun dismissSendDisclaimer() {
        sendDisclaimer?.dismiss()
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
        try { windowManager?.updateViewLayout(view, params) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
        bubbleMenu?.updatePositionFromBubble()
        liveTranscript?.repositionToBubble()
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
                try { windowManager?.updateViewLayout(view, params) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
                bubbleMenu?.updatePositionFromBubble()
                liveTranscript?.repositionToBubble()
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

        // Critical: re-assert the foreground-service type to include
        // MICROPHONE *now*, not relying on what was decided at onCreate.
        // If the user granted RECORD_AUDIO after onCreate ran, the FGS
        // was started as SPECIAL_USE only — calling AudioRecord on a
        // service that never declared the MICROPHONE FGS type throws
        // SecurityException on Android 14+. Cheap to re-assert; idempotent.
        val hasMicNow = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasMicNow) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: Exception) {
                Log.w(TAG, "FGS type re-assert failed: ${e.message}")
            }
        }

        // Cancel any in-flight reconstruction from a prior utterance. Without
        // this, a slow L4 call could resume mid-cycle and paste the previous
        // recording's text into the field the user is now recording into.
        // The cancel propagates to any child jobs in the same launch.
        currentReconJob?.cancel()
        currentReconJob = null

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
        if ((regenIcon?.isAvailable == true)) hideRegenIcon()
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

        // Gate the FSM transition FIRST. The "any → Listening" rule allows
        // recording from Idle / SendReady / Failed / Clarifying / Listening
        // but forbids it from Reconstructing — "in-flight ASR cannot be
        // interrupted." Previously the return value was discarded and the
        // recording proceeded regardless, leaving an in-flight reconstruction
        // racing a fresh recording for the same audio source. Now: if the
        // FSM refuses the transition, bail without starting recording.
        // Audit finding 2026-06-06.
        if (!BubbleStateBus.advance(BubbleState.Listening(System.currentTimeMillis()))) {
            Log.w(TAG, "startListening: FSM rejected Listening transition from ${BubbleStateBus.current()} — bailing")
            ConsoleLog.log("Recording blocked — wait for current reconstruction to finish", "warn")
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
        bubbleMenu?.dismiss()
        showLiveTranscript()
        Toast.makeText(this, getString(R.string.toast_listening), Toast.LENGTH_SHORT).show()

        // Visual: recording state — the inner knob warms to a glowing ember.
        // The "alive" motion (lean-in + a slow breathe) is driven by BubbleAffect
        // off the Listening FSM state — NOT a blink here. No "REC" tag, no
        // brightness-blink overlay: the bubble's own posture says it's recording.
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_active)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = ""
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            setTextColor(0xFFFFD580.toInt())
        }
        recordingStartTime = System.currentTimeMillis()

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
                if (isL4) {
                    // Silent-block bridging: when the speaker freezes mid-utterance
                    // at L4, offer one tap-to-finish completion via the existing
                    // clarifier. Handler self-gates on L4 + isRecording.
                    audioRecorder?.onSilentBlockMidSpeech = { onSilentBlockBridge() }
                }
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
            // Drop only true single-char or non-alphanumeric noise. The prior
            // `length <= 2` was over-aggressive — it discarded common short
            // utterances ("ok", "no", "hi", "yo", "to", "do"). Whisper hallucinates
            // "thank you" as a phrase, not bare "you" — so removing "you"/"You"
            // from the blacklist is safe; the verbose_json noSpeechProb guard
            // already catches real silence-driven hallucinations.
            val isHallucination = trimmed.isBlank() ||
                trimmed.length <= 1 ||
                trimmed in setOf(
                    "(", ")", "[", "]", "...", "(.)", "[.]",
                    "[BLANK_AUDIO]", "[silence]", "[Silence]", "[blank_audio]",
                    "Thank you.", "Thanks for watching.",
                ) ||
                trimmed.all { !it.isLetterOrDigit() }
            if (!isHallucination) {
                finalizedSegments = if (finalizedSegments.isBlank()) text else "$finalizedSegments $text"
                lastRawText = finalizedSegments
                updateLiveTranscript(lastRawText)
                // If stopAndProcess fell to the "no wav" branch and is waiting
                // for this callback, kick off the pipeline NOW — don't wait
                // for the 1500ms timer to fire. Cancel the timer so it doesn't
                // double-process.
                if (awaitingPostStopAsr) {
                    awaitingPostStopAsr = false
                    timerHandler.removeCallbacks(postStopAsrFallback)
                    timerHandler.removeCallbacks(postStopAsrOuterTimeout)
                    Log.i(TAG, "Post-stop Final ASR arrived: processing $lastRawText")
                    processText(lastRawText.trim())
                }
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
        // L1 Local must be CHIRP-FREE. Android SpeechRecognizer emits
        // USAGE_ASSISTANCE_SONIFICATION sounds on start AND stop that bypass
        // every standard audio-suppression mechanism (see README session-9
        // saga). The only fix is to never start it. Per operator 2026-06-06:
        // L1 Local sacrifices live partials for silence. whisper-tiny.en
        // gives the final transcript on stop, no chirp ever.
        @Suppress("UNUSED_VARIABLE") val liveOnIgnoredForL1Local = liveOn
        val l1LocalLiveOverride = false

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
            // No silent fallback to Android SpeechRecognizer. Per operator
            // 2026-06-06: L1 Local must never route through the native ASR
            // (chirp + accuracy hit). If whisper-tiny.en isn't on disk yet,
            // refuse the recording, reset the bubble, and tell the user the
            // model is still downloading. The download is a one-time ~40 MB
            // background fetch; usually completes within minutes of first
            // launch. Subsequent taps work normally once the model lands.
            Log.w(TAG, "whisper-tiny model not ready — refusing recording (no fallback to native ASR)")
            android.widget.Toast.makeText(
                this,
                "Speech model still downloading — try again in a moment",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            setBubbleIdle()
        }
    }

    /**
     * Silent-block bridging consumer (L4). The detector fired mid-recording —
     * the speaker froze. Stop the take WITHOUT the normal transcribe→inject
     * pipeline (same shape as the too-short abort branch below; processed=true
     * makes a later stopAndProcess a no-op), transcribe the partial audio, ask
     * the fast model for ONE completion, and offer it through the existing
     * clarifier (✓ types it, ✕ types nothing — speaker re-records).
     *
     * Stopping first is required: the clarifier's ✓/✕ taps only route while
     * !isRecording (see the bezel tap handler). Best-effort — any failure
     * quietly leaves the bubble idle.
     */
    private fun onSilentBlockBridge() {
        // Callback fires from AudioRecorder's capture thread. scope is
        // Dispatchers.Main, so this marshals onto Main; the suspend
        // transcribe/completePartial calls switch to IO internally.
        scope.launch {
            if (!isRecording || processed || WimApp.layer() < 4) return@launch
            if (clarifierUI?.isActive == true) return@launch

            processed = true
            isRecording = false
            val wavFile = audioRecorder?.stop()
            audioRecorder = null
            onDeviceRecognizer?.stop()
            scope.launch(Dispatchers.IO) { whisperLocalEngine?.stop() }
            dafPlayer?.stop(); dafPlayer = null
            setBubbleIdle()

            if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) return@launch

            val partial = try {
                WhisperClient.transcribe(wavFile, patienceMode = true, verboseSegments = false)
                    .text.trim()
            } catch (e: Exception) {
                Log.w(TAG, "block bridge transcribe failed: ${e.message?.take(120)}")
                return@launch
            }
            if (partial.isBlank()) return@launch

            val completion = ReconstructClient.completePartial(partial, tone = WimApp.tone())
                .firstOrNull()?.trim().orEmpty()
            if (completion.isBlank()) return@launch

            // Back on Main. Don't stomp a fresh recording or an active clarifier
            // that started during the transcribe/complete window.
            if (isRecording || clarifierUI?.isActive == true) return@launch
            clarifierUI?.show("USE?", "Finish it for you?\n“$completion”") { answer ->
                if (answer == true) {
                    WimAccessibilityService.instance?.injectText(completion)
                }
            }
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
            // WhisperLocalEngine.stop() does a captureThread?.join(500) +
            // an ONNX decode of the buffered audio — heavy work that froze
            // the UI on the discard path. Background it on Dispatchers.IO
            // so setBubbleIdle() can land immediately. Mirrors the pattern
            // at :1865 and :1952. Audit finding 2026-06-06.
            scope.launch(Dispatchers.IO) { whisperLocalEngine?.stop() }
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
        // Auto Quiet Mode: when the user hasn't forced Quiet Mode on, boost the
        // whole take before Whisper ONLY if it was whisper-soft. Amber confirms
        // on the bubble (pendingQuietAutoFlash -> setBubbleIdle). -38 dBFS ~=
        // whisper; the measured level is logged so the threshold can be tuned.
        if (wavFile != null) {
            val takeDbfs = audioRecorder?.lastTakeDbfs() ?: -120.0
            // Always log so the operator can see the level whether or not
            // auto-quiet triggers. Lets us tune the upper bound by sampling
            // whispered vs. normal-voice takes side by side.
            Log.i(TAG, "Take level: ${"%.1f".format(takeDbfs)} dBFS (auto-quiet range -110.0..-38.0)")
            if (!WimApp.prefs.getBoolean("quiet_mode", false) && takeDbfs in -110.0..-38.0) {
                Log.i(TAG, "Auto Quiet Mode engaged (${"%.0f".format(takeDbfs)} dBFS) — whisper-soft take")
                audioRecorder?.boostWavQuiet(wavFile)
                pendingQuietAutoFlash = true
            }
        }
        audioRecorder = null
        // Track the WAV so the L4 multi-pass decoder can read it after
        // reconstruction completes. Previous file (if any) is cleaned up
        // in setBubbleIdle when the cycle ends.
        lastWavFile?.let { try { it.delete() } catch (_: Exception) {} }
        lastWavFile = wavFile

        // Stop whichever engine was running this session; cheap defensive calls on all.
        onDeviceRecognizer?.stop()
        // WhisperLocalEngine.stop() runs sherpa-onnx decode synchronously
        // (~600-900 ms on a mid-range device). Dispatch off Main so the
        // user doesn't see dropped frames on every recording stop.
        scope.launch(Dispatchers.IO) { whisperLocalEngine?.stop() }

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
                    // OpenAI's gpt-4o-transcribe-api-ev3 rev (rolled out
                    // late May 2026) rejects `verbose_json` with HTTP 400.
                    // Only whisper-1 accepts verbose_json; everything else
                    // must use plain `json` (loses per-segment confidence,
                    // which is fine for L1-cloud + L2/L3 — only L4's
                    // MultiPassDecoder consumes the per-segment signal).
                    verboseSegments = (whisperModel == "whisper-1"),
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
            // 1500ms upper bound (was 400ms — too short for whisper-tiny.en
            // sherpa-onnx; observed Stop→Final-callback latency was ~540ms
            // on Samsung Z Flip 7 and scales with audio length). handleFinal
            // recovers earlier than this if the callback arrives during the
            // window; the timer is the bailout for when ASR never returns.
            awaitingPostStopAsr = true
            timerHandler.removeCallbacks(postStopAsrFallback)
            timerHandler.postDelayed(postStopAsrFallback, 1500)
        }
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
        return bubbleDock?.trySnap(params) ?: false
    }

    /** Apply the user's selected bubble color theme (bronze/aluminum/gunmetal). */
    private fun applyBubbleTheme() {
        bubbleAppearance?.applyTheme()
    }

    private fun setBubbleIdle() {
        // FSM: drop to Idle (force-set so we don't fight the transition
        // matrix on cancel/cleanup paths from arbitrary prior states).
        BubbleStateBus.reset()
        pulseRings?.stop()
        hideLiveTranscript()
        // DO NOT delete lastWavFile here. The previous `lastWavFile?.delete()`
        // raced with the cloud-Whisper transcribe coroutine launched from
        // stopAndProcess(): on-device returns NO_MATCH → state goes Idle →
        // file deleted → Whisper coroutine opens the file → ENOENT, which
        // silently kills every L2+ session ("Whisper failed: ... ENOENT
        // (No such file or directory)"). The file lives in cacheDir;
        // Android handles eviction, and the next recording overwrites
        // it at the same path. Bug introduced in 786c9e3 (2026-04-30).
        lastWavFile = null
        tipRotator?.maybeShow()
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
        // Auto Quiet Mode amber confirmation — flash "QUIET" in the brand amber
        // on the bubble for 1.5s after a boosted whisper-soft take, then revert.
        if (pendingQuietAutoFlash) {
            pendingQuietAutoFlash = false
            val lbl = bubbleView?.findViewById<TextView>(R.id.bubbleLabel)
            val idleColor2 = when (WimApp.bubbleTheme()) {
                "gunmetal" -> 0xFFC8B48A.toInt()
                "bronze" -> 0xFF1F1208.toInt()
                else -> 0xFF555560.toInt()
            }
            lbl?.apply {
                text = "QUIET"
                setTextColor(androidx.core.content.ContextCompat.getColor(this@BubbleService, R.color.orange_label))
            }
            timerHandler.postDelayed({ lbl?.apply { text = "IDLE"; setTextColor(idleColor2) } }, 1500)
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
    private fun startHotwordIfEnabled() { hotword?.startIfEnabled() }
    private fun stopHotwordListener() { hotword?.stop() }

    private fun relayToTermux(text: String) {
        WimApp.instance.appScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://localhost:3133/wim")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 500
                conn.readTimeout = 500
                conn.outputStream.write(text.toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { /* relay not running — silent skip */ }
        }
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
        val rect = WimAccessibilityService.instance?.focusedFieldBounds() ?: return
        wordHighlight?.show(rect, rangeStart, rangeEnd, ClarifierController.injectionTextLength())
    }

    fun hideWordHighlight() {
        wordHighlight?.hide()
    }

    fun updateIdleDisplay() {
        if (isRecording || clarifierUI?.isActive == true) return
        setBubbleIdle()
    }

    private fun updateBezelLabels() {
        // No-op in normal state — knob shows IDLE/REC/PROC
        // WORK/LIFE only shown during clarifier
    }

    // Clarifier pending-question + retry-count + pre-clarifier size/opacity
    // state moved to BubbleClarifierUI.

    // Twist mode — voice-triggered knob rotation to adjust size or opacity.
    // Cluster extracted to BubbleTwistController 2026-05-25 — seventh slice.
    private var twistController: BubbleTwistController? = null

    /**
     * Check if the bubble was dropped on a focused text field with highlighted text.
     * If yes, pick up the selection for Command Mode.
     */
    // ═══════════════════════════════════════════════════════════
    // TWIST MODE — voice-triggered knob rotation for size/opacity
    // ═══════════════════════════════════════════════════════════

    fun updateBubbleAppearance() {
        bubbleAppearance?.updateAppearance()
    }

    // Trigger thresholds for drag-to-top (open menu) and drag-to-bottom
    // (hide bubble). 40dp top keeps the menu trigger from firing during
    // normal repositioning.
    private val MENU_TRIGGER_DP = 40
    private val HIDE_TRIGGER_DP = 80

    /**
     * Snap the floating bubble to the canonical menu-anchor position
     * (centered horizontally, 340dp from top) over 220ms. Called from
     * BOTH menu-open paths — drag-to-top and double-tap — so the
     * comment-bubble orientation, tail-on-bezel landing, and animation
     * feel are identical regardless of how the user opens the menu.
     * Caller invokes bubbleMenu?.show() right after; the per-frame
     * bubbleMenu?.updatePositionFromBubble() call inside the animator keeps the
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
                try { windowManager?.updateViewLayout(view, params) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
                bubbleMenu?.updatePositionFromBubble()
            }
        }
        anim.start()
    }

    private fun showLiveTranscript() {
        liveTranscript?.showLive()
    }

    private fun updateLiveTranscript(text: String) {
        liveTranscript?.updateLive(text)
    }

    fun showPipelineStage(stage: String, detail: String) {
        liveTranscript?.showStage(stage, detail)
    }

    private fun hideLiveTranscript() {
        liveTranscript?.hide()
    }

    private fun setStageArc(stage: Int) { stageArc?.setStage(stage) }

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
            onboardingOverlay?.remove()
            showPipelineStage("🎤", "Close the app — I'm your app now. Tap me whenever you need to talk.")
            timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
        }
        activeDemoFinish = finish
        activeDemoScript = BubbleDemoScript(finish).also { it.start() }
    }

    private fun setBubbleProcessing() { stageArc?.setProcessing() }

    private fun checkRedo(text: String) {
        if (recentTexts.isNotEmpty() && PipelineAnalytics.jaccard(text, recentTexts.last()) > 0.7) {
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

    private fun processText(rawText: String) {
        val pipelineStart = System.currentTimeMillis()
        val asrMs = pipelineStart - recordingStartTime
        Log.i(TAG, "Processing (asr=${asrMs}ms): $rawText")
        checkRedo(rawText)
        setBubbleProcessing()

        // TWIST mode voice triggers — the transcript is a knob control command, not text
        val triggered = twistController?.trigger(rawText)
        if (triggered != null) {
            Log.i(TAG, "Twist trigger detected: $triggered")
            twistController?.enter(triggered)
            return
        }

        // COMMAND mode: the voice is a transform command, not text to dictate
        if (commandMode?.armed == true) {
            commandMode?.processCommand(rawText)
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

        // L1 Local raw fast-path. Per operator 2026-06-06: when the user is on
        // L1 + the local engine (whisper-tiny.en on-device), inject the user's
        // ASR output untouched. No voice shortcuts, no DisfluencyFilter, no
        // profile corrections, no Repunctuate. The user hears their own voice
        // back, raw. ONE exception (added 2026-06-06): strip Whisper system
        // markers like "[BLANK_AUDIO", "[INAUDIBLE]" etc. — those aren't user
        // speech, they're the engine's "I heard nothing" tokens, and injecting
        // them as text would be nonsense.
        if (layer < 2 && !WimApp.l1CloudAsr()) {
            val outText = DisfluencyFilter.stripBlockHallucinations(rawText, lastWhisperLowConf)
            Log.i(TAG, "L1 Local raw — injecting rawText (markers-only filtered): \"$outText\"")
            BubbleStateBus.advance(BubbleState.Reconstructing(System.currentTimeMillis(), layer = 1))
            setStageArc(1)
            BubbleStateBus.advance(BubbleState.Verified(System.currentTimeMillis()))
            setStageArc(2)
            ConsoleLog.log("Raw: \"${rawText.take(80)}${if (rawText.length > 80) "..." else ""}\"", "raw")
            if (outText.isBlank()) {
                // Engine returned only system markers (no speech). Don't inject
                // an empty string — toast and bail.
                ConsoleLog.log("No speech detected", "warn")
                Toast.makeText(this@BubbleService, "No speech detected", Toast.LENGTH_SHORT).show()
                setBubbleIdle()
                return
            }
            ConsoleLog.log("-> \"${outText.take(80)}${if (outText.length > 80) "..." else ""}\" [pure raw, markers stripped]", "out")
            if (WimAccessibilityService.instance == null) {
                Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
                setBubbleIdle()
                return
            }
            WimAccessibilityService.instance!!.injectText(outText)
            relayToTermux(outText)
            BubbleStateBus.advance(BubbleState.Injected(System.currentTimeMillis(), SuccessCue.PASTE))
            setStageArc(3)
            val totalMs = System.currentTimeMillis() - recordingStartTime
            ConsoleLog.log("[L1 Local raw] inject | ${totalMs}ms (asr:${asrMs})", "info")
            sessionHistory?.record(
                rawText = rawText,
                cleanText = outText,
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
            if (BuildConfig.DEBUG) {
                WimTestReceiver.lastResult = org.json.JSONObject().apply {
                    put("status", "ok")
                    put("raw_text", rawText)
                    put("clean_text", outText)
                    put("tone", tone)
                    put("layer", 1)
                    put("situation", situation)
                    put("asr_ms", asrMs)
                    put("recon_ms", 0)
                    put("total_ms", totalMs)
                    put("falcon_approved", true)
                    put("risk_flags", org.json.JSONArray())
                    put("wer", 0.0)
                }
            }
            // onInjectComplete() is the post-inject gate that decides between
            // SEND_READY (auto-send enabled + focused on a real editable in a
            // non-WiM app → bubble turns green, facehugger jumps to the Send
            // button) and plain Idle. Earlier I was bypassing it with a
            // setBubbleIdle() call, which is why L1 Local raw never reached
            // facehugger — bug fixed 2026-06-06.
            onInjectComplete()
            return
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
            // the "reconstruction" is deterministic (corrections + Repunctuate).
            // Transform logic extracted to L1Pipeline 2026-05-25 — second
            // Pipeline-cluster slice. Orchestration (FSM, inject, record)
            // stays inline.
            BubbleStateBus.advance(BubbleState.Reconstructing(System.currentTimeMillis(), layer = 1))
            setStageArc(1)
            Log.i(TAG, "L1 mode, applying profile corrections then injecting")
            val l1Result = L1Pipeline.transform(
                preCleaned = preCleaned,
                profile = profile,
                sessionNum = sessionHistory?.count() ?: 0,
                log = { msg -> Log.i(TAG, msg) }
            )
            val l1Text = l1Result.text
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
            relayToTermux(l1Text)
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

        // Apply profile corrections + phonetic-neighbor matching at L2+
        // client-side BEFORE GPT sees the text. Fixes the Lavrentiy bug
        // where corrections were only applied at L3+. Logic extracted to
        // L2CorrectionsStage 2026-05-25 — third Pipeline-cluster slice.
        var correctedText = L2CorrectionsStage.apply(
            preCleaned = preCleaned,
            profile = profile,
            sessionNum = sessionHistory?.count() ?: 0,
            layer = layer,
            log = { msg -> Log.i(TAG, msg) }
        ).text

        // L3+ gets profile vocabulary and corrections injected into the prompt
        val vocabulary = if (layer >= 3) profile.getVocabulary() else emptyList()
        val corrections = if (layer >= 3) profile.getCorrections() else emptyMap()

        currentReconJob = scope.launch {
            // Hoisted so the catch block below can cancel the L4 stage-animation
            // loop on reconstruction failure — Kotlin catch is a sibling scope.
            var turboTaxJob: Job? = null
            // Same reason — catch needs to cancel the parallel-launched
            // MultiPassDecoder so an in-flight Whisper call doesn't keep
            // burning API budget after reconstruction blew up.
            var multiPassDeferred: Deferred<MultiPassDecoder.MultiPassResult>? = null
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

                // TurboTax engagement: animated stage messages for L4 (long processing).
                // `launch { ... }` (no `scope.` prefix) uses the recon-job's coroutine
                // context, so the turbo loop becomes a CHILD of currentReconJob. When
                // the parent is cancelled — including via every error path, not just
                // the catch blocks we remembered to wire up — the child cancels
                // automatically. Was `scope.launch` (sibling to recon job, lifetime
                // tied to the BubbleService scope) which needed manual `.cancel()`
                // calls in every exit branch.
                turboTaxJob = if (layer >= 4) {
                    launch {
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

                // Audit Tier-2: kick MultiPassDecoder off in parallel with the
                // GPT/Sonnet call. The two are independent (both consume the
                // same wav) and previously ran sequentially — multipass after
                // reconstruction finished — so the user waited reconstruction
                // time + 3-6s of clarifier-prep time. Now total visible time
                // is max(reconstruction, multipass) instead of the sum, with
                // the multipass result already in hand when the clarifier
                // branch fires below. The L4 case saves the most: Sonnet ET
                // runs ~60-70s while multipass runs ~6s, so multipass finishes
                // far before reconstruction. At L2/L3 the wins are closer to
                // even but still measurable.
                run {
                    val wav = lastWavFile
                    if (layer >= 2 && wav != null && wav.exists() && wav.length() > 44) {
                        multiPassDeferred = async(Dispatchers.IO) {
                            val recognizer = WhisperApiL1(
                                model = "whisper-1",
                                language = primaryLanguage,
                                patienceMode = true,
                            )
                            MultiPassDecoder(recognizer).run(wav)
                        }
                    }
                }

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
                if (reconResult.downgradedFromL4 && !l4DowngradeNotified) {
                    l4DowngradeNotified = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(this@BubbleService, "L4 unavailable — using L3 this session.", Toast.LENGTH_SHORT).show()
                    }
                }
                Log.i(TAG, "Reconstruction (${reconResult.reconMs}ms): ${reconResult.cleanText}")
                ConsoleLog.log("Recon: ${reconResult.reconMs}ms | Falcon: ${if (reconResult.falconApproved) "OK" else "REJECTED"}", "info")

                // Post-reconstruction guards (LLM leak + length explosion).
                // Logic extracted to ReconstructionGuard 2026-05-25 — first
                // Pipeline-cluster slice.
                val guardResult = ReconstructionGuard.apply(
                    reconstructed = reconResult.cleanText,
                    fallbackText = correctedText,
                    fallbackWordCount = cleanWordCount,
                )
                var outputText = guardResult.outputText
                val leakDetected = guardResult.leakDetected
                guardResult.leakReason?.let {
                    Log.w(TAG, "$it — using raw")
                    ConsoleLog.log("$it — using raw", "error")
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
                    relayToTermux(outputText)
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
                        WimApp.instance.appScope.launch {
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
                                // Samsung's OEM HttpURLConnection stalls TCP
                                // if the output stream isn't closed before
                                // reading the response.
                                conn.outputStream.use { it.write(payload.toByteArray()) }
                                val responseCode = conn.responseCode
                                if (responseCode == 200) {
                                    // Only mark prompted on a successful round-trip — otherwise
                                    // a single network blip permanently suppresses the prompt
                                    // and L1 pack injection never activates.
                                    profileRef.markL1DetectPrompted()
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
                        }
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
                    PipelineAnalytics.recordPredictionsFromOutput(predPkg, outputText)
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
                if (!WimApp.isClarifierAllowed(clarifierPkg)) {
                    // Clarifier disabled for this app — discard the parallel-launched
                    // multipass to stop the in-flight Whisper calls and free the API
                    // budget. await() in the branch below would have consumed it.
                    multiPassDeferred?.cancel()
                }
                if (WimApp.isClarifierAllowed(clarifierPkg)) {
                    var wordConfidences: List<Clarifier.WordConfidence> = emptyList()
                    if (multiPassDeferred != null) {
                        try {
                            showPipelineStage("⟳ Reviewing", "Cross-checking unsure words")
                            val multi = multiPassDeferred.await()
                            // P0 fix — MultiPassDecoder ranges index into Whisper raw text,
                            // but Clarifier.evaluate is called with finalText = outputText
                            // (GPT-reconstructed). Without realignment, approve-swap
                            // corrupts the injected text. See ClarifierAlignment.kt.
                            wordConfidences = alignWordConfidencesToText(multi.wordConfidences, outputText)
                            Log.i(TAG, "MultiPass (L$layer): ${multi.disagreementSummary.size} disagreement positions, ${multi.wordConfidences.count { it.multiPassDisagreements > 0 }} flagged words, ${wordConfidences.size} aligned to outputText")
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
                multiPassDeferred?.cancel()
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

    private fun createNotificationChannel() { statusUI?.createChannels(CHANNEL_ID, ALERT_CHANNEL_ID) }
    private fun buildNotification(text: String = "Voice dictation active"): Notification =
        statusUI?.buildForegroundNotification(CHANNEL_ID, text) ?: error("statusUI not initialized")
    private fun updateNotification(text: String) { statusUI?.updateForegroundNotification(CHANNEL_ID, NOTIFICATION_ID, text) }
    private fun alertOnMissingPermissions() { statusUI?.alertOnMissingPermissions(ALERT_CHANNEL_ID, ALERT_NOTIFICATION_ID) }

    // Android 15+: any FGS type can be subject to a time cap (MICROPHONE included
    // under certain conditions). Without an onTimeout override, the system silently
    // kills the service when the cap is reached and the user sees the bubble vanish
    // mid-recording with no signal. Override surfaces it via toast + clean stop.
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout fired: startId=$startId fgsType=$fgsType — stopping service")
        try {
            Toast.makeText(this, "WiM mic session ended — re-open from launcher", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        // Cancel the demo script + clear refs FIRST — it has its own
        // private Handler scheduling ~50 deferred steps that each hold a
        // strong reference to this destroyed Service. Without cancel, the
        // bubble view-tree + this Service stay leaked for ~2.5 min.
        try { activeDemoScript?.cancel() } catch (_: Exception) {}
        activeDemoScript = null
        activeDemoFinish = null
        try { onboardingOverlay?.remove() } catch (_: Exception) {}
        onboardingOverlay = null

        shakeDetector?.stop()
        shakeDetector = null
        stopHotwordListener()
        hotword = null
        bubbleMenu?.dismiss()
        bubbleMenu = null
        hideLiveTranscript()
        dafPlayer?.stop()
        dafPlayer = null
        audioRecorder?.release()
        audioRecorder = null
        hideBubble()
        onDeviceRecognizer?.destroy()
        onDeviceRecognizer = null
        whisperLocalEngine?.destroy()
        whisperLocalEngine = null
        bubblePersona?.unbind()
        bubblePersona = null
        bubbleAffect?.unbind()
        bubbleAffect = null
        // Stop visual animators + null references so the destroyed Service
        // doesn't pin its view-tree through Animator callbacks.
        try { pulseRings?.stop() } catch (_: Exception) {}
        pulseRings = null
        tipRotator = null
        regenIcon = null
        bubbleDock = null
        bubbleAppearance = null
        twistController = null
        clarifierUI = null
        commandMode = null
        sendDisclaimer = null
        wordHighlight = null
        statusUI = null
        stageArc = null

        TranscriptBarController.unbind()
        IdleTimer.cancelAll()
        VoiceCommandRouter.unregister("clarifier")
        ClarifierController.reset()
        timerHandler.removeCallbacksAndMessages(null)
        // Cancel typewriter + cursor-blink ticks owned by the live transcript
        // overlay — otherwise they keep firing at 22ms/500ms forever after
        // the service dies.
        liveTranscript?.destroy()
        liveTranscript = null
        audioPlaybackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        audioPlaybackCallback = null
        audioManager = null
        bluetoothSco?.stop()
        bluetoothSco = null
        scope.cancel()
        super.onDestroy()
    }
}
