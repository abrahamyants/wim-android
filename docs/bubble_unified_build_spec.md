# WiM Bubble Unified Build Spec — Engagement-UX × Per-Word Clarifier Reconciliation

**Posture**: One bubble surface, two architectures, zero collisions. Spec A converts wait-time into perceived intelligence (pipeline parade + anticipatory prediction + personified affects). Spec B converts ASR uncertainty into a one-tap correction loop (per-word clarifier). They share the same physical bubble. This document locks the shared substrate so both ship without re-work.

**L1 ASR engine**: deferred to a `SpeechRecognizerL1` interface. Moonshine / sherpa-onnx / whisper.cpp / cloud-whisper-1 are all valid implementations behind it. The reconciled spec is engine-agnostic by construction.

---

## 1. Unified `BubbleState` sealed class

A single source of truth for "what is the bubble doing right now." Both `PipelinePhase` (Spec A) and the clarifier's CLARIFY state (Spec B) collapse into ONE machine. Affects (Spec A's `Affect` enum) are NOT states — they are decorations rendered on top of the active state. Predictions are NOT states either — they are a sidecar overlay attached to IDLE.

```kotlin
// app/src/main/java/com/wim/app/BubbleState.kt
sealed class BubbleState {
    object Idle : BubbleState()                              // dock, breathing
    data class Listening(val recordingStartMs: Long) : BubbleState()       // mic open
    data class Transcribing(val startMs: Long) : BubbleState()             // ASR running (engine-agnostic)
    data class Stripping(val startMs: Long) : BubbleState()                // disfluency filter
    data class ReadingContext(val startMs: Long) : BubbleState()           // pre-cursor audience read (L2+)
    data class Reconstructing(val startMs: Long, val layer: Int) : BubbleState() // L1/L2/L3/L4 model call
    data class Verified(val startMs: Long) : BubbleState()                 // Falcon/leak passed, pre-inject
    data class Injected(val startMs: Long, val cue: SuccessCue) : BubbleState()  // text in field
    data class Clarifying(                                   // [SPEC B] per-word clarifier active
        val flagged: List<FlaggedWord>,
        val cycleIndex: Int,
        val enteredAtMs: Long,
    ) : BubbleState()
    object SendReady : BubbleState()                         // green, auto-send armed
    data class Failed(val reason: FailReason) : BubbleState()              // network / falcon-reject / hallucination
}

enum class SuccessCue { PASTE, AUTO_SEND, COMMAND_REPLACE, REGEN_SUCCESS }
enum class FailReason { NETWORK, HALLUCINATED, FALCON_REJECT, INJECT_FAIL }

object BubbleStateBus {
    private val _state = MutableStateFlow<BubbleState>(BubbleState.Idle)
    val state: StateFlow<BubbleState> = _state.asStateFlow()
    fun advance(next: BubbleState) { /* validates against transition matrix below */ }
}
```

### State transition diagram (ASCII)

```
                         ┌────────────────────────────────────────┐
                         ▼                                        │
   ┌─────────┐  tap   ┌─────────┐  stop   ┌──────────────┐       │
   │  Idle   │──────► │Listening│────────►│ Transcribing │       │
   └────┬────┘        └─────────┘         └──────┬───────┘       │
        │  ▲                                     │               │
        │  │                                     ▼               │
        │  │                              ┌──────────────┐       │
        │  │                              │  Stripping   │       │
        │  │                              └──────┬───────┘       │
        │  │                                     ▼               │
        │  │                              ┌──────────────┐       │
        │  │                              │ReadingContext│ (L2+) │
        │  │                              └──────┬───────┘       │
        │  │                                     ▼               │
        │  │                              ┌──────────────┐       │
        │  │                       ┌──────│Reconstructing│       │
        │  │                       │      └──────┬───────┘       │
        │  │                       │             ▼               │
        │  │                       │      ┌──────────────┐       │
        │  │                       ▼      │   Verified   │       │
        │  │                  ┌────────┐  └──────┬───────┘       │
        │  └──────────────────│ Failed │         ▼               │
        │     timeout/cleanup └────────┘  ┌──────────────┐       │
        │                                 │   Injected   │       │
        │                                 └──┬─────┬─────┘       │
        │     auto-send-off                  │     │             │
        ├────────────────────────────────────┘     │             │
        │     auto-send-on                         ▼             │
        │  ┌─────────┐                      ┌──────────────┐     │
        ◄──│SendReady│◄────────────────────►│  Clarifying  │─────┘
           └─────────┘    complete/dismiss  └──────────────┘
                                            (entered iff Clarifier.evaluate
                                             returned ≥1 FlaggedWord)
```

### Transition validity matrix

