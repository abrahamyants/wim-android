# wim-android code review — 2026-05-10

Files reviewed:
- C:\Users\georg\Documents\GitHub\wim-android\app\src\main\java\com\wim\app\BubbleService.kt (4126 lines)
- C:\Users\georg\Documents\GitHub\wim-android\app\src\main\java\com\wim\app\Clarifier.kt
- C:\Users\georg\Documents\GitHub\wim-android\app\src\main\java\com\wim\app\ClarifierController.kt
- C:\Users\georg\Documents\GitHub\wim-android\app\src\main\java\com\wim\app\PredictionEngine.kt
- C:\Users\georg\Documents\GitHub\wim-android\app\src\main\java\com\wim\app\MultiPassDecoder.kt

Confirmation on parallel-reviewer findings:
- CR-04 (ClarifierController unsynchronized state): NOT a real issue — all callers run on Dispatchers.Main, BubbleService.scope is Main, and accessibility callbacks dispatch through main handler. Single-threaded by construction. Confidence 70 — not reporting.
- CR-13 (PredictionEngine prunes by insertion order): CONFIRMED, see CRITICAL-1.
- CR-12 ("<END>" magic-string sentinel): CONFIRMED low-impact, see IMPORTANT-3.
- HIGH-6 (scope.launch blocks lack CoroutineExceptionHandler): the SupervisorJob protects sibling coroutines from cancellation, but uncaught exceptions in unwrapped blocks crash the JVM — see CRITICAL-3 for one specific blast radius.

## CRITICAL (90-100)

### CRITICAL-1: triggerClarifierForTest hits FORBIDDEN state transitions, demo + test paths silently desync (confidence 95)

**File:** BubbleService.kt:243-249, also exercised at BubbleDemoScript.kt:218 and WimTestReceiver.kt:581
**Architecture rule violated:** Forbidden BubbleState transitions are logged but not thrown — they advance silently to no effect.

```kotlin
fun triggerClarifierForTest(flags: List<FlaggedWord>) {
    if (!BuildConfig.DEBUG) return
    // Force-set FSM into Injected so the Injected→Clarifying transition
    // is legal per spec §1 matrix.
    BubbleStateBus.advance(BubbleState.Injected(System.currentTimeMillis(), SuccessCue.PASTE))
    enterClarifierCycle(flags, autoSendEligible = false)
}
```

The comment claims `Injected→Clarifying` is legal — true. But it ignores that getting INTO `Injected` is itself constrained. Per BubbleState.kt:113-116:

```kotlin
is BubbleState.Idle -> when (to) {
    is BubbleState.Listening -> true
    else -> false
}
```

`Idle → Injected` is FORBIDDEN. The only legal path to `Injected` is `Verified → Injected`. So when `triggerClarifierForTest` is invoked from the demo script (BubbleDemoScript at 4500ms after onboarding starts) or from `adb am broadcast` test fixtures, the bubble is typically in `Idle` state. `BubbleStateBus.advance` logs an error and returns false. Then `enterClarifierCycle` calls `ClarifierController.enter` which calls `BubbleStateBus.advance(BubbleState.Clarifying(...))` — also forbidden from `Idle`, also silently rejected.

The result: `ClarifierController.flagged` is populated with test flags, `armAutoDismiss` posts the 3s timer, `paintClarifierLabel` runs, the bubble's label changes to "DUNKEN?" — but `BubbleStateBus.current()` is STILL `Idle`. The tap handler at BubbleService.kt:1031 checks `if (BubbleStateBus.current() is BubbleState.Clarifying)` to route approve/reject. That check is false. The user sees the clarifier UI but cannot approve, reject, or skip via tap. Voice command router at line 425 also gates on FSM state — also broken. Auto-dismiss fires after 3s, "Dunken?" disappears, demo continues to the next stage, and the user has just watched a non-functional demo.

**Fix:** Use `BubbleStateBus.reset()` (force-set, no validation) to land at Idle, then advance through the legal chain — or add a `forceSet` API specifically for test/demo entry. Quickest patch:

