# WiM Patent Claim Chain — Working Draft

Source: WiM SESSION_LOG_2026-04-26.md (claim threads), WiM SESSION_LOG_2026-04-27.md (Gemini stress-test, sharpened claim 1), Apr 30 implementation pass.

This file is the canonical pre-filing claim record. Operator (George Abrahamyants, JD) files provisional via Patent Center at $130 micro-entity fee; Claude maintains the source.

---

## Independent Claim 1 (sharpened from Apr 27)

> A method for cross-application voice manipulation on a mobile operating system, comprising:
>
> maintaining a persistent system-level overlay that remains visible across host application context switches;
>
> receiving voice input via said overlay;
>
> transmitting the voice input to a language model that produces a reconstructed text and a set of low-confidence token positions;
>
> entering a bidirectional clarification state in which the overlay displays a sequential review interface for the low-confidence tokens, **wherein the host application's window focus, accessibility focus, input method editor state, scroll position, and text-field cursor position remain unchanged for the duration of said clarification state**;
>
> receiving user **approve, reject, or defer** decisions via gestures on the overlay or voice commands sourced from the same speech-recognition channel;
>
> and, upon approval, modifying the host application's text field via an **operating-system accessibility framework providing programmatic text-field write access**, targeting only the substituted token's character range within a **target text segment**.

### Refinements vs. Gemini's draft

- Dropped `"remote"` from "language model" — future on-device LLMs fall inside.
- Added `"accessibility focus"` to the wherein-list — Android tracks it independently from window focus; examiner-attack hardening.
- Generalized `ACTION_SET_TEXT` → `"OS accessibility framework providing programmatic text-field write access"` — PCT-friendly, doesn't lock out iOS UI Automation / AssistiveTouch surfaces.
- Changed `"approve / reject"` → `"approve, reject, or defer"` — covers `ClarifierController.next()` cycle-skip (the third user-decision modality).
- Replaced `"previously injected segment"` → `"target text segment"` — covers Command Mode (drag-overlay-onto-selection) without spawning a second independent claim.

---

## Dependent Claims (supporting claim 1)

### Claim 2 — pre-roll audio buffer

> The method of claim 1, wherein the system maintains a continuous pre-roll audio buffer captured prior to user-initiated dictation, said buffer prepended to the dictation audio on activation, ensuring capture of speech preceding the user's tap-to-record gesture.

**Implementation evidence:** `AudioRecorder.primeBuffer()` opens an `AudioRecord(MIC)` instance the moment `showBubble()` runs. Captured audio is written to a circular buffer; on tap, the buffer is prepended to the recorded WAV before transmission. Wispr Flow, Apple Dictation, Google Voice Typing, Dragon Anywhere — none publicly document a pre-roll capture matching this architecture.

### Claim 3 — multi-pass agreement counts (claim 11(a) from Apr 26)

> The method of claim 1, wherein the language model output is cross-validated by re-decoding the dictation audio at multiple sampling temperatures, and per-token confidence is computed from cross-pass disagreement counts at each token position.

**Implementation evidence:** `MultiPassDecoder.run()` runs three whisper-1 passes at temperatures 0.0 / 0.4 / 0.8 in parallel. `MultiPassResult.wordConfidences` carries the per-token `multiPassDisagreements` count. Currently triggered at L2+ in production.

### Claim 4 — bounded review surface (claim 11(c) from Apr 26)

> The method of claim 1, wherein the bidirectional clarification state displays no more than three flagged tokens in a single cycle, regardless of the number of low-confidence positions identified.

**Implementation evidence:** `Clarifier.MAX_FLAGS = 3` hardcoded. Sorts by uncertainty score, takes top 3.

### Claim 5 — accessibility-mediated token swap (claim 11(e) from Apr 26)

> The method of claim 1, wherein upon receipt of an approve decision, the system replaces only the flagged token's character range within the target text segment via the accessibility framework's text-write action, leaving all other characters of the target text segment unchanged.

**Implementation evidence:** `WimAccessibilityService.swapWordInLastInjection(rangeStart, rangeEnd, replacement)` performs a precision range-replace using `ACTION_SET_TEXT` on a reconstituted full-text string that differs from the prior text only in the flagged range.

### Claim 6 — visible-anchor underline overlay (claim 13 from Apr 26)

> The method of claim 1, further comprising rendering a visible underline element in a separate, non-touchable overlay window positioned over the host application's text field for the duration of said clarification state, and wherein said underline does not modify the host application's text field state.

**Implementation evidence:** `WordHighlightView` (Apr 30 rebuild from selection-based to overlay-based). Window flags `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`. Sub-region horizontal estimate from char-offset ratio narrows the underline to the approximate word position when per-character bounds aren't available.

### Claim 7 — candidate priority order (claim 14 from Apr 26)