| From → To | Valid? | Notes |
|---|---|---|
| `Idle → Listening` | YES | user tap |
| `Listening → Transcribing` | YES | stop tap fires `stopAndProcess()` |
| `Listening → Idle` | YES | cancel (volume-down or twist-cancel) |
| `Transcribing → Stripping` | YES | ASR returned text |
| `Transcribing → Failed(HALLUCINATED)` | YES | `wasFullyHallucinated` |
| `Stripping → ReadingContext` | YES (L2+) | skip-direct-to-`Reconstructing` for L1 |
| `Stripping → Reconstructing` | YES (L1) | context read skipped |
| `ReadingContext → Reconstructing` | YES | always |
| `Reconstructing → Verified` | YES | Falcon + leak filter passed |
| `Reconstructing → Failed(*)` | YES | network or falcon-reject |
| `Verified → Injected` | YES | always |
| `Injected → SendReady` | YES | auto-send eligible AND clarifier returned 0 flags |
| `Injected → Clarifying` | YES | clarifier returned ≥1 flag (BLOCKS auto-send) |
| `Injected → Idle` | YES | no auto-send AND 0 flags |
| `Clarifying → SendReady` | YES | exit + auto-send was eligible (600ms hold first) |
| `Clarifying → Idle` | YES | exit + no auto-send |
| `Clarifying → Listening` | YES | user starts new recording mid-clarify (clarifier exits as REJECT-all) |
| `SendReady → Idle` | YES | auto-send timer expired or user cancelled |
| `Failed → Idle` | YES | after COMMISERATE affect completes |
| `* → Listening` | YES from Idle/SendReady/Failed/Clarifying | user always wins; recording is never blocked |
| `Reconstructing → Listening` | FORBIDDEN | in-flight ASR cannot be interrupted by record |
| `Clarifying → Reconstructing` | FORBIDDEN | clarifier is post-inject, no return to processing |
| `Idle → Clarifying` | FORBIDDEN | must come via `Injected` |
| `Verified → Clarifying` | FORBIDDEN | clarifier evaluation runs AFTER inject (needs lastInjectStart/End set) |

### Priority order when multiple sources want to drive a state change

1. **User direct gesture** (tap, swipe, twist) — always wins, except `Reconstructing → Listening` which is forbidden.
2. **Clarifier dismissal** (3s timeout, app-context loss, password-field detection) — high priority, exits cleanly.
3. **Pipeline phase advance** — fires via `BubbleStateBus.advance()` from `processText`.
4. **Affect/animation transitions** — NEVER drive state. They observe state and react.
5. **Prediction chip** — never drives state. Lives as a sidecar overlay on `Idle` only.

---

## 2. Shared resource ownership table

Five physically scarce resources on the bubble surface. Both specs reach for them. Locked ownership rules below.

| Resource | Owner-when-X | Priority order (highest first) | Queueing behavior | Conflict resolution |
|---|---|---|---|---|
| **Bezel pulse / ring animation** (`bubbleBezel`) | Pipeline-stage arc segments paint during `Transcribing → Verified`; clarifier owns during `Clarifying`; affect `RESTING` breathes during `Idle`; affect `LEANING_FORWARD` lifts during `Listening` | 1. Clarifier trigger pulse + approve/reject flashes (Spec B) 2. Failed COMMISERATE + crimson pulse (Spec A) 3. Pipeline stage arc (Spec A Direction 1) 4. Affect breathing/leaning (Spec A Direction 3) 5. Idle resting | Clarifier → if pipeline arc was mid-trace, instantly snapshot to "complete" and mute. Affect breathe → cancel the moment any state-driven animator starts. Pipeline arc → resumes only on next inject cycle. | Clarifier tells `BubblePersona.suspendArc()` on entry; resumes on exit. Affects observe state and self-cancel. |
| **Live transcript bar** (`showPipelineStage(stage, detail)` infra at line 2249) | Default: pipeline stage labels. While `Clarifying`: clarifier "did you mean → DANKESCHÖN" message. | 1. Clarifier candidate display 2. Pipeline stage label 3. Streaming partials (typewriter renderer) 4. Streak banners / micro-delights 5. Onboarding scripted text | Single `TranscriptBarController` with FIFO queue + priority-jump. Clarifier preempts immediately on entry, restores prior message on exit. Streaming partials suspended during `Clarifying`. | New file: `TranscriptBarController.kt` (~80 lines). Single owner. Existing `showPipelineStage` callers route through it. |
| **Voice command listener** (`OnDeviceRecognizer.onResults`) | Always listening when bubble visible. Dispatch chain: clarifier voice cycle (Spec B) → twist-trigger detector (existing) → primary dictation (existing) | 1. Clarifier ("next / yes / no / skip / swap") IFF `state is Clarifying` 2. Existing `detectTwistTrigger` 3. Primary `processText` route | New `VoiceCommandRouter` chain. Each handler returns `Boolean` (handled/not). First handler to return true short-circuits. | Hook in `OnDeviceRecognizer.onResults`. Strict-prefix command detection — single-token-only utterances flagged as commands while CLARIFY active. |
| **Idle timer** (3s clarifier auto-dismiss, 800ms anticipatory-prediction trigger window, breathe-pause-on-screen-off) | One shared `IdleTimer` utility, multiple subscribers with independent thresholds | N/A — independent timer slots, not contended | Each subscriber has its own slot ID. Reset per-subscriber, not globally. | New file: `IdleTimer.kt` (~60 lines). Holds N independent `Runnable`+`delay` slots keyed by string ID. |
| **Bubble label text** (`bubbleLabel` TextView) | Functional state IDLE/REC/PROC/SEND/FIX/PERM (existing), clarifier swap to flagged-word-with-? during `Clarifying`, micro-delight banner during streak | 1. Clarifier flagged word `DUNKEN?` 2. SEND/FIX/PERM functional 3. REC/PROC/IDLE functional 4. Micro-delights (only during IDLE) | Clarifier owns label EXCLUSIVELY while `Clarifying`. Affects (Spec A Direction 3) MUST NOT touch label — animate `bubbleBezel` and `bubbleInner` only. | Locked by sealed-class state. State-change handler is the only entry point that may call `bubbleLabel.text = ...`. |