```kotlin
fun triggerClarifierForTest(flags: List<FlaggedWord>) {
    if (!BuildConfig.DEBUG) return
    // Walk the legal transition chain from any starting state.
    BubbleStateBus.reset()
    BubbleStateBus.advance(BubbleState.Listening(System.currentTimeMillis()))
    BubbleStateBus.advance(BubbleState.Transcribing(System.currentTimeMillis()))
    BubbleStateBus.advance(BubbleState.Stripping(System.currentTimeMillis()))
    BubbleStateBus.advance(BubbleState.Reconstructing(System.currentTimeMillis(), 2))
    BubbleStateBus.advance(BubbleState.Verified(System.currentTimeMillis()))
    BubbleStateBus.advance(BubbleState.Injected(System.currentTimeMillis(), SuccessCue.PASTE))
    enterClarifierCycle(flags, autoSendEligible = false)
}
```

Or expose a debug-only `BubbleStateBus.forceTo(state)` and skip the chain.

This is a Phase-2 EARLIEST DEMO MILESTONE feature (per Clarifier.kt:21 docstring) — the demo is the product. A non-tappable demo is a marketing hole, not just a test bug.

---

### CRITICAL-2: PredictionEngine.recordCompletion drops the just-written entry on overflow (confidence 92)

**File:** PredictionEngine.kt:115-118

```kotlin
val pruned = if (history.size > MAX_HISTORY_PER_PKG) {
    history.entries.take(MAX_HISTORY_PER_PKG).associate { it.toPair() }
} else history
writeHistory(pkg, pruned)
```

`history` is a `Map<String, List<String>>` reconstructed from `JSONObject` via `readHistory`. JSONObject keys() preserves insertion order on Android. `readHistory` builds the in-memory map in JSON-file order (oldest → newest). The just-added entry was inserted via `history[priorTrigram] = list` (line 113), which either updates in place or appends at end (LinkedHashMap semantics, which is what JSONObject backs onto on Android API 23+).

`history.entries.take(MAX_HISTORY_PER_PKG)` therefore takes the OLDEST 64 entries and discards everything after. If the just-recorded trigram is new and the map was already at capacity, the new entry sits at position 65 and gets pruned immediately. `writeHistory(pkg, pruned)` persists the stale state. The user's most recent sentence-boundary completion is silently lost.

This also means once the per-pkg cache hits 64, it FREEZES — no further learning ever lands, because every new trigram gets pruned out before persistence. The "your bubble learned how you talk to your boss in Slack" claim (PredictionEngine.kt:18 docstring) breaks at trigram #65.

**Fix:** Reverse the take direction to keep most recent:

```kotlin
val pruned = if (history.size > MAX_HISTORY_PER_PKG) {
    history.entries.toList().takeLast(MAX_HISTORY_PER_PKG).associate { it.toPair() }
} else history
```

Or use a LinkedHashMap with insertion-order eviction explicitly. The KNOWN FINDINGS prompt described this as "prunes by insertion order not recency" — confirmed and high impact: this is silent data loss.

---

### CRITICAL-3: MultiPassDecoder word-position alignment breaks on punctuation-only tokens (confidence 90)

**File:** MultiPassDecoder.kt:80-90 (disagreement walker) vs MultiPassDecoder.kt:131-162 (primary walker)

The disagreement walker filters word-only tokens:
```kotlin
val tokenLists = texts.map { it.lowercase().split(Regex("\\s+")).filter { tok -> tok.any(Char::isLetterOrDigit) } }
```

The primary-text walker walks ALL whitespace-separated chunks regardless of letter content:
```kotlin
while (pos < text.length) {
    while (pos < text.length && text[pos].isWhitespace()) pos++
    if (pos >= text.length) break
    val start = pos
    while (pos < text.length && !text[pos].isWhitespace()) pos++
    ...
    idx++
}
```

If primary text contains a whitespace-isolated punctuation chunk (e.g. `"hello … world"`, `"yes — maybe"`, `"foo • bar"`), the two walkers' indices desynchronize. The disagreement walker sees positions {0, 1} → "hello", "world". The primary walker sees idx 0 → "hello", idx 1 → "…", idx 2 → "world". Disagreement variants for the actual "world" position get attached to the "…" WordConfidence record, and "world" gets `nBestAlternates = []`.

