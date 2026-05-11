# Test Coverage Gap Report — wim-android
**Date:** 2026-05-10
**Audited files:** L1PackHelper.kt, MultiPassDecoder.kt, BubbleService.kt (setBubbleIdle + L4 loop), WimApp.kt (clarifier default), BubbleState.kt (forceTo)
**Test directory:** app/src/test/java/com/wim/app/

---

## Summary

The 21-file test suite (350 tests) is well-structured overall. The freshly-added code introduced three
meaningful gaps and one systemic threat. None of the six audited changes have zero test coverage, but the
L1PackHelper polish/keep branching is entirely untested, the MultiPassDecoder's asrLogprob wiring has a
real logic hole with no regression guard, and the clarifier default change to "medium" silently changes
threshold behavior in any test that creates a fresh SharedPreferences without explicitly setting the key.

---

## Critical Gaps (Severity 8-10)

### 1. L1PackHelper.promptInjection — "keep" mode branch (Severity: 9)

**What is untested:** The entire `keepMode == true` code path in `promptInjection()`. There is no test
file for `L1PackHelper` at all.

**Two distinct behaviors with zero coverage:**
- `polish` mode (default): emits "NORMALIZE these patterns:" block with `promptHint` + both example sides
- `keep` mode (`accent_mode == "keep"`): emits "PRESERVE these authentic voice patterns" block with only
  `e.input` (not `e.output`), and a different framing paragraph

**The regression this prevents:** A future refactor that accidentally merges the two branches or swaps
their content would send the wrong instruction to the model — the opposite of what the user set. A Russian
speaker who explicitly chose "preserve my accent" would get normalization instructions instead. The
branches are structurally distinct: keep mode omits `e.output`, uses `m.mechanism.substringBefore(";")`
instead of `m.promptHint`, and produces a different closing sentence.

**Additional untested logic in L1PackHelper:**
- `loadPack` with `language == "en"` (early-return null path)
- `loadPack` cache hit path (second call same language)
- `loadPack` asset-not-found path (exception → cache null → return null)
- `activePack` reading `profile_l1` from prefs
- `promptInjection` returning empty string when no pack active
- `languageDisplayName` for the `else` branch (unknown language code)
- `BUILTIN_KEYS` membership

**Suggested test structure:**
```kotlin
// Needs Robolectric (WimApp.prefs access)
@Test fun promptInjection_keepMode_doesNotContainNormalize() {
    WimApp.prefs.edit().putString("accent_mode", "keep").commit()
    // inject a mock pack via cache, call promptInjection(mockContext)
    assertFalse(result.contains("NORMALIZE"))
    assertTrue(result.contains("PRESERVE"))
}

@Test fun promptInjection_polishMode_doesNotContainPreserve() {
    WimApp.prefs.edit().putString("accent_mode", "polish").commit()
    assertFalse(result.contains("PRESERVE"))
    assertTrue(result.contains("NORMALIZE"))
}

@Test fun promptInjection_keepMode_omitsOutputSideOfExamples() {
    // keep mode only shows e.input, never e.output
    // if this regresses, model gets misleading correction examples
}
```

---

### 2. MultiPassDecoder.wordConfidencesFromPrimary — asrLogprob wiring (Severity: 8)

**What is untested:** The `lowConfLogprobs` map logic. Every test in `MultiPassDecoderTest.kt` constructs
a `TranscribeResult` with the default `lowConfidenceSegments = emptyList()`. This means
`lowConfLogprobs` is always an empty map, `asrLogprob` is always `null` for every word, and the
`asrLogprob = lowConfLogprobs[wordKey]` assignment on line 164 has never been exercised.

**The bug this hides:** There is a duplicate-key collapse in the `lowConfLogprobs` construction:

```kotlin
val lowConfLogprobs: Map<String, Double> = primary.lowConfidenceSegments
    .flatMap { seg ->
        seg.text.lowercase().split(Regex("\\s+"))
            .filter { it.any(Char::isLetterOrDigit) }
            .map { tok -> tok.filter(Char::isLetterOrDigit) to seg.avgLogprob }
    }
    .toMap()   // duplicate keys: LAST one wins
```

If the same word token appears in two different low-confidence segments with different `avgLogprob`
values (e.g., "the" at -0.9 in segment 1 and "the" at -2.1 in segment 2), `.toMap()` silently drops all
but the last entry. The clarifier therefore receives the wrong logprob for repeated tokens, potentially
suppressing a flag that should fire.