> The method of claim 1, wherein the sequential review interface presents replacement candidates ordered by a priority comprising:
> (a) sentence-boundary continuation predictions derived from the user's prior accepted reconstructions in the same host application;
> (b) ASR n-best alternates from the original transcription pass;
> (c) phonetic neighbors drawn from the user's per-application vocabulary.

**Implementation evidence:** `Clarifier.buildCandidates` walks these three sources in order, dropping previously-rejected swaps via `recentSwaps`.

### Claim 8 — same-channel voice commands

> The method of claim 1, wherein the user's approve / reject / defer decisions, when issued by voice, are routed through the same speech-recognition channel that produced the original reconstructed text, without instantiating a separate recognizer instance.

**Implementation evidence:** `VoiceCommandRouter` claims voice commands ("yes", "no", "next", "skip", "swap") on the same `OnDeviceRecognizer` instance the dictation pass used.

### Claim 9 — application-context invariance verification

> The method of claim 1, further comprising recording host application state (window focus, IME state, scroll position, cursor position) at clarification-state-entry, recording said state at clarification-state-exit, and asserting equality, such that the system maintains evidentiary record of compliance with the wherein-clause.

**Implementation evidence:** Pending — a `WimTestReceiver` debug action would instrument this in DEBUG builds. Apr 30 plan to add ~40 lines.

---

## Independent Claim 2 (Apr 26 thread #2)

> A method for spatial-context voice command dispatch on a mobile operating system, comprising: presenting a persistent system-level overlay; receiving a drag gesture in which the overlay is moved into spatial proximity with a region of selected text in a host application's text field; receiving voice input via said overlay; transmitting the voice input and the selected text to a language model with a system instruction to apply the voice input as a transformation directive on the selected text; and replacing the selected text with the model's output via an OS accessibility framework providing programmatic text-field write access.

**Implementation evidence:** Command Mode — `BubbleService.checkCommandModePickup()` requires bubble-center-inside-field-bounds + non-empty selection range. `processCommand()` packages the source + voice command for `wim-reconstruct` Cloud Function (action: command, deployed Apr 30). `replaceSelection()` does the in-place swap.

---

## Independent Claim 3 (Apr 26 thread #4) — disfluency-aware reconstruction

> A method for layered reconstruction of disfluent voice input, comprising:
>
> selecting one of plural reconstruction layers based on a user-configurable parameter;
>
> at a first layer, performing deterministic post-processing including filler-word stripping, repetition collapse, and capitalization restoration;
>
> at a second layer, additionally invoking a language model with a system prompt comprising a strunk-and-white prose-rewriting directive and an audience-context block derived from the host application's package metadata;
>
> at a third layer, additionally injecting a per-user adaptive vocabulary, prior accepted-style examples, and prior rejected outputs as few-shot context;
>
> at a fourth layer, additionally invoking a clinical-grade prompt parameterized by the speaker's first language, identifying overt disfluencies (part-word repetitions, prolongations, blocks) and covert disfluencies (synonym substitution, circumlocution, sentence abandonment) for removal.

**Implementation evidence:** `ReconstructClient.reconstruct()` walks the layered prompt-builder; L1 path in `BubbleService.processText()` runs the deterministic pass; L2/L3/L4 routes through `wim-reconstruct` Cloud Function with appropriate layer-specific prompt augmentation.

**Gating risk:** `US20250246187A1` (filed 2025) — disfluent speech transcription/detection. Not surfaced in Gemini's 41-patent search. Claim-by-claim review pending (Session 18 open item).

---

## Prior-art positioning (Apr 27 stress-test, retained)

- **No § 102 anticipation** — none of the prior art (Wispr Flow, AssistiveTouch, Messenger chat heads, Apple Dictation, Google Voice Typing, US9465833B2, US8370147B2, WO2017143338A1) implements the application-context invariance wherein-clause. Voice-assistant prior art changes at least one host-app state variable (focus, scroll, cursor, IME).
- **§ 103 obviousness** is the real fight — KSR teaching-away defense is solid because IMEs are the OS-mandated input path; choosing accessibility introduces real complexity an examiner can't dismiss as "obvious to try."
- **Wispr Flow comparison** at commercial-feature level: Wispr Command Mode = IME-resident, NOT accessibility-mediated. Wispr does NOT have auto-Send-via-accessibility nor bidirectional clarification UI. Substrate model + claim 1 wherein-clause distinguish.

---

## Pickup list (claim-side)

1. Run 8 keyword strings through Google Patents + USPTO PPUBS (from Apr 27 list).
2. Claim-by-claim review against `US20250246187A1` (Gemini missed it).
3. Add claim 9 verification instrumentation (~40 lines, debug build).
4. File provisional via Patent Center; $130 micro-entity fee; operator files self.
