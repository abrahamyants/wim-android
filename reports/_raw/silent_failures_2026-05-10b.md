# Silent Failure Audit — wim-android — 2026-05-10b

Auditor: Claude Sonnet 4.6 (1M)
Files read: BubbleService.kt, MultiPassDecoder.kt, L1PackHelper.kt, WimApp.kt,
            WimAccessibilityService.kt, PredictionEngine.kt, MainActivity.kt

---

## FINDING 1 — CRITICAL

**Location:** `BubbleService.kt` line ~4097–4138 (reconstruction `catch (e: Exception)` block)

**Issue: turboTaxJob is NOT cancelled in the catch path.**

The `while(true)` coroutine (`turboTaxJob`) is launched at line ~3779 and cancelled only
at line 3850 — on the happy path immediately after `ReconstructClient.reconstruct()` returns.
When `ReconstructClient.reconstruct()` throws (network error, timeout, JSON parse failure,
`CancellationException` from the scope itself, etc.), execution jumps to the `catch` block.
The catch block at lines 4097–4138 does not call `turboTaxJob?.cancel()`.

```kotlin
} catch (e: Exception) {
    BubbleStateBus.advance(BubbleState.Failed(FailReason.NETWORK))
    Log.e(TAG, "Reconstruction error: ${e.message}", e)
    // ...
    setBubbleIdle()
    // turboTaxJob is STILL RUNNING — "Still working... 6s" will keep
    // overwriting showPipelineStage even after setBubbleIdle() returns.
}
```

**Hidden errors:** Any exception thrown by ReconstructClient — HttpException, SocketTimeoutException,
JSONException, IllegalStateException from OkHttp, CancellationException rethrown from a nested
coroutine — all leave the TurboTax loop alive.

**User impact:** After a failed reconstruction the bubble shows "IDLE" label but the pipeline stage
bar keeps writing "Still working... 8s … 10s … 12s" indefinitely, overwriting any subsequent
pipeline stage messages. On next recording, these ghost writes corrupt the transcription stage label
("Transcribing" is immediately overwritten by the stale "Still working…"). This loop holds a
coroutine alive inside the service's `scope` until the scope cancels (service destruction),
consuming a live coroutine and the Handler message queue forever.

**Recommendation:** Add `turboTaxJob?.cancel()` as the first line of the catch block:

```kotlin
} catch (e: Exception) {
    turboTaxJob?.cancel()           // <-- ADD THIS
    BubbleStateBus.advance(BubbleState.Failed(FailReason.NETWORK))
    Log.e(TAG, "Reconstruction error: ${e.message}", e)
    ...
}
```

---

## FINDING 2 — CRITICAL

**Location:** `BubbleService.kt` `setBubbleIdle()` lines ~2127–2142 — specifically line 2130

**Issue: `PredictionEngine.predictForContext()` called on the Main thread with SharedPreferences I/O.**

`setBubbleIdle()` is always called from the Main thread (it touches Views directly). Inside, it
calls `PredictionEngine.predictForContext(pkg)`. That function calls `AudienceContext.profileForPackage(pkg)`.
While `predictForContext` itself is cheap, `PredictionEngine.candidatesForPosition` and
`PredictionEngine.recordCompletion` both call `readHistory()` and `writeHistory()`, which do
`SharedPreferences.getString()` and `.edit().putString().apply()` respectively.

More critically: `setBubbleIdle()` is called from many coroutine-completion paths via
`timerHandler.post { setBubbleIdle() }`, from ASR callbacks, and from the reconstruction
coroutine's catch block. Any call path that does NOT bounce through `timerHandler` before
reaching `setBubbleIdle()` while a coroutine is completing on Dispatchers.IO runs View mutations
off the Main thread, which crashes with `CalledFromWrongThreadException`.

The specific concern flagged in the task: **the prediction chip call blocks the main thread**.
`predictForContext` is cheap (no disk I/O), but `WimApp.prefs.getString("prediction_history_$pkg")`
inside `readHistory` is a SharedPreferences read. On Android, the first access for a key that hasn't
been cached by the SharedPreferences instance can block on disk. With potentially many per-pkg
history keys, this stalls the UI thread while the IDLE label is being rendered.

