[Claude Code consulting Gemini 3.1-pro-preview for a second opinion]

# Task: Independent architecture + security + production-readiness audit of wim-android

You are reviewing the WiM Android codebase (Kotlin). Goal: surface architectural soundness issues, production-readiness blockers, security risks, and code-quality problems that a CLAUDE-FAMILY reviewer would systematically miss.

This is the third audit pass. The first two passes (2026-05-12 and 2026-05-13) used multiple Claude agents and found 15 issues that were fixed. The operator wants to know what Claude reviewers consistently overlook in this codebase. Be direct, push hard, prioritize what we genuinely don't see — not what we'd already catch.

## Context

WiM ("What I Meant") is an Android voice-to-intent reconstruction app:
- Floating bubble overlay (TYPE_APPLICATION_OVERLAY foreground service)
- Multi-layer pipeline: L1 (local Whisper-tiny.en via sherpa-onnx) → L2/L3 (cloud GPT-4o via OkHttp) → L4 (Claude Sonnet 4.6 with 8k extended thinking, called direct from device)
- Inputs from `OnDeviceRecognizer` (Android `SpeechRecognizer`) + `AudioRecorder` (WAV for cloud)
- Output injected via `WimAccessibilityService` using `ACTION_SET_TEXT` (clipboard+PASTE fallback)
- "Clarifier" feature: post-inject prompt offering word swaps for low-confidence ASR outputs (patent-pending — claim 11)
- Backend: signed-in users route L2/L3 via Firebase ID-token-authenticated Cloud Function `wim-reconstruct`; L4 always device-direct to api.anthropic.com
- Falcon (cross-LLM meaning-preservation validator) was retired 2026-05-10 — stub returns true
- Min SDK 26, Target SDK 35

## What the 5/12-5/13 audit already caught (do NOT re-surface these)

P0: daily_count never resets in Firestore for paying users (rate limit clobber); no WakeLock during L4 (now fixed); Clarifier word-swap offsets misalign GPT-reconstructed vs Whisper-text (now fixed via ClarifierAlignment); reconstructed text destroyed on focus drop (now fixed with clipboard fallback); ProfileManager unsynchronized mutations; clarifier_sensitivity "off" stickiness (now migrated); MultiPassDecoder last-wins logprob collision; hallucination filter discards "ok"/"no"/"you" (partially fixed); turboTaxJob scope (now hoisted); BubbleService 4126→4310 lines (god object) — partial extraction underway (`Bubble*` slices 2026-05-25); BubbleStateBus `forceTo()`/`reset()` bypassing FSM validation; H1 patent claim 1 contradicted by per-package switches in WimAccessibilityService; H2 four ASR engines (3 undocumented); H3 lastWavFile race (now @Volatile); H4 unbounded scope under FGS time caps; H6 BootReceiver mic gap; M1 god-object growth; M2 isWorkMode/tone ping-pong race (fixed with 30s lockout); Anthropic ET extinction (handled via GPT-4o fallback); 9 lying code comments (now refreshed). Play Store RED gates: privacy URL not hosted, zero screenshot PNGs, no 1024×500 feature graphic.

## Focus your review on what Claude reviewers consistently MISS

Claude reviewers tend to be strong at: race conditions, null-safety, resource leaks, coroutine cancellation, generic OWASP categories. They tend to be WEAK at:

1. **Android platform-specific footguns** — accessibility service lifecycle quirks, ANR risk under specific OEM behavior (Samsung One UI, Xiaomi MIUI), TYPE_APPLICATION_OVERLAY subtleties, Doze/App Standby battery interactions, foreground service `specialUse` Play Store policy compliance
2. **Multi-tenant key/cost exposure** — what happens when a $20 user runs 10,000 L4 reconstructions in a day; what stops a malicious user from extracting the bundled Anthropic key (RE+decompile, runtime hook); how the ID-token verification handles forged or stale tokens server-side; what kicks off accidentally-recursive cost loops
3. **Patent integrity** — the file has explicit patent claim references inline; whether the *implementation* actually matches the claim or whether dependent claims fold into independent claims due to scope drift
4. **Failure mode UX** — when the network is flaky, when Whisper hallucinates, when accessibility focus drops mid-inject, what does the USER see; what mode does the app accidentally land in
5. **Cross-version Android behavior gaps** — SDK 26 (Android 8) → SDK 35 (Android 15) is 7 OS releases; behavior changes in foreground services (Android 14), accessibility (Android 12+), overlay (Android 12+), `RECEIVE_BOOT_COMPLETED` (Android 10+ for non-default), `MANAGE_OVERLAY_PERMISSION` (Android 11+ visibility)
6. **Threading/dispatch fragility specific to Android Service** — main thread blocked during cloud calls, IBinder semantics, restoration after process death, START_STICKY redelivery
7. **Sneaky permission escalations or data leaks** — Audience-context plumbing (foreground package name) crossing the network boundary; accessibility tree dumps in logcat; clipboard-fallback exposure to other apps via ClipDescription
8. **Privacy compliance for Play Store** — DSAR (data subject access), GDPR Article 6/9 lawful basis for the LLM reconstruction, "voice data" classification, child-user signal, sensitive-category classification of speech-disfluency data, what Crashlytics actually exfiltrates
9. **Patent-claim-1 wherein clauses** — "application-context-invariant" — is this actually true given the per-app branches in WimAccessibilityService.findSendNodeForApp / autoSwitchTone / clarifier blacklist / audience-context per-package mapping? Are these dependent claims or do they break independent claim 1?

## Output format

Respond as a numbered list of FINDINGS. Each finding has:

- **SEVERITY**: CRITICAL / HIGH / MEDIUM / LOW
- **CATEGORY**: which weakness from the list above (or "Other")
- **WHERE**: file path + line numbers
- **WHAT**: the issue in one sentence
- **WHY CLAUDE MISSES THIS**: one sentence — what pattern-matching default leads Claude reviewers past it
- **EVIDENCE**: specific code or behavior pointer
- **FIX**: concrete, actionable

End with a "META-OBSERVATIONS" section (5-10 bullets) of what Claude reviewers structurally miss in Android codebases like this one. This is the most important section for the operator. Be direct.

The operator's preference is bold/blunt analysis without hedging. Don't pad with "may consider", "it would be worth thinking about", etc. State findings flatly.

## Source files

--- README.md (project overview, condensed) ---

# WiM — What I Meant

Voice-to-intent reconstruction for Android. Speak messy, get clean text.

WiM captures voice, transcribes on-device, optionally reconstructs through GPT/Sonnet, and pastes into whatever text field is focused via accessibility injection.

Architecture: On-device ASR (sherpa-onnx whisper-tiny.en) → GPT-4o / Sonnet 4.6 ET reconstruction → ACTION_SET_TEXT injection. Floating TYPE_APPLICATION_OVERLAY service. Falcon meaning-preservation validator (retired 2026-05-10 — stub returns true).

Layers:
- L1: on-device ASR + DisfluencyFilter + corrections. No cloud. (default)
- L2: + GPT-4o cleanup. Cloud Function `wim-reconstruct` if signed-in, else direct OpenAI.
- L3: + personal vocabulary/profile.
- L4: + Claude Sonnet 4.6 with 8K extended thinking. Whisper-1 with MultiPassDecoder (3 parallel passes for word-confidence). Device-direct to api.anthropic.com.

Two API paths:
1. Signed in (Firebase Auth + Google Sign-In) → Cloud Function backend at bakers-agent.cloudfunctions.net. Server holds OpenAI key. L4 still goes device-direct to Anthropic because backend has no Anthropic codepath.
2. Local API key → direct OpenAI / Anthropic calls. User enters sk-proj-... key in settings. DEBUG builds also bundle a key in assets/ (api_key.txt, anthropic_key.txt).

Permissions: SYSTEM_ALERT_WINDOW (overlay), RECORD_AUDIO (mic), BIND_ACCESSIBILITY_SERVICE (text injection), POST_NOTIFICATIONS, BLUETOOTH_CONNECT, FOREGROUND_SERVICE_SPECIAL_USE + FOREGROUND_SERVICE_MICROPHONE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RECEIVE_BOOT_COMPLETED.

--- app/src/main/AndroidManifest.xml ---

