# 06 — Code Reviewer (general quality + production reliability)

**Date**: 2026-05-29
**Repo**: `wim-android` (Lavrentiy intentionally out of scope)
**HEAD**: `49b50a6` `feat(bubble): relay injected text to Termux:3133 phone bridge`
**Baseline**: `DEEP_REVIEW_2026-05-12.md` + README `2026-05-12 to 2026-05-13` punch list

---

## TL;DR

BubbleService **shrank from 4310 → 2715 lines** across 18 refactor slices (`cd713e3` first, `d831944` last). The slices genuinely decoupled — concrete state moved into the slice classes, BubbleService holds a `var slice: BubbleSlice? = null` and the touch/lifecycle code calls into `slice?.method()`. Five slices have unit tests pinning observable behavior (`BubbleStateBusTest`, `BubbleDockTest`, `BubbleTwistControllerTest`, `MultiPassDecoderTest`, `PipelineAnalyticsTest`).

8 of the 13 open P1s from the 5/13 punch list are **FIXED** (worst-wins, ProfileManager concurrency, ET extinction, hallucination whitelist, clipboard fallback, WakeLock, turboTaxJob structural cancel, ProfileManager + L4 + clarifier sensitivity migration). 5 are **STILL UNFIXED** — most consequentially the **per-call OkHttpClient leak in `BubbleCommandMode`**, **legacy + FSM clarifier coexistence**, and **`showBubble` `addView` lacks try/catch** so a permission-revoke race crashes the service.

Three **NEW reliability bugs** introduced after 5/13:

1. **`BubbleCommandMode` makes both HTTP paths leak thread pools** — every Command-Mode utterance constructs a fresh `OkHttpClient.Builder().build()`. The exact P1 the 5/13 review flagged in BubbleService was **moved into the new slice without fixing it**, then doubled (two builders now instead of one). `BubbleCommandMode.kt:227, 260`.
2. **Two raw `java.lang.Thread { … }.start()` calls in BubbleService** for the L1-contrib and L1-detect HTTP posts — `BubbleService.kt:1301`, `BubbleService.kt:2470`. Thread-per-call, no executor, no backpressure. Pre-existed but neither was migrated when the surrounding code was sliced.
3. **`fixWavHeader` / `AudioRecorder.start` lack try/finally around `RandomAccessFile` and `FileOutputStream`** — `AudioRecorder.kt:101-119, 211-237`. Any throw between `seek(0)` and `close()` leaks the fd; under EBUSY / disk-full / ENOENT this fires under field conditions.

---

## (1) Newly introduced reliability bugs since 2026-05-13

### N1 — `BubbleCommandMode` doubles the OkHttp-per-call leak — HIGH

**File:line**: `BubbleCommandMode.kt:227` (backend path) and `:260` (direct-OpenAI path).

The 5/13 P1 was: BubbleService creates a fresh `OkHttpClient` per command-transform; thread pools accumulate. The 5/25 slice extracted command-mode into `BubbleCommandMode` and **kept both code paths exactly as they were** — `OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()` runs once per `processCommand()`, twice if both branches are exercised. Every other HTTP client in the codebase (`ReconstructClient.http`, `WhisperClient.http`, `UserDataClient.http`, `WhisperLocalModelDownloader.http`) is a singleton at file scope. This one alone leaks.

**Fix**: Hoist to a `companion object`:

```kotlin
private companion object {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
```

Then both `callBackendCommandTransform` and `callOpenAiTransform` use `httpClient.newCall(req)`. Five-line change.

### N2 — Raw `Thread {}.start()` for fire-and-forget HTTP — MEDIUM