**User impact:** Sub-second hitches on the UI thread when the bubble transitions to IDLE,
especially on first access to each pkg's prediction history. Worsens with number of apps used.
In the worst case (corrupted SharedPreferences XML) `readHistory()` throws, which is caught
silently and returns `emptyMap()` — correct behavior but the exception is swallowed without logging
(line 136 of PredictionEngine.kt: `catch (e: Throwable) { emptyMap() }`).

**Recommendation:** Compute the prediction on a background thread and post the UI update:

```kotlin
// In setBubbleIdle(), replace the inline call:
if (WimApp.predictionsEnabled()) {
    val pkg = WimAccessibilityService.currentForegroundPackage
    scope.launch(Dispatchers.IO) {
        val pred = PredictionEngine.predictForContext(pkg)
        withContext(Dispatchers.Main) {
            bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
                if (pred != null) {
                    text = "→ ${pred.tone.take(3).uppercase()}"
                    setTextColor(...)
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        }
    }
}
```

Also add logging to `PredictionEngine.readHistory` catch block — currently a completely silent failure.

---

## FINDING 3 — HIGH

**Location:** `BubbleService.kt` `setBubbleIdle()` line 2129 / `showMenu()` line ~2809

**Issue: `currentForegroundPackage` can be null or stale when prediction chip renders.**

`WimAccessibilityService.currentForegroundPackage` is set only on `TYPE_WINDOW_STATE_CHANGED`
accessibility events (WimAccessibilityService.kt line 117). It is:
- `null` until the first window-state event after service bind.
- Potentially stale: if the user switches apps while WiM is recording, the package reflects
  the previous foreground app at reconstruction time but the foreground app at inject time.
- Not updated on `TYPE_VIEW_FOCUSED` events — only on window changes.

`PredictionEngine.predictForContext(null)` safely returns null (line 48: `if (pkg.isNullOrBlank()) return null`), so there is no crash. However the chip silently shows nothing, which is correct behavior — but the comment at line 2124–2126 promises it "surfaces PredictionEngine's context-awareness," which is invisible to the user without any diagnostic when null.

More importantly: the same null package is passed to `Clarifier.evaluate()` at line 4055:
```kotlin
val clarifierPkg = WimAccessibilityService.currentForegroundPackage
if (WimApp.isClarifierAllowed(clarifierPkg)) {
```
`isClarifierAllowed(null)` returns `true` (WimApp.kt line 132: `if (pkg.isNullOrBlank()) return true`),
so a null package allows the clarifier to run against ALL apps unconditionally, including apps
the user may have placed on the blacklist by another package name. The blacklist check is defeated
when the package hasn't been captured yet.

**User impact:** User adds app X to the clarifier blacklist. If the accessibility service hasn't
yet received a window-state event, `currentForegroundPackage` is null, `isClarifierAllowed(null)` → true,
and the clarifier fires in app X anyway. User sees unexpected "did you mean?" prompts in apps they
explicitly blocked.

**Recommendation:** At minimum, log a warning when the package is null at clarifier-check time.
Defensively: fall back to `activeTextField?.packageName?.toString()` before using null:

```kotlin
val clarifierPkg = WimAccessibilityService.currentForegroundPackage
    ?: WimAccessibilityService.activeTextField?.packageName?.toString()
if (WimApp.isClarifierAllowed(clarifierPkg)) {
```

---

## FINDING 4 — HIGH

**Location:** `MultiPassDecoder.kt` lines 130–136 (the `lowConfLogprobs` map construction)

**Issue: Duplicate-word token collision — last one wins silently.**

```kotlin
val lowConfLogprobs: Map<String, Double> = primary.lowConfidenceSegments
    .flatMap { seg ->
        seg.text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.any(Char::isLetterOrDigit) }
            .map { tok -> tok.filter(Char::isLetterOrDigit) to seg.avgLogprob }
    }
    .toMap()   // <-- last-wins for duplicates
```

`.toMap()` on a list of pairs silently discards all but the last entry for any duplicate key.
When the same word appears in multiple low-confidence segments (e.g., repeated word in a block
event: "I I I want"), each occurrence has a different `avgLogprob`. The map only retains the last
segment's logprob for that word key.

