# WiM-Android Silent-Failure Audit — 2026-05-29

Auditor scope: focus files plus surrounding pipeline. Goal: find OTHER instances of the 5/13 `turboTaxJob` scope bug (val declared in try, catch couldn't see it) plus other silent-failure classes listed in the brief.

The 5/13 hoist + child-of-currentReconJob fix at `BubbleService.kt:2263` looks correct. **No other declared-in-try/cancelled-in-catch coroutine bug exists** — `multiPassDeferred` is also hoisted on the same line (2267), and the other three `scope.launch` blocks (358, 1258, 1720) don't declare jobs inside the try. **However**, both `multiPassDecoder` (line 2364) and the `turboTaxJob` `launch { ... }` (line 2298) launch CHILDREN of `currentReconJob`. The 5/13 fix declared `multiPassDeferred` at line 2267 outside the try and the catch cancels it at line 2629 — same hoist pattern, same intent, **correct**.

---

## 1. Confirmed Silent Failures (data loss / user-visible behavior break)

### 1.1 Backend response shape change silently strips reconstruction
**`ReconstructClient.kt:261`** — `json.optString("clean", rawText)`
- Trigger: backend renames the response field, sends `{"output": "..."}` instead of `{"clean": "..."}`, or returns an error envelope `{"error": "..."}` with HTTP 200. The `clean` key is absent, fallback returns `rawText`.
- Fix: require the field — `json.optString("clean", null) ?: throw RuntimeException("Backend response missing 'clean' field: ${responseBody.take(200)}")`. The catch at `BubbleService.kt:2627` already pastes `preCleaned` and toasts the user; surface the real failure instead of silently regressing to L1 behavior.

### 1.2 GDPR delete-data silently reports success on empty response
**`UserDataClient.kt:79`** — `resp.body?.string() ?: return@withContext false`
- Trigger: server returns 200 with empty body (Cloud Run cold-start race, network proxy truncation). Function returns false silently, caller in MainActivity treats as "delete failed but please try again" — but user's data may have actually been deleted, or may not have been. Compliance-fragile.
- Fix: distinguish empty-body-error from clean-not-deleted: `val respBody = resp.body?.string() ?: run { Log.e(TAG, "deleteData empty body, status=${resp.code}"); return@withContext false }`. Same pattern at line 49 for `exportData`.

### 1.3 GPT-4o response parsing crashes on OpenAI error envelope
**`ReconstructClient.kt:709-714`** — `JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")`
- Trigger: OpenAI 200-with-error envelope (rare but documented for organization billing failures), or response_format mismatch. `getJSONArray("choices")` throws JSONException. Caller's `catch (e: Exception)` at `BubbleService.kt:2627` treats this as a generic network error, sets `FailReason.NETWORK`, pastes pre-cleaned text. The user sees "Reconstruction failed — raw text used" but the actual cause (billing dead, model deprecated) is lost.
- Fix: check `json.has("error")` first, surface `json.optJSONObject("error")?.optString("message")` to the user / log distinctly.

### 1.4 Sonnet response text-block extraction throws cryptic error
**`ReconstructClient.kt:769`** — `throw RuntimeException("Sonnet response had no text block: ${responseBody.take(200)}")`
- Trigger: Sonnet exhausts its 8K thinking budget without emitting a text block (acknowledged at line 596-602 in the same file). The throw fires inside `reconstructWithSonnet`, the catch at line 609 sees it, logs "Sonnet L4 failed", and falls back to GPT-4o. Good — except: the `sonnetDowngraded = true` flag fires a one-time toast at `BubbleService.kt:2397-2400`. After the first ET-extinction event in the process lifetime, **every subsequent Sonnet failure is silent** (`l4DowngradeNotified` guard at `BubbleService.kt:84`). User pays Anthropic for Sonnet ET, gets silently downgraded to GPT-4o, never knows.
- Fix: log every downgrade with reason, not just toast. The toast guard is fine; the silent log gap is not.

### 1.5 ProfileManager.getTriggerWords / getOnsetWeights / getCovertProfile silently empty on parse error
**`ProfileManager.kt:245`, `:256`, `:267`** — `catch (_: Exception) { emptyList() }` / `emptyMap()`
- Trigger: one corrupt write to `profile_json` (concurrent merge race, OOM-during-save, disk full mid-write). User's accumulated trigger words, phoneme onset weights, and covert avoidance map silently vanish from L4 reconstruction prompts. The clinical-pipeline differentiator becomes a generic GPT-4o cleanup. User sees "WiM stopped feeling like mine" and uninstalls.
- Fix: log the exception (`Log.e(TAG, "trigger_words parse failed", e)`) AND preserve the corrupt blob the same way `loadProfile()` does (lines 106-112).

### 1.6 PredictionEngine history silently wipes on parse error
**`PredictionEngine.kt:136`** — `catch (e: Throwable) { emptyMap() }`
- Trigger: any corrupt write to `prediction_history_<pkg>`. User's learned per-app sentence-boundary completions vanish — the claim-14 differentiator ("your bubble learned how you talk to your boss in Slack") becomes empty. Clarifier cycles offer no completions for an app the user has dictated into 200 times.
- Fix: log + preserve the bad blob before returning emptyMap. Same template as ProfileManager.

### 1.7 ProfileManager.mergeRemoteProfile silently wipes local vocabulary
**`ProfileManager.kt:361-369`, lines 378-386** — Firestore sync union for vocabulary / filler_words
- Trigger: Lavrentiy desktop uploads `vocabulary` in a different shape (a single string instead of `List<String>`, or a Map). `raw as? List<String>` returns null silently → `emptySet()` → merged set excludes nothing local-side, but the user's local vocabulary becomes `(local + emptyList).toList()`. Currently benign for vocabulary. **But for `trigger_words` at line 339-344**, `raw as? List<String> ?: emptyList()` writes the empty list back into the profile JSON, **OVERWRITING the user's local trigger words**.
- Fix: skip the put-back when the cast fails — `val list = raw as? List<String> ?: run { Log.w(TAG, "trigger_words wrong shape: ${raw.javaClass}"); return@let }`.

### 1.8 L1 detection thread fires forever after 5 reconstructions
**`BubbleService.kt:2461-2519`** — `markL1DetectPrompted()` called inside `if (responseCode == 200)`
- Trigger: backend `wim-l1-guess` endpoint returns 5xx, network drops, or DNS fails. `markL1DetectPrompted()` never runs. **Every subsequent reconstruction at L2+** increments `l1DetectCount` AND spawns a new `Thread { ... }` (the count check `>= 5` is always true once crossed). Thread-per-reconstruction leak; battery and network burn.
- Fix: comment at line 2466-2469 ("Mark prompted BEFORE launching the thread so a network failure...") describes the correct behavior but the code does the opposite. Move `markL1DetectPrompted()` to outside the `if (responseCode == 200)` block, or call it before the Thread launches.

### 1.9 WimTileService click does nothing on FGS-not-allowed exception
**`WimTileService.kt:29-32`** — `catch (_: Exception) {}` after `startForegroundService`
- Trigger: Android 12+ `ForegroundServiceStartNotAllowedException` (app not in foreground, no qualifying exemption). Comment claims fall-through but the catch has no recovery code. User taps the QS tile, bubble doesn't appear, no toast, no log, no Sentry signal.
- Fix: log + toast on catch (`Toast.makeText(this, "Open WiM to summon the bubble", Toast.LENGTH_LONG).show()`).

### 1.10 OpenAI direct command transform silently drops failed responses
**`BubbleCommandMode.kt:278`** — `if (!resp.isSuccessful) return@withContext null`
- Trigger: 401 (bad key), 429 (rate limit), 500 (server). No log on this branch. User sees their voice command silently ignored.
- Fix: `Log.w(TAG, "callOpenAiTransform HTTP ${resp.code}: ${respBody.take(200)}"); return@withContext null` — match the sibling backend path at line 244.

### 1.11 AccessibilityNodeInfo recycle leak in `findEditableFocusedDescendant`
**`WimAccessibilityService.kt:496-506`** — recursive descent only recycles `child` when recursion returned null
- Trigger: every successful focused-field re-find. Intermediate `child` nodes between root and the matched descendant are never recycled because line 502's `return result` skips line 503's `child.recycle()`. Accessibility framework's node pool leaks across every WhatsApp / Gmail compose / Telegram open.
- Fix: hold `result` in a temp, always recycle non-returned children, return at the end:
  ```kotlin
  var result: AccessibilityNodeInfo? = null
  for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      if (result == null) {
          result = findEditableFocusedDescendant(child)
          if (result == null) child.recycle()
      } else {
          child.recycle()
      }
  }
  return result
  ```
- Same leak in `AgentCommandHandler.kt:417-425` `findFocused` (debug-only but still).

### 1.12 Word-swap accessibility failures silently advance the clarifier
**`ClarifierController.kt:87-97`** — `swapWordInLastInjection` returns false → `next()` runs anyway
- Trigger: host app's text field changed since the inject (Slack thread switch, user manually edited the box). `swapWordInLastInjection` returns false at `WimAccessibilityService.kt:420`. The clarifier moves to the next flagged word — the swap that the user expected never happened. No log fires above Log.w, no toast.
- Fix: when `swapWordInLastInjection` returns false, show a brief pipeline stage ("Couldn't swap — field changed") before advancing. Tells the user why their tap didn't change the text.

---

## 2. Latent Silent Failures (will bite when X happens)

### 2.1 OkHttp Response leak across every API client
**`WhisperClient.kt:166-167`, `ReconstructClient.kt:250-251`, `:703-704`, `:755-756`, `UserDataClient.kt:48-49`, `:78-79`, `BubbleCommandMode.kt:241-242`, `:276-277`** — none use `response.use {}`.
- Trigger: Response holders pile up in OkHttp's connection pool, idle connections never returned. After ~50-100 reconstructions, the pool exhausts and subsequent calls stall. `WhisperLocalModelDownloader.kt:90-109` does it right (uses `body.byteStream().use`) — the API clients are inconsistent.
- Fix: wrap each `http.newCall(req).execute()` in `.use { resp -> ... }`.

### 2.2 AudioRecorder.startRecording state not verified
**`AudioRecorder.kt:81`** — `recorder?.startRecording()` return value ignored
- Trigger: AudioRecord constructed but state is UNINITIALIZED (rare on modern devices, common when another app holds the mic exclusively). startRecording is a no-op silently. captureLoop sees `read <= 0` forever and spins.
- Fix: after line 81, check `recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING`, throw / surface to user if not.

### 2.3 AudioRecord.read negative return code burns CPU
**`AudioRecorder.kt:156-157`** and **`WhisperLocalEngine.kt:163-169`** — `if (read <= 0) continue`
- Trigger: AudioRecord.read returns ERROR_DEAD_OBJECT (-6) when the audio HAL crashes (Samsung mid-call), ERROR_INVALID_OPERATION (-3) when the recorder is released mid-loop. Loop never bails, burns CPU, captureThread leaks.
- Fix: `if (read < 0) { Log.e(TAG, "AudioRecord.read=$read, bailing"); break }`.

### 2.4 SpeechRecognizer hotword re-arms on permission-denied
**`HotwordListener.kt:86-95`** — restarts on every error code
- Trigger: user revokes RECORD_AUDIO mid-session. ERROR_INSUFFICIENT_PERMISSIONS (9) fires, the restart timer reschedules in 120ms, loop forever. Battery and CPU burn. User never sees a toast that hotword is broken.
- Fix: whitelist recoverable errors only (NO_MATCH, SPEECH_TIMEOUT, RECOGNIZER_BUSY, SERVER_DISCONNECTED). On INSUFFICIENT_PERMISSIONS / LANGUAGE_NOT_SUPPORTED / CLIENT, disable the hotword pref and toast.

### 2.5 BubbleService doesn't override `onTimeout` for Android 14+ FGS time-cap
**`BubbleService.kt`** — no `onTimeout(serviceId)` override; manifest declares FOREGROUND_SERVICE_TYPE_MICROPHONE
- Trigger: Android 14+ caps mic FGS at ~1 hour. System calls `onTimeout()` and the service is force-stopped. The bubble disappears mid-conversation, audio recording dies, user sees their bubble vanish.
- Fix: override `onTimeout(serviceId, fgsType)` → relaunch as specialUse or surface a toast explaining the cap.

### 2.6 ResultListener silently null on rotation
**`BubbleService.kt:1641-1645`, `:1657-1661`** — `whisperLocalEngine?.start(object : ResultListener { ... })`
- Trigger: `whisperLocalEngine` or `onDeviceRecognizer` is null because BubbleService was killed and recreated. `?.start(...)` no-ops. User holds bubble, sees recording UI, no result ever fires.
- Fix: assert engine non-null before showing recording UI, fall back to the Android SpeechRecognizer path with a toast.

### 2.7 fixWavHeader leaks RandomAccessFile on write failure
**`AudioRecorder.kt:211-236`** — no try/finally around `raf.write` / `raf.close`
- Trigger: disk full mid-header-write. `raf.close()` never runs, file handle leaks, next recording's `FileOutputStream(file)` call fails.
- Fix: `RandomAccessFile(file, "rw").use { raf -> ... }`.

### 2.8 AuthManager.getIdToken silently nulls on auth failure
**`AuthManager.kt:33-46`** — refresh failure returns null, caller picks device-direct path
- Trigger: token expired AND device offline AND refresh fails. `ReconstructClient.kt:108` sees `idToken != null` is false, routes to `reconstructDirect` which uses bundled / user OpenAI key. The user thinks they're using backend (their paid tier), but is silently consuming the bundled key budget.
- Fix: surface a distinct "auth-degraded" state to caller, show one-time toast "Using offline reconstruction — sign in to restore your backend".

### 2.9 Falcon retired but `falcon_ok` still trusted from backend
**`ReconstructClient.kt:263`** — `val falconOk = json.optBoolean("falcon_ok", true)`
- Trigger: backend version drift — Falcon retired client-side (`falconValidate` stub at line 659-670 returns true) but backend may still report `falcon_ok: false` on its own grounds. `optBoolean(default=true)` defaults to passing on missing key. **Backwards-compatible silent acceptance** — if backend ever decides reconstruction failed, the result is silently accepted.
- Fix: explicit `val falconOk = if (json.has("falcon_ok")) json.getBoolean("falcon_ok") else true` so missing-key is a logged event, not a silent default.

### 2.10 dispatchTapAt returns before gesture fires
**`WimAccessibilityService.kt:611-629`** — `dispatchGesture` is async, caller returns the dispatched flag, not the completed flag
- Trigger: foreground app changes between gesture submit and gesture fire (rare but real on slow mid-range devices). `onCancelled` logs a warning but caller has already returned true. Auto-send appears to succeed; nothing actually got sent.
- Fix: use a `CountDownLatch` (5s timeout) inside dispatchTapAt; return the completion flag, not the dispatch flag. Same pattern AgentCommandHandler already uses at line 344.

### 2.11 onAccessibilityEvent doesn't recycle `event.source`
**`WimAccessibilityService.kt:115`, `:125`** — `val source = event.source` retained as `activeTextField`
- Trigger: every accessibility event allocates a new AccessibilityNodeInfo. The prior `activeTextField` reference is overwritten without recycle. Accessibility-process node pool exhausts on apps that fire events rapidly (Gmail thread scroll).
- Fix: recycle the previous `activeTextField` before reassigning.

### 2.12 ProfileManager.saveProfile passes nullable maps as JSONObject constructor input
**`ProfileManager.kt:123-126`** — `JSONObject(corrections as Map<*, *>)`
- Trigger: any null in a value (rare, but possible during the Firestore merge when remote field is null). JSONObject's Map ctor calls `put(key, JSONObject.NULL)` for nulls. The profile JSON gets serialised with explicit nulls, then loadProfile's `getString(key)` throws on the next session start. Cascades to `resetToDefaults()` and **full user-data wipe**.
- Fix: filter null values before passing.

### 2.13 No recovery toast on whisper-tiny model corruption re-download
**`WhisperLocalEngine.kt:102-122`** — after re-download succeeds, user must hit record AGAIN to use it
- Trigger: model corruption recovery succeeds in background. The current recording session has already errored out (ERROR_INIT) and the user moved on. No re-prompt to retry.
- Fix: after `dlResult` is Success, fire a non-modal "Speech model restored — tap to dictate again" notification.

---

## 3. Logging Gaps That Hide Root Cause

### 3.1 Empty catches in lifecycle cleanup paths obscure why cleanup fails
- `BubbleService.kt:582, 1059, 1089, 2506, 2668, 2671` — all `catch (_: Exception) {}` on lifecycle / window-manager / wakelock-release paths. Reasonable on isolated calls but collectively means BubbleService onDestroy crashes are invisible.
- Fix: at minimum `Log.v` the exception class — `catch (e: Exception) { Log.v(TAG, "cleanup: ${e.javaClass.simpleName}") }` matches the pattern other window-manager calls already use.

### 3.2 WhisperClient hallucination strip strips an empty transcript silently
- `WhisperClient.kt:206-211` — when `wasFullyHallucinated` is true, only `Log.w` fires. Caller sees text="" but the caller's branching at `BubbleService.kt:1743-1748` treats hallucinated and silence equivalently.
- Fix: emit a distinct stage-bar message ("∅ Whisper hallucination") vs ("∅ Silence") so the user can tell the difference between mic-stuck and unclear-speech.

### 3.3 ReconstructClient `learnFromReconstruction` poisons profile on profanity-stripped output
- `ProfileManager.kt:280-303` — if reconstruction strips a critical word ("fuck" → ""), the algorithm "learns" a positional substitution. **Cross-references the prompt rule at `ReconstructClient.kt:314-316` which forbids softening profanity**, but if the model breaks the rule, the profile silently learns the censorship. No log of the corrections being auto-added.
- Fix: `Log.i` is present but auto-learned corrections should be `Log.w` so they show up in field log reviews. Better: refuse to auto-learn substitutions where the replacement length / case differs drastically.

### 3.4 MultiPassDecoder failure logged as warn, clarifier silently empty
- `BubbleService.kt:2599-2602` — `catch (e: Exception) { Log.w(TAG, "MultiPassDecoder failed: ${e.message} — clarifier proceeds with empty confidences") }`. Clarifier evaluator at `:2604` runs with empty wordConfidences and no flags fire. The differentiator feature silently disabled for that session.
- Fix: emit a stage-bar tick ("⟳ Cross-check skipped"), so the user has a visible signal multipass didn't run. Otherwise the absence-of-flag is invisible.

### 3.5 BubbleService.processAudioForTest swallows Whisper-fully-hallucinated into a test artifact only
- `BubbleService.kt:377-382` — debug-only, but the same handling at `:1743-1748` for the production path treats hallucination identically to silence. Combined with the WhisperClient hallucination-stripping at line 204, the user gets no clue why their mid-conversation utterance landed blank.
- Fix: same as 3.2 — distinct stage UI for hallucination vs silence.

### 3.6 BubbleService L1 contribution thread swallows POST failures
- `BubbleService.kt:1323` — `catch (_: Exception) {}`. Identical pattern at lines 2516-2518 logs the error; this one doesn't. Contributions silently dropped.
- Fix: `catch (e: Exception) { Log.w(TAG, "L1 contribution POST failed: ${e.message}") }`.

### 3.7 dispatchGesture onCancelled logs warning but caller is already gone
- `WimAccessibilityService.kt:623-625` — `onCancelled` warning lands in logcat with no context about why the user is still seeing "Sent!" feedback on the bubble. Diagnosing requires correlating the warning with the SEND_READY → IDLE transition timestamp.
- Fix: link the gesture to the SEND_READY click context (include the targetPkg in the cancel log), and have the cancel callback notify BubbleService so the UI can revert to "Send failed — tap to retry".

### 3.8 ProfileManager.startSync error handler logs but doesn't surface to UI
- `ProfileManager.kt:317-320` — Firestore listener error logs but the user has no signal that desktop-sync is broken. A wim-android user with a stale Lavrentiy profile has no clue their trigger words aren't refreshing.
- Fix: track last-sync timestamp, surface staleness in the Profile screen.

### 3.9 BubbleService L4 downgrade toast guarded forever
- `BubbleService.kt:84` `l4DowngradeNotified` is process-lifetime. Catches the first downgrade and **suppresses every subsequent one in the process**. User paid for Sonnet ET, getting GPT-4o silently after the first failure.
- Fix: log every downgrade (not just toast), reset guard daily or per N reconstructions.

### 3.10 Whisper API verbose_json wasFullyHallucinated detection misses silent-but-segmented case
- `WhisperClient.kt:202-205` — `wasFullyHallucinated = rawText.isNotBlank() && cleanText.isBlank()`. If Whisper returns rawText="" (empty top-level, segments present with no_speech_prob high), this returns `wasFullyHallucinated=false` and the caller treats it as a normal empty transcription.
- Fix: also flag-hallucinated when all `lowConfidenceSegments` have `blockSuspect=true` and rawText is empty.

---

## Notable areas that look correct

- `turboTaxJob` hoist + child-coroutine fix at `BubbleService.kt:2263-2319` — the comment block explicitly explains the parent-child relationship and the catch path. No regressions.
- `ProfileManager.loadProfile` corruption recovery at lines 101-113 — preserves the bad JSON before resetting.
- `AudioRecorder.captureLoop` write-failure path at lines 170-176 — sets isRecording=false to bail cleanly.
- `WhisperLocalEngine.start` model-corruption recovery at lines 102-122 — guarded by `redownloadTriggered` to prevent re-loop.
- `WhisperLocalModelDownloader` is the ONLY OkHttp consumer using `.use{}` correctly — should be the template for the others.
