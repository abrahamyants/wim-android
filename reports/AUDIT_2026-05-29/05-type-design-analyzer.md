# Type Design Audit — wim-android

**Date:** 2026-05-29
**Scope:** WiM-Android only. Excludes Lavrentiy.
**Auditor:** type-design-analyzer

This audit evaluates the WiM-Android hot types for invariant strength,
encapsulation quality, and primitive obsession. The repo has a strong
sealed-class FSM at its center (`BubbleState` + `BubbleStateBus`) but the
periphery is riddled with `String`/`Int` magic values, parallel mutable
flags that duplicate the FSM, and `data class`es with no constructor
invariants.

The 5/13 architecture-critic claim ("FSM + 6 boolean flags = parallel
sources of truth") is **CONFIRMED in stronger form** — it's actually
seven flags directly mirroring FSM state plus several more weakly
coupled. Catalog appears in section 3.

---

## 1. Types That ACTIVELY Allow Invalid States

Each entry: file:line, the invariant the type pretends to enforce, and
the exact illegal state the current type happily represents.

### 1.1 `ReconstructClient.reconstruct()` — 18-parameter function with cross-parameter constraints

`app/src/main/java/com/wim/app/ReconstructClient.kt:56-75`

**Implicit invariants (none enforced):**

- `tone ∈ {formal, professional, casual, friend}` — fallback at line
  297 silently masks invalid values: `TONE_RULES[tone] ?: TONE_RULES["casual"]!!`.
- `layer ∈ {1, 2, 3, 4}` — a `layer = 7` is accepted, hits the
  `if (layer in 2..3)` block on line 404 → silent no-op; reconstruction
  runs with no L2/L3 OR L4 guidance.
- `situation ∈ {default, high_stress, reading}` — fallback at line 304
  `SITUATION_SEVERITY[situation] ?: 1.0` silently degrades.
- `previousOutputs.isNotEmpty() ⇒ this is a regenerate` — implicit; no
  type-level expression of "fresh dictation vs regen vs initial."

**Invalid states representable:**

- `reconstruct(rawText="", layer=0, tone="emperor", situation="party")`
  compiles, runs, returns an empty-ish reconstruction with default
  fallbacks. Three production bugs hide here as silent quality
  degradation.

- `previousOutputs.isNotEmpty() && rawText != lastRegenCtx.rawText` — a
  caller could pass arbitrary regenerate history with arbitrary raw
  text. The `StyleExamples.pendingPair` state machine at lines 88-97
  assumes a strict promote/drop alternation; if the caller violates
  the contract (e.g. two regens with different raw texts), the
  pending-pair is corrupted silently.

### 1.2 `ReconstructClient.ReconstructResult` — sentinel-string riskFlags

`app/src/main/java/com/wim/app/ReconstructClient.kt:783-789`

```kotlin
data class ReconstructResult(
    val cleanText: String,
    val reconMs: Long,
    val riskFlags: List<String> = emptyList(),
    val falconApproved: Boolean = true,
    val downgradedFromL4: Boolean = false
)
```

**Invariants pretending to be enforced:**

- `riskFlags` is a vocabulary: `{"very_short_output",
  "large_length_delta", "contains_unfinished_fragment",
  "critical_token_lost"}` — see line 773-781. Anywhere downstream that
  does `if (riskFlags.contains("critical_token_lost"))` (line 623)
  is unguarded against typos. `flags.contains("criticla_token_lost")`
  compiles, returns false, silently disables the falcon-reject path.

- `falconApproved == false ⇒ cleanText == rawText` — enforced at the
  callsite (`val finalText = if (falconOk) cleanText else rawText`),
  not at the type. A caller can construct
  `ReconstructResult(cleanText="leak", falconApproved=false)` and the
  type does not object.

- `downgradedFromL4 == true ⇒ original layer was 4` — implicit; no
  source layer field exists to cross-check.

**Bug shape:** any new contributor adding a riskFlag has to remember to
update every consumer's string check. The 5/13 critic likely already
flagged this; it remains unaddressed.

### 1.3 `Clarifier.WordConfidence` — nullable + emptyList opt-outs collapse two distinct meanings

`app/src/main/java/com/wim/app/Clarifier.kt:35-43`

```kotlin
data class WordConfidence(
    val word: String,
    val rangeStart: Int,
    val rangeEnd: Int,
    val asrLogprob: Double?,
    val multiPassDisagreements: Int = 0,
    val nBestAlternates: List<String> = emptyList(),
    val phoneticNeighbors: List<String> = emptyList(),
)
```

**Invalid states representable:**

- `rangeStart > rangeEnd` (negative-length range) — not blocked. The
  `applyEdit` method in ClarifierController:228-231 has a defensive
  `if (start > end) return text`, proving the constraint exists at
  runtime but not at the type.
- `rangeStart < 0 || rangeEnd > finalText.length` — not blocked. Same
  defensive check at applyEdit; if defensive check is ever skipped
  (e.g. a future caller forgets), the substring throws
  `StringIndexOutOfBoundsException` mid-clarify.
- `asrLogprob == null && nBestAlternates.isEmpty() &&
  multiPassDisagreements == 0` — a `WordConfidence` carrying ZERO
  signal. `uncertaintyScore` returns 0.0 for this and the word never
  flags, but the calling pipeline still spent the type construction
  cycles building these empty records. This is a missing
  `sealed class { object NoSignal; data class Sig(...) }` distinction.
- `asrLogprob > 0` — logprobs are non-positive by definition. The
  uncertaintyScore math at line 116 (`-lp / 1.5`) would invert sign
  silently. Should be a `@JvmInline value class Logprob(val v: Double)`
  with `init { require(v <= 0.0) }`.

### 1.4 `FlaggedWord` — same range invariants, additional candidates-list constraint

`app/src/main/java/com/wim/app/BubbleState.kt:45-51`

```kotlin
data class FlaggedWord(
    val originalWord: String,
    val candidates: List<String>,
    val rangeStart: Int,
    val rangeEnd: Int,
    val uncertaintyScore: Double,
)
```

**Invalid states representable:**

- Empty `candidates` — ClarifierController.approve() at line 85 does
  `val replacement = current.candidates.firstOrNull() ?: return`, so
  an empty candidates list silently no-ops the approve. The user taps
  approve, nothing happens, no error logged.
- `originalWord in candidates` — Clarifier.buildCandidates strips this
  at line 167 but the type doesn't require it. A test could construct
  `FlaggedWord("foo", listOf("foo", "bar"), ...)` and the approve would
  "swap" foo→foo.
- `uncertaintyScore !in 0.0..1.0` — uncertaintyScore() coerces to
  0..1 at line 136, but the data class does not. A constructed value
  of 1.7 would sort highest in `sortedByDescending` regardless.
- `rangeStart == rangeEnd` — degenerate zero-length range. Silently
  accepted; swap inserts text at the position without removing
  anything (bug: clarifier "approve" doubles the text).

### 1.5 `BubbleState.Listening` etc. — timestamps without monotonicity guarantee

`app/src/main/java/com/wim/app/BubbleState.kt:21-36`

```kotlin
data class Listening(val recordingStartMs: Long) : BubbleState()
data class Transcribing(val startMs: Long) : BubbleState()
data class Stripping(val startMs: Long) : BubbleState()
// ...
```

**Invariants pretending to be enforced:**

- Transition validity matrix is excellent (`isValidTransition` at
  lines 105-177 is a textbook FSM). But:
- `Transcribing.startMs >= Listening.recordingStartMs` — not enforced.
  Caller could pass any Long.
- `Reconstructing.layer ∈ {1,2,3,4}` — `BubbleState.Reconstructing(t,
  layer = 7)` compiles. The 5/13 critic's concern about magic ints
  reaches into the FSM payload itself.

### 1.6 `ProfileManager` — `Map<String, String>` for covertProfile carries domain semantics

`app/src/main/java/com/wim/app/ProfileManager.kt:260`

`covert_profile` returns `Map<String, String>` — these are word→
substitution pairs where the user habitually avoids a feared word.
There's no type-level distinction between "avoided word" key and
"substitute" value; a caller can swap them. The type also allows
`covertProfile["foo"] = ""` (substitute with nothing) — likely a bug
shape if it ever appears, but no constructor validates.

`onset_weights` returns `Map<String, Double>` — phoneme → difficulty.
Weight is implicitly `0.0..1.0` (multiplied by 0.10 at Clarifier.kt:134
and 100 at line 541). Construction accepts any double. A bad backend
push of `weight = 12.7` silently corrupts uncertainty scores.

### 1.7 `WhisperClient.LowConfidenceSegment` — fields that constrain each other

`app/src/main/java/com/wim/app/WhisperClient.kt:222-227`

```kotlin
data class LowConfidenceSegment(
    val text: String,
    val avgLogprob: Double,
    val noSpeechProb: Double,
    val blockSuspect: Boolean
)
```

**Cross-field invariant:** `blockSuspect = noSpeechProb >
threshold`. The threshold depends on `patienceMode`; the constructor
takes neither. Anywhere downstream that needs to derive `blockSuspect`
from `noSpeechProb` again now has TWO answers and no canonical one.

Also: `avgLogprob > 0` accepted (logprobs non-positive); `noSpeechProb
!in 0..1` accepted (probabilities should be); construction does not
validate.

### 1.8 `ProfileManager` Firestore merge — `@Suppress("UNCHECKED_CAST")` past the type system

`app/src/main/java/com/wim/app/ProfileManager.kt:340-385`

Six `@Suppress("UNCHECKED_CAST")` of `doc.get("trigger_words") as?
List<String>` — Firestore can return any structure here. If a future
desktop push writes `List<Map<String, Any>>` (richer schema), the cast
fails silently to emptyList and the user loses their synced
clinical-learning profile with no error path.

The right type here is a parser returning `Result<ProfileDelta>` or
`Either<ParseError, ProfileDelta>`, NOT a string-keyed Firestore doc
threaded through six unchecked casts.

### 1.9 `BubblePersona` / `Affect` — `Affect.NONE` is a meaningless option

`app/src/main/java/com/wim/app/BubbleAffect.kt:40`

```kotlin
enum class Affect { RESTING, LEANING_FORWARD, TA_DA, HEAD_TILT, NONE }
```

`NONE` is referenced nowhere in BubbleAffect. It's dead enum surface.
Pattern: enums grow `NONE` cases as a "null object" pattern instead of
using `Affect?`. This is mild but signals a habit; the bigger problem
is that the affect is currently NOT a payload of any state — it's
derived imperatively in `onState`. If two affects ever need to layer
(e.g. HEAD_TILT during Clarifying + COMMISERATE on Failed transition),
the type provides no composition.

---

## 2. Types That Should Be Sealed/Enum But Are String/Int

### 2.1 `tone: String` — should be `enum class Tone`

- `WimApp.tone()` (`WimApp.kt:72`) returns `String`.
- `ReconstructClient.TONE_RULES` (`ReconstructClient.kt:23-28`) maps 4
  values, with silent `?:` fallback at line 297.
- `ReconstructClient.TONE_TEMPERATURE` (line 31-36) same 4 values,
  same silent fallback at line 298.
- `BubbleService.kt:1030,1033` writes `"professional"` / `"casual"`
  string literals.
- `PredictionEngine.kt:54-61` returns `tone: String` derived from a
  `when` over `profile.category` strings.

**Bug shape:** typo `"casaul"` compiles, falls through to the
fallback, uses "casual" rules with the WRONG temperature — feels like
working code in tests, mismatches caller intent in production.

**Fix shape:** `enum class Tone(val rule: String, val temperature: Double)`
collapses the three sources into one self-documenting type. Falling
through to a `null` from `Tone.fromPrefString(raw)` becomes explicit.

### 2.2 `situation: String` — should be `enum class Situation`

- `WimApp.situation()` (`WimApp.kt:80`) returns `String`.
- Vocabulary `{default, high_stress, reading}` lives in
  `ReconstructClient.SITUATION_SEVERITY` (line 39-43).
- `WimTestReceiver.kt:162` literally constructs a `setOf("default",
  "high_stress", "reading")` to validate — proves the enum exists in
  the dev's mind but not in the type.
- `OnDeviceRecognizer.kt:122` does `if (situation == "high_stress")` —
  every consumer reimplements the vocabulary.

**Fix shape:** `enum class Situation(val severity: Double) { DEFAULT(1.0),
HIGH_STRESS(1.5), READING(0.3) }`. The "set validation" at
WimTestReceiver becomes `enumValueOfOrNull`.

### 2.3 `layer: Int` — should be `enum class Layer` or `sealed class`

- `WimApp.layer()` (`WimApp.kt:79`) returns `Int`.
- Vocabulary `{1, 2, 3, 4}` with distinct behavior at each:
  - L1: local whisper-tiny.en, no reconstruction
  - L2: cloud reconstruct (GPT-4o)
  - L3: cloud reconstruct + profile vocab/corrections injection
  - L4: Sonnet 4.6 ET + multipass + clinical prompt
- Checks scattered: `if (layer >= 2)`, `if (layer in 2..3)`, `if
  (layer >= 4)`, `if (layer >= 3)`. Each is an implicit re-derivation
  of which layer-capabilities apply.

**Bug shape:** a future L5 (or L0) would silently route through every
`>= 2` and `>= 4` branch. The Reconstructing FSM state's
`layer: Int` field carries the same primitive obsession into the FSM
payload (`BubbleState.kt:26`).

**Fix shape:**

```kotlin
sealed class Layer(val number: Int) {
    object Local : Layer(1)
    object Cloud : Layer(2)
    object Profile : Layer(3)
    object Clinical : Layer(4)
}
```

`val supportsReconstruction get() = this !is Local`, `val
usesMultipass get() = this is Clinical`, etc. Every `if (layer >= 2)`
becomes a named capability check.

### 2.4 `bubbleTheme: String` — should be `enum class BubbleTheme`

- `WimApp.bubbleTheme()` returns `String`, default `"bronze"`.
- Vocabulary `{bronze, aluminum, gunmetal}` re-asserted at 5+
  callsites (`BubbleAppearance.kt:30-37`, `BubbleMenu.kt:79-86`,
  `BubbleLiveTranscript.kt:85-86`).
- Each consumer has its own `when` with fallthrough; a misspelling at
  one writes the wrong drawable, silently mismatching the displayed
  selection in BubbleMenu.

### 2.5 `riskFlags: List<String>` — should be `Set<RiskFlag>` (sealed/enum)

- `ReconstructClient.computeRiskFlags` produces 4 known strings (line
  773-781).
- `BubbleService.kt:622` does `riskFlags.contains("critical_token_lost")`
  — unguarded string match.
- Stored to SQLite as a flat string (`SessionHistory.kt:46`,
  `riskFlags: String`) — but consumers expect list semantics, so
  there's a hidden parse contract.

**Fix shape:** `enum class RiskFlag { VERY_SHORT_OUTPUT,
LARGE_LENGTH_DELTA, UNFINISHED_FRAGMENT, CRITICAL_TOKEN_LOST }`. The
SQLite column becomes `riskFlags.joinToString(",") { it.name }` —
caller-readable, schema-stable.

### 2.6 `audiencePackage: String?` — should be `sealed class AudienceContext.Profile?`

- `ReconstructClient.kt:74` accepts a raw package string then routes
  to `AudienceContext.formatReaderBlock(audiencePackage)`. The
  conversion is opaque to the type system.
- `AudienceContext.Profile.category: String` (line 49) is itself a
  magic-string vocabulary referenced in
  `PredictionEngine.kt:65,70`.

### 2.7 `FailReason` — IS an enum (good). But `SuccessCue` semantics couple to the parent state

`SuccessCue` is correctly modeled as an enum at `BubbleState.kt:38`.
However: `BubbleAffect.applyTaDa(cue)` (line 166-203) has a fifth
implicit `cue == AUTO_SEND` branch that schedules a follow-up pulse
**only if `BubbleStateBus.current() is BubbleState.SendReady`**
(line 200). This couples the affect's behavior to the FSM's
NEXT state. Two parallel sources of truth (the cue carried in
`Injected(...)` and the FSM's current state) drive a single branch.
Should be: `SuccessCue.AUTO_SEND` carries the followup directly OR
the followup is a separate, explicit `Injected → SendReady` transition
handler.

### 2.8 `correction direction` in `ProfileManager.addCorrection(from, to)`

`ProfileManager.kt:185-188`. The arguments `from` and `to` are both
`String`. Swap them at the callsite and you write the corrupt
direction silently. Should be `@JvmInline value class Misheard(val
v: String)` + `value class Correct(val v: String)`, or at the very
least named-args at every callsite (currently positional).

---

## 3. Mutable-Where-Immutable + Parallel Sources of Truth

This is where the 5/13 critic's claim about "FSM + 6 boolean flags"
lives. Confirmed and expanded — there are actually 7 flags directly
mirroring FSM state in `BubbleService.kt`, plus 2 more in
`ClarifierController`, plus `RecordingStateBus`.

### 3.1 Flags in `BubbleService.kt` that duplicate FSM state

| Flag | Line | Mirrors FSM state | Bug shape |
|------|------|------------------|-----------|
| `isRecording` | 103 | `state is Listening` | Set at line 1492 right before `BubbleStateBus.advance(Listening)`; set false at lines 1603, 1675, 1685 with three independent codepaths. Forget one branch → state lies. |
| `processed` | 140 | `state is Transcribing` ∨ later | Set at 1496, 1674, 1684 — same multi-write problem. |
| `holdModeActive` | 157 | NOT in FSM (UI-only) | OK, but coupled to `isRecording` at 896-898 with no atomic transition. |
| `isSendReady` | 1197 | `state is SendReady` | Set true at 1344 right after `advance(SendReady)`; set false at 988, 1174, 1211. Diverges from FSM on every codepath that forgets one. |
| `isRegenInFlight` | 197 | NOT in FSM | Should be its own `state is Regenerating` or carried as `state.regenInFlight: Boolean`. |
| `facehuggerActive` | 98 | NOT in FSM | Coupled to SendReady at 1371. |
| `l4DowngradeNotified` | 85 | NOT in FSM (toast guard) | Process-lifetime flag, defensible. |
| `isWorkMode` | 172 | mirrors `prefs["work_mode"]` | Cached pref → two sources of truth with the SharedPreference. |

The first five (`isRecording`, `processed`, `isSendReady`,
`isRegenInFlight`, `facehuggerActive`) are all duplicates of FSM state
that must be kept in sync manually across 3–6 codepaths each.
`isRecording` alone is set in 7 places.

**Concrete bug observed in the code:** `onInjectComplete()` at line
1233 does `if (isRecording) return`. This reads `isRecording`, NOT
`BubbleStateBus.current() is BubbleState.Listening`. If a future
refactor flips one but not the other, the FSM and the flag diverge.
The architecture-critic's parallel-sources concern is real and
exploitable.

### 3.2 `ClarifierController` triples up FSM state

`ClarifierController.kt:33-36`:

```kotlin
private var flagged: List<FlaggedWord> = emptyList()
private var cycleIndex: Int = 0
private var autoSendEligible: Boolean = false
```

`BubbleState.Clarifying(flagged, cycleIndex, enteredAtMs)` already
carries `flagged` and `cycleIndex`. The controller stores them AGAIN.
Then every `next()` call (line 117) advances `cycleIndex` AND re-emits
a new `BubbleState.Clarifying(flagged, cycleIndex, enteredAtMs)` —
two writes per logical transition. If they diverge (an exception
between line 117 and line 122), the FSM and the controller are out
of sync.

`recentSwaps` (line 39) is mutable state with no protection against
concurrent modification; the controller is an `object` (singleton)
that any thread can mutate.

### 3.3 `RecordingStateBus` ↔ `BubbleStateBus`

`RecordingStateBus.kt:9` — `_isRecording: Boolean` mirrored manually
by `BubbleService.kt:103-109` (the setter emits to
`RecordingStateBus`). Any caller that wants "is the user recording"
has three valid answers:

1. `BubbleService.instance?.isCurrentlyRecording()`
2. `RecordingStateBus.isRecording`
3. `BubbleStateBus.current() is BubbleState.Listening`

The three are usually consistent. They are not GUARANTEED to be
consistent — and when they diverge, the bug is invisible (caller
reads the wrong one and gets stale state).

**Fix shape:** delete `RecordingStateBus`. Have subscribers observe
`BubbleStateBus.state` and derive `isRecording = it is Listening`.

### 3.4 `WhisperLocalEngine.redownloadTriggered` is a `@Volatile var` in companion

`WhisperLocalEngine.kt:48`. Process-lifetime mutable singleton. Should
be a `val redownloadGate = AtomicBoolean(false)` for the
`compareAndSet` semantics that the code at line 106 manually
implements (`if (!redownloadTriggered) { redownloadTriggered = true ;
... }` — that's not atomic; two concurrent inits race).

### 3.5 `ProfileManager` collections leak mutability through getters

`ProfileManager.kt:233-237`:

```kotlin
fun getFillerWords(): List<String> = fillerWords.toList()
fun getCorrections(): Map<String, String> = corrections.toMap()
fun getVocabulary(): List<String> = vocabulary.toList()
```

These are safe (snapshot copies). But `correctionCounts`,
`correctionLastUsed`, `wpsHistory` are exposed as the live
`ConcurrentHashMap` / `CopyOnWriteArrayList` through file-write paths
without snapshot semantics — see `saveProfile()` at line 120 doing
`JSONObject(corrections as Map<*, *>)` directly on the live map. The
ConcurrentHashMap is thread-safe per-op but the JSON serialization is
NOT — a concurrent put during JSON traversal yields a partial write.

---

## 4. Encapsulation Breaks

### 4.1 `BubbleStateBus.forceTo()` is a public escape hatch

`BubbleState.kt:101-103`:

```kotlin
fun forceTo(next: BubbleState) {
    _state.value = next
}
```

Marked "Debug/test use only — never call from production code paths."
Then called from production:
- `BubbleService.kt:275` — `triggerClarifierForTest` (guarded by
  `BuildConfig.DEBUG` — OK).
- That's the only production use, but the door is wide open. A
  `@VisibleForTesting` annotation + same-package or test-source
  visibility would make the intent enforceable.

### 4.2 `BubbleService.bubbleView: View?` is `var`, public

`BubbleService.kt:94`. Anyone holding `BubbleService.instance` can
write `bubbleView = null` and crash every observer. Should be private
with a public read-only `View?` getter, or wrapped in a
`StateFlow<View?>` so observers can react.

### 4.3 `BubbleService.dockSlotRects` is `var Map<String, Rect>?` in companion

`BubbleService.kt:246`. Mutable static state. Any code can clobber the
dock map. The race window is: MainActivity.onResume sets the map,
BubbleService reads it on touch, MainActivity.onPause clears it. If
the user lifts a finger across the resume/pause boundary, the touch
handler may read a half-cleared map.

`BubbleService.onBubbleVisibilityChanged: (() -> Unit)?` same problem
(line 253) — public mutable closure pointer.

### 4.4 `BubbleService.lastWavFile: java.io.File?` — encapsulation via `@Volatile`

`BubbleService.kt:74`. The `@Volatile` is honest about the concurrent
access pattern, but the field is `private` only by class scope; the
companion's `instance` is public and the field is read/written from
five+ places. A `lastRecording: AtomicReference<File>` with explicit
get/set semantics would surface the contract.

### 4.5 `BubbleService` private state surface is enormous

The class file is 2700+ lines (line refs up to 2658 in the file I
sampled). It owns:
- 7+ flag fields tracked above
- 20+ delegate sub-modules (`bubbleAppearance`, `bubbleDock`,
  `bubbleMenu`, `bubbleAffect`, `clarifierUI`, `commandMode`,
  `pulseRings`, `tipRotator`, `regenIcon`, `stageArc`, `statusUI`,
  `bluetoothSco`, `hotword`, `shakeDetector`, `liveTranscript`,
  `sendDisclaimer`, `bubblePersona`, `wordHighlight`, etc.)
- Plus the FSM observer wiring, ASR engine refs, audio recorder,
  vibrator, window manager, coroutine scope.

This isn't a type-design violation in itself, but: each sub-module is
an `internal var = null` field, and the `init` flow at `start()` /
`onCreate()` is the only point where they're populated. There's no
type-level invariant "if BubbleService is running, all subsystems are
non-null." Most callsites do `bubbleAppearance?.applyTheme()` —
silent no-op if init failed.

**Fix shape:** lift the sub-modules into a `BubbleSubsystems` data
class constructed atomically at `onStartCommand`. Field becomes
non-null after construction; callsites drop `?.`.

---

## 5. Strengths

For balance:

- `BubbleState` sealed hierarchy + `BubbleStateBus.advance` is
  excellent. The transition matrix is the strongest invariant in the
  codebase. Forbidden transitions log + return false instead of
  throwing — production-safe.
- `SuccessCue` and `FailReason` correctly modeled as enums (not
  `String`).
- `ProfileManager` correctly uses `ConcurrentHashMap` /
  `CopyOnWriteArrayList` for the cross-thread mutable collections
  (line 51 comment is honest about the race it prevents).
- The `data class WhisperClient.TranscribeResult` cleanly carries the
  `lowConfidenceSegments` + `wasFullyHallucinated` flags as part of
  the response, not as out-params.
- `ReconstructResult.downgradedFromL4` is a typed Boolean instead of
  a status string.

---

## 6. Top Recommended Improvements (prioritized)

| Priority | Change | Cost | Bug-prevention value |
|----------|--------|------|----------------------|
| P0 | Replace `tone: String` with `enum class Tone(val rule, val temp)` | 1-2 hours; touches ~10 callsites | High — kills 3 silent fallthroughs |
| P0 | Replace `situation: String` with `enum class Situation(val severity)` | 1 hour | High — same shape |
| P0 | Replace `layer: Int` with `sealed class Layer` exposing named capabilities | 3-4 hours; touches every L*-conditional | Very high — eliminates `if (layer >= 2)` scatter |
| P0 | Delete the 5 flag mirrors in BubbleService (`isRecording`, `isSendReady`, `processed`, `isRegenInFlight`, `facehuggerActive`); read `BubbleStateBus.current()` instead | 4-6 hours; needs careful audit | Very high — collapses the parallel-state-of-truth problem |
| P1 | Delete `RecordingStateBus`; route subscribers through `BubbleStateBus.state.map { it is Listening }` | 1 hour | Medium |
| P1 | `enum class RiskFlag` replacing `riskFlags: List<String>` | 1 hour | Medium — kills typo bugs |
| P1 | `enum class BubbleTheme` replacing the 5+ scattered when-strings | 2 hours | Medium |
| P1 | Add `init` requires to `Clarifier.WordConfidence` and `FlaggedWord` for range invariants | 30 min | Medium — prevents future caller bugs |
| P2 | ClarifierController: stop locally storing `flagged`/`cycleIndex`; read from `BubbleStateBus.current() as BubbleState.Clarifying` | 2 hours | Medium |
| P2 | `@JvmInline value class Logprob` (≤ 0.0), `Probability` (0..1), `OnsetWeight` (0..1) for the 3 dimensions that have hard mathematical bounds | 2 hours | Low — most callers stay disciplined; defends against backend pushes |
| P2 | Replace 6 `@Suppress("UNCHECKED_CAST")` Firestore reads with a typed `ProfileDelta` parser | 3 hours | Medium |
| P3 | Drop `Affect.NONE`; use `Affect?` | 15 min | Low |
| P3 | Make `BubbleStateBus.forceTo` `@VisibleForTesting` + same-module visibility | 5 min | Low |

The P0 set is the highest-leverage. The flag-mirror problem in
particular is the 5/13 critic's concern made concrete, and it is the
one type-design issue most likely to cause a user-visible bug
(recording state, send-ready state, regen state — all three are
exactly the surface where "the bubble froze" / "tapped send and
nothing" reports come from).