The bezel paint at BubbleService.kt:1031-1040 routes approve/reject to `current.candidates.firstOrNull()` — which would be a real word the user said attempting to swap a punctuation glyph, OR vice versa. Worse: the user sees "…?" on the knob ("DOTS?" via uppercase — likely renders as "…?" or empty string).

**Fix:** Make the primary walker skip pure-punctuation tokens too, matching the disagreement walker's filter:

```kotlin
val word = text.substring(start, pos)
if (word.none(Char::isLetterOrDigit)) continue   // skip punctuation chunks
```

(Don't increment `idx` for skipped chunks — preserve alignment.)

Frequency: GPT often emits `…`, `—`, `–` between phrases especially in L4 stutter-aware reconstructions. Real likelihood this fires in production.

---

## IMPORTANT (80-89)

### IMPORTANT-1: lastWavFile race — multi-pass decoder reads file that can be deleted by next recording (confidence 85)

**File:** BubbleService.kt:3878-3893 vs 1727

```kotlin
// processText (line 3878):
val wav = lastWavFile
if (layer >= 2 && wav != null && wav.exists() && wav.length() > 44) {
    try {
        ...
        val multi = MultiPassDecoder(recognizer).run(wav)   // 3 parallel async whisper-1 calls
```

```kotlin
// stopAndProcess (line 1727), runs when user starts a NEW recording mid-clarifier-evaluation:
lastWavFile?.let { try { it.delete() } catch (_: Exception) {} }
lastWavFile = wavFile
```

The `val wav = lastWavFile` captures the File reference but not its bytes. MultiPassDecoder then makes 3 parallel network calls each of which reads the file's bytes. If the user starts a new recording before all 3 reads complete, `stopAndProcess` deletes the old wav. The in-flight read calls then either fail with `FileNotFoundException` or read partial data. The try/catch at line 3890 swallows the exception (`Log.w` only) and returns empty word confidences. Clarifier never fires for the prior recording.

Lower-severity than CRITICAL-3 because the catch handles it gracefully — but it's still a silent loss of clarifier intent on a real user pattern (re-record while the cycle is evaluating).

**Fix:** Pass the wav as a temp-copy reference, or hold a `RandomAccessFile` lock for the duration of the multi-pass run. Or just rename the file before deletion so the in-flight reads still see the old path.

---

### IMPORTANT-2: ClarifierController re-uses singleton state across BubbleService instances; no defensive null-check on stale references (confidence 82)

**File:** ClarifierController.kt:184-198 (onLabelUpdateRef, onExitRef invocations)

`ClarifierController` is an `object`. Its `onLabelUpdateRef` and `onExitRef` lambdas close over `BubbleService.this` indirectly via `paintClarifierLabel(...)` (BubbleService.kt:268) and the `setBubbleSendReady() / setBubbleIdle()` calls in the onExit lambda (line 274). 

If BubbleService is destroyed mid-cycle (e.g. system reclaim under memory pressure) AND `onDestroy` doesn't run before recreation (it's not guaranteed), the ClarifierController singleton survives at process level. The next BubbleService instance might call `enterClarifierCycle` while the auto-dismiss timer from the previous instance is still pending — the timer fires, calls `dismissAll() → exit()`, invokes `onExitRef` from the OLD service which has been destroyed. `BubbleService.instance?.hideWordHighlight()` (line 215, line 158) uses the static `instance` so it hits the new instance — but the captured `cb?.invoke(eligible)` at line 216 invokes lambdas from the dead service, which call `setBubbleSendReady()` on the dead service, which tries to update `bubbleView` (already torn down, null) — silent no-op via null-safe accessors.

`onDestroy` calls `ClarifierController.reset()` (line 4122) and that nulls the refs. So this only fires on hard service kill (no onDestroy). Real but rare.

**Fix:** Guard the lambda invocations against stale instance:

```kotlin
private fun emitLabelUpdate() {
    if (BubbleService.instance == null) return  // service torn down
    ...
}
```