**Hidden errors:** When `lowConfidenceSegments` is empty, `lowConfLogprobs` is an empty map —
this is the correct fast-path and isn't a bug. The bug fires on non-empty lists with repeated
words: the `asrLogprob` field for a word that appeared multiple times (exactly the stuttered words
WiM cares most about) will carry the logprob of whichever segment happens to be last, not the
worst one. The Clarifier may receive a falsely optimistic logprob for the highest-risk words.

**User impact:** Stuttered words (repetitions are the most common disfluency) have the lowest ASR
confidence in practice. If the last occurrence of "I" happens to land in a higher-confidence
segment, the Clarifier receives a better logprob than warranted and may not flag the word for
review, defeating the clinical purpose of multi-pass decoding.

**Recommendation:** Replace last-wins with worst-wins (lowest logprob = highest uncertainty = most
valuable to surface):

```kotlin
val lowConfLogprobs: Map<String, Double> = primary.lowConfidenceSegments
    .flatMap { seg ->
        seg.text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.any(Char::isLetterOrDigit) }
            .map { tok -> tok.filter(Char::isLetterOrDigit) to seg.avgLogprob }
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, logprobs) -> logprobs.min() }  // worst confidence wins
```

---

## FINDING 5 — HIGH

**Location:** `L1PackHelper.kt` lines 100–103 (`activePack`) and `promptInjection()` line 120

**Issue: `WimApp.prefs` accessed before `WimApp.onCreate()` completes — guaranteed crash path.**

```kotlin
fun activePack(context: Context): Pack? {
    val l1 = WimApp.prefs.getString("profile_l1", "") ?: ""
    return loadPack(context, l1)
}
```

`WimApp.prefs` is a `lateinit var` initialized in `WimApp.onCreate()` (WimApp.kt line 213).
If `L1PackHelper.activePack()` or `promptInjection()` is called before `WimApp.onCreate()`
completes, this throws `UninitializedPropertyAccessException`. The comment in the class says
"Loaded once from `assets/l1_packs/<language>.json`. Activated by the `profile_l1` shared
pref." — implying it could be called from `ReconstructClient` during processing, which is
launched from `BubbleService` (a separate component that Android can start before the Application
lifecycle settles in edge cases like process death + direct notification tap).

Similarly, `loadPack()` catches all `Exception` at line 92 and logs only a warning with no user
feedback. If the JSON parse fails (malformed asset), the pack returns null silently, the L1
injection disappears, and reconstruction silently degrades with no indication to the user or
developer that the pack was expected but missing.

**User impact:** In the `WimApp.prefs` uninitialized case: a silent hard crash (UninitializedPropertyAccessException
is not caught by the loadPack try-catch since the exception is thrown before entering it). In the
malformed JSON case: L1 normalization silently stops working — the user sees no change in
reconstruction quality, has no idea why the pack isn't applying, and no Sentry/logError fires
the issue upstream.

**Recommendation:**
1. Replace `WimApp.prefs` access in `activePack` with a `context.getSharedPreferences("wim", Context.MODE_PRIVATE)` call that doesn't rely on the Application singleton:
```kotlin
fun activePack(context: Context): Pack? {
    val prefs = context.applicationContext.getSharedPreferences("wim", Context.MODE_PRIVATE)
    val l1 = prefs.getString("profile_l1", "") ?: ""
    return loadPack(context, l1)
}
```
2. In `loadPack()`, upgrade the catch block from `Log.w` to `Log.e` and add a Sentry/logError call so pack parse failures surface in crash reporting.

---

## FINDING 6 — HIGH

**Location:** `WimApp.kt` lines 101–114 — `clarifierSensitivityThreshold()`

**Issue: Default changed from "off" to "medium" breaks old devices silently.**

The comment at lines 104–108 explicitly acknowledges this:
```kotlin
// Word-confidence scores from MultiPassDecoder are in 0..1, so
// a threshold >= 1.0 means nothing ever crosses → Clarifier.evaluate
// returns empty flags every call → no clarifier cycle ever fires.
// Reverse by changing default back to "medium" or letting the
// user pick a non-"off" sensitivity in settings.
"off" -> 999.0
```

Any device that had the old default pref value of "off" stored in SharedPreferences will continue
reading "off" → 999.0 (clarifier permanently disabled) even after the code ships with `"medium"` as
the default. The `prefs.getString("clarifier_sensitivity", "medium")` call reads the stored "off"
value, not the default. Users who never opened the Clarifier settings panel will have it silently
disabled forever.

