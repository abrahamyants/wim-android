# WiM Android — Code Review
**Date:** 2026-05-10  
**Reviewer:** CodeRabbit agent (Claude Sonnet 4.6)  
**Scope:** BubbleService.kt, ReconstructClient.kt, MultiPassDecoder.kt, PredictionEngine.kt, Clarifier.kt, ClarifierController.kt, WimAccessibilityService.kt, AuthManager.kt, app/build.gradle.kts, AndroidManifest.xml

---

## CRITICAL

### CR-01 — Plaintext signing credentials in version-controlled build.gradle.kts
**File:** `app/build.gradle.kts:25-28`  
**Severity:** Critical  

The release signing config embeds the keystore password and key password as plaintext strings in the tracked build file:

```
storeFile = file("../wim-release.jks")
storePassword = "wimstore2026"
keyAlias = "wim"
keyPassword = "wimstore2026"
```

Any future public or semi-public repo exposure makes the release signing key trivially extractable. The keystore file is also referenced with a relative path, meaning a co-located `.jks` would be included in the repository if not gitignored.

**Fix:** Move credentials to `local.properties` (gitignored) and read them via `project.findProperty()` or environment variables. Confirm `wim-release.jks` is in `.gitignore`.

---

### CR-02 — WimTestReceiver exported with no signature permission and only a runtime DEBUG guard
**File:** `AndroidManifest.xml:113-118`, `WimTestReceiver.kt:50-53`  
**Severity:** Critical  

```xml
<receiver
    android:name=".WimTestReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.wim.app.TEST" />
    </intent-filter>
</receiver>
```

The receiver is exported unconditionally in the manifest. The only protection is a runtime `if (!BuildConfig.DEBUG) return` check inside `onReceive`. This means:

1. The receiver is present and registered in the release APK (R8 strips dead code, not manifest components).
2. Any app on the device can broadcast `com.wim.app.TEST` to it.
3. If the `BuildConfig.DEBUG` guard ever fails (misconfigured build variant, obfuscation edge case), or if the receiver is invoked before `onReceive` returns, the capabilities exposed — arbitrary preference writes via `set_pref`, full pipeline injection via `inject_raw`, session export — are available to any installed app.

**Fix:** Gate the manifest declaration itself behind a debug-only source set, or add `android:permission="android.permission.INJECT_EVENTS"` (or a custom signature-level permission) so only the shell / your own signed app can broadcast to it. The correct approach is to move the `<receiver>` block into `app/src/debug/AndroidManifest.xml`.

---

## HIGH

### CR-03 — Bluetooth SCO BroadcastReceiver registered but never unregistered
**File:** `BubbleService.kt:456-471`  
**Severity:** High  

`setupBluetoothSco()` registers an anonymous `BroadcastReceiver` for `ACTION_SCO_AUDIO_STATE_UPDATED` but the reference is immediately discarded (local variable, not stored on `this`). `onDestroy()` has no corresponding `unregisterReceiver()` call for it.

```kotlin
val scoReceiver = object : android.content.BroadcastReceiver() { ... }
registerReceiver(scoReceiver, ...)
// scoReceiver goes out of scope — never unregistered
```

This leaks the receiver registration for the lifetime of the process, produces a `RuntimeException: Receiver not registered` if Android's VM GC finalizes the anonymous class before the service dies, and emits the logcat warning `BroadcastReceiver components are not allowed to register to receive intents` on some OEM builds.

**Fix:** Store `scoReceiver` as a member field and call `unregisterReceiver(scoReceiver)` in `onDestroy()`.

---

### CR-04 — ClarifierController is a global singleton with shared mutable state, no thread-safety
**File:** `ClarifierController.kt`  
**Severity:** High  

`ClarifierController` is an `object` (singleton) with mutable state (`flagged`, `cycleIndex`, `lastInjectionText`, `onLabelUpdateRef`, `onExitRef`, `recentSwaps`). All callers are on the main thread, which avoids data races in normal operation, but:

1. `approve()` calls `WimAccessibilityService.instance?.swapWordInLastInjection(...)` which internally calls `AccessibilityNodeInfo.performAction` — a binder call that can dispatch to the accessibility framework on a different thread. The `lastInjectionText` update in `approve()` (line 94) races with the binder completion.
2. `cancelForNewRecording()` nulls `onLabelUpdateRef` and `onExitRef` (lines 155-156) without any lock, while `emitLabelUpdate()` (line 188-189) reads them. If `startListening()` fires on the main thread while an in-flight `armAutoDismiss` callback posts via `IdleTimer`, `emitLabelUpdate` can call a null-reference.
3. `exit()` clears state before invoking `cb` (line 210-216). If `cb` re-enters `enter()`, the cleared state is partially restored, leaving `onExitRef` stale.

**Fix:** Since the main thread is the canonical execution context here, add `@MainThread` annotations and a `check(Looper.myLooper() == Looper.getMainLooper())` guard at the top of each public method to surface accidental cross-thread calls early. Make `exit()` capture all needed state into locals before clearing, which is partially done but incomplete.

---

### CR-05 — ReconstructClient creates a new OkHttpClient per `callBackendCommandTransform` call
**File:** `BubbleService.kt:3381-3384`  
**Severity:** High  

The main reconstruction path correctly uses a singleton `ReconstructClient.http`. However, `callBackendCommandTransform` in `BubbleService` constructs a fresh `OkHttpClient` on every command invocation:

```kotlin
val http = okhttp3.OkHttpClient.Builder()
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .build()
```

Each `OkHttpClient` creates its own thread pool, connection pool, and cache. On repeated command-mode invocations (which are not rare — the user may issue several per session), this accumulates unreleased thread pools and held connections. OkHttp's documentation explicitly states clients should be shared.

**Fix:** Either use the singleton `ReconstructClient.http` (adjusting the timeout per-call using `newCall` builder overrides) or promote a second singleton client for command-mode calls.

---

### CR-06 — Falcon validation returns `true` (pass) on every exception, masking network errors
**File:** `ReconstructClient.kt:694-697`  
**Severity:** High  

```kotlin
} catch (e: Exception) {
    Log.e("WiM", "Falcon validation error: ${e.message}")
    true
}
```

A transient network failure, a 5xx from the Anthropic/OpenAI endpoint, or a malformed JSON response all result in Falcon silently approving the reconstruction. This means a bad reconstruction that would have been caught by Falcon goes through to injection whenever the network is flaky. The comment says "Returns true on any error so a flaky validator never blocks reconstruction" — that is the intent — but it also means systematic validation failures (wrong API key, rate limiting) are invisible to the user and produce no circuit-breaker behavior.

**Fix:** Log the full exception type, not just the message. Consider incrementing a failure counter and, after N consecutive failures, surfacing a toast so the user knows Falcon is degraded. At minimum, distinguish `IOException` (transient, fail-open is acceptable) from `RuntimeException`/`JSONException` (parsing failures that indicate a logic bug and should not fail-open silently).

---

### CR-07 — `processText` can be called while `isRecording == true` via `processTextForTest`
**File:** `BubbleService.kt:311-318`  
**Severity:** High  

```kotlin
fun processTextForTest(rawText: String) {
    if (!BuildConfig.DEBUG) return
    lastWhisperLowConf = emptyList()
    recordingStartTime = System.currentTimeMillis()
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        processText(rawText)
    }
}
```

`processTextForTest` does not check `isRecording`. If the test runner fires this while a real recording session is in flight (which is easy to do accidentally — the ADB test receiver is on a background binder thread), `processText` runs concurrently with the recording pipeline. Both paths write to `lastRawText`, `finalizedSegments`, `processed`, and call `setBubbleProcessing()` / `setBubbleIdle()` in unsequenced order. `processed` is not atomic; the guard at the top of `stopAndProcess()` (`if (processed) return`) can fail to protect the second invocation.

**Fix:** Add `if (isRecording) return` at the top of `processTextForTest`. If testing mid-recording scenarios is needed, stop recording first.

---

### CR-08 — `AudioPlaybackCallback` registered in `onCreate` but never unregistered
**File:** `BubbleService.kt:397-406`  
**Severity:** High  

