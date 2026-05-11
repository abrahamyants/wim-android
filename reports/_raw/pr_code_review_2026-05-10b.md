# WiM Android — Comprehensive Code Review (2026-05-10b)

Scope: full-repo review of highest-risk files.

Files inspected:
- `app/src/main/java/com/wim/app/BubbleService.kt` (4313 lines)
- `app/src/main/java/com/wim/app/ReconstructClient.kt` (756 lines)
- `app/src/main/java/com/wim/app/MultiPassDecoder.kt` (175 lines)
- `app/src/main/java/com/wim/app/Clarifier.kt` (194 lines)
- `app/src/main/java/com/wim/app/ClarifierController.kt` (228 lines)
- `app/src/main/java/com/wim/app/PredictionEngine.kt` (149 lines)
- `app/src/main/java/com/wim/app/L1PackHelper.kt` (175 lines)
- `app/src/main/java/com/wim/app/WimAccessibilityService.kt` (718 lines)
- `app/src/main/java/com/wim/app/WimApp.kt` (287 lines)
- Supporting: `WhisperClient.kt`, `SpeechRecognizerL1.kt`, `BubbleState.kt`, `RecordingStateBus.kt`, `ProfileManager.kt`

---

## P0 — Critical (will produce wrong output / data corruption)

### P0-1. Clarifier word swap uses Whisper-text offsets applied to GPT-reconstructed text
**Files:** `BubbleService.kt:4055-4093`, `MultiPassDecoder.kt:117-173`, `Clarifier.kt:57-96`, `ClarifierController.kt:83-98`, `WimAccessibilityService.kt:354-401`

**Diagnosis:** `MultiPassDecoder.wordConfidencesFromPrimary` builds `WordConfidence.rangeStart/rangeEnd` as character offsets into `primary.text` — i.e., the Whisper transcription (raw audio re-transcribed three times). See MultiPassDecoder.kt:122 `val text = primary.text` and the docstring at lines 117-122: "Range offsets are character positions in the primary text."

`BubbleService.processText` at line 4074 then calls:
```kotlin
val flags = Clarifier.evaluate(
    finalText = outputText,            // GPT-4o RECONSTRUCTED text, ALREADY INJECTED
    wordConfidences = wordConfidences, // ranges into Whisper transcript text
    ...
)
```

`Clarifier.evaluate` passes those ranges straight through into the resulting `FlaggedWord`. `ClarifierController.approve()` then calls `WimAccessibilityService.swapWordInLastInjection(rangeStart=current.rangeStart, rangeEnd=current.rangeEnd, replacement=...)` — and `swapWordInLastInjection`'s contract (line 354) is that the ranges are "character offsets within the RECONSTRUCTION (the unpadded text passed to injectText)" — i.e., `outputText`, NOT the Whisper transcript.

**Impact:** The injected text is the GPT-reconstructed text (e.g., "I'm going to the store."). The clarifier swap targets offsets from a different string (e.g., "umm I'm going to teh stre"). When the user approves a flagged word, the swap targets the wrong characters and silently corrupts the injected text.

**Worked example:**
- Whisper output: `"umm I'm going to teh stre"` (25 chars). "teh" at offsets 17-20.
- GPT output: `"I'm going to the store."` (23 chars). "the" at offsets 13-16.
- Whisper transcript flags `teh` at range 17-20 with candidate `the`.
- User approves. `swapWordInLastInjection(17, 20, "the")` lands on `outputText`'s chars 17-20 = `" sto"`.
- Result: `"I'm going to thethere."`

Whenever the Whisper transcript and the reconstructed output diverge in length or word boundaries (which is the COMMON case at L2/L3/L4 — disfluency stripping, repunctuation, sentence restructure), the swap will corrupt the user's text.

**Fix:** Either (a) compute wordConfidences from the GPT-reconstructed output (re-tokenize `outputText` and re-attach low-conf signals via fuzzy/token-level alignment), (b) implement an alignment step (e.g., word-level Levenshtein) to map Whisper-text offsets to outputText offsets before populating FlaggedWord, or (c) reject `Clarifier.evaluate` calls where `finalText` differs from `primary.text` and gate the clarifier on raw Whisper output only. Option (b) is the patent-aligned answer (claim 11(e) talks about correlated word positions).

---

### P0-2. `MultiPassDecoder` token-index → primary-text-word-index alignment is fragile
**File:** `MultiPassDecoder.kt:79-90` + `MultiPassDecoder.kt:139-171`

**Diagnosis:** In `run()` at line 80, each pass is tokenized as `it.lowercase().split(Regex("\\s+")).filter { tok -> tok.any(Char::isLetterOrDigit) }` — i.e., punctuation-only tokens are FILTERED OUT. The `disagreementPositions` indices are positions in this filtered list.

