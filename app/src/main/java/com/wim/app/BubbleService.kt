package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
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
    private var displayListener: DisplayManager.DisplayListener? = null
    var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleTouchView: View? = null
    /** The solid bezel window's view tree — where every visible bubble widget lives. */
    val bezelHost: View? get() = bubbleTouchView
    private var bubbleTouchParams: WindowManager.LayoutParams? = null
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
    /** The ASR route that actually produced the current transcript. Logged
     *  once per pipeline run, separate from the reconstruction route — local
     *  ASR followed by cloud reconstruction is NOT an entirely local
     *  pipeline, and the console must not read like it is. */
    private var lastAsrRoute: String = "unknown"
    /** Milliseconds the speaker actually spoke on the last take — stamped when
     *  recording stops, BEFORE transcription runs. The WPM dial used asrMs,
     *  which is recording time plus transcription latency, and therefore always
     *  read lower than the user really speaks. */
    private var lastSpeechMs: Long = 0L
    private var bubblePersona: BubblePersona? = null
    private var bubbleAffect: BubbleAffect? = null
    /** Active onboarding demo (Phase 7). Tap handler skips it on touch. */
    private var activeDemoScript: BubbleDemoScript? = null
    private var activeDemoFinish: (() -> Unit)? = null
    private var sessionHistory: SessionHistory? = null
    private var dafPlayer: DafPlayer? = null
    private var audioRecorder: AudioRecorder? = null
    private var lastWhisperLowConf: List<WhisperClient.LowConfidenceSegment> = emptyList()
    private var lastWhisperSegments: List<WhisperClient.TimedSegment> = emptyList()
    private var lastWhisperWords: List<WhisperClient.TimedWord> = emptyList()

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
    // Double-tap-to-open-menu was removed 2026-07-25: it cost 300ms of dead
    // air on the front of every recording and never fired reliably in the
    // operator's hand. The menu opens by dragging the bubble to the top of the
    // screen, or from the ongoing notification's MENU action.
    // Press-and-hold threshold — 500ms is well above normal tap durations
    // (100-300ms) so accidental brushes never trigger record. Anything
    // shorter races with toggle-tap. Hold past this threshold = enter
    // hold-mode and start recording; release = stop. Drag during hold cancels.
    private val HOLD_THRESHOLD_MS = 500L
    private var holdModeActive = false
    private var holdStartRunnable: Runnable? = null
    private var singleTapRunnable: Runnable? = null
    private val recentTexts = mutableListOf<String>()
    private var redoCount = 0
    private var isWorkMode = false  // false=personal, true=work
    // Clarifier UI extracted to BubbleClarifierUI 2026-05-25 — eighth slice.
    // BubbleService still owns enterClarifierCycle/paintClarifierLabel since
    // they wrap ClarifierController, not the bubble's visual state.
    private var clarifierUI: BubbleClarifierUI? = null

    // Regenerate removed entirely 2026-08-22 (operator: "eliminate the
    // regenerate feature altogether — the bubble, the button, the code").
    // The lower-bezel icon was a few dp wide on a bubble the user can shrink
    // to 70%, and the tap zone it sat in was unreachable during SEND_READY
    // because the send branch claims the whole bubble first. An indigo
    // second countdown was tried the same night to give it a reachable
    // window and made things worse: it spent five seconds after EVERY take
    // answering a tap with a re-roll instead of a recording, which read as
    // the bubble being slow and busy, and a re-roll that cannot replace in
    // place appends, so the same sentence landed twice.
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

        /**
         * How long an explicit tone pick outranks [ToneFromContent].
         *
         * The legacy app-based router used 30 s, which was only ever meant to
         * stop a window event from ping-ponging the tone back. That is far too
         * short for a deliberate pick: the user opens the dashboard, taps
         * professional, returns to the app they were in, and starts talking —
         * easily past 30 s, and the classifier then silently overrode them.
         *
         * Ten minutes covers a deliberate pick and the run of messages that
         * follows it, and still lets the classifier resume later in the day
         * without the user having to remember to unset anything.
         */
        private const val MANUAL_TONE_LOCK_MS = 10 * 60 * 1000L
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

    /** Packages we've already put the work/casual question to in this service
     *  lifetime. Guards against re-asking on every window-state event for an
     *  app the user declined to answer — a timeout means "not now", and
     *  re-prompting on the next screen transition would be unusable. */
    private val toneAskedThisSession = mutableSetOf<String>()

    /**
     * "Quick question — is this for work or casual?"
     *
     * Called from the accessibility side when the foreground app matches
     * neither tone list and the user has never answered for it. The answer is
     * persisted per package, so any given app is asked about exactly once.
     *
     * Every guard here exists so the question can only appear at a moment
     * where it is not in the way: the bubble must be visible and genuinely
     * idle — not recording, not mid-pipeline, not already asking something
     * else. If any guard fails we return WITHOUT marking the package asked,
     * so the question surfaces on a later, calmer visit to the same app.
     */
    fun askToneForApp(pkg: String) {
        if (pkg.isBlank()) return
        if (!WimApp.prefs.getBoolean("auto_tone", false)) return
        if (WimApp.prefs.contains(ToneRouting.learnedKey(pkg))) return
        if (pkg in toneAskedThisSession) return
        if (bubbleView == null || isRecording) return
        if (clarifierUI?.isActive == true) return
        if (BubbleStateBus.current() !is BubbleState.Idle) return

        toneAskedThisSession.add(pkg)
        // The bezel only offers a bare ✓ and ✕. For a word question those read
        // fine ("did I hear this right?"), but for work-vs-casual neither symbol
        // maps to an answer, so the operator hit this live on 2026-07-29 and
        // could not tell what accepting meant. Spell the mapping out in the
        // subtitle instead of assuming the check reads as "work".
        clarifierUI?.show("WORK?", "✓ work    ✕ casual") { answer ->
            // null = the 10s timeout fired after its one retry. Don't guess and
            // don't record anything; the app stays in toneAskedThisSession so
            // this session stops bothering them about it.
            if (answer == null) return@show
            val tone = if (answer) ToneRouting.PROFESSIONAL else ToneRouting.CASUAL
            isWorkMode = answer
            WimApp.prefs.edit()
                .putString(ToneRouting.learnedKey(pkg), tone)
                .putString("tone", tone)
                .putBoolean("work_mode", answer)
                // Same lockout the bezel tap takes: this WAS a deliberate user
                // choice, so autoSwitchTone must not second-guess it on the very
                // next accessibility event.
                .putLong("manual_tone_set_ms", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Tone learned for $pkg → $tone")
            updateBezelLabels()
            updateIdleDisplay()
        }
    }

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
        if (!WimApp.testHooksEnabled()) return
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
        bubbleTouchView?.findViewById<BezelStageArcView>(R.id.bubbleStageArc)
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
        val view = bubbleTouchView ?: return
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
     * disfluency filter → reconstruction → integrity checks → inject → session record.
     *
     * Result written to WimTestReceiver.lastResult when complete.
     */
    fun processTextForTest(rawText: String) {
        if (!WimApp.testHooksEnabled()) return
        if (isRecording) return
        lastWhisperLowConf = emptyList()
        lastWhisperWords = emptyList()
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
        if (!WimApp.testHooksEnabled()) return
        recordingStartTime = System.currentTimeMillis()
        scope.launch {
            try {
                val whisperModel = when {
                    // L4's MultiPassDecoder reads per-segment confidence, and
                    // whisper-1 is the only model that still returns it.
                    WimApp.layer() >= 4 -> "whisper-1"
                    WimApp.layer() < 2 && WimApp.l1CloudAsr() -> "whisper-1"
                    // L2/L3 consume no segment data, so they take the newer,
                    // more accurate transcriber. Same price per minute.
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
                lastAsrRoute = "cloud ($whisperModel)"
                Log.i("WiM", "Test audio Whisper ($whisperModel via ${whisperResult.engine}, ${whisperResult.asrMs}ms): ${whisperResult.text}")
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

        // One-shot cleanup of an orphaned model directory.
        //
        // A punctuation-and-truecasing feature shipped and was reverted the same
        // hour on 2026-08-22 — it was built against a stale comment claiming
        // whisper-tiny.en returns flat lowercase, which it does not. Anyone who
        // ran that build already downloaded its 7 MB model into filesDir, and
        // reverting the code cannot reach it: app-private storage is sealed to
        // the app, so neither adb shell nor Shizuku can delete it from outside.
        //
        // Safe to keep forever — deleteRecursively on a path that does not
        // exist is a no-op — but it can go once no install from that window is
        // still in the wild.
        try {
            val orphan = java.io.File(filesDir, "punct-models")
            if (orphan.exists() && orphan.deleteRecursively()) {
                Log.i(TAG, "Removed orphaned punct-models directory")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "punct-models cleanup skipped: ${e.message}")
        }

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
            startForegroundCompat(initialNotif, fgsType)
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
        // Belt and braces for the off-screen bubble (2026-09-04): rotation,
        // fold and DeX also arrive as display changes, and those fire even
        // when the process-level configuration callback is skipped.
        try {
            val dm = getSystemService(DisplayManager::class.java)
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) reclampBubbleToScreen("display changed")
                }
            }
            dm.registerDisplayListener(listener, timerHandler)
            displayListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "display listener not registered: ${e.javaClass.simpleName}")
        }
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
            getBubbleView = { bubbleTouchView },
            timerHandler = timerHandler,
            onAppearanceChanged = { updateBubbleAppearance() },
            onExit = { setBubbleIdle() },
        )
        clarifierUI = BubbleClarifierUI(
            context = this,
            getBubbleView = { bubbleTouchView },
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
            getBezelHost = { bubbleTouchView },
            getSlotRects = { dockSlotRects },
            getVibrator = { vibrator },
            onApplyTheme = { applyBubbleTheme() },
            onMenuRepositioned = {
                syncBubbleTouchWindow()
                bubbleMenu?.updatePositionFromBubble()
            },
            onHideBubble = { hideBubble() },
            onNotificationText = { msg -> updateNotification(msg) },
        )
        bubbleAppearance = BubbleAppearance(getBubbleView = { bubbleTouchView })
        pulseRings = BubblePulseRings(getBubbleView = { bubbleView })
        tipRotator = BubbleTipRotator(
            timerHandler = timerHandler,
            onPipelineStage = { title, body -> showPipelineStage(title, body) },
            onHideLiveTranscript = { hideLiveTranscript() },
        )
        statusUI = BubbleStatusUI(service = this, getBubbleView = { bubbleTouchView })
        stageArc = BubbleStageArc(getBubbleView = { bubbleTouchView })
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
            bubbleViewProvider = { bubbleTouchView },
            vibrator = vibrator,
        ).also { it.bind(scope) }

        // Clarifier voice commands — claim 12(ii). Spec §2 row 3 priority:
        // claim only when state is Clarifying; primary dictation otherwise.
        VoiceCommandRouter.register("clarifier") { utterance ->
            if (BubbleStateBus.current() !is BubbleState.Clarifying) return@register false
            val token = utterance.trim().lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
            when (token) {
                // "swap" is in the patent's claim-8 evidence and in the build
                // spec's command list, and was the one word from that list
                // never wired up. It reads as approve — the user is asking for
                // the substitution the bubble just offered.
                "yes", "approve", "ok", "confirm", "swap", "change" -> {
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
        // The persistent bubble needs only SPECIAL_USE while idle. Add the
        // MICROPHONE type strictly while capture is active, then downgrade
        // again as soon as recording stops.
        setForegroundMicActive(false)
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
            "STOP" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }
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
                        showPipelineStage("🎤", "Close the app — I'm your app now. Tap me whenever you need to talk.", force = true)
                        timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
                    }
                    activeDemoFinish = finish
                    activeDemoScript = BubbleDemoScript(finish).also { it.start() }
                }, 800)
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification, serviceType: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setForegroundMicActive(active: Boolean) {
        val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            if (active) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForegroundCompat(buildNotification(), type)
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
                    updateBubbleWindowLayout(view, p)
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
        // The touch window now also DRAWS the bezel. Its window alpha stays
        // 1.0, so the visible bubble is genuinely solid — the 0.8 obscuring
        // alpha Android 12+ demands lives only on the big decor window
        // (bubbleView), which since this split contains nothing but the
        // translucent pulse rings, where 0.8 is imperceptible.
        bubbleTouchView = inflater.inflate(R.layout.bubble_bezel, null)
        // Kill touch sounds on every view in the bubble hierarchy
        fun disableSounds(v: View) {
            v.isSoundEffectsEnabled = false
            v.isHapticFeedbackEnabled = false
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) disableSounds(v.getChildAt(i))
            }
        }
        bubbleView?.let { disableSounds(it) }
        bubbleTouchView?.let { disableSounds(it) }

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
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ passes touches through a non-touchable application
            // overlay only when its obscuring alpha is at or below 0.8.
            // Since the bezel moved to the touch window, this window holds
            // only the pulse rings — 0.8 on translucent rings is invisible,
            // and the bezel itself no longer pays this tax.
            alpha = 0.8f
            // Default spawn: horizontally centered, ~40% from the top.
            // Window is 180dp = ~540px on density 3; computing it here
            // off the live display metrics so it works on any device.
            val spawnDensity = resources.displayMetrics.density
            val spawnWindowPx = (180 * spawnDensity).toInt()
            x = (resources.displayMetrics.widthPixels - spawnWindowPx) / 2
            y = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        }
        bubbleParams = params
        // Reused params may carry a spot from a wider screen (landscape,
        // unfolded, DeX). Pull it back inside the screen we have now.
        clampBubbleParamsToScreen(params)

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

        bubbleTouchView?.setOnTouchListener { v, event ->
            // TWIST mode — route all touch events to twist handler, bypass normal gestures
            if (twistController?.isActive() == true) {
                return@setOnTouchListener twistController?.handleTouch(event, v) ?: false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // The window carries ~18% headroom past the visible bezel
                    // so taffy stretch and affect nudges don't clip. A tap in
                    // that invisible margin is not a tap on the bubble.
                    val hcx = v.width / 2f
                    val hcy = v.height / 2f
                    val hdx = event.x - hcx
                    val hdy = event.y - hcy
                    val hr = BubbleTouchTarget.hitRadiusPx(
                        resources.displayMetrics.density, WimApp.bubbleSize()
                    )
                    if (hdx * hdx + hdy * hdy > hr * hr) return@setOnTouchListener false
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
                            // Cancel pending hold-mode trigger — user is
                            // moving the bubble, not holding to record.
                            holdStartRunnable?.let { timerHandler.removeCallbacks(it) }
                            holdStartRunnable = null
                            // Reset the double-tap window — a drag is not a tap.
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
                        clampBubbleParamsToScreen(params)

                        bubbleView?.let { updateBubbleWindowLayout(it, params) }
                        bubbleMenu?.updatePositionFromBubble()
                        liveTranscript?.repositionToBubble()

                        // Taffy: stretch in drag direction
                        val speed = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                        val baseScale = WimApp.bubbleSize() / 100f
                        val stretch = baseScale * (1f + (speed / 800f).coerceAtMost(0.15f))
                        val squeeze = baseScale * (1f - (speed / 1600f).coerceAtMost(0.08f))
                        val bezel = bubbleTouchView?.findViewById<FrameLayout>(R.id.bubbleBezel)
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
                        bubbleTouchView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
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
                            // Drag-to-hide is also the cancel gesture. hideBubble()
                            // only tears down the windows — it never touched the
                            // recorder, so dragging a RECORDING bubble into the hide
                            // zone left the microphone open with nothing on screen,
                            // and the post-stop ASR timer could still paste the
                            // abandoned utterance a beat later. Verified on device
                            // 2026-08-21: appops reported RECORD_AUDIO (running)
                            // after the bubble was gone.
                            //
                            // cancelRecording() has existed since the press-and-hold
                            // cancel was reverted (2026-07-25) with no caller. This
                            // is the gesture it was waiting for: drag-down already
                            // means "make this go away", and while recording that
                            // reads as "throw this take away" without overloading a
                            // tap or a hold.
                            if (isRecording) cancelRecording()
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
                    // The touch window IS the visible 72dp bezel (BubbleTouchTarget),
                    // so half its width is the bubble's own edge — nothing outside
                    // the bubble can reach this handler at all. 0.40 was leaving the
                    // outermost fifth of the visible bubble unresponsive on top of
                    // the knob/ring mismatch below.
                    val outerR = v.width * 0.50f    // bubble edge
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

                    // Clarifier answers — left half approves, right half rejects,
                    // anywhere on the visible bubble. Only claims the tap while a
                    // clarifier is actually on screen; otherwise the tap falls
                    // through to the record routing below.
                    //
                    // The idle work/casual toggle used to live here, on the bezel
                    // RING ONLY (dist > innerR), and that is what made recording
                    // feel like it needed a precise press: `innerR` is 0.18 of the
                    // bubble's width, but the knob drawn on screen is 0.30 of it.
                    // The outer ~9dp band of a 44dp knob looked exactly like the
                    // record button and silently flipped the user's tone instead.
                    // It was also invisible — updateBezelLabels() is a no-op in the
                    // idle state, so nothing on screen ever showed which tone was
                    // active or that a tap had changed it. Removed: tone is set in
                    // Settings, and ToneRouting now asks and learns per app.
                    if (!isRecording && dist < outerR && tapDuration < 300) {
                        if (BubbleStateBus.current() is BubbleState.Clarifying) {
                            try { vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
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
                            try { vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                            clarifierUI?.dismiss(tx < 0)
                            return@setOnTouchListener true
                        }
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

                    // No cancel gesture. Press-and-hold was tried on 2026-07-25
                    // and reverted the same day: press-and-hold IS how the user
                    // records a short message, so binding cancel to it makes one
                    // finger motion mean "send this" or "destroy this" depending
                    // on invisible state. cancelRecording() is kept for a future
                    // gesture that isn't already spoken for.

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

                    // Tap routing: recording in flight → stop. Otherwise → start.
                    if (isRecording) {
                        stopAndProcess()
                    } else {
                        // Start immediately. This used to be deferred by
                        // DOUBLE_TAP_TIMEOUT so a second tap could open the menu
                        // instead — which put 300ms of dead air on the front of
                        // every single recording, for a gesture the operator
                        // reports never actually fires. The menu already has two
                        // working ways in: drag the bubble to the top of the
                        // screen, and the MENU action on the ongoing
                        // notification. Neither costs anything per recording.
                        pulseRings?.start()
                        startListening()
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
        val opacity = WimApp.bubbleOpacity().coerceIn(0, 100) / 100f
        bubbleTouchView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.alpha = opacity

        val scale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
        val density = resources.displayMetrics.density
        // Decor window held at MAX size (= 120dp * 1.5 = 180dp) so the pulse
        // rings can animate outward freely. The bezel lives in the touch
        // window (sized by BubbleTouchTarget) and scales there.
        val maxWindowDp = 180
        val newWindowPx = (maxWindowDp * density).toInt()
        params.width = newWindowPx
        params.height = newWindowPx
        bubbleTouchView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.let {
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

        val touchView = bubbleTouchView ?: return
        val target = BubbleTouchTarget.bounds(
            visualX = params.x,
            visualY = params.y,
            visualWidthPx = newWindowPx,
            visualHeightPx = newWindowPx,
            density = density,
            sizePercent = WimApp.bubbleSize(),
        )
        val touchParams = WindowManager.LayoutParams(
            target.width,
            target.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = target.x
            y = target.y
        }
        bubbleTouchParams = touchParams
        try {
            windowManager?.addView(touchView, touchParams)
        } catch (e: Exception) {
            Log.e(TAG, "add touch window failed: ${e.message}")
            bubbleView?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            bubbleView = null
            bubbleTouchView = null
            bubbleTouchParams = null
            Toast.makeText(this, "Bubble touch layer could not start", Toast.LENGTH_LONG).show()
            return
        }

        // Prime the pre-roll audio buffer so first words aren't lost on tap
        if ((WimApp.apiKey().isNotBlank() || AuthManager.isSignedIn) && WimApp.layer() >= 2) {
            if (audioRecorder == null) audioRecorder = AudioRecorder(this)
            audioRecorder?.primeBuffer()
        }

        // Coat-rack signal: MainActivity's dock listens to this and ghosts
        // the matching slot to show the bubble is "in use."
        onBubbleVisibilityChanged?.invoke()
    }

    private fun hideBubble(notifyRecall: Boolean = true) {
        // Drop any in-flight send-ready timer + disclaimer so they don't
        // survive the bubble's lifetime.
        isSendReady = false
        timerHandler.removeCallbacks(revertSendReadyRunnable)
        dismissSendDisclaimer()
        hideWordHighlight()
        bubbleTouchView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm touch remove failed: ${e.javaClass.simpleName}") }
            bubbleTouchView = null
            bubbleTouchParams = null
        }
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}") }
            bubbleView = null
        }
        // Forget the last on-screen position. Without this, showBubble()'s
        // `bubbleParams ?: ...` reuses the saved spot — and the spot the user
        // just hid from is the bottom drop zone, so the bubble kept coming
        // back pinned to the bottom edge. Nulling it makes the next show fall
        // through to the default spawn (centred, upper-middle). The dock
        // pickup path is unaffected: it overwrites x/y with the finger point
        // right after the params are built.
        bubbleParams = null
        // Re-post notification so it stays visible as the recall button
        if (notifyRecall) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("Bubble hidden — tap to show"))
        }
        // Coat-rack signal: bubble is back on the hanger. Dock un-ghosts.
        onBubbleVisibilityChanged?.invoke()
    }

    /** True iff the floating bubble view is currently attached to the window manager. */
    fun isBubbleVisible(): Boolean = bubbleView != null

    // ── Tap-to-send state ────────────────────────────────────────────────
    // Flipped true after a successful injectText when tap-to-send assist is
    // enabled and a non-WiM editable is focused. Tap the bubble in this state
    // to fire ACTION_CLICK on the app's Send button. Reverts to IDLE
    // after SEND_READY_TIMEOUT_MS.
    private var isSendReady = false
    private val SEND_READY_TIMEOUT_MS = 5000L
    private var sendReadyExpiresAt = 0L
    private val sendReadyTickRunnable = object : Runnable {
        override fun run() {
            if (!isSendReady) return
            val remaining = ((sendReadyExpiresAt - System.currentTimeMillis() + 999) / 1000)
                .coerceAtLeast(0).coerceAtMost(99)
            bubbleTouchView?.findViewById<TextView>(R.id.bubbleTime)?.text = "${remaining}s"
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
    // — fifth BubbleWindow slice. Show path is dormant (tap-to-send confirm
    // flow is not currently wired) but the dismiss path is called
    // defensively from hideBubble.
    private var sendDisclaimer: BubbleSendDisclaimer? = null

    /**
     * Called after a successful injectText. Decides whether to rest at IDLE
     * or flip to SEND_READY (green label, separate tap to send). SEND_READY is
     * gated on user opt-in + a focused non-WiM editable so unsupported states
     * behave exactly as today.
     */
    // H8: the bubble reaches the host app's text field ONLY through this port,
    // never by grabbing WimAccessibilityService directly. Defaults to the live
    // accessibility service; a test sets [testInjectionPort] to a fake so the
    // recording/reconstruction pipeline can run with no real device.
    var testInjectionPort: BubbleInjectionPort? = null
    private val injectionPort: BubbleInjectionPort?
        get() = testInjectionPort ?: WimAccessibilityService.instance

    private data class FailureInfo(val reason: FailReason, val message: String)

    /**
     * Classify a pipeline exception so the user sees the real cause instead of a
     * blanket "network" failure (finding H4).
     *
     * The rules live in [FailureClassifier] — a pure object — so they can be
     * unit-tested against real exception types. Before 2026-07-29 they sat
     * inline here, private, and the only coverage this app had for "the cloud
     * died mid-sentence" was hoping.
     */
    private fun classifyFailure(e: Throwable): FailureInfo {
        val c = FailureClassifier.classify(e)
        return FailureInfo(c.reason, c.message)
    }

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
        val supported = injectionPort?.focusedAppSupportsAutoSend() == true
        val bv = bubbleView != null
        Log.i(TAG, "onInjectComplete: autoSend=$autoSendOn focusedAppSupports=$supported bubbleView=$bv → ${if (autoSendOn && supported && bv) "SEND_READY" else "Idle"}")
        if (autoSendOn && supported && bv) {
            setBubbleSendReady()
        } else {
            setBubbleIdle()
        }
    }


    private fun setBubbleSendReady() {
        val view = bubbleTouchView ?: return
        BubbleStateBus.advance(BubbleState.SendReady)
        isSendReady = true
        pulseRings?.stop()
        hideLiveTranscript()
        view.findViewById<FrameLayout>(R.id.bubbleInner)?.clearAnimation()
        BubbleLiveStateVisuals.apply(view, BubbleLiveStateVisuals.State.SEND_READY)
        // Live countdown under the SEND label — ticks every 250ms, shows "Ns"
        // remaining before auto-cancel.
        view.findViewById<TextView>(R.id.bubbleTime)?.apply {
            visibility = View.VISIBLE
            text = "${SEND_READY_TIMEOUT_MS / 1000}s"
            setTextColor(androidx.core.content.ContextCompat.getColor(this@BubbleService, R.color.green_bright))
        }
        sendReadyExpiresAt = System.currentTimeMillis() + SEND_READY_TIMEOUT_MS
        timerHandler.removeCallbacks(sendReadyTickRunnable)
        timerHandler.post(sendReadyTickRunnable)
        timerHandler.removeCallbacks(revertSendReadyRunnable)
        timerHandler.postDelayed(revertSendReadyRunnable, SEND_READY_TIMEOUT_MS)
        ConsoleLog.log("Ready to send — tap bubble or wait to cancel", "info")
        val facehuggerOn = WimApp.facehuggerSendEnabled()
        if (facehuggerOn) {
            val sendCenter = injectionPort?.getSendButtonCenter()
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
        // Tap-to-send consent is handled once at onboarding (BouncerActivity's
        // pageAutoSend acknowledgment row sets auto_send_disclaimer_count to
        // 99). No runtime popup — tapping the green bubble is itself the
        // deliberate action. The 5 s green-bubble timeout is the cancel path.
        actuallySend()
    }

    private fun actuallySend() {
        val ok = injectionPort?.clickSendButton() == true
        if (ok) {
            ConsoleLog.log("Tap-to-send dispatched", "info")
            Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
        } else {
            ConsoleLog.log("Tap-to-send failed — no Send node", "warn")
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
        updateBubbleWindowLayout(view, params)
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
                updateBubbleWindowLayout(view, params)
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
                setForegroundMicActive(true)
            } catch (e: Exception) {
                Log.w(TAG, "FGS type re-assert failed: ${e.message}")
            }
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            Log.w(TAG, "startListening blocked: RECORD_AUDIO not granted")
            return
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

        // Safety: refuse to record into password/sensitive fields
        if (WimAccessibilityService.isPasswordField) {
            Toast.makeText(this, "Password field — dictation blocked", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Blocked: password field focused")
            // Brief visual flash on bubble
            bubbleTouchView?.findViewById<FrameLayout>(R.id.bubbleBezel)?.apply {
                val restore = WimApp.bubbleOpacity().coerceIn(0, 100) / 100f
                animate().alpha(0.4f).setDuration(100).withEndAction {
                    animate().alpha(restore).setDuration(200).start()
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

        // Visual: recording state — the inner knob warms to a glowing RED ember.
        // The "alive" motion
        // (lean-in + a slow breathe) is driven by BubbleAffect off the Listening
        // FSM state. The shared renderer locks the three live states to:
        // RED "REC" → AMBER "PROC" → GREEN "SEND".
        BubbleLiveStateVisuals.apply(bubbleTouchView, BubbleLiveStateVisuals.State.RECORDING)
        bubbleTouchView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            // The idle prediction chip ("→ CAS") otherwise persists into recording —
            // setBubbleIdle writes it and nothing cleared it here.
            text = ""
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
        val cloudAvailable = WimApp.apiKey().isNotBlank() || AuthManager.isSignedIn
        // The transcription source the user picked, honoured at every layer.
        //
        // This was `l1CloudAsr() && layer < 2`, so choosing CLOUD did nothing at
        // L2 and L3: the local English-only whisper-tiny transcribed anyway.
        // The control sits under a heading called TRANSCRIPTION and gates behind
        // cloudUnlocked(), so a user could pay for cloud transcription and
        // silently get the local engine on three of the four layers. L2/L3
        // already route to cloud whenever the local engine is unavailable, so
        // this hands them a path they exercise regularly.
        //
        // Gated on cloudAvailable so a stale CLOUD pick — chosen while signed
        // in, then signed out — falls through to the local engine and, when
        // that is missing too, to the existing refusal toast. Previously it
        // took the cloud branch with no recorder running and captured nothing.
        //
        // The decision itself now lives in AsrRoute, which is four booleans and
        // an integer and therefore testable. Extracting it turned up a fifth
        // defect in this block: at L4 with no key and not signed in, the old
        // condition started no recorder (it required cloudAvailable) while the
        // engine picker still took the isL4 branch, which starts nothing
        // either. The bubble sat recording and captured silence. AsrRoute
        // refuses that cell instead, so the user is told.
        val route = AsrRoute.decide(
            layer = WimApp.layer(),
            cloudPreferred = WimApp.l1CloudAsr(),
            localModelReady = whisperLocalEngine?.isReady() == true,
            cloudReachable = cloudAvailable,
        )
        val cloudAsrSelected = route.useCloudRecorder && !isL4
        if (route.useCloudRecorder) {
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
                Log.e(TAG, "AudioRecorder failed: ${e.message}", e)
                audioRecorder = null
                if (route.useCloudRecorder) {
                    // At L4 / L1-cloud the WAV recorder is the ONLY capture path —
                    // there is no local engine to fall back to. Surface the failure
                    // now instead of letting post-stop ASR time out after ~30s with
                    // the bubble silently stuck (finding H6).
                    isRecording = false
                    Toast.makeText(this, "Couldn't start the microphone — try again", Toast.LENGTH_LONG).show()
                    setBubbleIdle()
                    return
                }
                // L2/L3: the local whisper engine still captures, so this is a soft
                // fallback — keep going on-device only.
                Log.w(TAG, "AudioRecorder unavailable — continuing on-device only (L${WimApp.layer()})")
            }
        }

        // Start DAF playback if enabled
        if (WimApp.dafEnabled()) {
            dafPlayer = DafPlayer(this)
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
            // Tell the user WHY the bubble just reset. Without this the engine
            // simply went idle with no signal (finding H5) — the sibling
            // "model not ready" branch already toasts, the real-error path didn't.
            val msg = when (code) {
                WhisperLocalEngine.ERROR_NO_MODEL -> "Speech model not ready — try again in a moment"
                WhisperLocalEngine.ERROR_AUDIO -> "Microphone unavailable — try again"
                WhisperLocalEngine.ERROR_INIT,
                WhisperLocalEngine.ERROR_INFERENCE -> "Speech engine error — tap to try again"
                else -> "Speech recognition failed — tap to try again"
            }
            try {
                Toast.makeText(this@BubbleService, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
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

        // Every branch below is gated on the route, so Decision.Refuse matches
        // none of them and falls through to the refusal at the end. Gating only
        // the tail would let an L4 refusal take the isL4 branch and leave the
        // bubble "recording" with nothing capturing — the failure this whole
        // extraction exists to remove.
        if (route.useCloudRecorder && isL4) {
            Log.i(TAG, "L4 mode: WAV captured, no live ASR (whisper-1 runs post-stop)")
            updateLiveTranscript("Recording for deep reconstruction…")
        } else if (cloudAsrSelected) {
            Log.i(TAG, "L1 cloud mode: WAV captured, no live ASR (whisper-1 runs post-stop)")
            updateLiveTranscript("Recording (cloud ASR)…")
        } else if (l1LocalLiveOverride) {
            Log.i(TAG, "LIVE on — routing L1 to Android SpeechRecognizer for real-time partials")
            lastAsrRoute = "local (Android SpeechRecognizer)"
            onDeviceRecognizer?.start(object : OnDeviceRecognizer.ResultListener {
                override fun onPartialResult(text: String) = handlePartial(text)
                override fun onFinalResult(text: String) = handleFinal(text)
                override fun onSoundEvent(label: String) = handleSound(label)
                override fun onError(errorCode: Int) = handleError(errorCode)
            })
        } else if (route.useLocalEngine) {
            Log.i(TAG, "Using whisper-tiny.en engine (local, sherpa-onnx)")
            lastAsrRoute = "local (whisper-tiny.en on-device)"
            whisperLocalEngine?.start(object : WhisperLocalEngine.ResultListener {
                override fun onPartialResult(text: String) = handlePartial(text)
                override fun onFinalResult(text: String) = handleFinal(text)
                override fun onSoundEvent(label: String) = handleSound(label)
                override fun onError(errorCode: Int) = handleError(errorCode)
            })
        } else if (audioRecorder != null) {
            // The WAV recorder is already capturing. This is the L2/L3 cloud
            // fallback the routing comment above promises — "falls back to
            // cloud gpt-4o-transcribe via AudioRecorder when not [ready]" —
            // and it was never reached: the recorder started, then this chain
            // fell through to the refusal below and idled the bubble. A
            // signed-in L2/L3 user whose local model had not landed could not
            // dictate at all, with a working cloud path already recording
            // their voice, and the mic stayed open behind the toast because
            // the refusal never stopped the recorder.
            Log.i(TAG, "whisper-tiny not ready — using cloud ASR (L${WimApp.layer()})")
            lastAsrRoute = "cloud (local model unavailable)"
            updateLiveTranscript("Recording (cloud ASR)…")
            // Still fetch the model so the next take runs on-device.
            WhisperLocalModelDownloader.ensureDownloading(this, scope)
        } else {
            // No silent fallback to Android SpeechRecognizer. Per operator
            // 2026-06-06: L1 Local must never route through the native ASR
            // (chirp + accuracy hit). If whisper-tiny.en isn't on disk yet,
            // refuse the recording, reset the bubble, and tell the user the
            // model is still downloading. The download is a one-time ~40 MB
            // background fetch; usually completes within minutes of first
            // launch. Subsequent taps work normally once the model lands.
            //
            // isRecording was left true here, so the bubble kept believing a
            // take was in flight after the refusal idled it.
            isRecording = false
            Log.w(TAG, "whisper-tiny model not ready — refusing recording (no fallback to native ASR)")
            // "Try again in a moment" was a lie whenever the one download
            // attempt from WimApp.onCreate had already failed: nothing on any
            // code path ever retried, so the user could tap forever behind that
            // toast. Kick a retry off the refusal itself, and say which of the
            // two situations they are actually in.
            val started = WhisperLocalModelDownloader.ensureDownloading(this, scope) { result ->
                scope.launch {
                    val msg = when (result) {
                        is WhisperLocalModelDownloader.Result.Success ->
                            "Speech model ready — tap to record"
                        is WhisperLocalModelDownloader.Result.Failed ->
                            "Speech model download failed: ${result.reason.take(60)}. " +
                                "Switch Layer I to Cloud in the menu to dictate now."
                    }
                    Toast.makeText(this@BubbleService, msg, Toast.LENGTH_LONG).show()
                }
            }
            val note = when {
                // The model being present but cloud unreachable is a different
                // problem, and "still downloading" is a lie in that case — it
                // is the only state L4 can refuse in, since L4 has no local
                // option at all.
                WhisperLocalModelDownloader.isInstalled(this) ->
                    if (WimApp.layer() >= 4) "Layer IV needs a connection — sign in or switch layer"
                    else "Cloud transcription needs a connection — sign in or switch to Local"
                started -> "Speech model downloading — one moment"
                WhisperLocalModelDownloader.inProgress -> "Speech model still downloading…"
                else -> WhisperLocalModelDownloader.lastFailure
                    ?.let { "Speech model unavailable: ${it.take(60)}" }
                    ?: "Speech model still downloading — try again in a moment"
            }
            Toast.makeText(this, note, Toast.LENGTH_LONG).show()
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
    /**
     * Abandon the in-flight recording and throw the audio away.
     *
     * Bezel tap while recording. `BubbleState`'s transition table has listed
     * `Listening → Idle` as a legal user cancel since the FSM was written, but
     * nothing ever implemented it — once you started talking the only exit was
     * to stop and let WiM transcribe, reconstruct and paste whatever it heard.
     * For someone who started a sentence and then thought better of it, "wait
     * for it to finish, then delete the text out of the field" is a bad answer.
     *
     * Mirrors the stop teardown exactly, minus the pipeline: nothing is sent
     * anywhere and the wav is deleted rather than left in cacheDir for the next
     * recording to overwrite.
     */
    private fun cancelRecording() {
        if (!isRecording) return
        Log.i(TAG, "Recording cancelled by user — discarding audio")
        processed = true
        isRecording = false
        val wav = audioRecorder?.stop()
        setForegroundMicActive(false)
        audioRecorder = null
        onDeviceRecognizer?.stop()
        scope.launch(Dispatchers.IO) {
            whisperLocalEngine?.stop()
            try { wav?.delete() } catch (_: Exception) {}
        }
        dafPlayer?.stop(); dafPlayer = null
        // The post-stop ASR recovery timer would otherwise fire on an utterance
        // the user explicitly abandoned and paste it a beat later.
        timerHandler.removeCallbacks(postStopAsrOuterTimeout)
        awaitingPostStopAsr = false
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
        setBubbleIdle()
    }

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
            setForegroundMicActive(false)
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
                    injectionPort?.injectText(completion)
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
            setForegroundMicActive(false)
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
        // Speaking time ends here — everything after this is machine time.
        lastSpeechMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(0L)
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
        setForegroundMicActive(false)
        // Auto Quiet Mode: when the user hasn't forced Quiet Mode on, boost the
        // whole take before Whisper ONLY if it was whisper-soft. Amber confirms
        // on the bubble (pendingQuietAutoFlash -> setBubbleIdle). -22 dBFS is
        // the validated threshold from lavrentiy.py:263 (QUIET_AUTO_DBFS = -22.0).
        if (wavFile != null) {
            val takeDbfs = audioRecorder?.lastTakeDbfs() ?: -120.0
            // Always log so the operator can see the level whether or not
            // auto-quiet triggers. Lets us tune the upper bound by sampling
            // whispered vs. normal-voice takes side by side.
            Log.i(TAG, "Take level: ${"%.1f".format(takeDbfs)} dBFS (auto-quiet range -110.0..-22.0)")
            if (!WimApp.prefs.getBoolean("quiet_mode", false) && takeDbfs in -110.0..-22.0) {
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
        if (wavFile != null) WavSilenceTrimmer.trim(wavFile)
        lastWavFile = wavFile

        // Stop whichever engine was running this session; cheap defensive calls on all.
        onDeviceRecognizer?.stop()
        // WhisperLocalEngine.stop() runs sherpa-onnx decode synchronously
        // (~600-900 ms on a mid-range device). Dispatch off Main so the
        // user doesn't see dropped frames on every recording stop.
        scope.launch(Dispatchers.IO) { whisperLocalEngine?.stop() }

        // Force the bar to appear during pipeline work so the user sees what's
        // happening (transcribe → reconstruct → integrity checks → inject) even when
        // LIVE toggle is off. Subsequent stages overwrite this.
        showPipelineStage("⏳ Transcribing", "Sending audio to Whisper")

        if (wavFile != null && wavFile.exists() && wavFile.length() > 44) {
            Log.i(TAG, "L${WimApp.layer()}: Sending ${wavFile.length()} bytes to Whisper API")
            scope.launch {
                try {
                    lastAsrRoute = "cloud"
                    val whisperModel = when {
                        // L4's MultiPassDecoder reads per-segment confidence, and
                        // whisper-1 is the only model that still returns it.
                        WimApp.layer() >= 4 -> "whisper-1"
                        WimApp.layer() < 2 && WimApp.l1CloudAsr() -> "whisper-1"
                        // L2/L3 consume no segment data, so they take the newer,
                        // more accurate transcriber. Same price per minute.
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
                        // Clock-broom input: L1-cloud is the tier whose whole
                        // pitch is "better ear, no model" — word timings let
                        // the filler strip tell the verb "like" from the tic.
                        wordTimestamps = l1CloudFastPath,
                        language = WimApp.appLanguage(),
                    )
                    // Name the road, not just the model. TranscribeResult.engine
                    // has always said "direct" or "backend" and was never
                    // logged, so nothing on the phone could tell whether a take
                    // went straight to OpenAI or through wim-reconstruct — the
                    // one question force_backend exists to answer. lastAsrRoute
                    // said "cloud" for both.
                    lastAsrRoute = "cloud/${whisperResult.engine} ($whisperModel)"
                    Log.i(TAG, "Whisper ($whisperModel via ${whisperResult.engine}, ${whisperResult.asrMs}ms): ${whisperResult.text}")
                    lastWhisperLowConf = whisperResult.lowConfidenceSegments
                    lastWhisperSegments = whisperResult.timedSegments
                    lastWhisperWords = whisperResult.timedWords
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
                            // M12: empty (non-hallucinated) result — tell the user
                            // instead of silently resetting the bubble.
                            Toast.makeText(this@BubbleService, "No speech detected — try again", Toast.LENGTH_SHORT).show()
                            setBubbleIdle()
                        }
                    }
                } catch (e: Exception) {
                    val failure = classifyFailure(e)
                    Log.e(TAG, "Whisper failed (${failure.reason}): ${e.message}")
                    BubbleStateBus.advance(BubbleState.Failed(failure.reason))
                    showPipelineStage("Error — tap to retry", failure.message)
                    Toast.makeText(this@BubbleService, failure.message, Toast.LENGTH_LONG).show()
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
            lastWhisperWords = emptyList()
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
        // FSM: drop to Idle, honouring the transition matrix.
        //
        // This used to call BubbleStateBus.reset(), which force-sets Idle with
        // no validation — and setBubbleIdle is the ordinary return-to-rest
        // path, reached from 26 sites including every error and cancel branch.
        // So the matrix was bypassed on the way back from everywhere.
        //
        // That matters because the matrix deliberately FORBIDS Idle from the
        // four mid-pipeline states — Stripping, ReadingContext, Reconstructing,
        // Verified. A stop in the middle of the pipeline is supposed to pass
        // through Failed, and Failed is what BubbleAffect and BubblePersona
        // watch to commiserate and to explain what went wrong. Slamming
        // straight to Idle skipped it, so a reconstruction that died mid-flight
        // read to the user as the bubble quietly going back to sleep. That is
        // the silent-reset complaint 6e8a1bf patched site by site; this is the
        // mechanism underneath it.
        //
        // Cleanup still never blocks: when the direct transition is refused we
        // route through Failed and land on Idle regardless.
        //
        // Already-Idle is checked first because the matrix forbids Idle→Idle,
        // and this function is called from cleanup paths that can run twice.
        // Without the check every redundant call logs two FORBIDDEN errors.
        if (BubbleStateBus.current() !is BubbleState.Idle &&
            !BubbleStateBus.advance(BubbleState.Idle)
        ) {
            BubbleStateBus.advance(BubbleState.Failed(FailReason.UNKNOWN))
            BubbleStateBus.advance(BubbleState.Idle)
        }
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
        bubbleTouchView?.findViewById<FrameLayout>(R.id.bubbleInner)?.clearAnimation()
        applyBubbleTheme()
        bubbleTouchView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
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
            val lbl = bubbleTouchView?.findViewById<TextView>(R.id.bubbleLabel)
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
        bubbleTouchView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
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
        // M3: relay each reconstruction to a listener on localhost, which is
        // how an agent running on the same phone reads the pipeline's output
        // — the results file lives in app-private storage and nothing else can
        // open it.
        //
        // Debug builds always; giveaway builds only when `termux_relay` is
        // explicitly switched on, default off. A stranger running the
        // giveaway APK must never POST their reconstructions anywhere, even to
        // their own loopback. `release` can never reach this at all.
        if (!BuildConfig.DEBUG &&
            !(WimApp.testHooksEnabled() && WimApp.prefs.getBoolean("termux_relay", false))
        ) return
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
        val rect = injectionPort?.focusedFieldBounds() ?: return
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
        syncBubbleTouchWindow()
    }

    /**
     * Keep the small, transparent touch window centered over the large visual
     * window. Android routes touches by window bounds, so this public-API split
     * is what lets nearby taps reach the app underneath while the 180dp visual
     * surface still has room for pulse and drag animations.
     */
    private fun syncBubbleTouchWindow() {
        val visualView = bubbleView ?: return
        val visualParams = bubbleParams ?: return
        val touchView = bubbleTouchView ?: return
        val touchParams = bubbleTouchParams ?: return
        val visualWidth = visualView.width.takeIf { it > 0 } ?: visualParams.width
        val visualHeight = visualView.height.takeIf { it > 0 } ?: visualParams.height
        val target = BubbleTouchTarget.bounds(
            visualX = visualParams.x,
            visualY = visualParams.y,
            visualWidthPx = visualWidth,
            visualHeightPx = visualHeight,
            density = resources.displayMetrics.density,
            sizePercent = WimApp.bubbleSize(),
        )
        touchParams.width = target.width
        touchParams.height = target.height
        touchParams.x = target.x
        touchParams.y = target.y
        if (touchView.isAttachedToWindow) {
            try {
                windowManager?.updateViewLayout(touchView, touchParams)
            } catch (e: Exception) {
                Log.v(TAG, "wm touch update failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Keep the bubble window inside the current screen. Same math the drag
     * handler always used: the window may hang off-screen by exactly the
     * invisible inset so the visible bezel sits flush with the edge (taffy
     * headroom baked in). Returns true when x or y actually moved.
     *
     * 2026-09-04: the bubble "vanished" for good on George's phone — it was
     * alive at x=1854 on a 1080-wide screen. The clamp only ran inside the
     * drag handler, so a position picked on a wider screen (rotate, fold,
     * DeX) survived every show and every screen change, off the right edge.
     */
    private fun clampBubbleParamsToScreen(params: WindowManager.LayoutParams): Boolean {
        val density = resources.displayMetrics.density
        val sizeScale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
        val taffyScaleMax = 1.15f
        val effectiveBezelDp = 72f * sizeScale * taffyScaleMax
        val insetPx = ((180f - effectiveBezelDp).coerceAtLeast(0f) / 2f * density).toInt()
        val windowPx = (180 * density).toInt()
        val (screenW, screenH) = screenSizePx()
        val nx = params.x.coerceIn(-insetPx, screenW - windowPx + insetPx)
        val ny = params.y.coerceIn(-insetPx, screenH - windowPx + insetPx)
        val moved = nx != params.x || ny != params.y
        params.x = nx
        params.y = ny
        return moved
    }

    /**
     * Rotation, fold/unfold, DeX, font/density change: the screen we clamp
     * against just changed shape. Re-clamp the live bubble so it can never be
     * left parked outside the visible area. Runs once now and once again a
     * beat later — display metrics can lag the first callback by a frame.
     */
    /**
     * The screen the overlay lives on, in px. A Service is not a UI context,
     * so its Resources can lag or ignore display-size changes; the
     * WindowManager's own bounds are the truth on API 30+.
     */
    private fun screenSizePx(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = windowManager?.currentWindowMetrics?.bounds
                if (b != null && b.width() > 0 && b.height() > 0) return b.width() to b.height()
            } catch (_: Exception) {}
        }
        return resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = screenSizePx()
        Log.i(TAG, "onConfigurationChanged: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp " +
            "res=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} wm=${w}x${h}")
        reclampBubbleToScreen("config change")
        timerHandler.postDelayed({ reclampBubbleToScreen("config change +300ms") }, 300)
    }

    private fun reclampBubbleToScreen(reason: String) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val before = "${params.x},${params.y}"
        if (clampBubbleParamsToScreen(params)) {
            val (w, h) = screenSizePx()
            Log.i(TAG, "bubble re-clamped on $reason: $before -> ${params.x},${params.y} (screen ${w}x${h})")
            updateBubbleWindowLayout(view, params)
            bubbleMenu?.updatePositionFromBubble()
            liveTranscript?.repositionToBubble()
        } else {
            // Position is fine; the touch layer still needs to track any
            // density change so taps keep landing on the bezel.
            syncBubbleTouchWindow()
        }
    }

    private fun updateBubbleWindowLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // "View not attached to window manager" — the overlay grant was
            // revoked, or the window was torn down, while the service kept
            // running. The old code logged at verbose and carried on, so
            // `bubbleView` stayed non-null: the service believed a bubble was
            // on screen that the user could no longer see or touch. Every
            // subsequent showBubble() returned early because bubbleView != null,
            // so the bubble could never come back without killing the service.
            //
            // Drop the reference so the next show rebuilds it, and say so
            // rather than leaving the user with a dead app and no explanation.
            Log.w(TAG, "bubble window is gone (${e.javaClass.simpleName}) — clearing state so it can be rebuilt")
            bubbleView = null
            bubbleParams = null
            // The invisible touch window is a separate overlay and outlives the
            // visual one. Left behind it would keep swallowing taps in the
            // bubble's old position with nothing drawn there.
            bubbleTouchView?.let { tv ->
                try { windowManager?.removeView(tv) } catch (_: Exception) {}
            }
            bubbleTouchView = null
            bubbleTouchParams = null
            if (!android.provider.Settings.canDrawOverlays(this)) {
                try {
                    Toast.makeText(
                        this,
                        "WiM lost permission to draw over apps — re-enable it to get the bubble back",
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (_: Exception) {}
            }
            return
        } catch (e: Exception) {
            Log.v(TAG, "wm op failed: ${e.javaClass.simpleName}")
        }
        syncBubbleTouchWindow()
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
                updateBubbleWindowLayout(view, params)
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

    fun showPipelineStage(stage: String, detail: String, force: Boolean = false) {
        liveTranscript?.showStage(stage, detail, force)
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
            showPipelineStage("🎤", "Close the app — I'm your app now. Tap me whenever you need to talk.", force = true)
            timerHandler.postDelayed({ hideLiveTranscript() }, 5000)
        }
        activeDemoFinish = finish
        activeDemoScript = BubbleDemoScript(finish).also { it.start() }
    }

    private fun setBubbleProcessing() {
        stageArc?.setProcessing()
        BubbleLiveStateVisuals.apply(bubbleTouchView, BubbleLiveStateVisuals.State.PROCESSING)
    }

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
        // Take a private copy: `lastSpeechMs` is one slot on the service, but
        // it is READ seconds later, inside the coroutines that record the
        // session after reconstruction returns. Starting a second dictation
        // before the first finishes — common, since reconstruction routinely
        // outlasts the user's reaction time (see onInjectComplete) — would
        // otherwise stamp the second take's speaking time onto the first take's
        // row, quietly corrupting the WPM history.
        val speechMs = lastSpeechMs
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
            injectionPort?.injectText(rawText)
            onInjectComplete()
            return
        }

        val layer = WimApp.layer()
        // Tone comes from THIS message, not from the app it's going into and
        // not from a remembered preference (operator 2026-07-29: Gmail is where
        // you email your brother; the same coworker gets a deadline at noon and
        // beers at six). The words decide; the clock only breaks a tie they
        // left open; the saved pref is the last resort. See ToneFromContent.
        // An explicit tone pick outranks the classifier. Picking a tone in the
        // dashboard, or answering the bubble's work/casual question, stamps
        // manual_tone_set_ms — the legacy app-based router has honored that
        // signal since it was added, but ToneFromContent shipped without
        // reading it and overrode the user unconditionally. Measured on device
        // 2026-07-29: the operator selected professional, said "hey man, I need
        // you to send the Henderson invoice before Friday", and got the
        // identical casual output he got on the casual run — because the words
        // said casual and the picker was never consulted. The tone tile was
        // decorative and the layer took the blame.
        val manualToneAgeMs = System.currentTimeMillis() - WimApp.prefs.getLong("manual_tone_set_ms", 0L)
        val manualToneHolds = manualToneAgeMs < MANUAL_TONE_LOCK_MS
        val tone = if (manualToneHolds) {
            ConsoleLog.log("Tone: ${WimApp.tone()} — you picked it", "info")
            WimApp.tone()
        } else if (WimApp.prefs.getBoolean("auto_tone", false)) {
            when (val v = ToneFromContent.classify(rawText, WimApp.profile().getVocabulary())) {
                is ToneFromContent.Verdict.Use -> {
                    Log.i(TAG, "Tone from content: ${v.tone} (${v.why})")
                    ConsoleLog.log("Tone: ${v.tone} — ${v.why}", "info")
                    v.tone
                }
                is ToneFromContent.Verdict.Ambiguous -> {
                    val now = java.util.Calendar.getInstance()
                    val byClock = ToneFromContent.breakTieByClock(
                        hourOfDay = now.get(java.util.Calendar.HOUR_OF_DAY),
                        isWeekend = now.get(java.util.Calendar.DAY_OF_WEEK).let {
                            it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY
                        },
                        workHours = WimApp.prefs.getString("work_hours", null),
                    )
                    if (byClock != null) {
                        Log.i(TAG, "Tone from clock (message was ambiguous): $byClock")
                        ConsoleLog.log("Tone: $byClock — ambiguous wording, went by the clock", "info")
                        byClock
                    } else {
                        Log.i(TAG, "Tone unresolved — using saved ${WimApp.tone()}")
                        ConsoleLog.log("Tone: ${WimApp.tone()} — couldn't tell from the message", "warn")
                        WimApp.tone()
                    }
                }
            }
        } else WimApp.tone()
        val situation = WimApp.situation()
        ConsoleLog.log("Processing... [L$layer ${tone.uppercase()} $situation]", "info")
        // ASR and reconstruction are separate stages with separate routes —
        // log which ASR actually succeeded so "local ASR + cloud
        // reconstruction" never reads as an entirely local pipeline.
        Log.i(TAG, "ASR route: $lastAsrRoute")
        ConsoleLog.log("ASR route: $lastAsrRoute", "info")
        val profile = WimApp.profile()

        // Rate-gap analysis: does the word count match the audio duration vs the
        // user's historical baseline? Low ratio = ASR dropped content (Samsung
        // pre-smooths "go go go go go" to "go go go"). Information-only; signals
        // downstream reconstruction that something may need recovery.
        val audioDurationSec = asrMs / 1000.0
        val rawWordCountForGap = rawText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val rateGap = RateGapDetector.analyze(rawWordCountForGap, audioDurationSec, profile.baselineWps())
        // Baseline hygiene (2026-08-04): junk takes must not teach the
        // baseline. A one-word "(chuckles)" hallucination over six seconds
        // reads as 0.16 wps and drags the median toward the floor — measured
        // live, baseline fell 1.9 → 1.2 after a run of junk sessions. A wrong
        // baseline then whispers "words are missing, invent from context" to
        // the model on every normal take. Feed the baseline only real speech:
        // enough words, enough audio, and not a take the gap detector already
        // flagged as content-dropped.
        if (rateGap.wps > 0 && rawWordCountForGap >= 5 &&
            audioDurationSec >= 2.0 && !rateGap.gapDetected
        ) {
            profile.recordWps(rateGap.wps)
        }
        if (rateGap.gapDetected) {
            Log.w(TAG, "Rate-gap: wps=${"%.2f".format(rateGap.wps)} baseline=${"%.2f".format(rateGap.baselineWps ?: 0.0)} ratio=${"%.2f".format(rateGap.ratio ?: 0.0)}")
            ConsoleLog.log("Rate-gap: ${((rateGap.ratio ?: 0.0) * 100).toInt()}% of baseline WPS — ASR likely dropped content", "warn")
        }

        // L1 route parity (spec 2026-07-29, supersedes the 2026-06-06 raw
        // fast-path): local and cloud transcription get the SAME deterministic
        // cleanup — voice shortcuts, block-hallucination strip,
        // DisfluencyFilter (fillers, repeated words/phrases, stutter
        // fragments) and L1Pipeline corrections — by falling through to the
        // shared path below. Cloud ASR only improves transcription accuracy;
        // neither route adds LLM reconstruction at Layer 1.

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
        // capInsaneRuns AFTER hallucination strip, BEFORE everything else —
        // including L4's unfiltered path. Whisper's repetition loop pasted
        // ~112 "to"s live (2026-08-05); the cap keeps 8 as evidence.
        val deHallucinated = DisfluencyFilter.capInsaneRuns(
            DisfluencyFilter.stripBlockHallucinations(
                expandedText, lastWhisperLowConf, log = { msg -> Log.i(TAG, msg) }
            )
        )
        if (deHallucinated != expandedText) {
            Log.i(TAG, "Block hallucination stripped: ${expandedText.length - deHallucinated.length} chars")
            ConsoleLog.log("Block hallucination stripped: ${expandedText.length - deHallucinated.length} chars", "warn")
        }

        // Pre-clean: strip disfluencies from raw ASR text before GPT sees it.
        // L4 disfluency-aware path needs to SEE the disfluencies to reason about them,
        // so we skip the pre-filter at L4 and let the clinical prompt handle it.
        val rawWordCount = deHallucinated.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        // Merge user profile fillers with language-pack fillers (empty for English).
        val fillerWords = profile.getFillerWords() + LangPack.fillers()
        showPipelineStage("\u29BB Cleaning up...", "Removing disfluencies & fillers")
        // Clock-broom (L1-cloud only): whisper-1 word timings tell the verb
        // "like" from the filler tic \u2014 strip only pause-bounded occurrences,
        // and keep the risky fillers OUT of the naive word-list pass so it
        // can't eat what the clock protected. No timings \u2192 old behavior.
        // Two brooms for the words that are fillers AND ordinary English —
        // "like", "you know", "i mean", "so", "well", "right". The naive
        // word-list pass cannot tell them apart and must never see them.
        //
        // The clock broom is the good one: it strips an occurrence only when a
        // real pause sits on both sides. It needs per-word timings, which only
        // whisper-1 returns, so it covers L1-cloud and nothing else. Everywhere
        // else these six went to the naive pass, and it ate the real ones —
        // measured on device 2026-08-22, "Is everything going well here?"
        // arrived as "Is everything going here?", and "regardless of layer,
        // right?" as "regardless of layer," which then collected a full stop
        // and reached the user as "layer,.".
        //
        // Where there is no clock, position stands in for it: strip these words
        // only where they OPEN a sentence, which is where a speaker fills time.
        // Inside a clause they are doing work. From L2 up the prompt already
        // says "Strip filler words" and the model can weigh context that no
        // regex here can, so the leftovers are its job, not ours.
        val clockBroom = layer < 2 && lastWhisperWords.isNotEmpty()
        val broomInput = if (clockBroom) {
            DisfluencyFilter.stripTimedFillers(
                deHallucinated, lastWhisperWords, log = { msg -> ConsoleLog.log(msg, "info") }
            )
        } else {
            DisfluencyFilter.stripLeadingMarkers(deHallucinated)
        }
        // Risky fillers are held back from the naive pass on EVERY route now,
        // not just the one with a clock.
        val safeFillers =
            fillerWords.filter { it.lowercase().trim() !in DisfluencyFilter.RISKY_FILLERS }
        val preCleaned = if (layer >= 4) deHallucinated else DisfluencyFilter.filter(
            broomInput,
            safeFillers,
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
                log = { msg -> Log.i(TAG, msg) },
                // Corrections are Layer-3+ (design call, George 2026-08-04).
                // The poisoned-list incident: 50 auto-learned pairs rewrote a
                // quarter of every plain transcription, and L1 has no guard.
                applyCorrections = false
            )
            val l1Text = l1Result.text
            // FSM: L1 has no model reconstruction — the deterministic pass is "trusted by
            // construction." Verified → Injected before the actual paste so
            // observers see the right transition order.
            BubbleStateBus.advance(BubbleState.Verified(System.currentTimeMillis()))
            setStageArc(2)
            if (stripped > 0 || l1Text != preCleaned) {
                val corrCount = if (l1Text != preCleaned) " + corrections" else ""
                showPipelineStage("\u2713 Cleaned", "$stripped words stripped$corrCount \u00B7 ${rawWordCount}w \u2192 ${l1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}w")
            }
            ConsoleLog.log("-> \"${l1Text.take(80)}${if (l1Text.length > 80) "..." else ""}\"  [${l1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}w]", "out")
            if (injectionPort == null) {
                Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
                setBubbleIdle()
                return
            }
            injectionPort!!.injectText(l1Text)
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
                riskFlags = emptyList(),
                speechMs = speechMs
            )
            SessionUpdateBus.emit()
            // Test automation: capture result
            if (WimApp.testHooksEnabled()) {
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

        // Apply phonetic-neighbor matching (L2/L3) and, at L3+, profile
        // corrections BEFORE GPT sees the text. Corrections are gated to
        // layer >= 3 inside the stage (design call, George 2026-08-04 —
        // the poisoned-list incident). Logic extracted to
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
            var multiPassDeferred: Deferred<MultiPassDecoder.MultiPassResult?>? = null
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
            // Tracks whether the reconstruction was actually delivered to the
            // field. Once true, a later exception must NOT overwrite it with raw
            // or report a failure the user never experienced (finding C2).
            var injected = false
            try {
                val layerLabel = when (layer) {
                    2 -> "generic cleanup"
                    3 -> "+ your vocabulary"
                    4 -> "disfluency-aware"
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
                val precedingContext = injectionPort?.getPreCursorContext() ?: ""
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
                    // Locale.US on purpose: this string goes into the model
                    // prompt, not onto the screen. On a French or Russian
                    // device the default locale renders "1,25", and a decimal
                    // comma inside a machine-facing note is a needless way to
                    // confuse the thing reading it.
                    val wpsStr = String.format(java.util.Locale.US, "%.2f", rateGap.wps)
                    val baselineStr = rateGap.baselineWps
                        ?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"
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
                    if (layer >= 4 && wav != null && wav.exists() && wav.length() > 44) {
                        multiPassDeferred = async(Dispatchers.IO) {
                            try {
                                val recognizer = WhisperApiL1(
                                    model = "whisper-1",
                                    language = primaryLanguage,
                                    patienceMode = true,
                                )
                                MultiPassDecoder(recognizer).run(wav)
                            } catch (e: Exception) {
                                // Contain the failure INSIDE the async. A throwing
                                // child async cancels its parent (currentReconJob),
                                // which would abort the in-flight reconstruction —
                                // the SupervisorJob on `scope` only shields siblings,
                                // not children. Returning null keeps multipass
                                // best-effort; the clarifier proceeds without it.
                                Log.w(TAG, "MultiPassDecoder failed (contained): ${e.message}")
                                null
                            }
                        }
                    }
                }

                // Wait for the cross-check BEFORE reconstructing, at L4 only.
                //
                // The three passes exist to find the words the ear was unsure
                // about, and that is exactly what the reconstruction needs to
                // know — the backend has carried a `whisper_disagreements`
                // field for it all along. It was never filled: this call passed
                // an empty list, so the signal reached only the post-paste
                // clarifier, and Sonnet reconstructed the operator's hardest
                // speech with no idea which words were shaky. Three extra
                // whisper-1 calls, paid for, and the model that matters most
                // never saw the answer.
                //
                // The cost is real and only paid at L4. Running the passes
                // alongside the reconstruction made them free in wall-clock;
                // waiting for them adds their ~6s to a call that already runs
                // 60-70s. On the clinical layer, for the speech this product
                // exists for, six seconds to stop the model guessing at a
                // blocked word is worth paying. L2/L3 never launch the passes,
                // so nothing changes for them, and a failure still yields null
                // and reconstructs exactly as before.
                var multiPass: MultiPassDecoder.MultiPassResult? = null
                if (multiPassDeferred != null) {
                    showPipelineStage("⟳ Cross-checking", "Listening again for unsure words")
                    multiPass = try {
                        multiPassDeferred.await()
                    } catch (e: Exception) {
                        Log.w(TAG, "MultiPassDecoder failed: ${e.message} — reconstructing without it")
                        null
                    }
                    multiPass?.let {
                        Log.i(TAG, "MultiPass: ${it.disagreementSummary.size} disagreement positions into the prompt")
                        if (it.disagreementSummary.isNotEmpty()) {
                            ConsoleLog.log("Cross-check: ${it.disagreementSummary.size} unsure words flagged to the model", "info")
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
                    // HELD BACK 2026-08-22, an hour after it was first sent.
                    //
                    // The passes still run and are still awaited, and the
                    // clarifier still gets them. But this field had never once
                    // carried a value — every caller passed emptyList() — and
                    // the first real payload took wim-reconstruct down:
                    //
                    //   MultiPass: 1 disagreement positions into the prompt
                    //   Backend error 500: {"error": "Reconstruction failed:
                    //   TypeError", "retriable": false, "tier": "Pro"}
                    //
                    // one second apart, on the operator's phone, at L4. The
                    // server has never parsed this shape because nothing ever
                    // sent it. Restore this line together with the server-side
                    // fix, not before — the deploy is what is missing, not the
                    // signal, and L4 failing outright is worse than L4 guessing.
                    whisperDisagreements = emptyList(),
                    speechSeverityMod = if (rateGap.gapDetected) 0.3 else 0.0,
                    precedingContext = precedingContext,
                    languageCode = primaryLanguage,
                    compressionRatioNote = compressionRatioNote,
                    audiencePackage = WimAccessibilityService.currentForegroundPackage,
                    wavFile = if (layer >= 4) lastWavFile else null,
                    whisperSegments = if (layer >= 4) lastWhisperSegments else emptyList(),
                )
                turboTaxJob?.cancel()
                if (reconResult.downgradedFromL4 && !l4DowngradeNotified) {
                    l4DowngradeNotified = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(this@BubbleService, "L4 unavailable — using L3 this session.", Toast.LENGTH_SHORT).show()
                    }
                }
                Log.i(TAG, "Reconstruction (${reconResult.reconMs}ms): ${reconResult.cleanText}")
                ConsoleLog.log("Recon: ${reconResult.reconMs}ms | Integrity: ${if (reconResult.falconApproved) "OK" else "RAW RESTORED"}", "info")

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

                // Net two — deterministic swear gate, professional/formal only
                // ("the transistor switch", George 2026-08-04). Runs after
                // every other guard so nothing downstream can reintroduce a
                // swear. Catches only what the prompt's register net missed.
                val gateResult = SwearGate.apply(outputText, tone)
                if (gateResult.caught > 0) {
                    outputText = gateResult.text
                    Log.i(TAG, "Swear gate: ${gateResult.caught} caught ($tone)")
                    ConsoleLog.log("Swear gate: ${gateResult.caught} caught", "warn")
                }

                val finalWordCount = outputText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                val integrityLabel = if (leakDetected) "\u26A0 LLM leak filtered" else if (reconResult.falconApproved) "\u2713 Meaning verified" else "\u26A0 Using original"
                BubbleStateBus.advance(BubbleState.Verified(System.currentTimeMillis()))
                setStageArc(2)
                showPipelineStage(integrityLabel, "${rawWordCount}w \u2192 ${finalWordCount}w \u00B7 ${reconResult.reconMs}ms")

                // Brief pause so user can see the final stage
                kotlinx.coroutines.delay(800)

                val accInstance = injectionPort
                val injectResult = accInstance?.injectText(outputText)
                if (injectResult == WimAccessibilityService.InjectResult.FIELD) {
                    relayToTermux(outputText)
                    BubbleStateBus.advance(BubbleState.Injected(System.currentTimeMillis(), SuccessCue.PASTE))
                    injected = true
                    setStageArc(3)
                } else {
                    // The reconstruction did NOT land in the target field: either
                    // there is no accessibility service, or injectText fell back to
                    // the clipboard (it already toasted the "tap a field" hint).
                    // Report INJECT_FAIL instead of a clean paste, still record the
                    // GOOD text so it isn't lost from history, and skip the
                    // field-dependent clarifier/learning steps below.
                    if (accInstance == null) {
                        Log.e(TAG, "No accessibility service instance — cannot inject")
                        Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "injectText did not reach the field (result=$injectResult) — clipboard fallback")
                    }
                    BubbleStateBus.advance(BubbleState.Failed(FailReason.INJECT_FAIL))
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
                        riskFlags = reconResult.riskFlags + "inject_fail",
                        speechMs = speechMs,
                    )
                    SessionUpdateBus.emit()
                    setBubbleIdle()
                    return@launch
                }

                // Diff-learning KILLED 2026-08-04 (design call, George). The
                // learner diffed the speaker's words against GPT's rework and
                // mistook paraphrase for correction — that is the factory that
                // manufactured the 50-pair poisoned list ("with"→"come",
                // "just"→"participated") which mangled a quarter of every L1
                // transcription. Corrections now enter the profile ONLY through
                // explicit user acts (profile UI, synced desktop edits).
                // learnFromReconstruction stays in ProfileManager for Lav
                // parity and its tests, but nothing on this path calls it.

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
                    riskFlags = reconResult.riskFlags,
                    speechMs = speechMs
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
                if (WimApp.testHooksEnabled()) {
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
                // The cancel that used to sit here is gone. It discarded the
                // passes whenever the clarifier was switched off for the
                // foreground app — after the three whisper-1 calls were already
                // in flight, so it freed nothing and lost the result. They feed
                // the reconstruction now and are awaited above, so there is
                // nothing left to cancel and no reason to want to.
                if (WimApp.isClarifierAllowed(clarifierPkg)) {
                    var wordConfidences: List<Clarifier.WordConfidence> = emptyList()
                    multiPass?.let { multi ->
                        // P0 fix — MultiPassDecoder ranges index into Whisper raw text,
                        // but Clarifier.evaluate is called with finalText = outputText
                        // (GPT-reconstructed). Without realignment, approve-swap
                        // corrupts the injected text. See ClarifierAlignment.kt.
                        wordConfidences = alignWordConfidencesToText(multi.wordConfidences, outputText)
                        Log.i(TAG, "MultiPass (L$layer): ${multi.disagreementSummary.size} disagreement positions, ${multi.wordConfidences.count { it.multiPassDisagreements > 0 }} flagged words, ${wordConfidences.size} aligned to outputText")
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
                        val supported = injectionPort?.focusedAppSupportsAutoSend() == true
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
                if (injected) {
                    // The reconstruction was already delivered to the field and the
                    // FSM is at Injected — this exception came from a POST-injection
                    // step (learning / L1-detect / prediction / clarifier). Do NOT
                    // overwrite the good text with raw or tell the user it failed.
                    // (advance(Failed) from Injected is a forbidden transition
                    // anyway.) Log it and settle the bubble like the success path.
                    Log.e(TAG, "Post-injection step failed (text already delivered): ${e.message}", e)
                    ConsoleLog.log("Post-inject step failed: ${e.message?.take(60)}", "warn")
                    try { onInjectComplete() } catch (_: Exception) { setBubbleIdle() }
                } else {
                    val failure = classifyFailure(e)
                    BubbleStateBus.advance(BubbleState.Failed(failure.reason))
                    Log.e(TAG, "Reconstruction error (${failure.reason}): ${e.message}", e)
                    ConsoleLog.log("Reconstruction failed [${failure.reason}]: ${e.message?.take(60)}", "error")
                    // Fallback: inject pre-cleaned text
                    injectionPort?.injectText(preCleaned)
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
                        riskFlags = listOf("reconstruction_failed"),
                        speechMs = speechMs
                    )
                    SessionUpdateBus.emit()
                    // Test automation: capture error
                    if (WimApp.testHooksEnabled()) {
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
                        Toast.makeText(this@BubbleService, "${failure.message} (kept your original words)", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                    setBubbleIdle()
                }
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
        displayListener?.let { l ->
            try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(l) } catch (_: Exception) {}
        }
        displayListener = null
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
        // M6: tear down the standalone overlay windows so their views (and the
        // service context they hold) don't leak if one was showing at teardown.
        wordHighlight?.hide()
        wordHighlight = null
        sendDisclaimer?.dismiss()
        sendDisclaimer = null
        dafPlayer?.stop()
        dafPlayer = null
        audioRecorder?.release()
        audioRecorder = null
        hideBubble(notifyRecall = false)
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