<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:name=".WimApp"
        android:allowBackup="false"
        android:enableOnBackInvokedCallback="true"
        android:icon="@mipmap/ic_launcher"
        android:label="What I Meant"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.WhatIMeant">

        <activity android:name=".BouncerActivity" android:exported="false" android:theme="@style/Theme.WhatIMeant" />
        <activity android:name=".onboarding.OnboardingActivity" android:exported="false" android:theme="@style/Theme.WhatIMeant" />
        <activity android:name=".GuideActivity" android:exported="false" android:theme="@style/Theme.WhatIMeant" />
        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service android:name=".BubbleService"
            android:foregroundServiceType="specialUse|microphone"
            android:exported="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Persistent floating overlay for voice-to-text dictation that must remain active while the user speaks into other apps" />
        </service>

        <service android:name=".WimTileService"
            android:label="WiM"
            android:icon="@drawable/ic_qs_tile_wm"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
            android:exported="true">
            <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE" /></intent-filter>
        </service>

        <receiver android:name=".WimWidgetProvider" android:exported="true">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_info" />
        </receiver>

        <receiver android:name=".BootReceiver" android:exported="false">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
        </receiver>

        <service android:name=".BubbleRestartJobService"
            android:permission="android.permission.BIND_JOB_SERVICE"
            android:exported="false"/>

        <service android:name=".WimAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_config" />
        </service>

    </application>
</manifest>

--- app/src/main/java/com/wim/app/WimApp.kt (full file, 292 lines) ---

[FILE CONTENTS — see WimApp.kt for full implementation; key flows for this audit:]

`apiKey()` reads from prefs `api_key` first, falls back to `bundledKey("api_key.txt")` which reads from `assets/` ONLY in DEBUG builds. Same pattern for `anthropicApiKey()` reading `anthropic_key.txt`.

The bundled keys are wrapped: `if (!BuildConfig.DEBUG) return ""` — so RELEASE builds return empty for the bundled key path. Users must either be signed in (and route through Cloud Function) or enter their own key.

`onCreate()` initializes Firebase, prefs, ProfileManager, sessionHistory. It auto-downloads whisper-tiny.en model (~40 MB) on first launch in a background coroutine if local ASR is enabled and not yet installed. Restores dark mode + locale from prefs. Registers a `FirebaseAuth.addAuthStateListener` that calls `profileManager.startSync(user.uid)` + `sessionHistory.syncFromFirestore` on sign-in.

One-time migration: if `clarifier_sensitivity = "off"` (old default), bump to "medium" once, set `clarifier_sensitivity_migrated` flag.

`clarifierSensitivityThreshold()` maps: "off" → 999.0, "low" → 0.85, "medium" → 0.55, "high" → 0.40, unknown → 999.0. The 999.0 default-on-unknown means a corrupted pref value disables clarifier silently.

--- app/src/main/java/com/wim/app/ReconstructClient.kt (full file, 790 lines) ---

[Key code excerpts from ReconstructClient.kt:]

OkHttpClient: connectTimeout 15s, readTimeout 60s.

`reconstruct()` is the top-level entry. Routing logic:
```kotlin
val idToken = AuthManager.getIdToken()
val anthropicKey = WimApp.anthropicApiKey()
val forceDirectForL4Sonnet = layer >= 4 && anthropicKey.isNotBlank()
val result = if (idToken != null && !forceDirectForL4Sonnet) {
    reconstructViaBackend(...)  // signed-in path — through Cloud Function
} else {
    reconstructDirect(...)  // device-direct OpenAI or Anthropic with bundled/user key
}
```

Backend POST: Authorization: Bearer ${idToken}, JSON body with raw text, tone, layer, situation, profile (vocabulary, corrections, filler_words, trigger_words, onset_weights, covert_profile, profile_l1), whisper_low_conf segments, whisper_disagreements, speech_severity_mod, preceding_context, language_code, compression_ratio_note, audience_package, rejection_history, style_examples, previous_outputs, regenerate.