```kotlin
audioManager?.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
    override fun onPlaybackConfigChanged(...) { ... }
}, null)
```

The callback is registered with a anonymous class on `onCreate` but `onDestroy` does not call `audioManager?.unregisterAudioPlaybackCallback(...)`. This leaks the anonymous class (which holds an implicit reference to `BubbleService`) through the `AudioManager` system service. The callback keeps firing after service destruction if the system delays deallocation.

**Fix:** Store the callback in a field and unregister it in `onDestroy`.

---

## MEDIUM

### CR-09 — `WimAccessibilityService.activeTextField` is a nullable static reference that outlives the host node
**File:** `WimAccessibilityService.kt:18-20`  
**Severity:** Medium  

```kotlin
var activeTextField: AccessibilityNodeInfo? = null
    private set
```

`AccessibilityNodeInfo` objects are pooled and recycled by the framework. Holding a static reference past the immediate event callback is only safe if the holder recycles it when done. The code defensively calls `node.refresh()` before use, which is correct, but:

1. If the node is recycled by the framework before `refresh()` is called, the call throws `IllegalStateException`.
2. `activeTextField` is set in `onAccessibilityEvent` from `TYPE_VIEW_FOCUSED` events (line 95) but is never cleared when the field loses focus (there is no `TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED` handler). A field that is scrolled off screen stays as the "active" field indefinitely.
3. `onInterrupt()` clears it (line 154) but `onInterrupt` fires only when the service is interrupted, not on normal focus changes.

**Fix:** Cache a snapshot of needed state (package name, text, cursor position) from the node at event time rather than storing the live node handle. If the live handle must be stored, add an explicit `TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED` handler that nulls it.

---

### CR-10 — `stopAndProcess()` 400ms timer race for on-device fallback path
**File:** `BubbleService.kt:1796-1805`  
**Severity:** Medium  

```kotlin
timerHandler.postDelayed({
    val onDeviceText = lastRawText.trim()
    if (onDeviceText.isNotBlank()) {
        processText(onDeviceText)
    } else {
        setBubbleIdle()
    }
}, 400)
```

The 400ms delay is intended to wait for `onFinalResult` from the on-device recognizer. But the `handleError` callback in `startListening()` (line 1625) calls `isRecording = false` and `setBubbleIdle()` directly, without canceling this pending 400ms runnable. If an ASR error fires before the 400ms expires, `setBubbleIdle()` runs, then 400ms later `processText(lastRawText)` runs on a stale result and tries to inject into whatever the current foreground field is at that moment.

**Fix:** Store the 400ms `Runnable` in a member variable and cancel it from `handleError` and from `onDestroy`.

---

### CR-11 — `ClarifierController.approve()` calls `next()` even when `swapWordInLastInjection` fails
**File:** `ClarifierController.kt:83-97`  
**Severity:** Medium  

```kotlin
fun approve() {
    val current = flagged.getOrNull(cycleIndex) ?: return
    val replacement = current.candidates.firstOrNull() ?: return
    val ok = WimAccessibilityService.instance?.swapWordInLastInjection(...) ?: false
    if (ok) {
        lastInjectionText = applyEdit(...)
        recordSwap(current.originalWord, replacement)
    }
    next()   // advances regardless of whether the swap succeeded
}
```

When `ok == false` (field diverged, accessibility service null, `performAction` rejected), the swap silently fails but the cycle advances. The user sees the clarifier move to the next word with no indication that the approved swap didn't land. The `recentSwaps` feedback loop also does not record the approved-but-failed swap, so `buildCandidates` will re-surface the same candidate on the next cycle.

**Fix:** When `ok == false`, either stay on the current word and surface a visual error state, or record the attempt as a rejection so the candidate is downranked. At minimum, log a warning that is visible to the user.

---

### CR-12 — `MultiPassDecoder` uses `<END>` as a sentinel that collides with real transcript content
**File:** `MultiPassDecoder.kt:85`  
**Severity:** Medium  

```kotlin
val wordsAtPos = tokenLists.map { it.getOrNull(pos) ?: "<END>" }
```