In `wordConfidencesFromPrimary` at line 141-171, the primary text is walked with a plain whitespace-tokenizer (no filter), and `idx` increments on EVERY whitespace-separated token, including any pure-punctuation tokens that the filter would have dropped. So `variantsByIdx[idx]` can be off by 1 or more if the primary's text contains pure-punctuation tokens (rare but possible — e.g., `"hello , world"` if Whisper emits a standalone comma, or `"... no"` with a leading ellipsis token).

**Impact:** When a primary text contains a pure-punctuation token, all subsequent `variantsByIdx[idx]` lookups misalign, attaching disagreement counts and n-best alternates to the WRONG words. Compounds the P0-1 issue.

**Fix:** Tokenize `primary.text` in `wordConfidencesFromPrimary` with the SAME filter used to compute disagreementPositions, OR maintain a parallel index map.

---

## P1 — Important (silent failures, data loss, or significant UX regression)

### P1-1. ASR hallucination filter drops legitimate short utterances ("you", "ok", "no", "Hi", "yo")
**File:** `BubbleService.kt:1682-1696`

**Diagnosis:**
```kotlin
val isHallucination = trimmed.isBlank() ||
    trimmed.length <= 2 ||                        // ← drops "ok", "no", "Hi", "Yo", "I'm" (no), "to", "do", ...
    trimmed in setOf(
        "(", ")", "[", "]", "...", "(.)", "[.]",
        "[BLANK_AUDIO]", "[silence]", "[Silence]", "[blank_audio]",
        "Thank you.", "Thanks for watching.", "you", "You",   // ← "you" and "You" are extremely common
    ) ||
    trimmed.all { !it.isLetterOrDigit() }
```

`trimmed.length <= 2` discards every two-character word — "ok", "no", "Hi", "yo", "to", "do", "in", "on", etc. Even worse, `"you"` and `"You"` (length 3) are explicitly in the set, so the second-most-common pronoun in English is unconditionally treated as a hallucination.

**Impact:** Local engine final results for any of these utterances are dropped. The fallback path (`lastRawText` from partials) saves some cases when partials exist, but at L1 with whisper-tiny.en (which is the default) partials don't always fire reliably and certainly not at L4/L1-cloud where the wav path doesn't go through these callbacks. Users replying "ok" or "you" to a thread will see nothing pasted.

**Fix:** Replace the blanket `length <= 2` check with a real silence/single-char punctuation check. Remove `"you"` / `"You"` from the bare-string blacklist (Whisper hallucinates "Thank you" as a phrase, not "you" alone — the per-segment `noSpeechProb` from verbose_json is the right signal for these). The existing `whisperResult.wasFullyHallucinated` path already strips real hallucinations.

---

### P1-2. Concurrent recording can paste stale text into a live recording
**File:** `BubbleService.kt:1564-1764`, `BubbleService.kt:3768-4096`

**Diagnosis:** `stopAndProcess()` launches a coroutine (`scope.launch`) that does Whisper transcription + reconstruction + inject. While the suspend is in-flight, the user can tap again to start a new recording. `startListening()` checks `if (isRecording) return` — but `isRecording=false` after stop, so it succeeds. It resets `processed=false`, clears `lastRawText`, etc.

The original launch eventually resumes and lands at line 3907 `WimAccessibilityService.instance!!.injectText(outputText)` — pasting the OLD recording's reconstructed text. `onInjectComplete()` at line 1260 guards against UI overwrite (`if (isRecording) return`) but the inject itself has already fired and the host app's field has been clobbered.

**Impact:** During the long-running reconstruction window (3–10s typical, up to 70s for whisper.cpp at L4), if the user starts a new recording, the old recording's text overwrites the new utterance's field state. Especially painful at L4 where the wait is longest.

**Fix:** Cancel the in-flight `scope.launch` job from `startListening()` (track it as a Job ref). Before injectText at line 3907, double-check `if (!isRecording)` to bail. Alternatively, make `scope.launch` use a child SupervisorJob that gets cancelled in startListening.

---

### P1-3. ProfileManager mutates shared collections without synchronization
**File:** `ProfileManager.kt:321-379`, `ProfileManager.kt:44-46, 221-225`

**Diagnosis:** `mergeRemoteProfile()` runs on the Firestore listener thread (line 304 `addSnapshotListener`). It mutates `vocabulary`, `corrections`, `fillerWords` directly (lines 354, 361, 370). Meanwhile `getVocabulary()`, `getCorrections()`, `getFillerWords()` are called from `BubbleService.processText`'s coroutine (`Dispatchers.Main`) at lines 1310-1322, 3744-3766 and read these same collections.