Direct path (`reconstructDirect`):
```kotlin
val apiKey = WimApp.apiKey()
if (apiKey.isBlank()) throw IllegalStateException("No API key configured")
// builds system prompt inline (extremely long — see GPT-4o + Sonnet branches)
// L4 routes to Sonnet 4.6 with 8K extended thinking. Falls back to GPT-4o on exception.
val anthropicKey = WimApp.anthropicApiKey()
val useSonnet = layer >= 4 && anthropicKey.isNotBlank()
var sonnetDowngraded = false
val cleanText = if (useSonnet) {
    try {
        reconstructWithSonnet(systemPrompt, rawText, anthropicKey)
    } catch (e: Exception) {
        Log.w("WiM", "Sonnet L4 failed (${e.message?.take(120)}), falling back to GPT-4o")
        sonnetDowngraded = true
        reconstructWithGpt4o(systemPrompt, rawText, apiKey, temperature)
    }
} else {
    reconstructWithGpt4o(systemPrompt, rawText, apiKey, temperature)
}
```

The system prompt for L2/L3 includes user vocabulary, corrections, persistent rejection history (`RejectionStore.recent()`), and style examples (`StyleExamples.recent()`) — concatenated into the prompt as numbered lists, sent EVERY request.

`reconstructWithSonnet` POSTs to https://api.anthropic.com/v1/messages directly from the device, with `x-api-key: $apiKey` header and `anthropic-version: 2023-06-01`. Model: claude-sonnet-4-6 (note: model ID literally "claude-sonnet-4-6" — check current ID). max_tokens 10000, temperature 1.0 (required for thinking), thinking budget 8000.

`reconstructWithGpt4o` POSTs to https://api.openai.com/v1/chat/completions, model gpt-4o, temperature varies by tone (0.1-0.4), max_tokens 1024.

`falconValidate` is now a stub returning true (retired 2026-05-10). The `riskFlags` computation still runs (computeRiskFlags checks length delta, critical token retention, fragment endings).

Persistent learning state machine: `StyleExamples.pendingPair` stages (raw, output) after each call. Next call decides whether the prior pair gets promoted to accepted (StyleExamples.record) or dropped (was rejected via regenerate).

--- app/src/main/java/com/wim/app/WimAccessibilityService.kt (full file, 767 lines) ---

Service `instance` + `activeTextField` + `isPasswordField` + `currentForegroundPackage` stored as STATIC companion properties.

`onServiceConnected()`:
- Sets `instance = this`
- If `onboarding_complete = false`: launches `BouncerActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP`
- Calls `showBubble()` (which calls `startForegroundService(BubbleService)`)
- Starts `AgentSocketServer` (DEBUG-only loopback port 9222)
- Subscribes `RecordingStateBus` to forward to agent event bridge

`onAccessibilityEvent()`:
- Returns if event.packageName == this package
- TYPE_VIEW_FOCUSED: if `source.isEditable`, stores as activeTextField + checks isPassword
- TYPE_WINDOW_STATE_CHANGED: caches `currentForegroundPackage = pkg`, calls `autoSwitchTone(pkg)`

`autoSwitchTone()`: respects 30s manual lockout (`manual_tone_set_ms`), maps work apps (Gmail, Outlook, Slack, Teams, LinkedIn, Docs, Asana, Trello, Jira) → "professional"; personal apps (WhatsApp, Telegram, Messenger, IG, Snap, Messages) → "casual"; "mail"/"email" substring → "professional".

`injectText()`:
- Tries stored `activeTextField` first; if `node.refresh()` returns false, searches all windows
- Falls back to clipboard + Toast "Tap a text field — your text is on the clipboard" if no field
- Handles Samsung hint-as-text quirk (Material 3 compose TextField returns hint via `.text` when empty)
- Smart spacing: prepends space if char-before-cursor isn't whitespace; appends trailing space
- Uses ACTION_SET_TEXT with `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`. If that fails, falls back to clipboard + ACTION_PASTE. If THAT fails, leaves text on clipboard.
- Stores `lastInjectStart`, `lastInjectEnd`, `lastInjectedExact`, `lastInjectedLeadingPad` for subsequent swap operations.

`swapWordInLastInjection(rangeStart, rangeEnd, replacement)`:
- Refuses if `lastInjectStart < 0` or range invalid
- Refuses if field state diverged from snapshot
- Shifts caller-supplied range by `lastInjectedLeadingPad` + `lastInjectStart` to absolute coords
- Performs ACTION_SET_TEXT with combined string