If a speaker utters the literal phrase "end" or any text that ASR transcribes as `<END>` (unlikely but possible with some models that emit XML-like tokens), it would be confused with the end-of-transcript sentinel. More practically, `<END>` as a word will appear in `variantsByPosition` and can be emitted as an `nBestAlternate` candidate in `wordConfidencesFromPrimary` (line 147 explicitly filters it, which is correct but only works if the filter is always applied — currently it is, but the coupling is fragile).

**Fix:** Use a private sentinel object or a dedicated data class rather than a magic string. The filter on line 147 (`it != "<END>"`) is fine as a guard but the two spots should be co-located or the sentinel should be a constant.

---

### CR-13 — `PredictionEngine.recordCompletion` prunes history by insertion order, not recency
**File:** `PredictionEngine.kt:115-118`  
**Severity:** Medium  

```kotlin
val pruned = if (history.size > MAX_HISTORY_PER_PKG) {
    history.entries.take(MAX_HISTORY_PER_PKG).associate { it.toPair() }
} else history
```

`history` is built from `JSONObject.keys()` (line 130), which returns keys in insertion order (or alphabetical on some Android JSON implementations). When the history exceeds 64 trigrams, `take(64)` keeps the oldest entries and discards the newest, which is the opposite of the desired move-to-front eviction policy. The per-trigram list inside each entry does use move-to-front correctly, but the cross-trigram cap does not.

**Fix:** Track insertion order explicitly (e.g., store entries as a JSONArray of `{key, values}` pairs or use a `LinkedHashMap` backed by a sorted-by-recency structure), or prune by dropping the oldest-accessed trigrams rather than the last-inserted ones.

---

### CR-14 — `BubbleService.instance` companion reference accessed after service destruction
**File:** `BubbleService.kt:209`, multiple call sites  
**Severity:** Medium  

`BubbleService.instance` is a nullable companion object field set in `onCreate` and nulled in `onDestroy`. Multiple call sites outside the service (e.g., `WimTestReceiver.kt`, `ClarifierController.kt:157`, `MainActivity`) read this field without null-safety on the result:

```kotlin
BubbleService.instance?.hideWordHighlight()  // ClarifierController.kt:157
```

While Kotlin's `?.` protects against NPE, the TOCTOU window between the null check and method dispatch means the service can be destroyed between the check and the call, producing a call on a partially-destroyed service object (e.g., `windowManager` already nulled, `scope` already cancelled). The `?.` makes the call appear safe but the underlying state is not.

**Fix:** Move `ClarifierController` callbacks that need the service into the service itself, or gate the `?.` calls with a `BubbleService.instance?.takeIf { it.isRunning() }` pattern. Add an `isRunning` flag that is set to false at the top of `onDestroy()` before anything else is torn down.

---

### CR-15 — `BubbleService.languageCode` is hardcoded to `"en"` at L2/L3/L4
**File:** `BubbleService.kt:3697`  
**Severity:** Medium  

```kotlin
val primaryLanguage = "en"
```

`WimApp.appLanguage()` is available and used correctly in the test path (`processAudioForTest`, line 345). The production `processText` path ignores it and hardcodes `"en"`. The L1-transfer pack (`L1PackHelper.promptInjection`) is injected by `ReconstructClient` and reads `profile_l1` from prefs, so that works. But `languageCode` passed to `ReconstructClient.reconstruct` is always `"en"`, meaning `LangPackHelper.normalizeLangCode`, the language-specific filler lists, natural-repeat protections, and the Falcon validator's language block all operate with the wrong language when the user has a non-English app language.

**Fix:** Replace `val primaryLanguage = "en"` with `val primaryLanguage = WimApp.appLanguage()`.

---

### CR-16 — `processText` calls `setBubbleProcessing()` before entering the coroutine, but errors in the coroutine leave the bubble in PROC state
**File:** `BubbleService.kt:3449`, `3778-3783`  
**Severity:** Medium  

