# WiM Android — Type Design Analysis
**Date:** 2026-05-10  
**Analyst:** Claude Sonnet 4.6 (1M context)  
**Scope:** Core product types across 7 source files

---

## Type: BubbleState (sealed class hierarchy) + BubbleStateBus object
**File:** `app/src/main/java/com/wim/app/BubbleState.kt`

### Invariants Identified
- Only one state is active at any time (sealed class + single StateFlow).
- State transitions follow a directed graph defined in `isValidTransition` (lines 105–177).
- `Listening` is the only state that can be entered from almost any other state (user-gesture-wins rule, line 109–119).
- `Reconstructing` is the only state from which the user cannot interrupt with `Listening` (line 111).
- `Clarifying.cycleIndex` is claimed to be within `[0, flagged.size)` — enforced externally by `ClarifierController`, not the type.
- `Clarifying.flagged` is claimed non-empty (a Clarifying state with zero flags is semantically illegal but constructible).
- `Injected.cue` is always one of the four `SuccessCue` enum values (enum gives this for free).
- `Failed.reason` is always one of the four `FailReason` enum values.
- `Listening.recordingStartMs`, `Transcribing.startMs`, etc., represent wall-clock timestamps — assumed positive, not enforced.

### Ratings
- **Encapsulation:** 7/10  
  `_state` is private and the public surface is a `StateFlow<BubbleState>`. `advance()` is the intended mutation path. However, `forceTo()` (line 101) is `public` with only a comment saying "Debug/test use only" — there is no `@VisibleForTesting` annotation, no internal modifier, and it is ACTUALLY called from `BubbleService.kt:253` in a production code path (not a test path). The escape hatch has leaked into production.

- **Invariant Expression:** 7/10  
  The sealed class makes it impossible to be in an unnamed state. Enum payloads on `Failed` and `Injected` are clear. However, `Clarifying` carries `cycleIndex: Int` with no upper bound encoded in the type — nothing in the data class prevents `cycleIndex >= flagged.size` or `cycleIndex < 0`. Similarly, an empty `flagged` list in `Clarifying` is constructible and semantically invalid. These are documented in spirit but not enforced structurally.

- **Invariant Usefulness:** 9/10  
  The FSM prevents the bubble from, e.g., transitioning directly from `Idle` to `Injected` without going through the recording pipeline. The `Reconstructing → cannot-interrupt` invariant is a real bug-preventer. The `Failed → Idle` funnel means every error resolves to a safe state. This is well-thought-out.

- **Invariant Enforcement:** 6/10  
  `advance()` enforces the matrix at runtime. But:  
  1. `forceTo()` is public and used in production (`BubbleService.kt:253`) — this means the transition matrix can be bypassed in any production path, not just tests.  
  2. The `else -> true` branch in `Listening` (line 117) is a silent permissiveness catch-all — any state not explicitly enumerated in the `when` block can transition to `Listening`. This is intentional but undocumented, and a future state added to the sealed class would silently inherit the permissive branch rather than requiring an explicit decision.
  3. There is no enforcement that `advance()` is called on the main/coroutine-confined thread. `StateFlow.value` assignment is thread-safe, but concurrent `advance()` calls from two coroutines create a TOCTOU window between the `isValidTransition` check (line 77) and the assignment (line 84).

### Strengths
- Sealed class exhausts the state space at compile time — adding a state without handling the `stateName()` `when` causes a warning.
- `StateFlow` semantics give late subscribers the current state free of charge.
- `reset()` and `forceTo()` are distinct, communicating different intent to readers.
- `FailReason` and `SuccessCue` enums are small, correctly scoped, and keep `Failed`/`Injected` payloads from being raw strings.

### Concerns
1. **[HIGH] `forceTo()` is public and called in production** (`BubbleService.kt:253`). The comment says "Debug/test use only." The reality is it's load-bearing in the demo/paste path. Either remove the restriction comment and document this as a legitimate escape hatch, or make the production call use a dedicated non-debug method. As-is, any future reader sees a warning they're told not to ignore, then finds it used in production.
2. **[MEDIUM] TOCTOU race in `advance()`** — `isValidTransition` reads `_state.value` (line 76), then `_state.value = next` (line 84). Between those two lines, another coroutine could have already advanced the state. On a single-threaded dispatcher this is safe; on `Dispatchers.Default` it is not. `advance()` should be `@Synchronized` or use `compareAndSet` semantics.
3. **[MEDIUM] `Clarifying` with empty `flagged` or `cycleIndex` out of bounds** is constructible. The type system permits `BubbleState.Clarifying(emptyList(), 0, 0L)`, which would crash `ClarifierController` on the first `getOrNull(cycleIndex)` check. These constraints belong in the constructor or should be encoded as a non-empty list type.
4. **[LOW] `else -> true` permissive catch-all** at line 117 will silently allow any future sealed class subtype to transition to `Listening`. This is probably desired, but deserves an explicit comment, not a fall-through default.