Or store a WeakReference<BubbleService> in the controller and null-check before each invocation.

---

### IMPORTANT-3: MultiPassDecoder "<END>" sentinel inflates disagreement counts when one pass returns shorter text (confidence 80)

**File:** MultiPassDecoder.kt:84-90, 145-149

```kotlin
val wordsAtPos = tokenLists.map { it.getOrNull(pos) ?: "<END>" }
if (wordsAtPos.toSet().size > 1) {
    disagreementPositions += pos
    variantsByPosition += wordsAtPos
}
```

When primary has 5 words and another pass has 3, positions 3 and 4 get "<END>" filled in for the shorter pass. The set then contains the real word + "<END>" → size 2 → flagged as disagreement. `multiPassDisagreements = 2`. But `nBestAlternates` filters "<END>" out (line 147), leaving an empty alternates list AND a non-zero disagreement count. The Clarifier scoring then awards 0.30 * (2/3) ≈ 0.20 uncertainty boost for what is really "the model just stopped early" — not actual word disagreement.

Lower impact because the score boost is small and the buildCandidates check `if (candidates.isEmpty()) return@mapIndexedNotNull null` filters out flags with no alternates anyway. So this rarely manifests in user-visible flags. But for downstream telemetry / training data (`disagreementSummary` is sent to backend per MultiPassDecoder.kt:48), the noise is real.

**Fix:** Discount "<END>" from the unique-set count, or skip positions where any pass is "<END>" entirely:

```kotlin
val realVariants = wordsAtPos.filter { it != "<END>" }.toSet()
if (realVariants.size > 1) {
    ...
}
```

Confirms the KNOWN FINDINGS CR-12 — magic-string sentinel works but inflates disagreement noise. Not catastrophic; worth fixing for clean signal.

---

### IMPORTANT-4: Overlay `addView` in showBubble lacks try/catch around the FIRST overlay add (confidence 80)

**File:** BubbleService.kt:1176

```kotlin
windowManager?.addView(bubbleView, params)
```

Every other `addView` site in this file (line 650, 2130, 2899, 3040, 1456) wraps in try/catch — the bubble's main showBubble path does NOT. If `Settings.canDrawOverlays(this)` flips false between the line-679 check and the line-1176 add (e.g., user revoked permission in Settings between checks, or system policy change on Samsung One UI), this throws `WindowManager.BadTokenException` or `SecurityException`, crashing the foreground service. The service restarts via `START_STICKY` (line 626) but state — sessionStartTime, recordingStartTime, audio focus, hotword listener — gets reset.

The same line 650 (`windowManager?.addView(overlay, params)`) for the onboarding overlay IS wrapped. The discrepancy looks accidental.

**Fix:** Wrap line 1176:

```kotlin
try {
    windowManager?.addView(bubbleView, params)
} catch (e: Exception) {
    Log.e(TAG, "Failed to add bubble overlay: ${e.message}")
    bubbleView = null
    bubbleParams = null
    Toast.makeText(this, "Couldn't show bubble — overlay permission needed", Toast.LENGTH_LONG).show()
    return
}
```

Same pattern as the onboarding overlay path.

---

### IMPORTANT-5: Two clarifier systems can be active simultaneously (legacy + FSM) without coordination (confidence 80)

**File:** BubbleService.kt:134, 2306, 2350 (legacy isClarifierActive) vs ClarifierController + BubbleStateBus.Clarifying (FSM)

The legacy `showClarifier` / `dismissClarifier` (used for command-mode confirmation, work/life toggle) runs independently of the FSM clarifier (`enterClarifierCycle` / `ClarifierController`). Both can fire concurrently:
- User triggers command-mode pickup via drag-to-text → legacy `showClarifier("?", "Rewrite this text?")` runs
- During that 10-second timeout, an L4 reconstruction completes and fires `enterClarifierCycle` (line 3907)
- Now: `isClarifierActive = true` AND `BubbleStateBus.current() is BubbleState.Clarifying`
- Tap routing at line 1031 hits FSM clarifier first, calls `ClarifierController.approve`
- Legacy clarifier's `clarifierTimeout` (line 155) is still armed; fires after 10s, retries showClarifier, paints over FSM bubble UI