`findFocusedEditableAcrossWindows()`: iterates `windows` (returned by service), skips own package, tries `findFocus(FOCUS_INPUT)` first then recursive descent. Recursive `findEditableFocusedDescendant` recycles children on dead-end branches "to prevent OOM in the accessibility process."

`clickSendButton()`: per-app `findSendNodeForApp` — branches for Google Messages, Samsung Messages, Gmail, WhatsApp, Telegram, Signal. Falls back to generic Send/Send message/Send SMS contentDescription match. Tier 1: ACTION_CLICK on node or ancestor. Tier 2: `dispatchGesture` synthetic tap at node's screen bounds center. Tier 3 on no-node: dumps full accessibility tree to logcat with tag "WiM.tree" (THIS LOGS USER UI STATE INCLUDING TEXT FIELDS TO LOGCAT).

`dumpAccessibilityTree()` logs: className, contentDescription (40 chars), text (30 chars), viewIdResourceName, isClickable, screen bounds — for every node in the tree, up to depth 30. Triggers on Send button not found.

--- app/src/main/java/com/wim/app/Clarifier.kt (full file, 193 lines) ---

Pure data object. `evaluate()` takes finalText + per-word WordConfidence + vocabulary + onsetWeights + recentSwaps + optional candidateProvider + contextPkg + threshold. Returns up to 3 FlaggedWord (claim 11(c) cap).

`uncertaintyScore()`: 0..1 score combining ASR logprob (weight 0.55, saturation at -1.5), multipass disagreements (weight 0.30, /3), phonetic-neighbor presence in vocab (+0.10), word not in vocab (+0.05), hard-onset boost via onsetWeights (+0.10 * weight).

`buildCandidates()`: priority order — sentence-boundary predictions (claim 14), then nBest alternates, then phoneticNeighbors. Filters out original word + rejected words from recentSwaps.

`atSentenceBoundary()`: prev word's last char in {'.', '?', '!'} OR idx == 0.

The FlaggedWord rangeStart/rangeEnd are in CHARACTER OFFSETS into the injected text (GPT-reconstructed output, not the raw Whisper text — alignment fixed via ClarifierAlignment.kt per the 5/13 audit).

--- app/src/main/java/com/wim/app/BubbleService.kt — key excerpts ---

[BubbleService.kt is 2715 lines — partial extraction underway. Key excerpts:]

Companion: `instance: BubbleService?` static reference, `dockSlotRects`, `onBubbleVisibilityChanged` callback. CHANNEL_ID = "wim_bubble" (no-badge), ALERT_CHANNEL_ID = "wim_bubble_alert" (badge ON). NOTIFICATION_ID = 1, ALERT_NOTIFICATION_ID = 2.

State:
```kotlin
@Volatile private var lastWavFile: java.io.File? = null  // race-protected
private var currentReconJob: Job? = null  // for cancel-on-new-recording
@Volatile private var l4DowngradeNotified = false
private var isRecording = false  // setter emits to RecordingStateBus
private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Unhandled coroutine exception: ${e.message}", e)
        try { FirebaseCrashlytics.getInstance().recordException(e) } catch (_: Throwable) {}
    }
)
```

`onCreate()`: instantiates ~20 helper components (BubbleShakeDetector, BubbleHotword, BubbleStatusUI, BubbleStageArc, BubbleWordHighlight, BubbleMenu, BubbleLiveTranscript, BubbleOnboardingOverlay, BubbleSendDisclaimer, BubbleTwistController, BubbleClarifierUI, BubbleCommandMode, BubbleDock, BubbleAppearance, BubblePulseRings, BubbleTipRotator, BubbleRegenIcon, BubbleBluetoothSco, etc.). Each takes lambda accessors for `getBubbleView`, `getBubbleParams`, etc. Registers AudioPlaybackCallback for chirp monitoring (logs every playback). Calls `startForeground(NOTIFICATION_ID, buildNotification(), fgsType)` with SPECIAL_USE | MICROPHONE (or just SPECIAL_USE if no mic permission).

`onStartCommand()`: actions SHOW, HIDE, OPEN_MENU, TOGGLE_RECORDING, TEST_CLARIFIER, ONBOARDING. Returns START_STICKY.

