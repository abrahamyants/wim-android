# WiM Android — Test Coverage Analysis
Generated: 2026-05-10

---

## 1. Actual Test Inventory (with file paths)

| File | Tests | Scope |
|------|-------|-------|
| `DisfluencyFilterTest.kt` | 27 | Core filter behavior |
| `DisfluencyFilterStressTest.kt` | 26 | Adversarial / edge cases |
| `ClinicalDisfluencyTest.kt` | 24 | Real Whisper outputs + bilingual |
| `DisfluencyPreservationGuardTest.kt` | 1 (parameterized over ~30 cases) | Natural-repeat preservation regression gate |
| `WhisperTranscriptionTest.kt` | 11 | Real transcription + known-gap documentation |
| `RiskFlagsTest.kt` | 16 | Flag computation behavior |
| `RiskFlagsStressTest.kt` | 21 | Boundary conditions on flags |
| `ReconstructPromptTest.kt` | 17 | Tone/severity map integrity |
| `ReconstructClientTest.kt` | 17 | Payload construction + response parsing |
| `ProfileLearningTest.kt` | 7 | Position-aware matching algorithm |
| `ProfileLearningStressTest.kt` | 12 | Adversarial matching inputs |
| `ProfileManagerTest.kt` | 19 | SharedPrefs persistence (Robolectric) |
| `SessionHistoryTest.kt` | 19 | SQLite operations (Robolectric) |
| `ClarifierTest.kt` | 11 | Uncertainty scoring + flag evaluation |
| `PhoneticMatcherTest.kt` | 11 | Double Metaphone + first-letter guard |
| `RepunctuateTest.kt` | 27 | Capitalization + punctuation logic |

Total: ~266 distinct test cases across 16 files.

---

## 2. Phase 0–7 Component Coverage Status

### Tested (with dedicated test files)
- **Clarifier.kt** — ClarifierTest.kt (11 tests). Coverage quality: good.
- **PhoneticMatcher.kt** — PhoneticMatcherTest.kt (11 tests). Coverage quality: good.
- **Repunctuate.kt** — RepunctuateTest.kt (27 tests). Coverage quality: comprehensive.

### NOT TESTED — No test file exists

| Component | Phase | Testability | Severity |
|-----------|-------|------------|---------|
| `MultiPassDecoder.kt` | 4 | Pure logic, no Android deps, fully testable in JVM | 9 |
| `RateGapDetector.kt` | (post-Phase 7) | Pure `object`, zero deps, trivially testable | 8 |
| `PredictionEngine.kt` | 5 | Depends on `WimApp.prefs` (SharedPreferences); Robolectric solvable | 7 |
| `BubbleStateBus.kt` / `BubbleState.kt` | 0 | Pure Kotlin FSM, no Android deps in transition logic | 8 |
| `VoiceCommandRouter.kt` | 3 | Pure Kotlin dispatch chain, zero deps | 7 |
| `ClarifierController.kt` | 2 | Depends on `BubbleService`/`IdleTimer`/`WimAccessibilityService` — integration-level | 4 |
| `AudienceContext.kt` | 2/3 | Pure Kotlin map lookup, zero deps | 6 |
| `LangPack.kt` (normalizeLangCode) | cross-phase | `normalizeLangCode()` is pure, testable today | 5 |

### Confirmed NOT testable without device (correctly excluded)
- `BubbleService` — requires Android service lifecycle
- `OnDeviceRecognizer` / `SpeechRecognizerL1` — requires `SpeechRecognizer` Android API
- `WhisperClient` / `ReconstructClient` (HTTP path) — network
- `WimAccessibilityService` — requires accessibility framework
- `WimTestReceiver` — ADB-only manual bridge (see section 5)

---

## 3. Critical Gaps — Severity 8–10

### Gap 1: MultiPassDecoder — SEVERITY 9

**File:** `app/src/main/java/com/wim/app/MultiPassDecoder.kt`

