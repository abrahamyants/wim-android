# Session Log — March 28, 2026

## Context
Resumed work on WiM Android after terminal crash. George + Claude Code (Opus 4.6, 1M context).

## What We Did

### 1. Recovered from terminal crash
- Identified uncommitted work: Google Sign-In UI, brand plate redesign with brass screws, BubbleService drag fix
- All changes were in working tree, not lost

### 2. Bubble pulse rings — the saga
- **Original bug:** Pulse rings (radiating lines when recording) were in a separate overlay window. When dragging the bubble, rings stayed at the old position.
- **Attempted fix 1:** Removed the code that killed rings during drag, kept `syncRingPosition()`. Didn't work — `syncRingPosition()` silently failed for unknown reasons.
- **Attempted fix 2:** Replaced rings with a bezel glow/scale animation. George rejected — "makes the button heavy, harder to move."
- **Attempted fix 3:** Embedded ring views inside the bubble layout, expanded window to 200dp. Broke drag tracking due to coordinate shifts and touch area issues.
- **Final solution:** Embedded ring views in bubble.xml, fixed window at 120dp, added touch radius check (only center 30% responds to taps). Rings animate inside the bubble's own view — no separate window, no syncing needed. Moves with bubble by definition.

### 3. Continuous listening
- Android's SpeechRecognizer auto-stops on silence. George wanted toggle behavior: tap to start, tap to stop.
- Modified `OnDeviceRecognizer` to restart automatically on `onResults` and `onError` (NO_MATCH, SPEECH_TIMEOUT) when `keepListening` flag is true.
- User speaks, pauses, speaks more — all accumulated. Only stops on second tap.

### 4. Build error discovery
- `android:paintFlags="8"` in activity_main.xml is not a valid XML attribute — was causing build failures.
- Removed it. This was blocking all builds from the start.
- Discovered that `gradlew.bat installDebug` works from terminal — Android Studio (Panda 2) was never needed.

### 5. Bubble opacity bug
- `bubbleView?.alpha = 0.7f` was set after L1 processing and never reset.
- After first dictation, entire bubble permanently 70% transparent regardless of opacity setting.
- Fixed: replaced with `setBubbleIdle()`.

### 6. Size slider recalibration
- George wanted current visual size (110%) at the slider midpoint.
- Changed range from 50-150 to 70-150, default 110%.

### 7. Bubble border color
- Changed bezel stroke from gray (#AAAAAF) to orange (#EE5A24) to match timer accent.

### 8. OpenAI API key replacement
- OpenAI disabled George's API key. Confirmed with `curl` (401).
- Generated new key, wrote to `lavrentiy/api_key.txt`.
- Injected into WiM Android on Jana's phone via `adb shell run-as` SharedPreferences edit.

### 9. Installed WiM on Jana's phone
- Samsung SM-S936U connected via USB.
- `gradlew.bat installDebug` deployed directly.
- API key injected via adb.
- Google Sign-In failed ("no credentials available") — likely SHA-1 fingerprint mismatch. Deferred.

### 10. Full codebase audit (Round 1)
Ran comprehensive code audit across all 11 components. Found:
- Double text injection race condition
- Stale accessibility node causing silent injection failure
- "Ready" status blocking signed-in users
- Layer I label "Whisper only" was wrong (it's on-device ASR)
- Corrections lost when vocabulary list empty
- WhisperClient.kt and AudioRecorder.kt are dead code
- No test suites

### 11. Play Store readiness fixes (Round 1) — 5 fixes
1. `processed` flag prevents double `processText()` call
2. `node.refresh()` + Toast before accessibility paste
3. Ready status accepts `AuthManager.isSignedIn`
4. Layer I label → "On-device"
5. Corrections sent when `vocabulary.isNotEmpty() || corrections.isNotEmpty()`

### 12. Feature comparison: WiM vs Lavrentiy
- Full feature-by-feature comparison table (50 features)
- WiM implements ~20% of Lavrentiy's feature surface
- Core pipeline is solid; clinical/analytics stack absent
- Identified 10 "lazy" features that should have been ported

### 13. Implemented 8 of 10 improvements
1. **Critical token retention** — regex check for numbers/amounts
2. **Risk flags** — very_short_output, large_length_delta, unfinished_fragment, critical_token_lost
3. **Prolongation detection** — `\b(\w)\1{3,}(\w+)` added to DisfluencyFilter
4. **Profile learning persistence** — correctionCounts saved to SharedPreferences
5. **OCD redo detection** — Jaccard similarity, warns after 3 consecutive similar recordings
6. **Falcon validation** — GPT meaning-preservation check, rejects bad output in SAFE mode
7. **Session history** — New SessionHistory.kt with SQLite table
8. **Orange bubble border** — visual accent matching timer

Skipped: Mode settings (unnecessary for consumers), Situation presets (too presumptuous), Whisper for L4 (deferred to v2)

### 14. Full codebase audit (Round 2)
Found 5 more issues:
1. Falcon/risk flags only on direct path, not backend
2. Manifest missing PROPERTY_SPECIAL_USE declaration
3. learnFromReconstruction receiving unfiltered rawText
4. ERROR_RECOGNIZER_BUSY killing continuous listening
5. setBubbleProcessing() was a no-op stub

### 15. Play Store readiness fixes (Round 2) — 5 fixes
1. Risk flags now run on backend path too
2. Manifest: added FOREGROUND_SERVICE_MICROPHONE + PROPERTY_SPECIAL_USE_FGS_SUBTYPE
3. learnFromReconstruction now receives preCleaned
4. ERROR_RECOGNIZER_BUSY handled with 150ms delayed restart
5. setBubbleProcessing() shows amber state with "PROC" label

### 16. Figma attempt
- Created Figma file, built 6 sections of the settings screen
- Hit Starter plan rate limit before screenshot validation
- Abandoned. Built HTML mockup with drawing tools instead.
- Drawing mockup worked but George decided deploy-to-phone (3 seconds) is faster than mockup iteration.

## Commits This Session
```
4a1f5f4 Add Google sign-in UI and brand plate styling
773fe4f Embed pulse rings in bubble, resize to 120dp
c090d87 Fix bubble opacity bug and adjust size slider range
7a56fbe Play Store readiness: 5 critical fixes + continuous listening
1295848 Add Falcon validation, risk flags, redo detection, session history, and disfluency improvements
c9fdd33 Fix 5 remaining Play Store blockers
```

## Still TODO
- Wire WhisperClient into L4 (code exists, just needs connection)
- Google Sign-In on Jana's phone (SHA-1 fingerprint registration)
- Test suites (zero tests currently)
- Play Store listing and screenshots