The Whisper path's `catch` block (around line 3778) shows:

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Whisper failed: ${e.message}")
    BubbleStateBus.advance(BubbleState.Failed(FailReason.NETWORK))
    showPipelineStage("Error", "Transcription failed")
    timerHandler.postDelayed({ setBubbleIdle() }, 2000)
}
```

The 2-second `setBubbleIdle` delay means the bubble sits in the PROC state for 2 seconds after a failure. That is by design for user feedback. However, if the service is destroyed during those 2 seconds (user force-stops, OOM kill), the `timerHandler.postDelayed` runnable fires against a null `bubbleView` / cancelled `scope`. The `setBubbleIdle` guard for `bubbleView == null` is implicit (it returns early from each `bubbleView?.find` call), so it won't crash, but the `BubbleStateBus.reset()` call inside `setBubbleIdle` runs against a destroyed FSM.

The same pattern appears in the reconstruction error handler around line 3820. The `timerHandler` is not cancelled in `onDestroy()`.

**Fix:** Cancel `timerHandler` callbacks in `onDestroy()` by calling `timerHandler.removeCallbacksAndMessages(null)`.

---

## LOW

### CR-17 — `build.gradle.kts` missing ABI filter — ships all four ABIs for sherpa-onnx
**File:** `app/build.gradle.kts`  
**Severity:** Low  

The `sherpa-onnx-1.12.40.aar` ships `.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`. Without an `abiFilters` block in `defaultConfig`, the release APK includes all four, adding ~160MB to the binary. Nearly all production Android devices shipping since 2018 are `arm64-v8a`.

**Fix:**
```kotlin
defaultConfig {
    ...
    ndk { abiFilters += listOf("arm64-v8a") }
}
```
Keep `x86_64` if emulator testing on x86 Mac hosts is needed (add it to the debug build type only).

---

### CR-18 — `AuthManager.getIdToken` silently returns null on token refresh failure; callers fall back to direct API path
**File:** `AuthManager.kt:26-39`, `ReconstructClient.kt:100-106`  
**Severity:** Low  

When both the initial `getIdToken(false)` and the forced refresh `getIdToken(true)` fail, `getIdToken` returns `null`. `ReconstructClient.reconstruct` then falls through to `reconstructDirect`, which requires a local API key. If there is no local key (the normal signed-in user path), `reconstructDirect` throws `IllegalStateException("No API key configured")`. This exception propagates to the caller in `BubbleService.processText` and causes the error-state path ("Error", "Reconstruction failed") rather than a more specific "session expired" message.

**Fix:** Distinguish token-refresh failures (session expired, network) from absent API key. Surface a "Session expired — please sign in again" toast when `getIdToken` returns null in a context where no fallback API key exists.

---

### CR-19 — `OfflineRecognizer.finalize()` used for JNI cleanup — deprecated GC finalizer pattern
**File:** `.r8-displaced-sherpa-wrappers/OfflineRecognizer.kt:193-199`  
**Severity:** Low  

```kotlin
protected fun finalize() {
    if (ptr != 0L) {
        delete(ptr)
        ptr = 0
    }
}
```

`Object.finalize()` is deprecated since Java 9 and unreliable (no guaranteed call timing, no guaranteed call at all on Android's ART with aggressive GC). The `release()` method wraps `finalize()`, which is the intended explicit cleanup path. This pattern is copied from the upstream sherpa-onnx sample code and is acceptable as-is since it is in the displaced wrappers directory and `release()` is the primary cleanup path. Flagging it for awareness.

**Fix:** Long-term, replace with `java.lang.ref.Cleaner` or ensure all call sites call `release()` explicitly and guard against double-free with the `ptr != 0L` check that is already present.

---

### CR-20 — `BubbleService.scope` uses `Dispatchers.Main` for all coroutines including network calls
**File:** `BubbleService.kt:168`  
**Severity:** Low  

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
```

`ReconstructClient.reconstruct` and `WhisperClient.transcribe` are correctly wrapped in `withContext(Dispatchers.IO)` inside their implementations, so the network I/O itself is off the main thread. However, any `scope.launch { }` block in `BubbleService` defaults to Main, meaning a slow synchronous operation accidentally placed directly in the block body (without a `withContext`) would jank the UI. The `turboTaxJob` coroutine (line 3669) is launched on Main and runs `kotlinx.coroutines.delay` in a loop — that is fine. But the structural intent is unclear to future maintainers.