The decoder's `wordConfidencesFromPrimary()` is non-trivial: it walks character offsets, handles punctuation stripping, aligns disagreement positions to primary-text word indices, and populates `nBestAlternates` by excluding the original word. Every one of these operations feeds directly into `Clarifier.evaluate()`, which the product's core patent claim (11) depends on. A bug in range calculation causes the wrong character range to be sent to `WimAccessibilityService.swapWordInLastInjection()`, silently injecting the wrong text into the host app.

**This function has zero test coverage.**

Specific failures a test suite would catch:
- Range offset off-by-one when the primary text has leading whitespace
- `<END>` sentinel leaking into `nBestAlternates` (the filter `it != "<END>"` could silently fail on a Unicode space variant)
- All-agreement fast path emits non-empty `disagreementSummary` (currently emits empty — but the fast-path `wordConfidencesFromPrimary(disagreements = IntArray(0), variants = emptyList())` has never been validated to produce correct ranges)
- Single-pass edge case (`passes.size < 2`) returns empty `wordConfidences` — correct behavior — but this is undocumented and could be changed accidentally

Testability: `MultiPassDecoder` only needs a `SpeechRecognizerL1` interface. A fake that returns `TranscribeResult(text = "...", asrMs = 0)` is trivial. The class is fully pure-Kotlin once the fake is in place. No Robolectric needed.

---

### Gap 2: BubbleStateBus transition matrix — SEVERITY 8

**File:** `app/src/main/java/com/wim/app/BubbleState.kt`

`BubbleStateBus.advance()` is the FSM gate for all 11 states and their allowed transitions. This is the highest-impact pure logic in the app: a bad transition silently allows the UI to enter an invalid state (e.g., `Reconstructing → Reconstructing` during an in-flight call, or `Clarifying → Listening` being blocked when the user taps record mid-cycle).

The transition matrix has a structural inconsistency worth flagging separately: the `to is BubbleState.Listening` guard at the top returns `true` for the default branch (`else -> true`), meaning any state can transition to `Listening` EXCEPT `Reconstructing`. But the states `Transcribing`, `Stripping`, `ReadingContext`, `Verified`, and `Injected` are NOT explicitly listed — they fall through to `else -> true`. That may be intentional (spec says "user gesture always wins") but it means a `Verified → Listening` transition is currently allowed. Tests would pin this contract.

Testability: `BubbleStateBus` uses `MutableStateFlow` which is pure Kotlin. The entire transition matrix can be tested without Android context by iterating over state pairs. No Robolectric needed.

Specific failures a test would catch:
- `Reconstructing → Listening` allowed (should be FORBIDDEN per spec — in-flight ASR cannot be interrupted)
- `Clarifying → Listening` blocked (should be ALLOWED — user starts new recording)
- `Verified → Listening` allowed/forbidden — currently falls to `else -> true`, undocumented
- `advance()` return value (`true`/`false`) never verified against expected allowed/forbidden pairs

---

### Gap 3: RateGapDetector — SEVERITY 8

**File:** `app/src/main/java/com/wim/app/RateGapDetector.kt`

This is a pure `object` with a single `analyze()` function that has zero dependencies. It implements George's rate-gap hypothesis: Samsung's native ASR pre-smooths disfluencies, so audio longer than the word count explains signals missing content. This signal feeds L4's recovery prompt.

The boundary conditions are the entire value of this component:
- `audioDurationSec < MIN_AUDIO_SEC_FOR_ANALYSIS` (1.5s) — clips shorter than this return `gapDetected = false` unconditionally. If this threshold is wrong, all short clips are silently mislabeled.
- `baseline == null` — insufficient history case. Currently returns `ratio = null, gapDetected = false`. This is the cold-start path that every new user hits for their first 10 sessions.
- Division by zero guard: `if (it > 0) wps / it else null` — correct, but untested.
- `threshold` parameter default is `GAP_RATIO_THRESHOLD = 0.6`. Boundary: `ratio < 0.6` fires the gap. The exact boundary (0.6 itself) is not tested.