`MutableList.toList()` and `MutableMap.toMap()` snapshots are not atomic — a concurrent mutation during snapshot can throw `ConcurrentModificationException`. Same for `corrections.forEach { (k, v) -> corr.put(k, v) }` in `ReconstructClient.reconstructViaBackend` line 148.

**Impact:** Rare-but-real crashes during simultaneous Firestore sync + active reconstruction. Hard to reproduce. Symptom: random crash mid-paste.

**Fix:** Wrap all reads/writes in synchronized blocks, or move the merge to the main thread (`Handler(Looper.getMainLooper()).post { mergeRemoteProfile(...) }`), or use `CopyOnWriteArrayList` / `ConcurrentHashMap`.

---

### P1-4. `MultiPassDecoder` triggered at L2/L3 makes 3 cloud Whisper calls AFTER inject — large latency
**File:** `BubbleService.kt:4055-4073`, `MultiPassDecoder.kt:21-25`

**Diagnosis:** At L2/L3 with `lastWavFile != null` (only true when AudioRecorder was started — see line 1653 gating), the clarifier check at line 4067 calls `MultiPassDecoder(WhisperApiL1).run(wav)` which fires 3 parallel calls to OpenAI Whisper-1. The user already saw the paste land at line 3907; they wait an additional ~3-6s before the clarifier dialog appears (since the 3 calls run in parallel via async/awaitAll but each is ~3-6s).

The cost note in `MultiPassDecoder.kt:21-25` claims "L1-L3 paths do NOT call this — too expensive". CLAUDE.md acknowledges this contradiction and says it's intentional ("MultiPassDecoder (triggered at L2+, not just L4 per Session 18 #107)"). But the cost-note docstring is now misleading, and the latency cost is real: at L2/L3 with cloud whisper-1 multi-pass, every reconstruction adds 3-6s of post-paste latency before clarifier flags appear. Many users will have already moved on.

**Impact:** Wasted Whisper API calls, slow clarifier appearance, stale docstring.

**Fix:** Either kick off MultiPassDecoder.run(wav) in PARALLEL with the GPT reconstruction (not sequentially after it), or move the multi-pass calls earlier (immediately after the first Whisper succeeds), so the cross-pass disagreement count is ready when reconstruction finishes. Update the MultiPassDecoder docstring. Consider gating to L4 only when latency budget matters more than the clarifier feature.

---

### P1-5. processTextForTest / processAudioForTest bypass the FSM matrix
**File:** `BubbleService.kt:317-379`, `BubbleService.kt:3615`

**Diagnosis:** Both debug-only test entry points feed text directly into `processText()` without first advancing the FSM through `Listening → Transcribing`. `processText` then calls `BubbleStateBus.advance(BubbleState.Stripping)` at line 3615. The FSM matrix in `BubbleState.kt:120-129` only permits `Listening → Transcribing` and `Transcribing → Stripping` — from `Idle`, only `Idle → Listening` is allowed. So `Idle → Stripping` is FORBIDDEN, the advance is silently rejected (`BubbleStateBus.advance` returns false and logs an error), and downstream FSM-driven UI (BubblePersona, BubbleAffect) never see the Reconstructing / Verified states.

**Impact:** Test results don't exercise the same FSM path as production. Tests may pass while production code paths fail. The 1248-case test matrix referenced in CLAUDE.md may be hiding real FSM bugs.

**Fix:** Add `BubbleStateBus.forceTo(BubbleState.Listening(System.currentTimeMillis()))` + `BubbleStateBus.advance(BubbleState.Transcribing(...))` to the top of `processTextForTest` / `processAudioForTest` so tests see the same matrix.

---

### P1-6. `swapWordInLastInjection` snapshot check is bypassed when ranges happen to land inside divergent text
**File:** `WimAccessibilityService.kt:354-401`

**Diagnosis:** The defensive check at lines 372-377 requires `current.substring(lastInjectStart, lastInjectEnd) == lastInjectedExact`. This only catches the case where the FIELD has been edited externally. It does NOT catch the P0-1 case where the offsets being applied are nonsensical because they came from a different source string. The substring check passes (the field still matches the original injection), and the swap silently produces garbage.

**Fix:** This is a downstream consequence of P0-1. Once P0-1 is fixed (alignment step), this is fine. But while P0-1 exists, add a length-bounds check: `if (rangeEnd > primaryTextLength) refuse swap`.

---

## P2 — Notable

