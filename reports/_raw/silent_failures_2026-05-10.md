# Silent Failure Audit — wim-android
**Date:** 2026-05-10  
**Files examined:** BubbleService.kt, ReconstructClient.kt, WhisperClient.kt, MultiPassDecoder.kt, AuthManager.kt

---

## HISTORICAL BUG VERIFICATION

**CONFIRMED FIXED.** The `processText()` L2+ catch block at line 3917 now calls `sessionHistory?.record()` with `riskFlags = listOf("reconstruction_failed")` and emits `SessionUpdateBus`. The L1 success path also calls `sessionHistory?.record()` at line 3590. The 4-month data-loss bug is closed in all catch branches examined.

---

## FINDINGS — RANKED BY SEVERITY

---

### CRITICAL-1
**Location:** `BubbleService.kt:3917–3953` — `processText()` L2+ outer catch block  
**Issue:** The fallback inject of `preCleaned` text is silent to the user. `sessionHistory.record()` is now called (fix confirmed), but the user receives no notification that GPT failed and they received raw de-disfluency-filtered text instead of a real reconstruction. The bubble just returns to IDLE. A user at L4 in a medical dictation context has no idea their output bypassed the clinical model and received the same output as L1.  
**Hidden errors this catch can swallow:** network timeouts, SSL errors, Firebase token expiry, JSON parse errors on the backend response, OOM during JSON construction, InterruptedException from coroutine cancellation, any unchecked exception inside `profile.learnFromReconstruction()`, `recordPredictionsFromOutput()`, `Clarifier.evaluate()`, or `MultiPassDecoder.run()` — all of these land in the same single catch.  
**User impact:** User sees reconstructed text that is NOT what L2/L3/L4 would have produced, with no indication that the AI pipeline failed. For a stutter user in a high-stakes context (job interview, medical appointment), this is a correctness failure that is invisible.  
**Recommendation:** Add a Toast in the catch block that tells the user GPT reconstruction failed and they received cleaned raw text. The log line exists; the user-facing signal does not.  
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Reconstruction error: ${e.message}", e)
    ConsoleLog.log("Reconstruction failed: ${e.message?.take(60)}", "error")
    // ADD THIS:
    Toast.makeText(this@BubbleService,
        "Reconstruction failed — pasted cleaned speech instead",
        Toast.LENGTH_SHORT).show()
    WimAccessibilityService.instance?.injectText(preCleaned)
    ...
```

---

### CRITICAL-2
**Location:** `BubbleService.kt:3438–3440` — `callOpenAiTransform()` catch block  
**Issue:** Completely empty catch — swallows the exception, logs nothing, returns null. The outer `processCommand()` catch (line 3358) does log, but by the time null propagates back, the original exception type and stack trace are gone. Any network error, SSL failure, JSON parse error, or unexpected API shape change is silently converted to null with zero diagnostic information.  
**Hidden errors:** Every exception type — OkHttp IOExceptions, JSONException, SSLHandshakeException, SocketTimeoutException, OutOfMemoryError (technically Error not Exception, but OOM can surface here on large response bodies).  
**User impact:** Command Mode transform fails; user sees "Failed — Try again" with no actionable information. Developer has no log to diagnose whether this is a network issue, key expiry, or API change.  
**Recommendation:**  
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "callOpenAiTransform failed: ${e.message}", e)
    null
}
```

---