### Recommended Improvements
- Mark `forceTo()` as `@VisibleForTesting` and create a separate `forceInject()` or `enterDemoMode()` method that documents its production use case, or concede the production use and drop the "never call from production" comment.
- Add `init { require(flagged.isNotEmpty()) { "Clarifying requires at least one flagged word" } }` and `require(cycleIndex in flagged.indices)` to `Clarifying`.
- Wrap the `isValidTransition` + assignment in a `synchronized(this)` block in `advance()`, or use `MutableStateFlow.update { }` semantics (which are not atomic for the read-validate-write pattern — actually needs `AtomicReference.compareAndSet`).

---

## Type: WordConfidence (data class in Clarifier) + FlaggedWord (BubbleState.kt)
**Files:**  
- `app/src/main/java/com/wim/app/Clarifier.kt` lines 36–44 (`WordConfidence`)  
- `app/src/main/java/com/wim/app/BubbleState.kt` lines 45–51 (`FlaggedWord`)

### Invariants Identified
- `WordConfidence.rangeStart` < `rangeEnd` (assumed; both are Int, no enforcement).
- `WordConfidence.asrLogprob` is null when unknown, non-positive when present (logprobs are always ≤ 0).
- `WordConfidence.multiPassDisagreements` ≥ 0 (Int default, no clamp enforced at the type level).
- `FlaggedWord.rangeStart` < `rangeEnd` (same: no enforcement).
- `FlaggedWord.candidates` is non-empty (enforced by Clarifier.evaluate at line 82, but not by the type).
- `FlaggedWord.uncertaintyScore` is in [0.0, 1.0] (enforced by `coerceIn` at line 137 in `uncertaintyScore()`, but the field itself accepts any Double).
- `WordConfidence.rangeStart`/`rangeEnd` are character offsets into the injected text — they can be stale if the text is mutated after construction.

### Ratings
- **Encapsulation:** 5/10  
  Both are `data class` with all fields public val — correct for immutable value types. But neither validates constraints in construction. All invariants are enforced by callers, not the types themselves.

- **Invariant Expression:** 5/10  
  `asrLogprob: Double?` correctly uses nullable to express "unknown confidence." The range fields (`rangeStart`, `rangeEnd`) give no indication of their relationship. The `candidates: List<String>` gives no indication that it must be non-empty. `uncertaintyScore: Double` gives no indication of its [0, 1] domain.

- **Invariant Usefulness:** 8/10  
  The nullability on `asrLogprob` is a genuinely useful distinction — `MultiPassDecoder` explicitly sets it to `null` for words in confident segments vs. a real value for low-confidence segments. This prevents treating absence-of-signal as a specific signal.

- **Invariant Enforcement:** 3/10  
  Almost nothing is enforced at construction time:
  - A `WordConfidence` with `rangeStart > rangeEnd` is freely constructible and would cause an invalid accessibility highlight.
  - A `FlaggedWord` with `candidates = emptyList()` is freely constructible. `ClarifierController.kt:85` calls `current.candidates.firstOrNull() ?: return`, which silently no-ops on an empty list — behavior is arguably correct but the type permits a state that has no semantic meaning (a flagged word with nothing to suggest).
  - `uncertaintyScore: Double` in `FlaggedWord` accepts `-1.0` or `2.0` freely.

### Strengths
- Nullable `asrLogprob` is the right modeling choice — it distinguishes "confident" from "unknown" without a sentinel value.
- `phoneticNeighbors` and `nBestAlternates` defaulting to `emptyList()` keeps construction simple for callers that don't have this data yet.

### Concerns
1. **[HIGH] `FlaggedWord.candidates` can be empty** — `Clarifier.evaluate` filters to non-empty at line 82, but callers in `BubbleDemoScript.kt:220`, `WimTestReceiver.kt:568` construct `FlaggedWord` directly and could pass empty lists. The accessibility underline and clarifier tap-handler will silently no-op rather than crash, which is safe but confusing during manual testing.
2. **[MEDIUM] `rangeStart > rangeEnd` is constructible** in both types. `WordTiming.startMs > endMs` is the same issue in `SpeechRecognizerL1.kt:48`. These are character/time ranges with a mandatory ordering that is documented nowhere in the type.
3. **[MEDIUM] `uncertaintyScore` in `FlaggedWord` is documented as [0, 1] but accepts any Double** — should be a value class or at minimum constrained in `FlaggedWord`'s construction path.
4. **[LOW] `asrLogprob` is documented as "closer to zero = more confident" but accepts positive values** — logprobs are mathematically always ≤ 0. A positive value here would invert the score computation in `uncertaintyScore()`. No guard at construction or in the scoring function.