`startListening()`:
- Cancels `currentReconJob` (prevents stale paste from prior recording)
- Stops hotword listener
- Tears down clarifier if active
- BLOCKS recording into password fields (returns early after Toast + visual flash)
- Sets `isRecording = true`, advances FSM to Listening
- Requests AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE for 600ms to suppress SpeechRecognizer beep
- Picks engine: L4 → WAV via AudioRecorder + whisper-1 on stop; L1 cloud → same; L1 local → whisper-tiny.en via WhisperLocalEngine (sherpa-onnx); LIVE preview override → Android SpeechRecognizer for partials; fallback → SpeechRecognizer

`processText()`:
- TWIST mode triggers (knob control commands) intercepted first
- COMMAND mode triggers next (text-transform commands)
- INSTANT mode: bypass everything, paste raw
- Calls `RateGapDetector.analyze` to compare wps to user baseline
- Applies user voice shortcuts (newline-separated trigger=replacement pairs from prefs)
- Strips Whisper block hallucinations via `DisfluencyFilter.stripBlockHallucinations`
- Pre-cleans disfluencies (skipped at L4 — the clinical prompt sees them)
- L1 path: direct inject via WimAccessibilityService
- L2+ path: `currentReconJob = scope.launch { ... }` with L4 WakeLock (120s timeout):
    ```kotlin
    val wakeLock = if (layer >= 4) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        try { pm.newWakeLock(PARTIAL_WAKE_LOCK, "WiM:L4Recon").apply {
            setReferenceCounted(false); acquire(120_000L)
        } } catch (_: Exception) { null }
    } else null
    ```
- `turboTaxJob`: child coroutine showing rotating stage messages every 1200ms for L4 (hoisted outside try so catch can cancel)
- `multiPassDeferred`: parallel async on Dispatchers.IO running 3-pass Whisper for word-confidence — runs alongside the main reconstruction call
- Calls `ReconstructClient.reconstruct(...)` with full profile + signals
- Applies `ReconstructionGuard.apply` (LLM leak + length explosion guards)
- Injects via `WimAccessibilityService.instance.injectText(outputText)`
- Caches `lastRegenCtx` for re-roll, clears+adds to `previousOutputs`
- L2+: calls `profile.learnFromReconstruction(preCleaned, outputText)`
- L1 auto-detect: if `profile.profileL1() == null` and not prompted, increments counter; at >= 5 reconstructions fires a background `Thread { ... }` POST to `https://us-central1-bakers-agent.cloudfunctions.net/wim-l1-guess` (NO AUTH, NO API KEY, raw `Thread` not coroutine, sends `text.take(3000)` of the reconstructed output as JSON)
- Records to `sessionHistory`
- Records to `PipelineAnalytics.recordPredictionsFromOutput` (per-package sentence-boundary tuples)
- If multiPass result available + clarifier allowed for foreground app: runs `Clarifier.evaluate` and enters clarifier cycle if any flags
- catch block: cancels turboTaxJob + multiPassDeferred, advances FSM to Failed(NETWORK), injects pre-cleaned text as fallback, records error session, Toast "Reconstruction failed — raw text used", `setBubbleIdle()`
- finally: `wakeLock?.release()`

`onDestroy()`:
```kotlin
instance = null
shakeDetector?.stop()
stopHotwordListener()
bubbleMenu?.dismiss()
hideLiveTranscript()
dafPlayer?.stop()
audioRecorder?.release()
hideBubble()
onDeviceRecognizer?.destroy()
whisperLocalEngine?.destroy()
bubblePersona?.unbind()
bubbleAffect?.unbind()
TranscriptBarController.unbind()
IdleTimer.cancelAll()
VoiceCommandRouter.unregister("clarifier")
ClarifierController.reset()
timerHandler.removeCallbacksAndMessages(null)
liveTranscript?.destroy()
audioPlaybackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
bluetoothSco?.stop()
scope.cancel()
```

Note: NO unregistering of broadcast receivers if any; NO release of acquired wake locks held outside the per-recon scope; AudioFocusRequest is requested but auto-abandoned via postDelayed — if Service dies in between, the focus stays held.

---

Now produce the audit. Number findings starting at 1. Be ruthless.