### HIGH-1
**Location:** `BubbleService.kt:1778–1783` — Whisper transcription catch in `stopAndProcess()`  
**Issue:** When Whisper API fails, `sessionHistory?.record()` is NOT called. The user attempted a dictation; audio was recorded and sent to the cloud; the session is simply dropped from history. This is the same pattern as the historical 4-month silent data-loss bug, but at the ASR layer rather than the reconstruction layer.  
**Hidden errors:** Network timeout, Whisper API 4xx/5xx, `IllegalStateException("No API key for Whisper")`, JSON parse on malformed response.  
**User impact:** Session count is wrong. Any stats or "recent sessions" view will be missing entries for failed Whisper calls. The user sees "Error — Transcription failed" on the bubble but the event is not recorded.  
**Recommendation:** Record a failed session in the catch block, mirroring the reconstruction catch:
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Whisper failed: ${e.message}")
    val totalMs = System.currentTimeMillis() - recordingStartTime
    sessionHistory?.record(
        rawText = "",
        cleanText = "",
        tone = WimApp.tone(),
        layer = WimApp.layer(),
        situation = WimApp.situation(),
        asrMs = System.currentTimeMillis() - pipelineStart,
        reconMs = 0,
        totalMs = totalMs,
        falconApproved = false,
        riskFlags = listOf("whisper_failed")
    )
    SessionUpdateBus.emit()
    BubbleStateBus.advance(BubbleState.Failed(FailReason.NETWORK))
    showPipelineStage("Error", "Transcription failed")
    timerHandler.postDelayed({ setBubbleIdle() }, 2000)
}
```

---

### HIGH-2
**Location:** `BubbleService.kt:3382–3406` — `callBackendCommandTransform()` — no `connectTimeout`  
**Issue:** The OkHttpClient built inside `callBackendCommandTransform()` sets only `readTimeout(30s)` and no `connectTimeout`. The OkHttp default connect timeout is 10 seconds, but this is implicit reliance on a library default that can silently change. More critically, there is no `writeTimeout`, so uploading the `source` text body has no enforced limit. If the user is on a slow connection and `source` is a large block of selected text, the write can hang indefinitely (OkHttp default write timeout is 10s, but not set here explicitly).  
**Same issue appears at:** `BubbleService.kt:3413` — `callOpenAiTransform()` also has only `readTimeout`.  
**Recommendation:** Both ad-hoc OkHttpClient instances should either reuse `ReconstructClient.http` or set all three timeouts explicitly:
```kotlin
val http = okhttp3.OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

---

### HIGH-3
**Location:** `BubbleService.kt:648–652` — `showOnboardingOverlay()` catch block  
```kotlin
try {
    windowManager?.addView(overlay, params)
    onboardingOverlay = overlay
} catch (_: Exception) {}
```
**Issue:** The exception is silently swallowed. If `addView` throws (overlay permission revoked mid-session, window already added, low-memory trim), `onboardingOverlay` is null but the code continues as if the overlay is live. The subsequent `removeOnboardingOverlay()` call (line 656) has the same pattern. No log, no user feedback.  
**Hidden errors:** `WindowManager.BadTokenException`, `IllegalStateException` (view already added), security exceptions if overlay permission was revoked.  
**Recommendation:** At minimum, log the failure:
```kotlin
} catch (e: Exception) {
    Log.w(TAG, "showOnboardingOverlay: addView failed: ${e.message}")
}
```

---