**User impact:** The clarifier feature — described as the key differentiator — never fires for
any existing user whose device retained the "off" default. No error, no log message, no feedback.
The user sees IDLE after every reconstruction with no "did you mean?" prompts, and has no way to
know the feature is disabled.

**Recommendation:** Add a one-time migration on app startup to clear the stale "off" value:

```kotlin
// In WimApp.onCreate(), after prefs is initialized:
if (prefs.getString("clarifier_sensitivity", null) == "off") {
    prefs.edit().putString("clarifier_sensitivity", "medium").apply()
    Log.i("WiM", "Migrated clarifier_sensitivity from 'off' to 'medium'")
}
```

---

## FINDING 7 — MEDIUM

**Location:** `BubbleService.kt` lines 3779–3800 (the `while(true)` loop inside `turboTaxJob`)

**Issue: The `while(true)` loop has only one cooperative cancellation point (`delay()`) and no
timeout bound.**

The loop structure is:
```kotlin
while (true) {
    showPipelineStage("⌛ Still working...", "Deep reconstruction — ${elapsedSec}s")
    kotlinx.coroutines.delay(2000)
    elapsedSec += 2
}
```

Cancellation works correctly via `delay()` — `kotlinx.coroutines.delay` throws `CancellationException`
when the coroutine is cancelled. **However**, cancellation only occurs on the success path at line 3850
and is missing from the catch path (see Finding 1). The secondary concern is the absence of any
maximum elapsed time bound: if reconstruction is genuinely stuck (OkHttp read timeout missed,
server holding connection open), `elapsedSec` grows without bound, `showPipelineStage` keeps running,
and the turboTaxJob lives until service destruction — which can be hours.

OkHttp's default timeouts (if not set) are unlimited connect + read. If `ReconstructClient` sets
only connect timeout but not read timeout (to be verified), the cloud call can hang forever, the
turboTaxJob will display "Still working... 3600s" without any user action available.

**User impact:** User launches L4, long processing stalls network-side, bubble shows infinite
"Still working…" counter. There is no cancel affordance on the bubble during reconstruction
(isRecording = false so tap = new record attempt, which is blocked by the processing state).
User must kill the app.

**Recommendation:** In addition to fixing Finding 1, add an elapsed-time guard:

```kotlin
val MAX_RECON_SEC = 120
while (elapsedSec < MAX_RECON_SEC) {
    showPipelineStage("⌛ Still working...", "Deep reconstruction — ${elapsedSec}s")
    kotlinx.coroutines.delay(2000)
    elapsedSec += 2
}
// Timeout: surface actionable error and cancel the parent scope
showPipelineStage("✕ Timeout", "Reconstruction took too long — tap to retry")
timerHandler.postDelayed({ setBubbleIdle() }, 3000)
// Optionally: coroutineContext.cancel() or parent job cancel
```

---

## FINDING 8 — MEDIUM

**Location:** `MultiPassDecoder.kt` lines 56–66

**Issue: `passes.firstOrNull()` returns null-primary when all temperature calls fail — not handled.**

```kotlin
val primary = passes.firstOrNull()?.result
    ?: TranscribeResult(text = "", asrMs = 0)
```

When `passes.size < 2` (line 59), an early return fires with the empty primary. But this branch
only fires for `passes.size < 2`, which in practice means 0 or 1 passes. If all three `async`
calls in `deferred.awaitAll()` complete successfully but every `result.text` is blank (Whisper
returned empty strings for all temperatures — e.g., very short audio, device mic issue), `passes`
has 3 elements and the code falls through to the disagreement analysis. `tokenLists` will all be
empty lists, `maxLen` = 0, the loop body never executes, and `wordConfidences` comes back empty.
This is actually correct behavior — but the `primary.text = ""` case inside `wordConfidencesFromPrimary`
is guarded by `if (text.isBlank()) return emptyList()`, so there is no crash.

The real gap: if one or more of the three `async` calls throws (network error on one of the
parallel Whisper calls), `awaitAll()` rethrows the first exception. The entire `MultiPassDecoder.run()`
call throws, which is caught in BubbleService at line 4070–4072:

```kotlin
} catch (e: Exception) {
    Log.w(TAG, "MultiPassDecoder failed: ${e.message} — clarifier proceeds with empty confidences")
}
```