These are all pure numerical computations. A test file is 20 lines of Kotlin, no mocking needed.

---

## 4. Important Gaps — Severity 5–7

### Gap 4: VoiceCommandRouter — SEVERITY 7

**File:** `app/src/main/java/com/wim/app/VoiceCommandRouter.kt`

Pure Kotlin dispatch chain. The router's spec comment explicitly calls out "router starvation" as Risk #5 — a handler that incorrectly returns `true` swallows all subsequent dictation. No test verifies:
- First handler returning `true` stops the chain (second handler never called)
- Exception in a handler is caught and does NOT stop the chain (the `catch` block returns `false`)
- `dispatch("")` returns `null` (blank guard)
- Duplicate registration via the `if (handlers.any { it.name == name }) return` guard — registers once, second call is no-op
- `unregister` removes exactly the named handler, not all handlers

A handler bug here causes ALL dictation to be swallowed silently — the user speaks and nothing happens — which looks identical to a BubbleService crash or mic permission failure.

---

### Gap 5: AudienceContext.profileForPackage — SEVERITY 6

**File:** `app/src/main/java/com/wim/app/AudienceContext.kt`

Pure Kotlin `when` expression. The `formatReaderBlock()` output feeds directly into L2/L3 reconstruction prompts. Bugs here affect every reconstruction for every user using a supported app.

Untested:
- `null` package returns `null` profile (already guarded, but untested)
- Empty string package returns `null`
- Known app packages (`com.slack`, `com.google.android.gm`, etc.) return expected profiles
- Fallback `when` branch for unknown packages with "mail"/"chat"/"messag" substrings
- `formatReaderBlock()` output for known vs unknown package — the "NEVER echo back" directive is present in the output string, testable

---

### Gap 6: PredictionEngine cold-start + confidence math — SEVERITY 7

**File:** `app/src/main/java/com/wim/app/PredictionEngine.kt`

The `predictForContext()` method has time-of-day confidence arithmetic (`confidence += 0.10` / `confidence -= 0.10`) and a `MIN_CONFIDENCE_FOR_CHIP = 0.55` gate. The move-to-front `recordCompletion()` list management caps at 5 per trigram and 64 total per pkg.

Specific untested behaviors:
- Work app outside business hours: `confidence = 0.6 - 0.10 = 0.50 < 0.55` → returns `null`. If this boundary shifts, chip appears/disappears unexpectedly.
- `recordCompletion` with the same completion twice: move-to-front deduplication — the list should not grow, and the item should appear at index 0.
- `MAX_HISTORY_PER_PKG = 64` cap: inserting entry 65 prunes to 64. Currently uses `entries.take(MAX_HISTORY_PER_PKG)` which preserves insertion order, not recency order — this is a potential logic bug.
- `candidatesForPosition` with blank `priorTrigram` returns `emptyList()` — currently guarded but untested.
- Corrupt JSON in SharedPreferences returns `emptyMap()` gracefully (the `catch` is there but untested).

This requires Robolectric for SharedPreferences, same as `ProfileManagerTest` already uses.

---

### Gap 7: LangPack.normalizeLangCode — SEVERITY 5

**File:** `app/src/main/java/com/wim/app/LangPack.kt`

`normalizeLangCode()` is pure and untested. It handles `null`, blank, `"en-US"`, `"es_ES"`, and edge inputs. The function directly controls which language pack is loaded. An incorrect normalization means a user's non-English filler list is never loaded, so disfluency stripping falls back to English-only defaults.

Specific untested inputs:
- `null` → `"en"`
- `""` → `"en"`
- `"en-US"` → `"en"`
- `"es_ES"` (underscore variant) → `"es"`
- `"ZH"` (uppercase) → `"zh"`
- `"zh-Hant-TW"` (three-part BCP47) → `"zh"` (split on `-`, first part only)