The state machines collide. User sees a "?" knob mid-cycle, taps, gets unpredictable behavior. Unlikely to fire often (requires a command-mode pickup followed by a separate-thread reconstruction, but command-mode ALSO triggers a recording → reconstruction → so this is reachable).

**Fix:** Make `enterClarifierCycle` cancel any active legacy clarifier first:

```kotlin
private fun enterClarifierCycle(flags: List<FlaggedWord>, autoSendEligible: Boolean) {
    if (isClarifierActive) dismissClarifier(null)   // tear down legacy first
    ...
}
```

Or unify under the FSM. The patent claims architecture treats Clarifying as a single state — having two parallel clarifier systems is a code smell that will keep generating bugs.

---

### IMPORTANT-6: Clarifier.evaluate uses `vocabulary.map { it.lowercase() }` inside a hot path that runs per word, allocating O(N) lists each iteration (confidence 80)

**File:** Clarifier.kt:127

```kotlin
if (vocabulary.isNotEmpty() && wc.word.lowercase() !in vocabulary.map { it.lowercase() }) {
    score += 0.05
}
```

`vocabulary.map { it.lowercase() }` allocates a new List<String> for EVERY word in the utterance. If the user has 200 vocabulary words and the utterance has 30 words, that's 6000 string allocations per reconstruction. On main thread.

**Fix:** Hoist out of the per-word loop:

```kotlin
fun evaluate(...) {
    ...
    val vocabularyLower = vocabulary.map { it.lowercase() }.toSet()
    ...
}

private fun uncertaintyScore(wc, vocabularyLower: Set<String>, onsetWeights) {
    ...
    if (vocabularyLower.isNotEmpty() && wc.word.lowercase() !in vocabularyLower) {
        score += 0.05
    }
}
```

Set lookup is O(1) vs List `in` which is O(N). Alloc-once vs alloc-per-word. Latency win on every reconstruction with vocabulary.

---

## NOT REPORTING (below confidence threshold)

- Clarifier.kt:71 inner `val candidates` shadows outer `val candidates` — works but ugly. Confidence 60.
- BubbleService.kt:1267 `regenHideJob = scope.launch { delay(8000); hideRegenIcon() }` — uncaught exception in `hideRegenIcon` (e.g. view destroyed mid-animation) propagates only to scope's exception handler (none specified). SupervisorJob shields siblings but a CoroutineExceptionHandler would log it. Confidence 70 — caught most uses by the existing try/catch at addView/removeView sites.
- PredictionEngine.kt:147 reading Calendar on every chip render is fine for once-per-menu-open, not a perf issue.
- ClarifierController auto-dismiss timer survives a process kill (it's a Handler postDelayed via IdleTimer): if the process is killed and restarted by START_STICKY, the timer is gone, but `flagged` is also gone, so dismissAll's `if (flagged.isEmpty()) return` short-circuits. Safe.
- BubbleService.kt:3658 `scope.launch` for reconstruction — exception path falls to line 3917 catch, then `setBubbleIdle()` runs on main. State recovers. Acceptable.
- PredictionEngine.predictForContext work-hours math (9..17): correct per spec, IntRange end-inclusive in Kotlin. No bug.
- swapWordInLastInjection Unicode handling — uses Java String char indices (UTF-16 code units), same encoding as Kotlin/JVM substring. Safe for BMP characters; would break on emoji boundaries inside a flagged word, but the WordConfidence ranges come from MultiPassDecoder which walks the same UTF-16 string the way ACTION_SET_TEXT consumes it. Round-trip clean. Confidence 30 — not a bug.

## Summary

3 critical, 6 important.  The CRITICAL-1 (triggerClarifierForTest demo path is non-functional) is the most user-visible — it breaks the onboarding bubble demo. CRITICAL-2 (PredictionEngine pruning) is silent data loss that compounds over weeks of use. CRITICAL-3 (MultiPassDecoder punctuation alignment) is a real-likelihood word-swap-on-wrong-position bug that shows up during L4 reconstructions where GPT inserts em-dashes / ellipses.