**No test currently verifies:**
- A word in a low-confidence segment gets non-null `asrLogprob`
- A word NOT in any low-confidence segment gets null `asrLogprob`
- Duplicate word token across two segments (last-wins behavior, or documenting it as intentional)
- `asrLogprob` flows through to `WordConfidence.asrLogprob` at all

**Suggested test:**
```kotlin
private fun segRecognizer(text: String, lowConfSegs: List<WhisperClient.LowConfidenceSegment>): SpeechRecognizerL1 =
    object : SpeechRecognizerL1 {
        override suspend fun transcribe(...): TranscribeResult =
            TranscribeResult(text = text, asrMs = 10, lowConfidenceSegments = lowConfSegs)
        ...
    }

@Test fun asrLogprob_populatedForWordInLowConfSegment() = runBlocking {
    val seg = WhisperClient.LowConfidenceSegment("dunken", avgLogprob = -2.3, noSpeechProb = 0.1, blockSuspect = false)
    val decoder = MultiPassDecoder(segRecognizer("dunken thanks", listOf(seg)))
    val result = decoder.run(dummyFile)
    assertNotNull(result.wordConfidences[0].asrLogprob)
    assertEquals(-2.3, result.wordConfidences[0].asrLogprob!!, 0.01)
}

@Test fun asrLogprob_nullForWordNotInLowConfSegment() = runBlocking {
    val seg = WhisperClient.LowConfidenceSegment("dunken", avgLogprob = -2.3, noSpeechProb = 0.1, blockSuspect = false)
    val decoder = MultiPassDecoder(segRecognizer("dunken thanks", listOf(seg)))
    val result = decoder.run(dummyFile)
    assertNull(result.wordConfidences[1].asrLogprob) // "thanks" not in low-conf seg
}
```

---

## Important Improvements (Severity 5-7)

### 3. WimApp.clarifierSensitivityThreshold — default "medium" change (Severity: 7)

**The threat to existing tests:** `clarifierSensitivityThreshold()` reads from `WimApp.prefs`. Any test
that:
1. Uses Robolectric + injects a fresh `SharedPreferences` without explicitly setting `"clarifier_sensitivity"`
2. Calls anything that eventually calls `clarifierSensitivityThreshold()`

...previously got `999.0` (disabled), now gets `0.55` (active).

**Current exposure:** Grepping all 21 test files for `clarifierSensitivityThreshold` returns zero hits —
no test currently exercises this method directly. However the `PredictionEngineTest` and `ProfileManagerTest`
both inject fresh prefs via `WimApp.prefs = prefs` with `prefs.edit().clear().commit()`. If either test
class ever calls a code path that internally reads `clarifierSensitivityThreshold()`, the behavior
silently changed.

**Missing tests that should now exist:**
```kotlin
// Needs Robolectric, uses WimApp.prefs injection pattern from PredictionEngineTest
@Test fun clarifierThreshold_noPrefsSet_returnsDefaultMedium() {
    // fresh prefs, no key set
    assertEquals(0.55, WimApp.clarifierSensitivityThreshold(), 0.001)
}

@Test fun clarifierThreshold_off_returns999() {
    prefs.edit().putString("clarifier_sensitivity", "off").commit()
    assertEquals(999.0, WimApp.clarifierSensitivityThreshold(), 0.001)
}

@Test fun clarifierThreshold_low_returns0point85() {
    prefs.edit().putString("clarifier_sensitivity", "low").commit()
    assertEquals(0.85, WimApp.clarifierSensitivityThreshold(), 0.001)
}

@Test fun clarifierThreshold_unknownValue_returns999() {
    prefs.edit().putString("clarifier_sensitivity", "banana").commit()
    assertEquals(999.0, WimApp.clarifierSensitivityThreshold(), 0.001)
}
```

The "unknown value → 999.0" case is the most important: it's the safe fallback, and it's currently
only described in a code comment. No test pins it.

---

### 4. BubbleService.setBubbleIdle — prediction chip branch (Severity: 5)

**What is untested:** The new `PredictionEngine.predictForContext(pkg)` branch inside `setBubbleIdle()`.
`BubbleService` is a Service and can't be unit-tested without a full instrumented test setup, which the
project doesn't currently use. This is expected.