---

## 5. WimTestReceiver — ADB-Only, Not in Automated Tests

`WimTestReceiver` is a `BroadcastReceiver` that requires a running `BubbleService` and Android context. It is correct that it has no JVM unit tests. Its handlers (`handleTriggerClarifier`, `handleInjectRaw`, etc.) are integration paths only testable on-device.

The `trigger_clarifier` action parses a custom wire format (`"original,start,end|cand1,cand2"`). That parsing logic (`handleTriggerClarifier` internals) is non-trivial and currently untestable because it's a private method on `WimTestReceiver`. The parse error path silently returns an empty flags list and writes an error JSON — not a crash, but a silent failure. The format is hand-constructed in ADB invocations and has no schema validation. This is acceptable for a debug-only bridge but worth noting.

---

## 6. Test Quality Assessment

### What the existing tests do well

**DisfluencyFilter suite** (78+ cases across 4 files): Genuinely behavioral. Tests check the output contract ("contains 'want'", "does not contain 'um'") rather than internal state. The `WhisperTranscriptionTest` is especially valuable — it uses a real Whisper API transcription of a stuttered speech clip and explicitly documents known gaps (comma-separated repeat handling) with `assertTrue` reasoning rather than hiding failures. The `DisfluencyPreservationGuardTest` is a proper regression gate: a single parameterized pass over 30 fixture cases that would catch any future pipeline component breaking natural-repeat preservation.

**SessionHistoryTest and ProfileManagerTest**: Both use Robolectric correctly. The `migration_v1ToV2` test in `SessionHistoryTest` is the right approach — manually constructing the old schema and running the migration SQL is the only reliable way to test schema upgrades.

**ClarifierTest**: Tests behavior contracts, not implementation. The `capAt3Flags_evenWith5Uncertain` test verifies `MAX_FLAGS` enforcement. The `candidates_excludeRecentlyRejected` test exercises the feedback loop closure, which is a real regression risk.

### Tests that test constants, not behavior — LOW VALUE

`ReconstructPromptTest` is almost entirely testing that named constants have specific values (`temp_formal` asserts `0.1`, `severity_default` asserts `1.0`, etc.). This couples the tests to implementation details. If the constants change for a valid reason (e.g., prompt tuning), these tests break without any behavioral regression. The tests that are genuinely useful here are `toneRules_allDifferent` (ensures no accidental copy-paste duplication of rules) and `toneRules_allFourPresent` (structural integrity). The numeric constant assertions should be replaced or removed.

**Specific low-value tests:**
- `temp_formal`, `temp_professional`, `temp_casual`, `temp_friend` (4 tests) — testing that constants equal specific floats
- `severity_default`, `severity_highStress`, `severity_reading` (3 tests) — same problem
- `toneRule_formal_noContractions`, `toneRule_professional_business`, etc. — substring assertions on prompt text

These 7+ tests would fail on any legitimate prompt-tuning change and provide no regression value.

### Tests with copy-pasted algorithm logic — FRAGILITY RISK

Both `ProfileLearningTest` and `ProfileLearningStressTest` replicate the `findBestMatch` logic in a private `helper` function rather than testing `ProfileManager.learnFromReconstruction()` directly. This means if the production algorithm diverges from the helper, the tests pass while the shipped code is broken. The comment in `ProfileLearningTest` acknowledges this: `"The count threshold (3) is tested via ProfileManagerTest with Robolectric."` The `ProfileManagerTest.learning_needsThreeOccurrences` test is the correct approach — it tests the actual `ProfileManager` through its public API. The two `ProfileLearning*` test files are testing a replica, not the real code.

---

## 7. CI Coverage

`ci.yml` runs `gradle testDebugUnitTest` on every push and PR to `main`. This runs all tests in `app/src/test/`. Tests are blocking — CI fails if any test fails.