### HIGH-4
**Location:** `BubbleService.kt:1329–1335` — `performRegen()` catch block  
**Issue:** When regen fails, the catch logs and calls `showRegenIcon()` (making the regen icon available again), but does NOT toast the user. The pipeline stage label does get updated via `ConsoleLog.log("Regen failed: ...", "error")` but `ConsoleLog` is the developer console overlay, not a user-visible Toast. A user who taps regen and sees nothing happen has no idea whether the request failed or is still in flight.  
**Recommendation:** Add a Toast in the catch:
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Regen error: ${e.message}", e)
    ConsoleLog.log("Regen failed: ${e.message?.take(60)}", "error")
    Toast.makeText(this@BubbleService, "Regen failed — try again", Toast.LENGTH_SHORT).show()
    showRegenIcon()
}
```

---

### HIGH-5
**Location:** `BubbleService.kt:3670–3683` — nested `turboTaxJob` inside the outer `scope.launch`  
```kotlin
val turboTaxJob = if (layer >= 4) {
    scope.launch {
        val stages = listOf(...)
        for ((title, sub) in stages) {
            showPipelineStage(title, sub)
            kotlinx.coroutines.delay(1200)
        }
    }
} else null
```
**Issue:** This nested `scope.launch` has no try-catch. Any exception inside it (e.g., if `showPipelineStage` throws, or if the coroutine is cancelled in an unexpected state) will propagate to the `SupervisorJob` at the `scope` level and be silently discarded. Because `SupervisorJob` does not cancel the parent on child failure, this is not a crash risk — but it means the L4 animation stages can die silently and the user sees a frozen pipeline label.  
**More importantly:** `turboTaxJob?.cancel()` at line 3732 is inside the try block but NOT inside the catch block. If `ReconstructClient.reconstruct()` throws, `turboTaxJob` keeps running — the animation loop continues firing `showPipelineStage()` calls after the error handler has already called `setBubbleIdle()`. This produces a race condition where the idle state can be immediately overwritten by a stale animation frame.  
**Recommendation:** Cancel `turboTaxJob` in the catch block and add error handling:
```kotlin
} catch (e: Exception) {
    turboTaxJob?.cancel()  // ADD THIS
    Log.e(TAG, "Reconstruction error: ${e.message}", e)
    ...
}
```

---

### HIGH-6
**Location:** `BubbleService.kt` — all `scope.launch` blocks lack `CoroutineExceptionHandler`  
Lines with bare `scope.launch { ... }`: 329, 1267, 1296, 1742, 3320, 3658, 3670.  
**Issue:** The scope uses `SupervisorJob + Dispatchers.Main`. Under `SupervisorJob`, an unhandled exception in a child coroutine is swallowed — it does not crash the app and does not propagate to the parent. But it also emits no user feedback and no error log. Any exception thrown outside a try-catch in these coroutines is silently dropped.  
**Most dangerous instance:** Line 1267 (`regenHideJob`) and line 1296 (`performRegen`) — if `delay()` or `hideRegenIcon()` throws, `isRegenInFlight` is never reset to false in the finally block (line 1334 handles it for the regen path, but `regenHideJob` at 1267 has no finally at all).  
**Recommendation:** Either add a `CoroutineExceptionHandler` to the scope definition:
```kotlin
private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main +
    CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Unhandled coroutine exception: ${e.message}", e)
    }
)
```
Or add try-catch inside every launch block that does non-trivial work.

---

### HIGH-7
**Location:** `AuthManager.kt:28–39` — `getIdToken()` catch blocks  
```kotlin
return try {
    user.getIdToken(false).await().token
} catch (e: Exception) {
    Log.e(TAG, "Failed to get ID token: ${e.message}")
    try {
        user.getIdToken(true).await().token
    } catch (e2: Exception) {
        Log.e(TAG, "Token refresh also failed: ${e2.message}")
        null
    }
}
```
**Issue:** When both token fetch attempts fail, the method returns null. `ReconstructClient.reconstruct()` at line 101 treats null as "not signed in" and falls back to the direct OpenAI path (which requires a local API key). If the user is signed in but the token is temporarily unavailable (network blip, token rotation), the production backend is bypassed — tier enforcement, rate limiting, and the server-side API key are all skipped. The user unknowingly routes through the dev/local path.  
**Hidden failure:** Token failure looks identical to "not signed in." There is no way to distinguish "Firebase was unreachable for 2 seconds" from "user is genuinely signed out."  
**Recommendation:** Return a distinct sentinel or throw so callers can differentiate "genuinely no token" from "token fetch failed." At minimum, emit a ConsoleLog.log warning when falling back to direct path:
```kotlin
val idToken = AuthManager.getIdToken()
val result = if (idToken != null) {
    reconstructViaBackend(...)
} else {
    // ADD: distinguish token failure from signed-out
    Log.w(TAG, "No idToken — using direct OpenAI path (dev mode or token fetch failed)")
    reconstructDirect(...)
}
```

---

### MEDIUM-1
**Location:** `ReconstructClient.kt:748–779` — `falconValidateGpt()` — not a suspend function, calls `http.newCall(request).execute()` synchronously  
**Issue:** `falconValidateGpt` and `falconValidateClaude` are regular (non-suspend) functions called from `falconValidate` which is a suspend function running inside `withContext(Dispatchers.IO)`. The blocking `execute()` call is correct here. However, neither function has a try-catch of its own — they rely entirely on the `falconValidate` outer try-catch at line 694. If the outer try-catch is removed or refactored in a future change, both functions become uncaught-throw paths.  
**Recommendation:** Add defensive try-catch inside each Falcon function so they are self-contained:
```kotlin
private fun falconValidateGpt(...): Boolean {
    return try {
        ...
        val response = http.newCall(request).execute()
        ...
    } catch (e: Exception) {
        Log.w("WiM", "falconValidateGpt exception: ${e.message}")
        true // fail-safe: don't block reconstruction
    }
}
```

---

### MEDIUM-2
**Location:** `ReconstructClient.kt:252` — `optString("clean", rawText)` fallback  
```kotlin
val cleanText = json.optString("clean", rawText)
```
**Issue:** If the backend returns a 200 with a JSON body that is missing the `"clean"` key (e.g., a backend schema change, partial response, or a response that has `"text"` instead of `"clean"`), `optString` silently returns `rawText` as the fallback. The user receives their raw input as if reconstruction succeeded. There is no log warning that the expected key was absent.  
**Recommendation:**
```kotlin
val cleanText = json.optString("clean", "").takeIf { it.isNotBlank() }
    ?: run {
        Log.w("WiM", "Backend response missing 'clean' key — using rawText. Body: ${responseBody.take(200)}")
        rawText
    }