### Recommended Improvements
- Add `init { require(rangeStart <= rangeEnd) }` to both `WordConfidence` and `FlaggedWord`.
- Change `FlaggedWord.candidates` to a type that enforces non-emptiness, or add `init { require(candidates.isNotEmpty()) }`.
- Enforce `asrLogprob?.let { require(it <= 0.0) }` or document the mathematical constraint explicitly.
- Consider a `@JvmInline value class UncertaintyScore(val value: Double)` with a factory that enforces the [0,1] range, replacing the bare Double in `FlaggedWord.uncertaintyScore`.

---

## Type: MultiPassResult + Pass (data classes in MultiPassDecoder)
**File:** `app/src/main/java/com/wim/app/MultiPassDecoder.kt` lines 35–49

### Invariants Identified
- `MultiPassResult.primary` is the result of the first temperature in `temperatures` (always `temperatures[0]` = 0.0 by default).
- `MultiPassResult.passes.size == temperatures.size` — the passes list corresponds 1:1 with the input temperature list.
- `MultiPassResult.wordConfidences.size` matches the word count in `primary.text` (constructed by `wordConfidencesFromPrimary`).
- `MultiPassResult.disagreementSummary.size` equals the number of positions where passes disagreed.
- `Pass.temperature` is one of the values in `temperatures` — no enforcement, just implied from construction.
- When `passes.size < 2`, `wordConfidences` and `disagreementSummary` are both empty.

### Ratings
- **Encapsulation:** 6/10  
  These are pure data bags created and consumed by `MultiPassDecoder.run()`. Encapsulation is reasonable — construction is private to `run()`. However, the types are `public data class`, so callers could construct bogus instances (e.g., `MultiPassResult` where `primary` is not in `passes`, or where `wordConfidences.size` mismatches word count).

- **Invariant Expression:** 5/10  
  The relationship between `primary` and `passes[0]` is stated only in a comment (line 39). The 1:1 correspondence between `passes` and `wordConfidences` word positions is implied but not expressed. `Pass.temperature` has no domain constraint in the type.

- **Invariant Usefulness:** 8/10  
  `disagreementSummary` as a `List<String>` with the formatted `"position N: variant1|variant2"` strings is convenient for passing to the backend as-is but makes it unqueryable without string parsing. If the backend ever needs to act on specific positions, this type becomes a liability. For now — backend just appends it as a JSON array — it's fine.

- **Invariant Enforcement:** 7/10  
  Because `MultiPassResult` is only ever constructed inside `run()`, the invariants hold in practice. The risk is purely from direct construction in tests or future callers. The early-return `passes.size < 2` path (line 59) correctly produces empty collections. The full-agreement fast path (line 70) correctly produces zero-disagreement output.

### Strengths
- The `Pass` type cleanly pairs `temperature` with its `TranscribeResult` — when reading logs, the decoder that produced a given result is always traceable.
- Nullability of `asrLogprob` flows through correctly from `LowConfidenceSegment` mapping.
- The `disagreementSummary` as a pre-formatted list means the backend receives structured strings without any additional transformation code.