**Why it matters enough to note:** The new code touches `bubbleTime` visibility in two places with
different conditions (`pred != null` vs `predictionsEnabled()`). A logic inversion — e.g., setting
`GONE` instead of `VISIBLE` on the non-null branch — would silently hide the prediction chip even when
predictions are on and a prediction is available. There is no existing instrumented test for `setBubbleIdle`.

**Mitigation path (if coverage is desired):** Extract the chip-text computation into a pure function:
```kotlin
// testable without Service context
internal fun predictionChipText(pkg: String?, enabled: Boolean): String? =
    if (!enabled) null else PredictionEngine.predictForContext(pkg)?.tone?.take(3)?.uppercase()
```
Then the logic is coverable from a plain unit test.

**Current priority:** Low without extraction refactor. Not blocking.

---

### 5. BubbleService — L4 while(true) progress loop (Severity: 5)

**What is untested:** The `turboTaxJob` coroutine at BubbleService line ~3778. It runs staged messages
then enters a `while(true) { delay(2000); elapsedSec += 2 }` that counts up until `turboTaxJob.cancel()`
is called.

**The regression risk:** If `turboTaxJob.cancel()` is not called (e.g., because the
`finally` block or the cancellation site is missing/removed), the loop runs indefinitely after the
pipeline completes, calling `showPipelineStage` on a view that has already moved to a different state.
On older Android (API < 31), calling `Handler.post` from a cancelled coroutine context after the Service
is destroyed can cause a crash.

**Why this is low priority for tests:** `BubbleService` coroutine orchestration is not practically
testable without a running Service. The risk is real but the fix path is architectural (not test-driven).
Flag for code review: confirm `turboTaxJob?.cancelAndJoin()` (or at minimum `turboTaxJob?.cancel()`) is
called in both the success path and the error path before exiting the `processText` coroutine scope.

---

## Test Quality Issues

### 6. BubbleStateBus.forceTo — tested but docstring-only semantics not pinned (Severity: 3)

**Current coverage:** Two tests in `BubbleStateBusTest` cover `forceTo()`:
- `forceTo_bypasses_matrix_and_sets_state`: proves Idle → Injected works
- `forceTo_fromInjected_toClarifying`: proves it updates state correctly

**What's missing:** The docstring says "Debug/test use only — never call from production code paths."
No test pins the contract that `forceTo` does NOT emit through the transition validator (i.e., that
calling it on a forbidden transition returns no error, logs nothing, and just sets state). The existing
tests only verify the positive case. A test that verifies `forceTo` on a truly nonsensical transition
(e.g., Idle → Reconstructing) still succeeds and sets state would lock in the bypass semantics:

```kotlin
@Test fun forceTo_completelyForbiddenTransition_stillSetsState() {
    // Idle → Reconstructing is forbidden via advance; forceTo should bypass silently
    BubbleStateBus.forceTo(BubbleState.Reconstructing(0L, layer = 4))
    assertTrue(BubbleStateBus.current() is BubbleState.Reconstructing)
}
```

This is low severity because the existing tests already prove the bypass works for the demo use case.

---

## Positive Observations

- `BubbleStateBusTest` is excellent. 30+ tests covering every edge of the transition matrix including
  the critical Reconstructing → Listening FORBIDDEN guard. Uses `@Before reset()` correctly to avoid
  singleton contamination between tests.

- `MultiPassDecoderTest` covers the happy paths, edge cases (empty, single-word, single-temperature,
  unicode), and the nBest filtering well. The test fake (SpeechRecognizerL1 implemented inline) is the
  right pattern for the interface — no mocking framework needed.

- `ClarifierTest` tests the threshold parameter explicitly and the `Clarifier.evaluate` signature
  accepts `threshold` as a parameter, which is the right design — it means the test doesn't depend on
  `WimApp.prefs` at all. The default-change in `WimApp.clarifierSensitivityThreshold()` therefore does
  NOT invalidate any existing `ClarifierTest` assertions (the tests pass threshold directly).

- The `PredictionEngineTest` / `ProfileManagerTest` pattern of injecting `WimApp.prefs` via the
  `internal set` accessor is clean and reusable. Any new tests for `clarifierSensitivityThreshold`
  should follow this exact pattern.

- No existing test is overfit to implementation. The `MultiPassDecoderTest` fakes test behavior
  (what words come back) not mechanism (how the decoder calls the recognizer).