```

---

### MEDIUM-3
**Location:** `BubbleService.kt:556` — vibrator catch in shake detector swallows silently  
```kotlin
try {
    vibrator?.vibrate(...)
} catch (_: Exception) {
}
```
**Issue:** This is one of approximately 15 identical vibrator/WindowManager catch-all patterns throughout the file. For vibrator calls, swallowing is reasonable (vibrator failure is non-critical). But the same pattern is used for `windowManager?.addView()` at line 1456:
```kotlin
try { windowManager?.addView(container, lp) } catch (_: Exception) {}
```
This silently fails to show the send disclaimer dialog. If the user taps the bubble to send and the disclaimer never appears (because `addView` threw), the bubble returns to idle silently. The user never sees the send confirmation and doesn't know why nothing happened.  
**Recommendation:** WindowManager `addView/removeView/updateViewLayout` failures for user-visible UI elements should log at warning level. Vibrator failures can stay silent.

---

### MEDIUM-4
**Location:** `BubbleService.kt:1579–1584` — `AudioRecorder` start failure  
```kotlin
try {
    if (audioRecorder == null) audioRecorder = AudioRecorder(this)
    audioRecorder?.start(quietMode = ...)
    Log.i(TAG, "L${WimApp.layer()}: AudioRecorder started for cloud-ASR fallback")
} catch (e: Exception) {
    Log.e(TAG, "AudioRecorder failed, will use on-device only: ${e.message}")
    audioRecorder = null
}
```
**Issue:** When `AudioRecorder.start()` fails (mic permission revoked mid-session, another app holds exclusive audio focus), the code logs and falls back silently to on-device ASR. The user at L4 is now using Android SpeechRecognizer (or nothing) instead of Whisper. The bubble shows "REC" but the backend cloud path is dead. No user notification.  
**Recommendation:** Toast the user at L4 specifically, since the fallback at L4 is worse (no WAV = no Whisper-1 clinical path = no L4 reconstruction):
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "AudioRecorder failed, will use on-device only: ${e.message}")
    if (WimApp.layer() >= 4) {
        Toast.makeText(this, "Mic unavailable — L4 clinical mode requires audio recording", Toast.LENGTH_SHORT).show()
    }
    audioRecorder = null
}
```

---

### MEDIUM-5
**Location:** `BubbleService.kt:3808–3809` — accessibility service null check inside the success path  
```kotlin
if (WimAccessibilityService.instance != null) {
    WimAccessibilityService.instance!!.injectText(outputText)
    ...
} else {
    Log.e(TAG, "No accessibility service instance!")
    Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
}
```
**Issue:** When `WimAccessibilityService.instance` is null, reconstruction succeeded, the session is recorded to `sessionHistory` (line 3821), but `injectText` is never called — the text is lost. `sessionHistory.record()` records `cleanText = outputText` as if the text was successfully injected. The session data is wrong (it says "injected" but it was not). The user sees the "enable accessibility" toast, which is correct, but the session history reflects a false success.  
**Recommendation:** Record a failed injection in the else branch, not a successful one:
```kotlin
} else {
    Log.e(TAG, "No accessibility service instance!")
    Toast.makeText(...)
    sessionHistory?.record(
        rawText = preCleaned,
        cleanText = outputText,
        ...
        riskFlags = listOf("inject_failed_no_accessibility")
    )
}
```
The current code falls through to the `sessionHistory?.record()` call at line 3821 which runs in both branches, recording the event as if injection succeeded.

---