### Concerns
1. **[MEDIUM] `primary` is not guaranteed to equal `passes[0].result`** — the comment says "temperature 0 unless temperatures was reordered" but the code uses `passes.firstOrNull()?.result` (line 56), which means if the caller constructs `MultiPassDecoder(recognizer, listOf(0.8, 0.0, 0.4))`, the `primary` is the 0.8-temperature result, but the field comment says "temperature 0." The invariant is a lie for non-default temperature orderings.
2. **[MEDIUM] `multiPassDisagreements` semantics are inverted at line 165** — the comment in `MultiPassResult.wordConfidences` says "1 = all agreed, 2 = two-way split, 3 = three-way split," but `WordConfidence.multiPassDisagreements` is documented as "count of decoders that disagreed; 0 = full agreement." In `wordConfidencesFromPrimary`, the value `multi` is the count of unique variants (line 154), then the assignment at line 165 does `if (multi > 1) multi else 0` — so when all agree, the field is 0 (correct), but when there are 2 unique variants, the field is 2 (which, per the field's own doc, means "2 decoders disagreed," which may or may not be true). The `WordConfidence` comment and the `MultiPassResult` comment describe the field differently.
3. **[LOW] Empty `temperatures` list** produces a `MultiPassResult` with `primary = TranscribeResult(text="", asrMs=0)` (the default from the `?: TranscribeResult(...)` at line 57). This is a silent data loss path — no exception, no log, just empty output.

### Recommended Improvements
- Remove the "temperature 0 unless temperatures was reordered" qualifier from the `primary` field comment and replace it with "the result of `temperatures[0]`" to be accurate regardless of ordering.
- Reconcile `multiPassDisagreements` semantics: pick one of (a) unique-variant-count or (b) disagreeing-decoder-count, update both the `WordConfidence` field doc and the `wordConfidencesFromPrimary` logic to be consistent.
- Add `require(temperatures.isNotEmpty())` to `MultiPassDecoder`'s constructor so the empty-temperature path fails loudly.

---

## Type: Prediction (data class in PredictionEngine)
**File:** `app/src/main/java/com/wim/app/PredictionEngine.kt` lines 30–35

### Invariants Identified
- `confidence` is in [0.0, 1.0] (enforced by `coerceIn` at construction site, line 80 — but not in the type).
- `confidence >= MIN_CONFIDENCE_FOR_CHIP (0.55)` at the time `predictForContext` returns — the null return (line 74) means a `Prediction` is never emitted below threshold. However, nothing prevents constructing a `Prediction(recipient="", tone="", confidence=0.0, reason="")` directly.
- `recipient` is non-empty (enforced by caller at line 76 via `profile.audience`).
- `tone` is one of `"professional"` or `"casual"` — only two values ever assigned, but the field is a bare String.

### Ratings
- **Encapsulation:** 4/10  
  `PredictionEngine` is an `object` with full access to `SharedPreferences` via `WimApp.prefs`. The `readHistory`/`writeHistory` methods are private — good. But `recordCompletion()` is public on the object, meaning any caller anywhere in the app can inject arbitrary history. There is no access control on the learning state machine.

- **Invariant Expression:** 3/10  
  `tone: String` is the single largest invariant failure — only two values are ever emitted (`"professional"`, `"casual"`), but the type accepts any String. This should be a sealed class or at minimum an enum. The `confidence: Double` field has no domain annotation. `recipient: String` has no non-empty constraint.

- **Invariant Usefulness:** 7/10  
  The null-return-below-threshold pattern at line 74 is correct — a `Prediction?` return type forces callers to handle absence rather than checking a confidence field themselves. The move-to-front history logic in `recordCompletion` is reasonable for a frequency-recency tradeoff.

- **Invariant Enforcement:** 4/10  
  `confidence` is clamped at the construction site in `predictForContext` but not in the `Prediction` type itself. `tone` is never validated — a future caller constructing `Prediction(..., tone="AGGRESSIVE", ...)` would compile and flow into rendering code with no error. `recipient.take(40)` at line 76 truncates silently — the type expresses no maximum length constraint.

### Concerns
1. **[HIGH] `tone: String` with no domain constraint** — the bubble rendering code and any downstream consumer treat this as a free-form string but only ever receive two values. When a third tone is added (`"intimate"`, `"urgent"`), there will be no compile-time signal that rendering code needs updating. This should be `enum class Tone { PROFESSIONAL, CASUAL }` or a sealed class.
2. **[MEDIUM] `confidence: Double` is unconstrained in the type** — the threshold check and clamp both live outside `Prediction`. A `Prediction` with `confidence = -1.0` would incorrectly suppress the chip.
3. **[MEDIUM] `PredictionEngine.recordCompletion()` is fully public** — any component can write to the prediction history. This is an unprotected write path into persistent state. Ideally this is package-private or called only through a defined interface.
4. **[LOW] `readHistory` silently swallows all `Throwable`** at line 136 — any JSON corruption returns `emptyMap()` without logging. History corruption is silently discarded, which is safe but unobservable.

### Recommended Improvements
- Replace `tone: String` with `enum class Tone { PROFESSIONAL, CASUAL }`.
- Add `init { require(confidence in 0.0..1.0) { "confidence must be in [0,1]" } }` to `Prediction`.
- Consider restricting `recordCompletion()` to `internal` visibility.
- Add a `Log.w` in `readHistory`'s catch block so corruption is at least observable in logcat.

---

## Type: TranscribeResult + LowConfidenceSegment (data classes in WhisperClient)
**File:** `app/src/main/java/com/wim/app/WhisperClient.kt` lines 222–234

### Invariants Identified
- `TranscribeResult.text` is already hallucination-stripped before construction (enforced by `transcribe()`).
- `wasFullyHallucinated == true` implies `text == ""` — enforced by the logic at lines 204–205 but not by the type.
- `wasFullyHallucinated == false && text == ""` is a valid state (empty but not hallucinated — silence or no speech).
- `lowConfidenceSegments` is non-empty only when at least one segment crossed the logprob or no-speech thresholds.
- `LowConfidenceSegment.avgLogprob` is expected to be in (-∞, 0] (logprob convention) — not enforced.
- `LowConfidenceSegment.noSpeechProb` is expected in [0.0, 1.0] (probability) — not enforced.
- `LowConfidenceSegment.blockSuspect == true` implies `noSpeechProb > noSpeechThreshold` — enforced at construction (line 187), but not in the type.
- `asrMs` is a positive duration in milliseconds — not enforced.

### Ratings
- **Encapsulation:** 8/10  
  `WhisperClient` is an `object` with private helpers. `transcribe()` is the only public entry point. `LowConfidenceSegment` and `TranscribeResult` are nested inside `WhisperClient`, which is good — they cannot be instantiated from outside without going through the client path (in practice; technically they're public data classes so direct construction is possible but obviously wrong).

- **Invariant Expression:** 6/10  
  The relationship between `wasFullyHallucinated` and `text` is a logical consequence that is invisible in the type. The `blockSuspect` field implies a precondition (`noSpeechProb > threshold`) that is invisible in `LowConfidenceSegment`. This is the classic boolean-flag-as-poor-man's-sum-type anti-pattern.

- **Invariant Usefulness:** 9/10  
  The `wasFullyHallucinated` flag is critical — it distinguishes "ASR returned empty" from "ASR returned text that was all hallucination." The pipeline needs this distinction to bail out (silence = keep waiting; full hallucination = it heard something, but it was noise). This is a real invariant that prevents a concrete class of bugs.

- **Invariant Enforcement:** 7/10  
  `transcribe()` correctly populates all fields from actual API response data. `stripHallucinations()` is called before `TranscribeResult` construction. The pre-compiled regex (line 106, `by lazy`) is a genuine win — avoiding ~100–200ms overhead per call. However, `LowConfidenceSegment.blockSuspect` is set from the live threshold (lines 187, 195) but the threshold varies by `patienceMode`, meaning the same segment could produce `blockSuspect = true` in one call and `false` in another. The field encodes a context-dependent decision rather than a property of the data.

### Strengths
- `wasFullyHallucinated` as an explicit field is better than requiring every consumer to test `text.isBlank()`.
- The `HALLUCINATION_PATTERNS` list is comprehensive and well-maintained.
- `lazy` compilation of regexes is the correct Android pattern for expensive construction.
- The dual `TranscribeResult` type in `SpeechRecognizerL1.kt` (lines 59–65) correctly extends the legacy type with `words: List<WordTiming>` without breaking existing callers.

### Concerns
1. **[MEDIUM] `blockSuspect` is threshold-dependent** — the field encodes a decision made with a specific `patienceMode` setting, but the threshold value is not stored in the segment. A consumer who needs to re-evaluate with a different threshold cannot do so. Better: store raw `noSpeechProb` (already there) and let the consumer apply the threshold. Then `blockSuspect` becomes derivable and `LowConfidenceSegment` becomes purely descriptive. The field as-is mixes data with a policy decision.
2. **[MEDIUM] `wasFullyHallucinated && text.isNotBlank()` is constructible** — though the production path never does this, the type permits it. A `Clarifying` state entered on a fully-hallucinated result would be meaningless.
3. **[LOW] `LowConfidenceSegment.avgLogprob` accepts positive values** — same issue as `WordConfidence.asrLogprob`. Logprobs are always ≤ 0 mathematically. A positive value here would cause `MultiPassDecoder.wordConfidencesFromPrimary` to incorrectly assign a logprob to a word (the normalization formula at `Clarifier.kt:117` maps `-lp / 1.5` into [0,1], which would go negative for a positive logprob).
4. **[LOW] `WhisperClient.TranscribeResult` and `SpeechRecognizerL1.TranscribeResult` are two distinct types with the same name** — `WhisperApiL1.transcribe()` converts between them at lines 101–110 in `SpeechRecognizerL1.kt`. This is source-compat debt: callers must know which `TranscribeResult` they have. The field names match, but `WhisperClient.TranscribeResult` has no `words` field and `SpeechRecognizerL1.TranscribeResult` does.

### Recommended Improvements
- Remove `blockSuspect` from `LowConfidenceSegment` and let consumers derive it from `noSpeechProb` against their chosen threshold. If the flag is needed for logging convenience, make it a computed property.
- Rename one of the two `TranscribeResult` types — the simplest fix is `WhisperRawResult` vs `TranscribeResult` (use the latter for the interface-level type only).
- Add `require(avgLogprob <= 0.0)` to `LowConfidenceSegment` construction or document the mathematical constraint.

---

## Type: Pack + Marker + Example (data classes in L1PackHelper)
**File:** `app/src/main/java/com/wim/app/L1PackHelper.kt` lines 40–55

### Invariants Identified
- `Pack.language` matches the filename key used to load it (`l1_packs/<language>.json`) — no enforcement; the JSON `language` field could differ from the file path key.
- `Pack.markers` is non-empty for any language with a pack (an empty markers pack would produce no prompt injection, silently wasting a round-trip).
- `Marker.id` is unique within a pack — no enforcement, no validation.
- `Marker.examples` can be empty (handled gracefully by `promptInjection` at lines 129, 142).
- `Example.input` and `Example.output` are non-blank when used in prompt injection — validated inline at lines 129, 141 (`isNotBlank()`).
- `Pack.notes` is null iff the JSON field is blank or absent — enforced by `takeIf { it.isNotBlank() }` at line 88.
- `cache[language] = null` is stored for failed loads — this means a failed load is permanently cached in-process and will not be retried even if the asset becomes available (which it won't, but it would affect tests).

### Ratings
- **Encapsulation:** 6/10  
  `cache` is `private` — good. The `loadPack` method is the canonical entry point. But the cache is a `mutableMapOf` with no synchronization, and `L1PackHelper` is an `object` accessed from the main thread and potentially from coroutines. If two coroutines call `loadPack` concurrently for the same language for the first time, both miss the cache, both load the asset, and both write to the map — this is a read-and-set race on a non-synchronized mutable map.

- **Invariant Expression:** 4/10  
  The three data classes are pure value containers with no self-enforced constraints. The language-filename correspondence invariant is entirely implicit. The non-emptiness of `markers` is implicit. `Marker.id` uniqueness is implicit.

- **Invariant Usefulness:** 7/10  
  For an asset-backed read-only structure loaded once per language, the simple data class shape is appropriate. The `notes: String?` field correctly uses nullable to distinguish absent vs. present — better than defaulting to `""`.

- **Invariant Enforcement:** 3/10  
  Construction from JSON in `loadPack` is the only real enforcement point. There is no validation that `markers` is non-empty, that `language` matches the key argument, or that `Marker.id` values are unique. The try/catch at line 92 swallows all exceptions — a malformed pack silently becomes `null`, which then disables L1 normalization for that user without any visible error.

### Concerns
1. **[HIGH] No synchronization on `cache`** — `mutableMapOf` is not thread-safe. Concurrent `loadPack` calls from different coroutines on `Dispatchers.IO` could corrupt the map. Use `ConcurrentHashMap` or confine `loadPack` to a single thread.
2. **[HIGH] `Pack.language` vs. load-key mismatch is silent** — if a JSON file at `l1_packs/russian.json` declares `"language": "Russian"` (capitalized), `loadPack("russian")` caches the result under key `"russian"` but the `Pack.language` field says `"Russian"`. The `languageDisplayName()` function at line 151 expects lowercase keys. The mismatch would cause `activePack()` to work correctly (caches under the right key) but `Pack.language` to be used in prompt output with inconsistent casing.
3. **[MEDIUM] `catch (e: Exception)` swallows load failures silently** — the `Log.w` at line 93 logs the message, but downstream the user gets no L1 normalization without knowing why. This is acceptable for the typical runtime (asset is always present) but makes debugging pack authoring errors difficult.
4. **[LOW] `BUILTIN_KEYS` includes `"en"` which is explicitly excluded from loading** (line 60) — this is a documentation mismatch: "en" in `BUILTIN_KEYS` means "no pack," but the list is called `BUILTIN_KEYS` as if "en" has a pack. A comment should clarify this.

### Recommended Improvements
- Replace `mutableMapOf<String, Pack?>()` with `ConcurrentHashMap<String, Pack?>()`.
- Add a post-load assertion: `require(pack.language.lowercase() == language.lowercase())` to surface asset-key mismatches during development.
- Add `require(markers.isNotEmpty())` to `Pack`'s `init` block (or at the call site in `loadPack`) so an empty markers file fails loudly.

---

## Type: ReconstructResult (data class in ReconstructClient)
**File:** `app/src/main/java/com/wim/app/ReconstructClient.kt` line 750

### Invariants Identified
- `cleanText` is the text to inject. When Falcon rejects, `cleanText = rawText` (the original) — enforced in `reconstructViaBackend` at line 255 and in `reconstructDirect` at line 633.
- `falconApproved == false` → `cleanText == rawText` (the fallback) — enforced at call sites, not in the type.
- `falconApproved == true` → `cleanText` is the LLM reconstruction — enforced at call sites.
- `riskFlags` is empty in the backend path (`emptyList()` at line 264) — even when the backend produces `falcon_ok = false`, no flags are provided from the backend call. Risk flags are only computed in `reconstructDirect`.
- `reconMs` is a positive duration — not enforced.
- `riskFlags` contains strings from a fixed vocabulary (`"very_short_output"`, `"large_length_delta"`, `"contains_unfinished_fragment"`, `"critical_token_lost"`) — no enforcement.

### Ratings
- **Encapsulation:** 4/10  
  `ReconstructResult` is a public `data class` nested in `ReconstructClient`. The invariant that `falconApproved == false → cleanText == rawText` is enforced at two separate call sites (lines 255, 633) using the same inline `if (falconOk) cleanText else rawText` pattern. This is duplicated enforcement that can drift.

- **Invariant Expression:** 3/10  
  The relationship between `falconApproved` and `cleanText` content is the central invariant of the type — it determines whether the injected text is safe — and it is expressed nowhere in the type itself. A caller receiving a `ReconstructResult` cannot know from the type alone that `cleanText` might equal the raw input. This is a classic nullable-as-sum-type failure: the correct model is a sealed class.

- **Invariant Usefulness:** 8/10  
  `falconApproved` as an explicit field is the right call — callers like `BubbleService.kt:3898` use it to choose UI feedback ("Meaning verified" vs "Using original"). The `riskFlags` list is useful for logging and the `critical_token_lost` flag gates Falcon validation.

- **Invariant Enforcement:** 3/10  
  The `falconApproved → cleanText` invariant is enforced at two separate if-branches in two different private functions. The backend path produces `riskFlags = emptyList()` always — even when `falconOk = false`, no flags are attached, so callers in `BubbleService` that check `riskFlags` (line 4041) only get data from the direct path, not the backend path. This is an asymmetry that could cause behavioral differences between production (backend) and dev (direct) paths.

### Strengths
- `reconMs` as an explicit field means latency is always traceable without additional instrumentation.
- The `falconApproved` boolean prevents the system from using a Falcon-rejected output even if a caller forgets to check.

### Concerns
1. **[HIGH] The `falconApproved → cleanText == rawText` invariant is duplicated across two private functions and not encoded in the type.** The correct type is:
   ```kotlin
   sealed class ReconstructResult {
       data class Approved(val cleanText: String, val reconMs: Long, val riskFlags: List<String>) : ReconstructResult()
       data class Rejected(val rawText: String, val reconMs: Long) : ReconstructResult()
   }
   ```
   or alternatively a single class where `cleanText` is unambiguously the text to inject and the `falconApproved` field is truly informational. As-is, every caller must re-implement the `if (falconApproved) use cleanText else use rawText` logic or trust that the producing code already did it — and the producing code in the backend path did it (`finalText = if (falconOk) cleanText else rawText`), while `cleanText` is the field name that holds the already-resolved value. The field name `cleanText` implies it's clean regardless of Falcon, which is true at the call sites, but the field receives the name of the pre-resolution variable.
2. **[HIGH] `riskFlags = emptyList()` always in the backend path** (`reconstructViaBackend`, line 264) — the backend performs its own Falcon validation server-side, but the client never receives risk flags from it. `BubbleService.kt` code that reads `reconResult.riskFlags` for logging (line 4041) will always see an empty list in production. The field communicates nothing in the production path, which masks any meaning it provides in the dev/fallback path.
3. **[MEDIUM] Falcon is now a stub returning `true`** (line 679: "Retired 2026-05-10") but the `falconApproved` field in `ReconstructResult` defaults to `true` (line 754) and is set from the stub. Any consumer that treats `falconApproved = false` as a meaningful signal will never see that state in the direct path, and in the backend path only when the server-side Falcon fires. The field is now half-dead in the client.
4. **[LOW] `riskFlags` flag values are bare strings** — `"critical_token_lost"` etc. should be an enum. Adding a new flag requires string-matching in every consumer and has no compile-time completeness check.

### Recommended Improvements
- Resolve the naming confusion: rename `cleanText` to `outputText` or `injectionText` to make clear it is the text to inject regardless of Falcon status, and add a separate `wasReconstructed: Boolean` if callers need to know whether it's the LLM output or the raw fallback.
- Make `riskFlags` an enum or sealed class: `enum class RiskFlag { VERY_SHORT_OUTPUT, LARGE_LENGTH_DELTA, UNFINISHED_FRAGMENT, CRITICAL_TOKEN_LOST }`.
- Either populate `riskFlags` from the backend response or remove the field from the backend path's `ReconstructResult` construction and set it explicitly to a sentinel that means "not computed."
- Consider whether `falconApproved` still earns its field slot given Falcon is now a stub — if it's informational only, document this explicitly in the field's KDoc.

---

## Cross-Cutting Issues

### 1. [HIGH] Nullability used as a poor man's sum type — two instances
- `Prediction.tone: String` — only two values ever emitted; should be `enum class Tone`.
- `ReconstructResult.cleanText` + `falconApproved: Boolean` — the Boolean signals which semantic meaning `cleanText` carries, making it effectively a union type encoded as a struct with a discriminant. This is exactly the scenario sealed classes exist to solve.

### 2. [HIGH] `forceTo()` in production code path
`BubbleStateBus.forceTo()` is used at `BubbleService.kt:253` with the comment that the FSM cannot reach `Injected` via normal transitions at that moment. This documents a gap in the transition matrix — the demo/paste path does not share the normal `Idle → Listening → ... → Injected` chain. The correct fix is either (a) add a specific transition to the matrix that covers this path, or (b) rename `forceTo()` to something that names the production use case (`enterDemoInjection()`), removing the false "Debug/test use only" framing.

### 3. [MEDIUM] Two `TranscribeResult` types with the same name in adjacent files
`WhisperClient.TranscribeResult` (lines 229–234 of `WhisperClient.kt`) and `TranscribeResult` in `SpeechRecognizerL1.kt` (lines 59–65). The adapter at `WhisperApiL1.transcribe()` converts between them, but any reader seeing `TranscribeResult` in `MultiPassDecoder.kt:35` must determine which type is in scope. The field structures are almost identical but not the same — `WhisperClient.TranscribeResult` lacks `words`.

### 4. [MEDIUM] Logprob sign convention is relied upon but never enforced
`asrLogprob` in `WordConfidence`, `avgLogprob` in `LowConfidenceSegment`, and the logprob-to-uncertainty normalization at `Clarifier.kt:117` all rely on logprobs being ≤ 0. A positive value would produce a negative uncertainty score (clamped to 0 by `coerceIn`), silently treating a corrupt signal as "perfectly confident." None of the three types validate this.

### 5. [LOW] Object singletons with mutable state and no thread-safety annotation
`BubbleStateBus`, `WhisperClient`, `PredictionEngine`, and `L1PackHelper` are all `object` singletons. `BubbleStateBus` uses `StateFlow` (thread-safe). `WhisperClient` is stateless except for `OkHttpClient` (thread-safe). `PredictionEngine.readHistory`/`writeHistory` use `SharedPreferences.edit().apply()` which is thread-safe. `L1PackHelper.cache` is a raw `mutableMapOf` — not thread-safe (documented above). The pattern is mostly safe but inconsistent — no `@ThreadSafe`/`@NotThreadSafe` documentation anywhere.

---

## Severity Summary

| Severity | Count | Items |
|---|---|---|
| HIGH | 8 | `forceTo()` in production; TOCTOU in `advance()`; `Clarifying` with empty flags; `FlaggedWord.candidates` can be empty; `tone: String` in Prediction; `falconApproved → cleanText` duplicated invariant; `riskFlags = emptyList()` in backend path; `L1PackHelper.cache` not thread-safe |
| MEDIUM | 9 | `cycleIndex` unbounded; `rangeStart > rangeEnd` constructible; `uncertaintyScore` unbounded Double; `blockSuspect` encodes policy not data; backend/direct `riskFlags` asymmetry; `MultiPassDecoder.primary` invariant inaccurate; `multiPassDisagreements` semantics conflict; `TranscribeResult` name collision; logprob sign unenforced |
| LOW | 6 | `else -> true` catch-all; `asrLogprob` accepts positive values; `riskFlags` as bare strings; `BUILTIN_KEYS` includes "en" with no comment; `Falcon` stub / half-dead field; `LowConfidenceSegment.avgLogprob` accepts positive |