This catch is correctly present and the warning log is correct. However, if the exception is a
`CancellationException` (the parent scope was cancelled while the parallel whisper calls were in
flight), catching `Exception` here also catches `CancellationException` (which is a subclass of
`Exception` in older Kotlin coroutines versions, or `CancellationException` extends `IllegalStateException`
in newer versions). The `Log.w` call will succeed but the coroutine will continue past the catch block
rather than propagating the cancellation — this is a coroutine anti-pattern that can prevent clean
service shutdown.

**Recommendation:** Re-throw `CancellationException` explicitly:

```kotlin
} catch (e: CancellationException) {
    throw e   // let coroutine machinery handle scope cancellation
} catch (e: Exception) {
    Log.w(TAG, "MultiPassDecoder failed: ${e.message} — clarifier proceeds with empty confidences")
}
```

---

## FINDING 9 — MEDIUM

**Location:** `BubbleService.kt` line 1363 — L1 contribution fire-and-forget `Thread`

**Issue: Raw `Thread` with completely empty `catch (_: Exception) {}` block — total silent failure.**

```kotlin
Thread {
    try {
        // HTTP POST to wim-l1-contribute
        conn.responseCode // trigger request
        conn.disconnect()
    } catch (_: Exception) {}
}.start()
```

Every possible failure — DNS failure, connection refused, HTTP 500, JSON serialization error,
`SecurityException` from network permission, `OutOfMemoryError` — is swallowed with no logging
whatsoever. If the contribution endpoint is down, if the user's device blocks background network
on Android 14+ background restrictions, if the API changes and starts returning 4xx — nobody will
ever know.

**User impact:** L1 contribution data silently stops flowing. The team has no signal that
contributions are failing, no Sentry event, no logcat entry. The feature appears to be enabled
but produces zero data.

**Recommendation:** Add at minimum a warning log on failure, and check the response code:

```kotlin
Thread {
    try {
        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            Log.w("WiM", "L1 contribution returned HTTP $responseCode")
        }
        conn.disconnect()
    } catch (e: Exception) {
        Log.w("WiM", "L1 contribution failed: ${e.message}")
    }
}.start()
```

---

## FINDING 10 — MEDIUM

**Location:** `WimAccessibilityService.kt` lines 458–469 — `showBubble()` catch block

**Issue: Broad `catch (e: Exception)` with no error context on the start-bubble failure.**

```kotlin
private fun showBubble() {
    try {
        val intent = Intent(this, BubbleService::class.java).apply { action = "SHOW" }
        startForegroundService(intent)
        Log.i("WiM", "Bubble service started via startForegroundService")
    } catch (e: Exception) {
        Log.e("WiM", "Failed to start bubble service: ${e.message}")
    }
}
```

This catches `SecurityException` (missing FOREGROUND_SERVICE permission), `IllegalStateException`
(calling startForegroundService when app is in background — Android 12+ restriction),
`ActivityNotFoundException`, and any other RuntimeException uniformly. The user gets no feedback:
the accessibility service silently continues running but the bubble never appears.

**Hidden errors:** Android 12+ throws `ForegroundServiceStartNotAllowedException` (a subclass of
`IllegalStateException`) when `startForegroundService` is called from background. This is caught,
logged as "Failed to start bubble service: ForegroundServiceStartNotAllowedException", and
swallowed. No toast, no notification. The user sees: the accessibility service is on, WiM appears
to be running, but no bubble. This is one of the hardest failure modes to diagnose.

**Recommendation:** Add a user-visible notification or toast on failure, and distinguish the
background-restriction case:

```kotlin
} catch (e: Exception) {
    Log.e("WiM", "Failed to start bubble service: ${e::class.simpleName}: ${e.message}")
    Toast.makeText(this, "WiM bubble failed to start — reopen the app to restore", Toast.LENGTH_LONG).show()
}
```

---

## FINDING 11 — MEDIUM

**Location:** `BubbleService.kt` lines 3925–3928 — inject-path when `WimAccessibilityService.instance == null`

**Issue: Reconstruction result is silently discarded when accessibility service disappears mid-cycle.**

```kotlin
} else {
    Log.e(TAG, "No accessibility service instance!")
    Toast.makeText(this@BubbleService, getString(R.string.toast_enable_accessibility), Toast.LENGTH_SHORT).show()
}
```