**File:line**: `BubbleService.kt:1301-1324` (L1-contrib regen path); `BubbleService.kt:2470-2519` (L1-detect on session #5).

Both spawn a fresh `java.lang.Thread` per call. The L1-detect path is gated by a "prompted once" pref, so worst case 1 thread per uid. The L1-contrib path fires on every regen for users who opted in — could be 20+ per session. Neither uses `WimApp.appScope` (the existing `Dispatchers.IO` scope that exists specifically for this).

**Fix**: Replace each `Thread { … }.start()` with `WimApp.instance.appScope.launch(Dispatchers.IO) { … }`. Same fire-and-forget semantics, no thread-per-call, exceptions surface through the appScope SupervisorJob.

### N3 — `AudioRecorder.start` + `fixWavHeader` leak fds on exception — MEDIUM

**File:line**: `AudioRecorder.kt:101-119` and `:211-237`.

`start()` opens a `FileOutputStream`, writes the 44-byte WAV header, dumps the pre-roll buffer, then assigns to `outputStream`. If `applyQuietModeProcessing` or `fos.write` throws after `FileOutputStream(file)` opens but before `outputStream = fos`, the fd is unreachable and leaks. Same pattern in `fixWavHeader`: `RandomAccessFile(file, "rw")` → `seek(0)` → `write(header.array())` → `close()`. Throw between `seek` and `close` (disk full, file deleted underneath us) leaks an fd per call.

**Fix**: Wrap both in `.use { … }`:

```kotlin
RandomAccessFile(file, "rw").use { raf ->
    raf.seek(0)
    raf.write(header.array())
}
```

For `start()`, capture `outputStream = fos` and put the pre-roll dump in a `try { … } catch (e: Exception) { fos.close(); outputStream = null; throw }`.

### N4 — `processTextForTest` `isRecording` check races handler post — LOW (DEBUG-only)

**File:line**: `BubbleService.kt:339-347`.

`if (isRecording) return` then `Handler.post { processText(rawText) }` — the recording state can change between the check and the dispatched task. Also at 342 it zeroes `lastWhisperLowConf` while a real recording's handleFinal may still be writing to it. `BuildConfig.DEBUG`-gated so the production blast radius is bounded; the `WimTestReceiver` infrastructure exercises this exact path on the Z Flip 7 test rig.

**Fix**: Move the `if (isRecording) return` inside the `Handler.post { }` block. Move `lastWhisperLowConf = emptyList()` likewise.

### N5 — `relayToTermux` swallows all network exceptions silently — LOW

**File:line**: `BubbleService.kt:1892-1906`.

Empty `catch (_: Exception)`. Phone-bridge relay failure is unlogged so a user wondering why their Termux session isn't getting the dictation has nothing in logcat. Trivial to add `Log.v` so test rigs can see failures without polluting prod logs.

### N6 — `bubbleParams ?: WindowManager.LayoutParams(...)` reuses stale params on re-show — LOW

**File:line**: `BubbleService.kt:684`.

`showBubble` does `val params = bubbleParams ?: WindowManager.LayoutParams(...)`. On the second `showBubble` after a `hideBubble`, `bubbleParams` is non-null (it survives `hideBubble` — only the view is removed), so spawn-position logic at 700-706 is skipped and the bubble pops up wherever it was last time. Intentional for re-show after hide; but it also means a saved x/y from a screen rotation can land off-screen and the clamp at 859-866 fires only on drag. Not a crash, just a layout edge case.

---

## (2) P1 items from 5/13 — status

### Fixed (8)

| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | `MultiPassDecoder` last-wins → worst-wins | **FIXED** | `MultiPassDecoder.kt:134-142` — explicit `groupBy({first}, {second}).mapValues { lp.min() }` with comment "WORST-wins" |
| 2 | `ProfileManager` synchronization | **FIXED** | `ProfileManager.kt:52-63` — `CopyOnWriteArrayList` + `ConcurrentHashMap` throughout. Firestore listener + reconstruction reads are now thread-safe by construction |
| 3 | Hallucination filter `"ok"`/`"no"`/`"you"` whitelist | **FIXED** | `BubbleService.kt:1582-1589` — drops only single-char or explicit phrase blacklist; comment explicitly notes the `length <= 2` was over-aggressive |
| 4 | Clarifier offset misalignment (P0 #3 from 5/12) | **FIXED via alignment** | `ClarifierAlignment.kt` — left-to-right exact-token re-mapper; called at `BubbleService.kt:2598`. Pre-inject reorder NOT done; alignment substitutes |
| 5 | Anthropic ET extinction fallback to GPT-4o | **FIXED** | `ReconstructClient.kt:606-616` — `try reconstructWithSonnet catch fall to reconstructWithGpt4o`. `downgradedFromL4=true` propagates to one-time Toast at `BubbleService.kt:1280-1284, 2395-2399` |
| 6 | Clarifier sensitivity `"off"` sticky migration | **FIXED** | `WimApp.kt:214-219` — one-time shim flips `"off"` → `"medium"` and stamps `clarifier_sensitivity_migrated` |
| 7 | `PARTIAL_WAKE_LOCK` during L4 reconstruction | **FIXED** | `BubbleService.kt:2271-2279` — acquired in coroutine, 120s timeout, released in `finally` at 2670-2672 |
| 8 | `turboTaxJob` structural cancellation | **FIXED** | `BubbleService.kt:2260-2319` — `launch { … }` (no `scope.` prefix) makes it a child of `currentReconJob`, structural-cancel by parent. Comment 2291-2296 explicit on the mechanism |
| 9 | Bluetooth SCO receiver unregister | **FIXED** | `BubbleBluetoothSco.kt:78-83` + `BubbleService.kt:2710` — unregistered in `stop()` called from `onDestroy` |
| 10 | AudioPlaybackCallback unregister | **FIXED** | `BubbleService.kt:2709` — `audioManager?.unregisterAudioPlaybackCallback(it)` |
| 11 | Clipboard fallback on focus drop | **FIXED** | `WimAccessibilityService.kt:248-255` — clipboard write + Toast when no field re-found |
| 12 | Hardcoded `"en"` language code | **FIXED** | `BubbleService.kt:2333, 2390, 2442` — `WimApp.appLanguage()` propagated |

### Still open (5)

| # | Item | Severity | File:line | Recommended action |
|---|---|---|---|---|
| 13 | `OkHttpClient` per command-transform | **HIGH** | `BubbleCommandMode.kt:227, 260` | Hoist to companion-object singleton. See N1 above |
| 14 | Legacy `clarifierUI` + FSM `BubbleStateBus.Clarifying` can both be active | **MEDIUM** | `BubbleService.kt:1009 / 1019` (touch handler checks both) | Decide which is canonical: the FSM clarifier (`BubbleState.Clarifying`) is the documented patent path; the legacy `BubbleClarifierUI.show()` is the work/life toggle. Rename `BubbleClarifierUI` → `BubbleConfirmPrompt` (no business logic conflict, just naming clarity); state that the work/life confirm is NOT a clarifier in the comment block |
| 15 | `showBubble` `addView` lacks try/catch | **MEDIUM** | `BubbleService.kt:1158` | One-line fix: `try { windowManager?.addView(bubbleView, params) } catch (e: WindowManager.BadTokenException) { Log.e(TAG, "addView failed — overlay revoked?"); bubbleView = null; return }`. The 658 `canDrawOverlays` check has a TOCTOU race with permission revocation |
| 16 | `Clarifier.evaluate` allocates `vocabulary.map { it.lowercase() }` per word | **LOW** | `Clarifier.kt:126` | Hoist outside `mapIndexedNotNull` loop: `val vocabLower = vocabulary.map { it.lowercase() }.toSet()` once at evaluate entry, then `wc.word.lowercase() !in vocabLower`. ~6000 string allocs collapse to 1 collection alloc |
| 17 | `ClarifierController.approve()` advances cycle on failed swap | **LOW** | `ClarifierController.kt:83-98` | Guard `next()` with `if (ok) next() else dismissAll()` — failed swap means the host app rejected ACTION_SET_TEXT (focus lost, field re-created), and continuing through more words is pointless |

### Architecturally still open (not P1 — tracked from 5/12)

- `forceTo()` still exists at `BubbleState.kt:101-103`; called only from `triggerClarifierForTest` (debug-gated). `reset()` at `:93-95` IS called from production in `BubbleService.setBubbleIdle` line 1830 — bypasses transition matrix on every recording teardown. Architecture-critic finding holds, no action this pass.
- `RecordingSession.kt` extraction NOT done. `currentReconJob` field (`BubbleService.kt:80`) and structural-cancel pattern delivered the bleeding-bug subset (stale paste race, orphan turboTaxJob) without the ~600 LOC class extraction. Pragmatic — the harder follow-ups (clarifier-vs-recon legacy, command-mode-vs-recon) still need the full extraction.
- 8 mutable booleans parallel to FSM (`isRecording`, `holdModeActive`, `facehuggerActive`, `processed`, `isRegenInFlight`, `isSendReady`, `commandMode.armed`, `clarifierUI.isActive`) unchanged. Each is a potential source-of-truth divergence with `BubbleStateBus`.

---

## (3) Code-quality nits worth a sweep

### Q1 — Crashlytics is a single CoroutineExceptionHandler; no breadcrumbs at decision points

**File:line**: `BubbleService.kt:205-213` is the only `FirebaseCrashlytics` site in the entire `app/src/main/java/com/wim/app/` tree (verified by grep across all .kt). When L4 falls back to GPT-4o (`ReconstructClient.kt:610-612`), when Whisper hallucinates the whole utterance (`BubbleService.kt:1743-1747`), when WiM Accessibility Service is null mid-inject (`BubbleService.kt:2449-2451`), when `addView` would throw (after fix N1) — none of these record breadcrumbs. Production users hitting these paths give Crashlytics nothing to reconstruct the failure from.

**Fix**: Add `FirebaseCrashlytics.getInstance().log("L4→GPT-4o downgrade: ${e.message?.take(120)}")` at each decision point. ~6 sites identified above. Half-hour sweep.

### Q2 — `BubbleStateBus.reset()` is a production bypass of the FSM

**File:line**: `BubbleState.kt:88-95`, called from `BubbleService.kt:1830` (`setBubbleIdle`). Comment says "lifecycle teardown / onDestroy paths"; in practice `setBubbleIdle` runs on every successful Inject, every Failed-state recovery, every clarifier exit. That's not teardown — that's the normal success path silently bypassing the transition matrix.

**Fix**: Either (a) add `Idle` as a legal target from every state in `isValidTransition` and call `advance(Idle)`, OR (b) keep `reset()` but rename to `slamToIdle()` so the call site visibly admits the bypass. Architecturally (a); pragmatically (b).

### Q3 — Two `OkHttp` calls in `BubbleService.kt` (Termux + L1-contrib HTTP) use `HttpURLConnection` instead of the shared OkHttp singleton

**File:line**: `BubbleService.kt:1310-1324` and `BubbleService.kt:2475-2519`. Codebase has 4 file-scope OkHttp singletons; these two paths reinvent the wheel with raw `URL.openConnection()`. Inconsistent and harder to thread through later (cert pinning, future shared retry logic).

**Fix**: Route both through `ReconstructClient.http` (visibility-broaden it) or a dedicated `WimHttp` object. Same fire-and-forget semantics, one less HTTP stack to maintain.

### Q4 — `BubbleService.kt:1582-1589` hallucination filter — magic-string set rebuilt per call

**File:line**: `BubbleService.kt:1582-1588`. The `setOf(...)` literal is reconstructed every ASR finalization. Not a hotspot (one set alloc per recording) but easy to hoist to a `private companion val HALLUCINATION_PHRASES = setOf(...)`.

### Q5 — `WimAccessibilityService` `findFocusedEditableAcrossWindows` returns a node without recycling sibling/ancestor nodes

**File:line**: `WimAccessibilityService.kt:479-490`. Caller stores the returned node in `activeTextField` (now a static var on the companion). When `activeTextField` is replaced on the next call, the prior node is not recycled. AccessibilityNodeInfo recycling is deprecated in API 33+ so on modern devices this is no-op; on API 28-32 still a slow node-pool leak.

**Fix**: Before assignment of new `activeTextField`, recycle the old: `activeTextField?.recycle(); activeTextField = newNode`. The framework hands you a recycle-tracked node; failing to recycle counts against the per-process node pool until GC.

### Q6 — `BubbleService.kt:166-169` `deferredSingleTapAction` reads `isRecording` from `bubbleView`'s touch listener closure scope, but `isRecording` is a `var` field on the enclosing service — Kotlin captures by reference. Fine. But the comment at 162-163 says "Re-checks isRecording / isDragging" — `isDragging` is local to the touch listener block (line 714) and NOT in the runnable's scope. Dead comment.

### Q7 — `BubbleService.kt:43` `BubbleService` is still 2715 lines. The 18 slices got it under the 3000-line "service" threshold but the inline `setOnTouchListener` block (724-1124, 400 lines of touch handling) is the next slice candidate.

**Recommendation**: `BubbleTouchHandler` extract — gestures (single/double-tap, hold, drag, taffy stretch, dock-snap, magnet pull, send-confirm, regen tap, clarifier tap-left/tap-right, deferred-tap). Matches the architecture-critic's `BubbleTouchController` proposal from 5/12 #18.

### Q8 — `MainActivity.kt` is now the largest file at 3207 lines (BubbleService was, until refactor). UI plumbing, dock state, sessions list, console, settings panels, sign-in. Same god-class shape that BubbleService had a month ago. Worth flagging for the next refactor sprint.

### Q9 — `BubbleService.kt:74` `@Volatile lastWavFile` plus the in-flight cleanup at `:1703` (`?.delete()` before reassign) + `:1834` (`?.delete()` on idle) — there's still a window where MultiPassDecoder is mid-`run(wav)` (`MultiPassDecoder.kt:51-107`) and a concurrent `stopAndProcess` from a new recording deletes the file under it. Whisper-1 reads bytes async; the delete will succeed on most filesystems (file becomes unlinked but still open via fd) but the WAV header `RandomAccessFile` cycle in `fixWavHeader` then writes to the unlinked fd successfully — confusing logs. Move multipass off the shared `lastWavFile` reference: copy `val wav = lastWavFile` inside the `currentReconJob.launch` block (already does at 2362), but ALSO snapshot the path so a later idle/cleanup can't race with the in-flight read. Current snapshot at 2362 is sufficient as long as `await()` blocks before `setBubbleIdle()` runs. The `multiPassDeferred?.cancel()` at the catch path is correct.

### Q10 — `Clarifier.kt:67` `recentSwaps.filter { it.second.isBlank() }.map { it.first.lowercase() }.toSet()` recomputes every `evaluate()` call. List of 12 max — fine.

### Q11 — Tests cover state-bus + dock + twist + multipass + pipeline analytics + L1 pipeline + L2 corrections stage + reconstruction guard + profile manager + profile learning + clarifier. Coverage is on the unit-testable pure-data parts. **BubbleService itself remains untested** — the FSM-integrated paths (process flow, clarifier handoff, scope cancellation, wake-lock acquire/release) need an instrumented test rig (or a `RecordingSession` extraction that isolates the orchestration logic). The 5/13 punch list called this out; partial progress via the slices, none directly on BubbleService.

---

## What I did not check this pass

- e2e on the Z Flip 7 (operator-side; the audit task explicitly said "static review").
- Backend (`wim-reconstruct` Cloud Function) — Lavrentiy/server-side review out of scope.
- Full architecture refactor to `RecordingSession` / `BubbleUiController` / `BubbleTouchController` — flagged 5/12, partial via `currentReconJob` field. Sprint-sized.

---

## Recommended fix order (~3 hours total for Tier 1)

**Tier 1 — bleeding bugs (under 30 min each):**
1. N1: `BubbleCommandMode` OkHttp singleton. ~5 min.
2. P1 #15: `showBubble` try/catch around `addView`. ~5 min.
3. P1 #16: `Clarifier.evaluate` vocab hoist. ~5 min.
4. P1 #17: `ClarifierController.approve()` guard. ~5 min.
5. N3: `AudioRecorder.fixWavHeader` `.use {}`. ~10 min.
6. Q1: Crashlytics breadcrumbs at 6 decision points. ~30 min.

**Tier 2 — hygiene (under 2 hours):**
7. P1 #14: Rename `BubbleClarifierUI` → `BubbleConfirmPrompt`. ~15 min.
8. N2: Two raw `Thread {}` → `appScope.launch`. ~10 min.
9. Q2: Rename `BubbleStateBus.reset()` → `slamToIdle()`. ~5 min.
10. Q3: Route Termux + L1-contrib HTTP through shared OkHttp. ~30 min.
11. Q4: Hallucination set hoisted to companion. ~5 min.
12. Q5: AccessibilityNodeInfo recycle hygiene. ~15 min.

**Tier 3 — architecture sprint (deferred from 5/12):**
13. `RecordingSession` extraction.
14. `BubbleTouchHandler` extract (Q7).
15. `MainActivity` decomposition (Q8).

---

*Verified against HEAD `49b50a6`. All file:line references re-checked at commit time.*