**Fix:** Consider `Dispatchers.Main.immediate` for UI-driving coroutines and use `Dispatchers.IO` for any coroutine that may do I/O directly. This is a readability and future-proofing concern, not a current bug.

---

### CR-21 — `WimAccessibilityService.findEditableFocusedDescendant` has unbounded recursion depth
**File:** `WimAccessibilityService.kt:448-456`  
**Severity:** Low  

The recursive descent has no depth limit. Some apps (especially WebView-based ones) produce very deep accessibility trees (60+ levels). At pathological depth this could produce a `StackOverflowError`. The companion `dumpAccessibilityTree` method does have a `depth > 30` guard (line 571) but `findEditableFocusedDescendant` does not.

**Fix:** Add a depth parameter with a cap (e.g., 40 levels) mirroring the pattern in `dumpAccessibilityTree`.

---

### CR-22 — `ReconstructClient.reconstructDirect` injects L1PackHelper twice for L2/L3
**File:** `ReconstructClient.kt:455, 567`  
**Severity:** Low  

`L1PackHelper.promptInjection` is called at line 455 (inside the `if (layer in 2..3)` block for L2/L3 artifacts guidance) and again at line 567 (in another `if (layer in 2..3)` block near the end of the `buildString`). Both conditions are `layer in 2..3`, so the L1 injection appears twice in the system prompt for L2/L3 calls. At L4 it is absent (correct per the gating comment), but the duplication at L2/L3 is unintentional and wastes tokens.

**Fix:** Remove the second call at line 567. The first injection at line 455 is already inside the L2/L3 artifact guidance block which is the correct placement.

---

### CR-23 — `BubbleService` has no `IdleTimer` cancel on `onDestroy` for the `clarifierTimeout` Runnable
**File:** `BubbleService.kt:155-167, 4101-4125`  
**Severity:** Low  

The legacy `clarifierTimeout` Runnable (used by the old `showClarifier`/`dismissClarifier` flow, distinct from `ClarifierController`) is posted via `timerHandler.postDelayed(clarifierTimeout, 10000)` in `showClarifier`. `onDestroy` calls `IdleTimer.cancelAll()` (which handles `ClarifierController`'s timer) but does not call `timerHandler.removeCallbacks(clarifierTimeout)`. If the service is destroyed while the old clarifier is active, the Runnable fires after `onDestroy`, calling `dismissClarifier` on a partially-destroyed service.

**Fix:** Add `timerHandler.removeCallbacks(clarifierTimeout)` to `onDestroy`.

---

## BUILD / CONFIGURATION

### CR-24 — `WimTestReceiver` kept by proguard-rules.pro in release builds
**File:** `app/proguard-rules.pro:56`  
**Severity:** High (pairs with CR-02)

```
-keep class com.wim.app.WimTestReceiver { *; }
```

This explicitly prevents R8 from stripping `WimTestReceiver` from the release APK. Combined with the manifest exporting it unconditionally (CR-02), the full test attack surface is available in production. Even with the `BuildConfig.DEBUG` runtime guard, the class is present, the manifest entry is present, and any security researcher or malicious app can enumerate it.

**Fix:** Remove this line from `proguard-rules.pro`. The manifest fix in CR-02 (moving `<receiver>` to debug source set) will make the class unreachable from production even without the proguard keep. If the proguard keep is needed for some other reason, add `if (BuildConfig.DEBUG)` at the class level (a static final field check that R8 can reason about).

---

### CR-25 — `firebase-bom:33.7.0` is not the latest; `firebase-perf-ktx` adds startup latency
**File:** `app/build.gradle.kts:75`  
**Severity:** Low  

Firebase BOM 33.7.0 was released in late 2024. As of May 2026 it is several versions behind. `firebase-perf-ktx` (Performance Monitoring) instruments HTTP calls, app startup, and screen rendering — this adds measurable startup latency (~50-100ms) and background overhead that affects battery on a persistent foreground service. If Performance Monitoring data is not being actively consumed, the dependency should be removed.

**Fix:** Bump Firebase BOM to the current version. Evaluate whether `firebase-perf-ktx` is producing actionable data; if not, remove it.