### LOW-1
**Location:** `MultiPassDecoder.kt:51–56` — `coroutineScope` with parallel `async` blocks  
```kotlin
val deferred = temperatures.map { temp ->
    async { Pass(temp, recognizer.transcribe(audioFile, ...)) }
}
val passes = deferred.awaitAll()
```
**Issue:** `coroutineScope` + `async` uses structured concurrency — if any single `transcribe()` call throws, `awaitAll()` will rethrow and all other in-flight calls are cancelled. This is actually correct behavior for the multi-pass use case (fail fast = don't waste 2 more Whisper calls). However, the caller at `BubbleService.kt:3887–3892` wraps this in a catch that logs and continues with empty confidences. This is correct. The issue is that `MultiPassDecoder.run()` itself has no try-catch — it relies entirely on its single callsite. Any future callsite that forgets to wrap it will crash silently under `SupervisorJob`.  
**Recommendation:** Not a blocking issue given current single callsite, but document the contract:
```kotlin
/**
 * @throws Exception if all passes fail. Caller must handle.
 */
suspend fun run(audioFile: File): MultiPassResult = coroutineScope { ... }
```

---

### LOW-2
**Location:** `BubbleService.kt:329` — `processAudioForTest()` coroutine  
```kotlin
scope.launch {
    try {
        ...
    } catch (e: Exception) {
        Log.e("WiM", "Test audio processing failed: ${e.message}", e)
        WimTestReceiver.lastResult = org.json.JSONObject().apply { ... }
    }
}
```
**Issue:** This is a test-only path (guarded by `BuildConfig.DEBUG`). The catch block is present and records to `WimTestReceiver.lastResult`. However, if `WhisperClient.transcribe()` succeeds but then `processText()` throws (which is a non-coroutine function that posts to the main handler and runs asynchronously), the `WimTestReceiver.lastResult` may never be set — tests that poll `lastResult` could hang. This is a test infrastructure reliability issue, not a production safety issue.

---

## SUMMARY TABLE

| # | File | Lines | Severity | Issue |
|---|------|-------|----------|-------|
| CRITICAL-1 | BubbleService.kt | 3917–3953 | CRITICAL | Fallback inject on reconstruction failure has no user-visible notification |
| CRITICAL-2 | BubbleService.kt | 3438–3440 | CRITICAL | `callOpenAiTransform` catch swallows everything silently with no log |
| HIGH-1 | BubbleService.kt | 1778–1783 | HIGH | Whisper failure does not call `sessionHistory.record()` — session data lost |
| HIGH-2 | BubbleService.kt | 3382, 3413 | HIGH | Ad-hoc OkHttpClient instances missing `connectTimeout` and `writeTimeout` |
| HIGH-3 | BubbleService.kt | 648–652 | HIGH | `showOnboardingOverlay` WindowManager failure swallowed silently |
| HIGH-4 | BubbleService.kt | 1329–1335 | HIGH | `performRegen` failure not toasted — user sees nothing |
| HIGH-5 | BubbleService.kt | 3670–3683, 3917 | HIGH | `turboTaxJob` not cancelled in catch block — races with error recovery |
| HIGH-6 | BubbleService.kt | 329, 1267, 1296, 1742, 3320, 3658, 3670 | HIGH | All `scope.launch` blocks lack `CoroutineExceptionHandler` |
| HIGH-7 | AuthManager.kt | 28–39 | HIGH | Token fetch failure silently bypasses backend, routes to dev OpenAI path |
| MEDIUM-1 | ReconstructClient.kt | 748–779 | MEDIUM | Falcon helper functions have no self-contained error handling |
| MEDIUM-2 | ReconstructClient.kt | 252 | MEDIUM | `optString("clean", rawText)` silently uses raw input on missing key |
| MEDIUM-3 | BubbleService.kt | 1456 | MEDIUM | `addView` for send disclaimer swallowed — user never sees confirmation |
| MEDIUM-4 | BubbleService.kt | 1579–1584 | MEDIUM | AudioRecorder failure at L4 has no user notification |
| MEDIUM-5 | BubbleService.kt | 3808–3809 | MEDIUM | Session recorded as success even when accessibility inject was skipped |
| LOW-1 | MultiPassDecoder.kt | 51–56 | LOW | No contract documentation on exception propagation |
| LOW-2 | BubbleService.kt | 329 | LOW | Test audio pipeline has async timing gap for `WimTestReceiver.lastResult` |