**Critical gap: no test results artifact uploaded.** If a test fails on CI, you see the Gradle output in the log but no structured JUnit XML report. Adding an artifact upload step for `app/build/test-results/` would make failures searchable and comparable across runs.

**No instrumented tests** (`androidTest/`) exist in the repo. All tests are JVM unit tests (pure Kotlin + Robolectric). This is correct given the component architecture — the testable logic is correctly isolated into Android-free classes.

**No coverage report step.** `testDebugUnitTest` runs tests but does not generate a JaCoCo coverage report. Adding `jacoco` to `build.gradle.kts` and uploading the HTML report as an artifact would close this gap. Not a blocking issue — the behavioral tests are the real value — but useful for identifying which branches of untested components are reachable.

---

## 8. Highest-Risk Untested Code Paths (Production Impact Ranking)

Ranked by: probability of hitting the path in production × severity of silent failure.

1. **MultiPassDecoder word-range calculation** (rank 1): Every L4 reconstruction goes through this. A range offset bug causes `swapWordInLastInjection` to replace the wrong character range in the host app's text field. Silent from the user's perspective until they notice their text is garbled. Frequency: every L4 session. Severity: injected text corruption.

2. **BubbleStateBus forbidden transition not enforced** (rank 2): `Reconstructing → Listening` must be FORBIDDEN (spec). If the `else -> true` permissive branch is ever reached for this pair, the user can interrupt an in-flight reconstruction, causing the pipeline to complete into a state the FSM doesn't expect. Frequency: low (requires specific tap timing) but the failure is a state corruption that could crash the service.

3. **RateGapDetector boundary conditions** (rank 3): Cold-start path (`baseline == null`) hits every new user for their first 10 sessions. If the `null` guard silently returns the wrong gap value, the L4 prompt gets wrong disfluency-recovery instructions for all new users.

4. **VoiceCommandRouter exception swallowing** (rank 4): The `catch` block in `dispatch()` returns `false` (correct). But if any handler throws AND the exception message contains null, `Log.e` with null message may behave differently across Android versions. More importantly: a thrown exception in the clarifier handler means the voice command was NOT handled — the user says "yes" to a clarifier prompt and it falls through to dictation, injecting the word "yes" into their text. Frequency: low, but the failure mode is exactly backwards from the intended behavior.

5. **PredictionEngine confidence boundary at 0.55** (rank 5): Work apps outside 9–17 produce `confidence = 0.50`, which is below the chip threshold. If the arithmetic accidentally produces `0.55` instead of `0.50`, the chip appears when it shouldn't. Cumulative frequency: every user of Slack/Gmail outside business hours.

---

## 9. Summary Table

| Component | Has Tests | Severity | Blocking |
|-----------|-----------|----------|---------|
| DisfluencyFilter | Yes (78+ cases) | — | — |
| Clarifier | Yes (11 cases) | — | — |
| PhoneticMatcher | Yes (11 cases) | — | — |
| Repunctuate | Yes (27 cases) | — | — |
| ProfileManager | Yes (19 Robolectric) | — | — |
| SessionHistory | Yes (19 Robolectric) | — | — |
| ReconstructClient (logic) | Yes (17 cases) | — | — |
| **MultiPassDecoder** | **No** | **9** | **Yes** |
| **BubbleStateBus** | **No** | **8** | **Yes** |
| **RateGapDetector** | **No** | **8** | **Recommended** |
| **VoiceCommandRouter** | **No** | **7** | **Recommended** |
| **PredictionEngine** | **No** | **7** | **Recommended** |
| **AudienceContext** | **No** | **6** | **Nice to have** |
| **LangPack (normalize)** | **No** | **5** | **Nice to have** |
| ClarifierController | No | 4 | No — integration-only |
| WimTestReceiver | No | — | Correctly excluded |
| BubbleService | No | — | Correctly excluded |