After 0.8s of `kotlinx.coroutines.delay(800)` for the "brief pause" at line 2904, the
accessibility service may have been unbound by the OS (memory pressure, accessibility settings
toggled off). The reconstruction result — which may have taken 10+ seconds to generate on L4 —
is thrown away. No session record is written for this path. The user sees a toast but has no
way to recover the reconstructed text (no clipboard copy, no retry mechanism).

**User impact:** On L4 (clinical, the highest-value path), a 10-15 second reconstruction cycle
completes, the result is computed correctly, then silently discarded because the accessibility
service disconnected during the 800ms delay. The session record shows nothing happened. The user
has to speak again.

**Recommendation:** Copy the reconstructed text to the clipboard as a fallback before giving up:

```kotlin
} else {
    Log.e(TAG, "No accessibility service instance — accessibility service unavailable after reconstruction")
    // Preserve the work: copy to clipboard as fallback
    val clip = android.content.ClipData.newPlainText("wim", outputText)
    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
    Toast.makeText(this@BubbleService, "Accessibility lost — text copied to clipboard", Toast.LENGTH_LONG).show()
    // Still record the session
    sessionHistory?.record(rawText = preCleaned, cleanText = outputText, ...)
}
```

---

## FINDING 12 — MEDIUM

**Location:** `BubbleService.kt` line 2105 — `lastWavFile` deletion in `setBubbleIdle()`

**Issue: WAV deletion failure is silently swallowed.**

```kotlin
lastWavFile?.let { try { it.delete() } catch (_: Exception) {} }
lastWavFile = null
```

If `it.delete()` returns `false` (the file is still open by MultiPassDecoder, or the path is on
external storage with revoked permission), or throws, the reference is cleared (`lastWavFile = null`)
but the file persists on disk. On the next recording cycle, the previous WAV is not overwritten
(new file gets a different temp name), and no cleanup occurs. Over many recording sessions, WAV
files accumulate silently in the app's temp directory, consuming storage.

**User impact:** Progressive storage leak. On devices with limited internal storage (64 GB entry-level
Android), this can fill the cache partition over time and cause AudioRecorder to fail when trying
to write new temp files.

**Recommendation:** Log on delete failure:

```kotlin
lastWavFile?.let { f ->
    if (!f.delete()) {
        Log.w(TAG, "Failed to delete WAV temp file: ${f.absolutePath} (${f.length()} bytes)")
    }
}
lastWavFile = null
```

---

## SUMMARY TABLE

| # | Severity | File | Line(s) | Description |
|---|----------|------|---------|-------------|
| 1 | CRITICAL | BubbleService.kt | ~4097 | turboTaxJob not cancelled in catch block — ghost loop |
| 2 | CRITICAL | BubbleService.kt | ~2130 | PredictionEngine.predictForContext() on Main thread with disk I/O risk |
| 3 | HIGH | BubbleService.kt | 2129, 4055 | currentForegroundPackage null defeats clarifier blacklist |
| 4 | HIGH | MultiPassDecoder.kt | 130–136 | Duplicate-word logprob collision — worst confidence not preserved |
| 5 | HIGH | L1PackHelper.kt | 100–103 | WimApp.prefs access before Application.onCreate() — crash + silent JSON failure |
| 6 | HIGH | WimApp.kt | 101–114 | "off" default migration missing — clarifier permanently disabled on old devices |
| 7 | MEDIUM | BubbleService.kt | 3795 | while(true) has no max-elapsed bound — infinite loop on stuck cloud call |
| 8 | MEDIUM | MultiPassDecoder.kt | 4070 | CancellationException caught by broad Exception handler — scope leak |
| 9 | MEDIUM | BubbleService.kt | ~1363 | L1 contribution Thread has empty catch — zero observability on failure |
| 10 | MEDIUM | WimAccessibilityService.kt | 458–469 | Broad catch on startForegroundService — no user feedback on bubble start failure |
| 11 | MEDIUM | BubbleService.kt | 3925–3928 | Reconstruction result discarded silently when a11y service disappears — no clipboard fallback |
| 12 | MEDIUM | BubbleService.kt | 2105 | WAV temp file deletion failure swallowed — progressive storage leak |