### P2-1. `MultiPassDecoder.run` ignores `lowConfidenceSegments` when all passes agree on text — but logprob signal is dropped
**File:** `MultiPassDecoder.kt:69-77`

**Diagnosis:** When `texts.toSet().size == 1` (all three passes returned identical text), the short-circuit at line 70-77 returns `wordConfidencesFromPrimary(primary, IntArray(0), emptyList())`. That helper still consults `primary.lowConfidenceSegments` for asrLogprob, so logprob signal IS preserved. Verified correct. Move on.

### P2-2. `passes.size < 2` early-return drops asrLogprob signal too
**File:** `MultiPassDecoder.kt:59-66`

**Diagnosis:** When `passes.size < 2` (only one temperature supplied or only one succeeded — rare since async/awaitAll fails atomically), `wordConfidences = emptyList()` despite `primary.lowConfidenceSegments` potentially having signal. Asymmetric with the all-agree path which DOES populate confidences. Effectively prevents single-pass clarification.

**Fix:** Build wordConfidences from primary even in the single-pass case, passing `IntArray(0)` / `emptyList()` for disagreements.

### P2-3. New OkHttpClient instances per call
**File:** `BubbleService.kt:3487-3489, 3518`

**Diagnosis:** `callBackendCommandTransform` and `callOpenAiTransform` each construct a new `OkHttpClient` per call. OkHttp's connection pool and dispatcher thread pool are not shared. Wasteful — `OkHttpClient` is documented as a shared singleton.

**Fix:** Move the OkHttpClient to a top-level `private val` in BubbleService, or reuse `WhisperClient.http` / `ReconstructClient.http`.

### P2-4. `MainActivity.onSessionsSynced` static reference held forever
**File:** `WimApp.kt:24`, `MainActivity.kt:218`

**Diagnosis:** `WimApp.onSessionsSynced = { loadSessions() }` captures `this` (MainActivity reference) and is never cleared on MainActivity destruction. The static field keeps the activity alive after the user navigates away.

**Fix:** Set `WimApp.onSessionsSynced = null` in MainActivity.onDestroy(), or use a WeakReference, or wire through a lifecycle-aware observer.

### P2-5. `dockSlotRects` static reference can leak Activity-context drawables / rect refs
**File:** `BubbleService.kt:224`

**Diagnosis:** `var dockSlotRects: Map<String, android.graphics.Rect>?` is a companion-object var. The Rect itself doesn't hold context, but the comment at line 232 says "Cleared on MainActivity.onPause" — verify in MainActivity that this is actually nulled. Same for `onBubbleVisibilityChanged`.

### P2-6. Stale doc comment in `MultiPassDecoder`
**File:** `MultiPassDecoder.kt:21-25`

**Diagnosis:** Says "L1-L3 paths do NOT call this — too expensive". Current code (BubbleService.kt:4059) calls it at L2+. Update the docstring to match implementation.

### P2-7. `ReconstructClient.reconstructWithSonnet` not wrapped in `withContext(Dispatchers.IO)`
**File:** `ReconstructClient.kt:699-737`

**Diagnosis:** The function is `private fun` (not `suspend fun`), and the caller at line 589 is inside `withContext(Dispatchers.IO)` already, so the synchronous HTTP call happens on IO. Verified safe. (Was looking for a missing dispatcher issue — false alarm.)

### P2-8. Hallucination patterns regex performance edge case
**File:** `WhisperClient.kt:47, 49-50, 91, 93-94`

**Diagnosis:** Patterns like `"transcribed by https://otter.ai[^.]*"` and `"powered by[^.]{0,30}"` use `[^.]*` greedy match. With pre-compiled regexes (lazy init at line 106-108), the cost is amortized, but on degenerate input (no period in the entire text), `[^.]*` can match the whole tail. Bounded variants use `{0,30}` or `{0,40}` which limit this — but `[^.]*` (unbounded) at line 47 doesn't. Minor.

### P2-9. `Repunctuate.apply` called after corrections but before FSM Verified state
**File:** `BubbleService.kt:3679-3683`

**Diagnosis:** L1 path applies Repunctuate at 3679, then advances FSM to Verified. The order looks correct. No issue.

### P2-10. `recentSwaps` order in feedback loop
**File:** `Clarifier.kt:68-69`

**Diagnosis:** `recentSwaps.filter { it.second.isBlank() }.map { it.first.lowercase() }.toSet()` only flags as "rejected" when `second` (replacement) is blank. In ClarifierController.reject (line 107), every candidate in `current.candidates` is recorded with replacement "". So all candidates get added to rejected set. Approve at line 95 records with non-blank replacement. The rejected set captures only rejections. OK.

---

## P3 — Minor / nitpicks