---

## 3. Integrated affect catalog

Spec A's affect catalog stays as-is, with one explicit binding change: **CLARIFY entry IS the trigger for `HEAD_TILT`**. They are the same physical UX moment (bubble looks puzzled, asks the user a question). Same animation. One spec, one trigger, no competition.

| Affect | Trigger (state observed) | Animation (Spec A unchanged) | Notes |
|---|---|---|---|
| `RESTING` | `state == Idle` AND no prediction chip visible | 4.2s breathe (bezel scale 1.0 → 1.012, knob ±0.6° rotation, tick alpha +12%) | Auto-pauses when screen has been off > 2 minutes (battery rule) |
| `LEANING_FORWARD` | `state is Listening` | 180ms `OvershootInterpolator(1.6f)`, bezel +translationY -3dp, knob scale 1.0 → 1.04 | |
| `THINKING_SWAY` | `state is Reconstructing` AND elapsed > 1.5s | 2.6s sine sway, knob ±2.4° rotation, `EaseInOutCubic` | Suspended if `Clarifying` enters (it shouldn't — `Reconstructing → Clarifying` is forbidden — but defensive) |
| `HEAD_TILT` | `state is Clarifying` (entry) — INTEGRATED with Spec B clarifier trigger | 240ms `OvershootInterpolator(1.8f)`, knob rotation 0 → 8°, holds until exit | **Integration point**: fires SIMULTANEOUSLY with Spec B's clarifier trigger pulse. Clarifier owns the bezel pulse; HEAD_TILT owns the knob rotation. They run on physically separate views, no contention. On exit, knob unwinds 8° → 0° over 180ms while clarifier sparkle plays on bezel. |
| `TA_DA` | `state is Injected` (entry) | 380ms scale pop 1.0 → 1.10 → 0.96 → 1.0, 24ms haptic, single celebratory pulse ring | If next state is `Clarifying`, TA_DA still plays (success was achieved); HEAD_TILT begins immediately after TA_DA's 380ms completes. Sequenced, not overlapped. |
| `COMMISERATE` | `state is Failed` (entry) | 600ms Y-squash 1.0 → 0.96 → 1.0 (a sigh) | No haptic, no pulse rings. Bubble droops, recovers, returns to `Idle`. |

### Crossover rules (NEW — only relevant in reconciled architecture)

- **TA_DA → HEAD_TILT sequence on injected-with-flags**: Inject succeeded (TA_DA earned), but clarifier also flagged. Sequence: TA_DA full 380ms → on completion, advance state to `Clarifying`, fire HEAD_TILT + clarifier trigger pulse together. Reads as "got it in! …but wait, let me check one thing."
- **THINKING_SWAY mutual exclusion with Reconstructing brain-glyph oscillation**: Both fire during long L4 reconstruction. Sway is on knob, brain-glyph is on overlay strip — physically separate. Both run. Spec A risk note remains: needs live visual review.
- **RESTING auto-pause when prediction chip visible**: Both Spec A's prediction chip and Spec A's resting breathe are gentle motions. Running them simultaneously double-busies the IDLE moment. Rule: chip visible = breathe paused; chip dismissed = breathe resumes within 200ms.

---

## 4. Anticipatory prediction → clarifier integration

**Real synergy moment**, not a forced bridge. When the prediction engine is highly confident about completion at sentence boundaries, those predictions become high-quality candidates for clarifier flags at adjacent positions.

### Data flow

```
PredictionEngine.predictForContext(pkg, hour, weekday)
    ├─→ returns Prediction(recipient, tone, confidence)             [Spec A original]
    └─→ ALSO populates PredictionEngine.recentSentenceCompletions   [NEW]
        a sliding window of the last N inferred sentence-end → likely-next-word
        pairs from accumulated session history

Clarifier.evaluate(...)
    │
    ├─ for each candidate flagged word at sentence-boundary position:
    │   │
    │   └─→ PredictionEngine.candidatesForPosition(
    │           pkg, priorWords, position
    │       ): List<String>
    │       returns up to 3 high-confidence next-word predictions
    │       drawn from this user's history in this app
    │
    └─→ merge those into FlaggedWord.candidates AHEAD of phonetic-neighbor candidates
        (user's own history beats global vocab — speaker adaptation in action)
```

### Interface specification

```kotlin
// In PredictionEngine.kt — new sidecar method, additive
interface CandidateProvider {
    fun candidatesForPosition(
        pkg: String,
        priorWords: List<String>,    // up to 8 words preceding the flagged position
        position: Int,
        maxCandidates: Int = 3,
    ): List<String>
}

object PredictionEngine : CandidateProvider {
    override fun candidatesForPosition(
        pkg: String, priorWords: List<String>, position: Int, maxCandidates: Int
    ): List<String> {
        val ctx = recentSentenceCompletions[pkg] ?: return emptyList()
        val priorTrigram = priorWords.takeLast(3).joinToString(" ").lowercase()
        return ctx[priorTrigram].orEmpty().take(maxCandidates)
    }
}

// In Clarifier.kt — additive parameter
fun evaluate(
    finalText: String,
    wordConfidences: List<WordConfidence>,
    vocabulary: List<String>,
    onsetWeights: Map<String, Double>,
    recentSwaps: List<Pair<String, String>>,
    candidateProvider: CandidateProvider? = null,   // NEW
    contextPkg: String? = null,                      // NEW
): List<FlaggedWord> { ... }
```

When `candidateProvider != null` and the flagged word is at a sentence-boundary position (preceded by `.`, `?`, `!`, or position 0 in a fresh segment), the merge order for the FlaggedWord candidates list becomes:

1. Prediction engine candidates (this user's history in this app) — top of list
2. N-best alternates from Whisper / engine-agnostic ASR
3. Phonetic neighbors from per-user vocab
4. Generic vocab matches

This is the "your bubble learned how you talk to your boss in Slack" moment encoded in the candidate ranking.

---

## 5. Reconciled file list

| File | Source | Type |
|---|---|---|
| `app/src/main/java/com/wim/app/BubbleState.kt` | BOTH (new — replaces Spec A's PipelinePhase enum AND Spec B's CLARIFY state) | NEW |
| `app/src/main/java/com/wim/app/BubblePersona.kt` | A | NEW |
| `app/src/main/java/com/wim/app/BezelStageArcView.kt` | A | NEW |
| `app/src/main/java/com/wim/app/PredictionEngine.kt` | A (with §4 candidate-provider extension) | NEW |
| `app/src/main/java/com/wim/app/PredictionChipView.kt` | A | NEW |
| `app/src/main/java/com/wim/app/TypewriterTextRenderer.kt` | A (Secondary) | NEW |
| `app/src/main/java/com/wim/app/MicroDelights.kt` | A (Secondary) | NEW |
| `app/src/main/java/com/wim/app/Clarifier.kt` | B | NEW |
| `app/src/main/java/com/wim/app/ClarifierController.kt` | B | NEW |
| `app/src/main/java/com/wim/app/WordHighlightView.kt` | B | NEW |
| `app/src/main/java/com/wim/app/MultiPassDecoder.kt` | B (renamed from MultiPassWhisper — engine-agnostic) | NEW |
| `app/src/main/java/com/wim/app/SpeechRecognizerL1.kt` | RECONCILED (engine-agnostic abstraction) | NEW |
| `app/src/main/java/com/wim/app/TranscriptBarController.kt` | RECONCILED (single owner of `showPipelineStage`) | NEW |
| `app/src/main/java/com/wim/app/IdleTimer.kt` | RECONCILED (shared timer utility) | NEW |
| `app/src/main/java/com/wim/app/VoiceCommandRouter.kt` | RECONCILED (priority dispatch chain) | NEW |
| `app/src/main/res/drawable/glyph_listening.xml` + `glyph_transcribing.xml` + `glyph_stripping.xml` + `glyph_reading_context.xml` + `glyph_reconstructing.xml` + `glyph_verified.xml` | A | NEW (6 glyphs) |
| `app/src/main/res/drawable/prediction_chip_bg.xml` | A | NEW |
| `app/src/main/java/com/wim/app/BubbleService.kt` | BOTH (merged edits — see §10) | MODIFIED |
| `app/src/main/java/com/wim/app/MainActivity.kt` | A | MODIFIED (Predictions toggle + Clarify-sensitivity segmented) |
| `app/src/main/java/com/wim/app/WimAccessibilityService.kt` | BOTH (A: foreground-app subscriber list; B: `swapWordInLastInjection` method) | MODIFIED |
| `app/src/main/java/com/wim/app/BubbleDemoScript.kt` | A | MODIFIED (Mr. DNA onboarding) |
| `app/src/main/java/com/wim/app/WhisperClient.kt` → engine-agnostic `SpeechRecognizerL1` impl | B (with engine abstraction) | MODIFIED (existing concrete renamed to `WhisperApiL1.kt` implementing `SpeechRecognizerL1`) |
| `app/src/main/java/com/wim/app/ReconstructClient.kt` | B (wordCandidates return field) | MODIFIED |
| `app/src/main/java/com/wim/app/OnDeviceRecognizer.kt` | B (route through `VoiceCommandRouter`) | MODIFIED |
| `app/src/main/res/layout/bubble.xml` | A (BezelStageArcView added) | MODIFIED |
| `app/src/main/java/com/wim/app/WimTestReceiver.kt` | BOTH (A: `simulate_prediction`; B: `trigger_clarifier`) | MODIFIED |

**Total: 15 new Kotlin files, 7 new drawables, 9 modified files.**

---

## 6. Reconciled build order

The reconciled order interleaves both specs around shared substrate first, then layers visible features in priority of perceived value to George's stutter-test demo.

### Phase 0 — Shared substrate (LOCKED FIRST, ~6 hours)

1. `BubbleState.kt` sealed class + `BubbleStateBus` (RECONCILED)
2. `IdleTimer.kt` shared utility (RECONCILED)
3. `TranscriptBarController.kt` single owner of overlay strip (RECONCILED)
4. `VoiceCommandRouter.kt` priority dispatch chain (RECONCILED)
5. `SpeechRecognizerL1` interface + extract existing concrete into impl (RECONCILED — engine-agnostic boundary)

**Acceptance**: existing functionality preserved (recording → reconstruct → inject still works in Gmail). State machine drives existing behavior with no UX change. NO regressions on auto-Send chain.

### Phase 1 — Pipeline parade (Spec A Direction 1, ~2 days)

6. `BubblePersona.kt` skeleton + `PipelinePhase`-as-`BubbleState` mapping (A)
7. Retrofit `BubbleService.processText` to call `BubbleStateBus.advance(...)` at stage boundaries (A)
8. Route stage labels through `TranscriptBarController` (A using RECONCILED owner)
9. Minimum-dwell hold logic per layer (A)
10. (Skip BezelStageArcView and glyphs for now — text labels first to validate hypothesis)

**Acceptance**: stutter-test-runner.sh shows the parade flowing on each script. George spot-checks: does the wait feel different?

### Phase 2 — Clarifier plumbing (Spec B Phase 1+2 collapsed, ~1.5 days)

11. `Clarifier.kt` with thresholds + unit tests (B) — pure data, no Android
12. Add word-timestamp / word-confidence parsing to `SpeechRecognizerL1` interface + concrete impl (B, engine-agnostic)
13. `swapWordInLastInjection()` in `WimAccessibilityService` + unit tests (B)
14. `WordHighlightView.kt` underline overlay (B)
15. `ClarifierController.kt` driving the `Clarifying` state via `BubbleStateBus` (B)
16. Wire `Clarifier.evaluate` + `enter` into `BubbleService.processText` AFTER inject success (B)
17. Bubble label `DUNKEN?` flagged-word display + `1/3` counter via `bubbleTime` slot (B)
18. Wire left/right tap → approve/reject through new state-aware tap handler in `BubbleService` (B)
19. Voice command interception via `VoiceCommandRouter` (B using RECONCILED router)

### *** EARLIEST DEMO MILESTONE ***

**At end of Phase 2 the bubble is testable end-to-end on George's phone**:
- Pipeline parade narrating the 9-15s L4 reconstruction wait.
- Clarifier appearing for known-misheard cases ("dunken" → "Dankeschön") with tap-to-swap.
- All on the engine-agnostic L1 path — works with whatever ASR is plugged in.
- Functional plumbing complete; visual polish (arc view, affects, predictions) deferred.

This is the version George should test against the 4 reference stutter scripts. Decision gate: do the labels + clarifier feel like enough lift to ship a v1, or push through the polish layers first?

### Phase 3 — Visible polish (Spec A Direction 1 visuals + Spec A Direction 3 core affects, ~3 days)

20. `BezelStageArcView.kt` + 6 glyph drawables (A)
21. Insert `<BezelStageArcView>` into `bubble.xml` inside `bubbleBezel` (A)
22. Bezel pulse priority arbitration: clarifier suspends arc on `Clarifying` entry; resumes on exit (RECONCILED)
23. `BubbleAffect` core: `RESTING` + `LEANING_FORWARD` + `TA_DA` (A) — the 80% personality wins
24. Wire `HEAD_TILT` to `Clarifying` state entry — INTEGRATED, fires alongside clarifier trigger pulse (A+B)
25. Trigger-pulse + approve/reject bezel flashes (B) running on the same bezel surface, arbitrated by §2 ownership table

### Phase 4 — Multi-pass decoder + visible-anchor polish (Spec B Phase 4 + remaining clarifier polish, ~1.5 days)

26. `MultiPassDecoder.kt` — only runs on L4, wraps the L1 interface, returns disagreement counts (B, engine-agnostic — works whether L4 = cloud whisper or local fallback)
27. Word-position mapping after L4 reconstruction (B Risk #2 mitigation — augment L4 prompt to return `{final_index: raw_index}` map)
28. Clarifier-sensitivity segmented control in MainActivity Profile section (B)
29. Per-app no-flag list (B)
30. Compat-test sweep across 6 auto-send apps (B)

### Phase 5 — Anticipatory prediction (Spec A Direction 2, ~2 days)

31. `PredictionEngine.kt` with cold-start path from `AudienceContext` (A)
32. `PredictionChipView.kt` chip UI (A)
33. Wire chip into `setBubbleIdle()` with 800ms gate (A)
34. Add `CandidateProvider` interface — prediction engine surfaces sentence-boundary candidates to clarifier (RECONCILED §4)
35. Profile toggle in MainActivity (A)
36. Usage-history persistence + adaptive predictions (A — second pass after cold-start validated)

### Phase 6 — Full affect catalog + secondaries (Spec A remainder + secondaries, ~2 days)

37. `THINKING_SWAY` + `COMMISERATE` (A — the polish affects)
38. `TypewriterTextRenderer.kt` for streaming partials (A Secondary)
39. Variable success cues — branch on `SuccessCue` for PASTE/AUTO_SEND/COMMAND_REPLACE/REGEN_SUCCESS (A Secondary)
40. `MicroDelights.kt` — wink, streak banner, milestone (A Secondary, must defer first 20 injects)

### Phase 7 — Mr. DNA onboarding (Spec A Mr. DNA, ~1 day)

41. Extend `BubbleDemoScript.kt` to script the affect catalog into a 13s onboarding parade (A)
42. Add `Clarifying` step to onboarding script — bubble flags one fake word, demos the cycle UX (NEW INTEGRATION — onboarding teaches the clarifier UX too)
43. Skip-on-tap behavior (Spec A Risk mitigation)

**Total reconciled effort**: ~13-15 days for everything. **Earliest demo ship**: end of Phase 2 (~3 days from Phase 0 start). Phases 3-7 are progressive polish layers that ship independently.

---

## 7. Renumbered patent claim chain

Claims 1-3 unchanged (existing layered structure: bidirectional companion + accessibility / disfluency-L1-transfer / pre-roll-buffer-field-adaptive-cursor-aware). Spec A claims 4-10 and Spec B claims 4-6 renumbered into a single chain 4-13. Cross-references corrected.

**Claim 4** [from SPEC A — pipeline indicator]: *"The system of claim 1, wherein the persistent overlay further comprises a pipeline stage indicator visually segmented into a plurality of stages corresponding to discrete processing operations performed on the captured speech signal, wherein each segment is illuminated in temporal sequence as its corresponding processing operation completes, and wherein each segment is held visible for a minimum dwell period independent of actual operation duration such that perceived processing fluency is decoupled from underlying processing latency."*

**Claim 5** [from SPEC A — pipeline continuing-animation]: *"The system of claim 4, wherein at least one segment of the pipeline stage indicator displays a continuing animation when its corresponding processing operation exceeds a predetermined threshold duration, the continuing animation distinct from the segment's static illuminated state and signaling to the user that processing is ongoing rather than failed."*

**Claim 6** [from SPEC A — predictive contextual indicator]: *"The system of claim 1, wherein the persistent overlay further presents, prior to receipt of any user input initiating speech capture, a predictive contextual indicator displaying a predicted recipient or medium category and a predicted style parameter, the predicted values derived from a usage history conditioned on at least one of: foreground application identity, time of day, day of week, and visible recipient identifier extracted from the foreground application's accessibility node tree."*

**Claim 7** [from SPEC A — directional-dismiss + cooldown]: *"The system of claim 6, wherein the predictive contextual indicator is dismissable by a directional gesture distinct from the gesture initiating speech capture, and wherein repeated dismissals in a same foreground application context cause subsequent predictions for said context to be suppressed for a cooldown interval."*

**Claim 8** [from SPEC A — in-place tone edit]: *"The system of claim 6, wherein the predicted style parameter is editable in-place via a tap gesture on the predictive contextual indicator without leaving the overlay's persistent display state and without altering said style parameter's persisted default value."*

**Claim 9** [from SPEC A — idle low-amplitude periodic deformation]: *"The system of claim 1, wherein the persistent overlay exhibits a continuous low-amplitude periodic deformation in an idle state, the deformation distinct from any deformation occurring during user interaction or processing operations, said periodic deformation having a duration period of greater than two seconds and a peak amplitude of less than 2% of the overlay's resting visual dimension."*

**Claim 10** [from SPEC A — state-specific deformation patterns]: *"The system of claim 1, wherein the persistent overlay exhibits a state-specific deformation pattern selected from a finite set of pattern types, each pattern type corresponding to a distinct internal processing state, including at least: an attentive forward-lean pattern triggered by speech capture initiation; a contemplative oscillation pattern triggered by reconstruction operations exceeding a threshold duration; an inquisitive tilt pattern triggered by initiation of a clarification dialog; and a confirmation pulse pattern triggered by successful injection of reconstructed text into the host application."*

**Claim 11** [from SPEC B — per-word confidence + cycle, was Spec B claim 4, renumbered]: *"The system of claim 1, wherein the companion application: (a) extracts per-word confidence signals from the speech recognition pipeline, including but not limited to acoustic-decoder log-probabilities, multi-pass decoding agreement counts across two or more independent inference passes at differing decoding parameters, n-best alternate candidates, and phonetic-neighbor matches against a per-user learned vocabulary; (b) computes an uncertainty score for each transcribed word combining at least two of said signals; (c) selects a bounded subset comprising the N most-uncertain words, wherein N is no greater than five and wherein selection is gated by an uncertainty threshold tuned to under-flag rather than over-flag, such that the typical utterance produces zero flagged words; (d) presents said selected subset to the user as a sequential, cycle-through review interface displayed via a persistent floating overlay companion, wherein each word may be approved (substituting a system-suggested candidate into the previously injected text) or rejected (preserving the original word) without leaving the host application or text field; and (e) substitutes the approved candidate into the host application's text field via the same accessibility-service text-injection mechanism that performed the original injection, targeting only the substituted word's character range within the previously injected segment."*

**Claim 12** [from SPEC B — multimodal cycle interaction, was Spec B claim 5, cross-ref updated]: *"The system of claim 11, wherein the cycle-through review interface accepts approve and reject decisions through three coextensive input modalities: (i) directional touch interactions on bounded regions of the floating overlay corresponding to approve and reject affordances; (ii) recognized voice commands sourced from the same speech-recognition channel used for primary dictation, including command tokens that perform implicit advancement to the next flagged word after each decision; and (iii) auto-dismissal after a configurable inactivity interval of three seconds or less, wherein auto-dismissal applies a default-reject decision to all unreviewed flagged words such that the user's non-engagement preserves the originally injected text without modification."*

**Claim 13** [from SPEC B — visible-anchor underline overlay, was Spec B claim 6, cross-ref updated]: *"The system of claim 11, further comprising a transient visual indicator overlay displayed atop the host application's text field at the screen-coordinate boundaries of each flagged word, wherein the indicator is rendered through a separate operating-system-level overlay window distinct from the host application's process and visually identifies the words whose confidence values triggered selection without requiring modification of the host application's user interface or process state."*

**Claim 14** [NEW — emerges from reconciliation; integration claim]: *"The system of claim 11 in combination with claim 6, wherein at least one candidate suggested in the cycle-through review interface for a flagged word at a sentence-boundary position is sourced from the predictive contextual indicator's underlying usage-history model, such that words a particular user has previously dictated in a particular foreground application context are preferentially offered as alternates for low-confidence words occurring at sentence boundaries in that same context."*

This is the genuinely-new IP that emerges only when both architectures are merged. The clarifier candidate ranking conditioned on per-user per-app history is not in either source spec individually — it is a property of the integration. Worth filing as a deliberate dependent claim.

---

## 8. Engine-agnostic verification

### Confirmation

The reconciled spec contains NO references to:
- Moonshine
- sherpa-onnx
- whisper.cpp
- Specific cloud-whisper-1 model name in interface contracts (though "cloud whisper-1 default" is referenced in the build-order narrative as the current Phase 0-2 implementation, the interface itself is engine-neutral)

Where Spec A originally said "Whisper API or on-device" and "200-400ms (Whisper API or on-device)," the reconciled spec says "200-400ms (engine-dependent)." Where Spec B used `WhisperClient`, the reconciled spec uses `SpeechRecognizerL1` interface with `WhisperApiL1` as one concrete implementation. Where Spec B said `MultiPassWhisper`, reconciled is `MultiPassDecoder`.

### Interface definition

```kotlin
// app/src/main/java/com/wim/app/SpeechRecognizerL1.kt
interface SpeechRecognizerL1 {
    suspend fun transcribe(audioFile: File, requestWordTimings: Boolean = false): TranscribeResult
    val supportsWordTimings: Boolean
    val supportsLogprobs: Boolean
    val isLocal: Boolean    // tells controller whether to draw "TRANSCRIBING" stage at all
}

data class TranscribeResult(
    val text: String,
    val asrMs: Long,
    val lowConfidenceSegments: List<LowConfidenceSegment> = emptyList(),
    val wasFullyHallucinated: Boolean = false,
    val words: List<WordTiming> = emptyList(),  // may be empty if engine doesn't support
)

// Implementations (Phase 0 ships one — TBD per WiM dispatch Task 3):
class WhisperApiL1 : SpeechRecognizerL1 { ... }            // current cloud
class FallbackLocalL1 : SpeechRecognizerL1 { ... }          // engine TBD per dispatch Task 3
```

Both Clarifier (Spec B) and PipelinePersona (Spec A) consume only the interface. Stage labels still fire ("TRANSCRIBING") regardless of impl. Clarifier silently skips word-confidence flagging when `supportsWordTimings == false` — graceful degradation built in.

### Removed references checklist

- [x] No `Moonshine` class references in shared code
- [x] No `sherpa-onnx` library references
- [x] No `whisper.cpp` references
- [x] No `WhisperClient` references in NEW reconciled-spec code (existing concrete renamed to `WhisperApiL1` implementing the interface during Phase 0)
- [x] All "TRANSCRIBING" stage durations annotated as "engine-dependent" not "Whisper-specific"
- [x] All file names and class names in the new code use engine-neutral terms (`MultiPassDecoder` not `MultiPassWhisper`; `SpeechRecognizerL1` not `WhisperRecognizer`)

---

## 9. Reconciled-architecture risks

These are NEW risks introduced by integration. Spec A's risks (battery cost of breathe, contact-name extraction, dwell calibration, prediction false-positive cost, onboarding length, glyph design, claim 6 prior art, HERO_BUBBLE recovery, auto-Send preservation, label-color conflict) and Spec B's risks (logprob approximation, word-position mapping after L4, Compose field opacity, 3s timer reading-time, voice command false positives, multi-pass cost, sensitivity default) all REMAIN.

**Net-new reconciliation risks:**

1. **State-machine deadlock if `Clarifying` exit fails to fire `SendReady` correctly.** Auto-Send ordering requires that `Clarifying → SendReady` triggers the same `setBubbleSendReady()` call that `Injected → SendReady` would have triggered. Add explicit unit test: clarifier completes (all approved), auto-send eligible app → `setBubbleSendReady()` was called exactly once. **Test before shipping Phase 2.**

2. **TA_DA → HEAD_TILT sequencing race.** TA_DA is a 380ms animation; if `Clarifying` state advances before TA_DA completes, the HEAD_TILT may fire on top of an in-progress TA_DA scale animation. Both are on the inner knob (TA_DA scales it; HEAD_TILT rotates it). Solution: state advance to `Clarifying` is gated on a 380ms minimum-hold post-`Injected`. Affects subscribe to `BubbleStateBus.state` and observe ordered transitions, not simultaneous ones.

3. **Bezel pulse priority arbitration may visibly stutter if Failed fires during clarifier.** If Falcon rejects post-clarifier (impossible by design — clarifier runs after inject which runs after Falcon — but if architecture changes), the priority order says clarifier wins. Document this invariant in `BubbleStateBus.advance` as an assertion: `state is Failed` from `state is Clarifying` is FORBIDDEN.

4. **TranscriptBarController preemption visible flicker.** Clarifier preempts transcript bar instantly on entry. If a stage label was mid-typewriter-render, the cut-to-clarifier-message is visually abrupt. Mitigation: clarifier preempt animates a 120ms cross-fade rather than a hard cut. Test on slow device — Pixel 4a or older — where animations clip more visibly.

5. **VoiceCommandRouter starvation under flood.** If the clarifier handler is registered first and never returns false, primary dictation never fires. Defensive: clarifier handler MUST return false unless `state is Clarifying`. Add a router-level invariant: any handler that returns `true` outside of its declared active state is a logged error.

6. **Anticipatory prediction → clarifier candidate poisoning.** If the user has a low-quality history (5 awkward Slack messages all using the same wrong word), the prediction engine will surface that wrong word as a top candidate in the clarifier. This is "speaker adaptation working as intended" but produces visibly bad suggestions. Mitigation: candidate provider downweights if the suggestion was REJECTED in a recent clarifier cycle (close the feedback loop).

7. **`WordHighlightView` overlay window plus `BezelStageArcView` together = two new system overlay windows.** Battery + draw-overhead concerns. The overlay window for word highlights is short-lived (entered on `Clarifying`, removed on exit), so the steady-state overhead is one (BezelStageArcView is a child View, not a separate window). Acceptable but worth measuring.

8. **`Clarifying → Listening` transition (user starts new recording mid-clarify) cleanup ordering.** Both the clarifier overlay (WordHighlightView window) and the affect (HEAD_TILT) need to tear down BEFORE `Listening` enters, otherwise the `LEANING_FORWARD` affect tries to animate the inner knob while it's still rotated 8°. Fix: clarifier exit cleanup is synchronous (window remove, knob unwind kicked off) before the state advance to `Listening` is committed.

9. **Multi-pass decoder + thinking-sway + brain-glyph oscillation = three concurrent CPU loads during long L4.** Multi-pass decoder runs additional ASR inference; THINKING_SWAY runs a 2.6s ValueAnimator; brain-glyph oscillates a 14° comet on the bezel arc. Combined CPU load during a 12-second L4 reconstruct is non-trivial. Measure with `dumpsys batterystats` on a 5-minute heavy stutter session.

10. **Spec A's "PROC" amber label and Spec B's clarifier-flagged-word label are physically the same TextView.** Spec A keeps the existing PROC label flow; Spec B replaces label content with `DUNKEN?` during `Clarifying`. The state machine guarantees only one is active at a time, but if a state transition fires during a label cross-fade animation, the label may be in an inconsistent text state for ~80ms. Mitigation: cancel any in-flight label animator before any state-driven label text change.

---

## 10. File-conflict warning

These files are touched by BOTH specs. Sequential edits required. Recommend a single PR per file rather than parallel feature-branch edits.

| File | Spec A wants | Spec B wants | Sequencing recommendation |
|---|---|---|---|
| `BubbleService.kt` | Phase advance calls in `processText`/`stopAndProcess`; affect calls in lifecycle methods; prediction chip integration in `setBubbleIdle`; ~+200 lines | `Clarifier.evaluate` + `clarifierController.enter` after inject; `clarifierController.dismissAll()` in `setBubbleIdle` and recording start; tap-handler third branch for clarifier; ~+80 lines | **SEQUENTIAL.** Phase 0 substrate goes in first (`BubbleStateBus` wiring). Then Phase 1 retrofits stage advances. Then Phase 2 adds clarifier hooks (which observe state, no new entry points). Then Phase 5 adds prediction chip. Each phase = its own commit. **DO NOT** edit both spec's BubbleService changes in parallel branches — guaranteed merge conflict on `setBubbleIdle` (line 1465-ish) and the tap handler (line 685-ish). |
| `bubble.xml` | Add `<BezelStageArcView>` inside `bubbleBezel` | No layout changes (reuses existing `clarifierBgYes/No` widgets) | **NO CONFLICT.** Single addition from Spec A in Phase 3 step 21. |
| `WimAccessibilityService.kt` | Foreground-app subscriber callback list | New method `swapWordInLastInjection` | **NO CONFLICT.** Different sections. Add both in Phase 0 substrate setup if convenient, or independently per spec phase. |
| `MainActivity.kt` | Predictions toggle in Profile section | Clarifier-sensitivity segmented control in bubble menu / Profile | **PARTIALLY CONFLICTING.** Both add Profile-section UI. Lay out Profile section once with both controls in a single edit during Phase 5. Avoid two parallel Profile edits. |
| `WimTestReceiver.kt` | `simulate_prediction` action | `trigger_clarifier` action | **NO CONFLICT.** Different action handlers, different code paths. Add per-spec phase. |
| `OnDeviceRecognizer.kt` | (No explicit Spec A change; secondary streaming-partials only) | `onResults` interception via `VoiceCommandRouter` | **NO CONFLICT.** Spec B's change uses the RECONCILED `VoiceCommandRouter` which Phase 0 lays down. |
| `BubbleDemoScript.kt` | Drive affect catalog for Mr. DNA onboarding | (Not touched by Spec B originally) | **INTEGRATION OPPORTUNITY.** Add `Clarifying` step to the onboarding script in Phase 7 step 42 — teach the clarifier UX inside the onboarding parade. Single edit, no conflict. |

**Recommendation summary**: One developer (Claude main session) should own the BubbleService edits sequentially across phases. Other files can parallelize across phase boundaries safely. Lock the `BubbleState` sealed class definition in Phase 0 step 1 BEFORE either spec commits any code that touches the state surface — this is the single load-bearing lock.

---

**End of reconciled spec.**