### P3-1. `processed` flag not volatile despite touch handler races
**File:** `BubbleService.kt:110`

`processed` is mutated from main thread only (stopAndProcess + startListening), so no volatile needed. Verified. (Was checking for cross-thread access.)

### P3-2. `BubbleService.runFeatureParade` idempotency check is fine
**File:** `BubbleService.kt:3338-3350`

`if (activeDemoScript != null) return` is fine. No issue.

### P3-3. `BUILTIN_KEYS` contains "en" but `loadPack("en")` returns null
**File:** `L1PackHelper.kt:170-174`

When iterating `BUILTIN_KEYS` for UI display, the "en" entry will always fail to load. Minor — likely intentional (English = no pack = no override).

### P3-4. Unbounded `corrections.putIfAbsent` in mergeRemoteProfile
**File:** `ProfileManager.kt:361`

Remote corrections are merged via putIfAbsent. No cap. Could grow large if Lavrentiy desktop accumulates many corrections. Minor.

### P3-5. `WimApp.bundledKey` returns empty in release builds — intentional
**File:** `WimApp.kt:32-39`

Defensive — release builds don't ship bundled keys. Confirmed safe.

### P3-6. `BluetoothSco` SCO connection start is fire-and-forget without confirming AudioFocus
**File:** `BubbleService.kt:482-491`

Best-effort SCO start. If it fails, `try/catch` swallows. Minor — user can re-pair manually.

### P3-7. `disclaimerView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }` in dismissSendDisclaimer
**File:** `BubbleService.kt:1512-1516`

Sets `disclaimerView = null` only after the remove call. If `removeView` throws, the field is still set to null on line 1517. OK.

### P3-8. `recordPredictionsFromOutput` doesn't lowercase the next-word
**File:** `BubbleService.kt:3380, 3384`, `PredictionEngine.kt:104-118`

`nextClean` keeps original case. `PredictionEngine.recordCompletion` stores it as-is. `candidatesForPosition` returns them as-is. So suggestions preserve case. Probably intentional — but worth noting that the trigram lookup is lowercased while the candidates aren't.

### P3-9. Variable shadowing in `Clarifier.evaluate` is confusing but correct
**File:** `Clarifier.kt:71-90`

Outer `val candidates: List<FlaggedWord>` shadowed by inner `val candidates: List<String>` within `mapIndexedNotNull`. Kotlin scope rules make this OK but it's hard to read. Consider renaming the inner one to `candidateStrings`.

### P3-10. Auto-send disclaimer count bypass at 99
**File:** `BubbleService.kt:1417-1423`

Comment notes "BouncerActivity's pageAutoSend acknowledgment row sets auto_send_disclaimer_count to 99". This is an implicit magic value; no constant. Minor.

### P3-11. `dockSlotRects` iteration uses `for ((_, rect) in rects)` discarding theme
**File:** `BubbleService.kt:843-854`

Magnetic pull iterates rects but discards the theme key. Then later `checkDockSnap` re-iterates with theme. Two passes. Minor perf — not worth fixing.

---

## Notable correctness wins observed

- `BubbleStateBus.advance` returns false on forbidden transitions instead of throwing — good resilience for a recording service.
- `swapWordInLastInjection` has a snapshot check to refuse edits when the field has been modified externally — protects user edits.
- `WimAccessibilityService.findFocusedEditableAcrossWindows` walks all windows + falls back to recursive descent — robust against the Gmail-recycles-tree bug from Session 13.
- `Repunctuate.apply` is idempotent — safe to call on already-punctuated text.
- AudioRecorder pre-roll is released before hotword listener acquires the mic (line 2167-2171) — explicit fix for the dead-recognizer bug.
- `MultiPassDecoder.run` uses `async/awaitAll` for parallel Whisper passes (line 51-56) — correct concurrency primitive.

---

## Recommendations (ranked)

1. **P0-1 fix is load-bearing** for the clarifier feature. Either fix the offset alignment OR scope the clarifier to L1 (where Whisper text IS the injected text) until alignment lands.
2. **P1-1** can be fixed in ~5 lines (`trimmed.length <= 1`, remove "you"/"You" from blacklist).
3. **P1-2** — make `processText`'s `scope.launch` cancellable from `startListening`. Track the Job.
4. **P1-3** — synchronize ProfileManager mutations. Lowest-effort fix is `Handler(Looper.getMainLooper()).post { mergeRemoteProfile(...) }` to keep everything on main thread.
5. **P1-4** — kick off MultiPassDecoder in parallel with reconstruction so total latency = max(recon, multipass) rather than sum.

---

End of review. file:line citations throughout for traceability.
