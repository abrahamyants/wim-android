# WiM — What I Meant

**Voice-to-intent reconstruction for Android.** Speak messy, get clean text.

WiM captures your voice, transcribes it on-device, and optionally reconstructs it through GPT to produce clean, polished text — injected directly into whatever text field you're typing in.

## How It Works

1. Enable the WiM accessibility service
2. A floating bubble appears over all apps
3. Tap the bubble to start recording, tap again to stop
4. Your speech is transcribed, cleaned, and pasted into the active text field

## Architecture

- **On-device ASR** — Android SpeechRecognizer, zero cost, zero latency, continuous listening
- **GPT reconstruction** — OpenAI API (gpt-4o for all layers, Gemini removed)
- **Falcon validation** — Meaning-preservation check after reconstruction
- **Accessibility injection** — Clipboard + ACTION_PASTE into any text field
- **Floating bubble** — TYPE_APPLICATION_OVERLAY foreground service with pulse ring animations

## Layers

| Layer | Name | What It Does | Cost |
|-------|------|-------------|------|
| I | Transcribe | On-device ASR + disfluency filter | Free |
| II | Reconstruct | GPT cleans grammar, applies tone | API |
| III | Profile | + personal vocabulary and corrections | API |
| IV | Stutter | + clinical stutter reconstruction | API |

## Features

- **4 tone settings** — Casual, Professional, Friend, Formal
- **3 situation modes** — Default, High Stress, Reading
- **DAF** — Delayed Auditory Feedback (30-300ms configurable)
- **Profile learning** — Auto-learns corrections from usage, persists across sessions
- **Disfluency filtering** — Stutter fragments, word/phrase repetitions, prolongations, fillers, false starts
- **Falcon validation** — GPT meaning-check rejects bad reconstructions
- **Risk flags** — Critical token retention, length delta, short output detection
- **OCD redo detection** — Warns on 3+ consecutive similar recordings
- **Session history** — SQLite storage of all recordings with metrics
- **Firebase Auth** — Google Sign-In with backend proxy (no API key needed for consumers)
- **Twist mode** — Voice-triggered knob rotation gesture to adjust size/opacity
- **Command mode** — Drag bubble over highlighted text, speak a transform command, GPT rewrites selection
- **Sound-wave logo bars** — 14 animated bars (7 per side) flank the WiM logo, react to recording state
- **Bubble theme variants** — 3 metal palettes (bronze/aluminum/gunmetal) available as drawable sets
- **Timer cap** — Session timer caps at 99:59 to prevent 3-digit overflow; resets on session reset

## Voice Commands

Speak these as the **first words** into the bubble. They're detected by strict prefix match in `BubbleService.processText()` — trigger phrases bypass all normal paste logic.

| Command | Action |
|---------|--------|
| `settings size` / `knob size` | Enter size twist mode |
| `settings opacity` / `knob opacity` | Enter opacity twist mode (DIM) |

**In twist mode**: bubble enlarges to max, tick mark rotates to show current value. Touch + twist along the 9→12→3 o'clock arc (180°) to adjust. Release = green ✓ checkmark on knob. Tap checkmark to commit, tap outside or wait 5s to revert.

- **12 o'clock (0°)** = midpoint (size 110%, opacity 60%)
- **9 o'clock (-90°)** = min (size 70%, opacity 20%)
- **3 o'clock (+90°)** = max (size 150%, opacity 100%)

Twist commands are self-contained on the bubble — no text field or target app required. Works from home screen.

## Two API Paths

1. **Signed in with Google** — Requests go through Cloud Function backend at `bakers-agent.cloudfunctions.net`. Server holds the API key.
2. **Local API key** — Direct OpenAI calls. Enter your `sk-proj-...` key in the app settings.

## Build & Deploy

No IDE needed. From terminal:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew.bat installDebug
```

Builds and installs directly to a connected phone via USB debugging.

## Relationship to Lavrentiy

WiM is the consumer mobile face of the [Lavrentiy voice reconstruction engine](https://github.com/gugosf114/lavrentiy). Same reconstruction philosophy, simplified for phone use. WiM implements ~20% of Lavrentiy's feature surface — the core pipeline (record, transcribe, reconstruct, inject) plus Android-specific features (floating bubble, on-device ASR, Firebase auth). Lavrentiy's clinical/analytics stack (paralinguistic detection, prosodic analysis, covert avoidance, exposure scoring, etc.) is not in WiM.

## Tech Stack

- Kotlin, Android SDK 35 (min SDK 26)
- Firebase Auth + Google Identity
- OpenAI API (Whisper + GPT)
- OkHttp for networking
- SQLite for session history
- SharedPreferences for profile and settings

## Permissions

- **Overlay** — Floating bubble over other apps
- **Microphone** — Voice recording
- **Accessibility** — Text field detection + text injection

---

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
4. Layer I label changed to "On-device"
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

## Commits — Session 1 (March 28)
```
4a1f5f4 Add Google sign-in UI and brand plate styling
773fe4f Embed pulse rings in bubble, resize to 120dp
c090d87 Fix bubble opacity bug and adjust size slider range
7a56fbe Play Store readiness: 5 critical fixes + continuous listening
1295848 Add Falcon validation, risk flags, redo detection, session history, and disfluency improvements
c9fdd33 Fix 5 remaining Play Store blockers
```

## Session 2 — March 28-29

### What We Did

1. **Codebase audit triage** — reviewed 18-component audit, identified 4 of 6 findings were already fixed in Round 2 commits, 2 real issues remained
2. **Fix Google Sign-In client ID** — one-character typo (`iaccojon` → `iaccojn`), later replaced entirely when Firebase created new OAuth clients
3. **Add Falcon validation to backend path** — safety checks now run on both direct and backend reconstruct paths
4. **Dead code removal** — deleted WhisperClient.kt, stripped AudioRecorder, removed signInAnonymously, unused WimApp getters, sessionCount, hideBubble (-216 lines)
5. **Session transcript history** — collapsible SESSIONS section on main screen with dark cards matching Лаврентий Sessions tab. Tap to copy. L1 sessions now recorded.
6. **Speaker grille UI** — maroon panel (#5c2a2a), programmatic honeycomb mesh (GrilleDrawable), speaker cone vector, WiM badge with brass screws
7. **Bubble knob enhancements** — orange tick mark at 12 o'clock, inner radial gradient depth
8. **Full pipeline timing** — asr_ms, recon_ms, total_ms tracked. DB schema v2 migration.
9. **Whisper API wired into L4** — AudioRecorder captures WAV alongside on-device ASR, sends to Whisper for L4. Falls back to on-device if no API key or Whisper fails.
10. **Fix double transcription** — separated finalized segments from partial results to prevent duplication on recognizer auto-restart
11. **Double-tap bubble menu** — floating settings panel (opacity, size, live transcript toggle, open settings) with brushed aluminum texture and rounded corners
12. **Live transcript strip** — floating text bar above bubble showing partial ASR results during recording (toggle in menu)
13. **In-app manual** — accordion-style guide (Bubble, Layers, Tone, Situation, DAF, Sessions) extracted from Лаврентий owner's manual
14. **Status LED** — green/red LED replaces old "Ready" text
15. **Google Sign-In fully configured** — registered debug SHA-1 in Firebase, created Android OAuth client in GCP, enabled Google provider in Firebase Auth, updated google-services.json and web client ID

16. **Edison bulb status indicator** — replaced flat LED with vector Edison bulb (glass envelope, coiled filament, screw cap) in brand plate. Green = idle, red = setup needed.
17. **Launcher icon redesign** — stereo knob with orange bezel, tick mark at 3 o'clock, copper gold W
18. **Section headers enlarged** — 14sp bold with prominent ▼/▶ arrows, individual glass pill backgrounds
19. **Scrollable sessions panel** — fixed 280dp height, nested scroll, semi-transparent maroon cards show speaker behind
20. **Nixie tube stats row** — Words, Sessions, WPM in dark glass cells with orange/green glow text matching Лаврентий dashboard
21. **Footer redesign** — compact "powered by Лаврентий" plate with brass screws, privacy policy link
22. **Compact signed-in section** — single 40dp strip with email, FREE badge, sign out
23. **Bubble menu polish** — semi-transparent (88% opacity), brushed aluminum, larger labels
24. **6 Лаврентий parity fixes** — Falcon bypass for signed-in users, reading severity (0.3), professional temperature (0.15), prolongation regex, learning gate, backend payload
25. **Full component audit** — 13-component code-level audit, all WORKS except profile learning (fixed) and prolongation regex (fixed)
26. **Backend payload expansion** — now sends trigger_words, onset_weights, covert_profile (empty stubs until features ported)
27. **5 audit follow-up fixes** — position-aware profile learning, prolongation regex capture group, trust backend falcon_ok, injection failure toast, section prefs reset

### Лаврентий Desktop Test Suite (Session 2)
19 test files. 1,312 assertions. Zero failures. All clean.

### Commits — Session 2
```
560f02e Fix Google Sign-In client ID, add Falcon to backend path, remove dead code
596acf9 Add session transcript history to main screen
3061879 Add speaker grille UI, knob tick mark, and full pipeline timing
66a56a7 Wire Whisper API into L4 stutter pipeline
12c1a25 Fix double transcription in continuous listening
f0c3a08 Add double-tap bubble menu with opacity, size, live transcript toggle
ea12f1a Fix double-tap detection and add live transcript strip
428c88d Fix bubble menu crash: use themed context for MaterialSwitch inflation
f6e2c56 Fix menu dismiss on outside touch, add brushed aluminum texture
dcab3dd Add in-app manual, status LED, and rounded menu corners
e28f4ef Rebuild manual as accordion with expandable topic rows
11dface Change manual subtitle and body text to red accent color
977f0b4 Update google-services.json and web client ID for Google Sign-In
95cb629 Update README with Session 2 log and current TODO
a180310 Move status to brand plate with Edison bulb, compact signed-in section
869a137 Place Edison bulb adjacent to WiM text
2b8509b Enlarge section headers 30% with prominent collapse arrows
616d7df Redesign launcher icon as stereo knob
f35c18c Scrollable sessions panel with transparent maroon cards
226e37a Change speaker cone to light gray
6084d5e Fix sessions panel scroll with nestedScrollingEnabled
5225aef Replace footer with 'powered by Лаврентий' plate
ecfcddd Add privacy policy link to footer
77fcbf1 Uniform section spacing, remove dividers, fix default expanded states
0dc168e Fix bulb placement, individual glass pills per section header
3e0e4de Add LED stats row: Words, Sessions, WPM
ece07bc Rebuild stats row as nixie tube cells
7eeb3cc Pin footer to bottom of screen
fcdeda2 Fix 6 critical Лаврентий parity issues
5086b69 Fix 5 remaining issues from component audit
41cb990 Send full profile payload to backend
```

## Testing

7 test files, 126 assertions. Run with `./gradlew.bat testDebugUnitTest`.

| File | Tests | What it covers |
|------|-------|---------------|
| DisfluencyFilterTest | 27 | Stutter fragments, word/phrase repeats, fillers, false starts, prolongations, combined, edge cases |
| DisfluencyFilterStressTest | 26 | Adversarial inputs, Unicode/Russian, nested patterns, prolongation edges, idempotence |
| RiskFlagsTest | 16 | Critical token retention (numbers/dollars/percents), short output, length delta, fragments |
| RiskFlagsStressTest | 21 | Empty inputs, boundary conditions, complex patterns, multiple simultaneous flags |
| ProfileLearningTest | 7 | Position-aware matching, junk promotion prevention, distance threshold, count threshold |
| ProfileLearningStressTest | 12 | Multiple substitutions, position boundaries, large inputs, duplicates, empty inputs |
| ReconstructPromptTest | 17 | Tone temps match Лаврентий, severity values match, tone rules distinct, threshold triggers |

### Not yet tested (needs Android mocks)
- SessionHistory (SQLite — needs Context)
- ProfileManager save/load (needs SharedPreferences)
- BubbleService state machine (needs Service lifecycle)
- OnDeviceRecognizer (needs SpeechRecognizer)
- HTTP clients (WhisperClient, ReconstructClient — needs OkHttp mock)

## Session 3 — April 1, 2026

### The API 400 Mystery — Solved

Three separate sessions crashed with `API Error: 400 — "Could not process image"`, killing ~27 hours of work across 3 attempts. Root cause: **Claude Code's `Read` tool was reading `.png` screenshot files from a multi-display Windows setup. Instead of returning actual PNG bytes, the system returned a text warning (`[Warning] Multiple displays were found...`) but wrapped it in an `image/png` content block. The API rejected it because the "image" was just ASCII text.**

Crashed sessions and their triggers:
| Session | File Read | Error |
|---------|-----------|-------|
| `6bfa8d60` | `C:/Users/georg/Downloads/current_state.png` | Text wrapped as PNG |
| `482f0462` | `wim-android/phone_screenshot.png` | Text wrapped as PNG |
| `a561c16e` | `C:/Users/georg/Downloads/redesign_check.png` | Text wrapped as PNG |

This is a Claude Code bug — the Read tool should validate image headers before tagging content as `image/png`. Once a bad image enters conversation history, every subsequent turn fails because the API resends the full context.

**Workaround:** Use `adb shell screencap` to take phone screenshots instead of reading existing PNG files. Always specify the display ID to avoid the multi-display warning.

### What We Built

**UI Overhaul (29+ design items from George's feedback chunks)**

1. **WM logo** — Discovered the real logo (Limelight font W and M letterforms from the launcher icon foreground vector). Replaced the coil/waveform drawable everywhere — sessions panel watermark (1.75x), footer, bottom-left badge on square dark tile.
2. **Sessions panel** — Maroon-tinted mesh background, WM watermark centered, speaker cone replaced. Logo on square dark tile bottom-left.
3. **Category headers** — Changed from Limelight to sans-serif-light (matching "What I Meant" subtitle). All categories now visually consistent.
4. **Google sign-in** — Real Google G logo (transparent PNG from official source), removed "Free tier" label, clean layout.
5. **Brand plate** — Slimmer (reduced padding, smaller WiM text 26sp), screws downsized to 7dp small Phillips, ? guide button moved to footer.
6. **Footer** — Removed "powered by Лаврентий" text, kept WM logo + Privacy Policy. Small Phillips screws. ? guide button repositioned right of screws.
7. **Nixie tube readouts** — Brighter interior, copper gold border, compact padding. Added 4th cell (SCORE placeholder). Vintage retro styling (muted warm colors, thin font, reduced glow).
8. **Permissions restyled** — Changed from Material Buttons to row format matching Situation (Overlay/Accessibility/Microphone with copper gold descriptions and checkmarks).
9. **Settings repositioned** — Moved under Permissions as a category, matching header style.
10. **Profile cleanup** — Removed 👤 emoji, clean sans-serif text. "Your speech" options confirmed already removed.
11. **Unified card backgrounds** — card_bg, card_bg_pressable, card_bg_selected all use same base color (#FF222230).
12. **Category spacing** — Increased (14dp top margin, 6dp bottom) for more breathing room.
13. **Background pattern** — Diagonal crosshatch with vignette, visible texture.
14. **Grille pattern** — Visible crosshatch with copper border on all category content areas.
15. **Sticky scroll** — WiM brand plate frozen at top, everything else scrolls. Stats row moved to scrollable area.
16. **Stats clickable** — Each nixie cell taps to expand its relevant category.
17. **Session timer** — Now tracks full session duration (from bubble start), not individual recording time. No reset on double-tap.
18. **Clarifier acknowledgment** — Copper gold glow instead of bright green Unicode checkmark.
19. **Default bubble** — Opacity 100%, size 95%.
20. **POST_NOTIFICATIONS permission** — Added to manifest + runtime request on Android 13+. Fixes Samsung greyed-out notification toggle that was killing the foreground service.

**Lavrentiy Fixes**
- Fixed stuck recording on F9 hold-to-talk: added toggle safety (F9 while recording stops it), 3-second startup guard, 30-second auto-stop timeout.
- Portable launcher uses `desktop.py` wrapper, not `lavrentiy.py` directly.

**Phone Diagnostic**
- Identified Samsung killing bubble via `Suppressing notification from package com.wim.app by user request` — caused by missing POST_NOTIFICATIONS permission on Android 13+/targetSdk 35.

### Commits — Session 3
```
a6d5f9b Refactor main layout, add drawables & permission
5227af2 Add 4th nixie tube, fix layout gaps, freeze WiM brand plate
c9171b2 Nixie tubes, timer, permissions, grille, brand plate, footer
a7b6008 Unify category colors, clickable stats, spacing, background pattern
```

## Session 4 — April 2, 2026

### Design System Overhaul

Applied Лаврентий desktop dashboard dark mode design system to WiM Android. Read the actual CSS from `dashboard.html` and extracted exact color values:
- Background: `#1a1a1e`, Surface: `#222226`, Raised: `#2a2a2f`, Elevated: `#333338`
- Borders: `rgba(255,255,255,0.08)`, Text: `#d4d4d8` primary, `#f0f0f4` bright
- Stat cells: deep dark glass `#0c0c0e → #141418`, digits `#ff8c42` with multi-layer glow
- Category labels: `#EE5A24` orange (not white, not gold)
- Card rows: 3-stop top-lit gradient matching Лаврентий `.tone-btn` / `.layer-opt`
- Sessions panel: `#3a1a1a` maroon (Лаврентий `--console-bg`)

Also created full light mode theme (Лаврентий `:root` values):
- Light bg: `#d8d8dc`, Surface: `#c8c8cc`, Raised: `#bfbfc4` (brushed aluminum)
- Light text: `#2a2a30` primary, `#111116` bright
- Drawables split: `drawable/` = light, `drawable-night/` = dark

### Color Experiments

Replaced all gold (`#D4A574`) with white — 65 occurrences across 22 files (XML + Kotlin). Then reverted category headers to orange `#EE5A24` after comparing with actual Lavrentiy screenshots. Screws also got white center color from the gold→white sweep.

Captured actual Lavrentiy UI element screenshots using headless Playwright connecting to `localhost:7878`:
- `lav_stat_cell.png`, `lav_sidebar.png`, `lav_section.png`, `lav_layer_opt.png`, `lav_tone_btn.png`, `lav_full_dark.png`

### Critical Bug: 4-Month Silent Session Data Loss

**Root cause:** `BubbleService.processText()` calls the reconstruction API (OpenAI GPT via backend). When the API fails (timeout, 403, network error), the `catch` block injects fallback pre-cleaned text BUT never calls `sessionHistory.record()`. Every failed API call = lost session.

**The specific failure:** Backend returns `403: {"error": "Layer 4 requires Pro tier", "max_layer": 2, "tier": "Free"}`. George's Firestore user record has `tier: "Free"`. Every L4 call has been silently rejected for months. The catch block swallowed the error, injected raw text, and moved on. Zero sessions recorded.

**Fix:** Added `sessionHistory.record()` to the catch block with `falconApproved=false` and `riskFlags=["reconstruction_failed"]`. Sessions are now recorded regardless of API success.

**Secondary fix:** `syncFromFirestore()` was async but `loadSessions()` ran before sync completed. Added callback pattern: `WimApp.onSessionsSynced` fires after Firestore sync, triggers `loadSessions()` refresh.

### Other Fixes
- **Overlay permission crash guard:** `canDrawOverlays()` check before `windowManager.addView()`. Prevents `BadTokenException` when accessibility enabled before overlay.
- **Permission order enforcement:** Accessibility button blocks with toast if overlay not granted first.
- **Dark/light mode toggle:** Restored in Settings with `AppCompatDelegate` and SharedPreferences persistence.
- **Ambient glow orb:** Removed from fixed top section (was causing 300dp gap), placed as centered overlay in root FrameLayout.

### Commits — Session 4
```
990859f Apply Лаврентий dark mode design system to WiM
aca1448 Add Лаврентий light mode theme alongside dark mode
11a5e2a Replace all gold with white, orange labels, filament glow
c722263 Restore dark/light mode toggle in Settings
0bd132b Guard bubble overlay with canDrawOverlays check
b7d7fea Enforce overlay-before-accessibility permission order
3c28b2b Fix sessions not showing after sign-in on fresh install
7b9cd39 Record session even when reconstruction API fails
```

## Session 5 — April 4, 2026

### Pipeline Overhauls (Лаврентий Parity)

**L2-L3 now use Whisper API.** Previously only L4 used Whisper; L1-L3 used Android's native SpeechRecognizer. Paid tiers deserve better input quality. L1 stays on-device (free/offline differentiator). L2+ send audio to Whisper → GPT.

**Pre-roll audio buffer.** When bubble is visible + L2+ + API key available, AudioRecorder primes a 500ms circular buffer via continuous mic capture. When user taps to record, the buffer is prepended to the recording file — first words no longer cut off by startup latency.

**Whisper confidence signals wired through.** `avg_logprob`, `no_speech_prob`, and `block_suspect` per segment extracted from verbose_json and passed to the backend reconstruction call at all paid layers (matching Лаврентий behavior).

**Whisper hallucination stripper.** 22 known patterns ("Transcribed by otter.ai", "Thanks for watching", etc.) auto-removed from every Whisper response. Prevents YouTube-training-data leakage into transcripts during silent moments.

**L1 profile corrections.** Learned word replacements now apply at Layer 1 client-side without GPT. Matches Лаврентий's `apply_profile_corrections()`. Also applied at L2+ before GPT sees text.

**Profile decay.** Corrections unused for 100+ sessions auto-removed every 30 sessions. Tracks `correction_last_used` per entry.

**Brown's phonetic risk prediction.** New `PhoneticRisk.kt` — Brown's 4-feature model (onset, content-word, length, position) predicts per-word difficulty. Injected into L4 prompt alongside personal onset weights.

**Clinical L4 prompt expanded.** Direct path now includes ~80 lines of Лаврентий's few-shot examples (blocks, syllable reps, prolongations, filler stacking, avoidance, Whisper artifacts). Previously the direct path was a fraction of what backend got.

**L2-L3 ASR artifact guidance.** 8 artifact types + 4 examples added to direct-path prompt.

**NATURAL_REPEATS protection.** 22 entries ("had had", "bye bye", "ну ну") no longer stripped by DisfluencyFilter.

**Stutter-aware Falcon prompt.** Full Лаврентий version for L4 validation. Also now tone-aware — formal/professional/casual/friend each get appropriate rewrite-latitude notes.

### Shared Bug Fixes (Both Apps Had These)

- **#9: DisfluencyFilter runs before L4.** Fixed — L4 now skips pre-filtering so GPT sees raw disfluencies to reason about.
- **#10: Falcon ignores tone.** Fixed — Falcon now accepts tone parameter, includes tone-appropriate rewrite latitude.
- **#7: strip_block_hallucinations L1-only.** Fixed — now runs at all layers via `DisfluencyFilter.stripBlockHallucinations()`.
- **#8: profile corrections L3+ only.** Fixed — client-side corrections at L2+ before GPT.

### New Features

**Command Mode.** Drag bubble over highlighted text → bubble picks up selection → clarifier "?" with ✓/✕ → ✓ arms listening → user speaks freeform command ("formal", "shorter", "translate to Russian") → GPT transforms → `ACTION_SET_TEXT` replaces original selection. No new permissions required (uses existing accessibility service).

**INSTANT mode.** New status chip (first position). When ON, bypasses everything — pastes raw ASR text directly, no DisfluencyFilter, no GPT, no Falcon, no session logging. For speed-over-quality moments.

**Quiet Mode.** Audio modifier toggle under LAYER. Applies +18dB gain + 80Hz high-pass + soft clipping to mic audio before Whisper upload. For whispered/soft speech in public spaces.

**Status chip row.** 4 tappable chips under VU meters (MODE/LAYER/SITUATION/TONE). Tap cycles through values, shows current state. Replaces DAF chip with MODE (Standard/Instant). Full-width, maroon value text on transparent pill backgrounds.

**VU meter gauges.** Replaced nixie tube stat cells with custom Canvas VuMeterView — semi-circular arc with tick marks, needle, seamless two-tone transition to maroon red zone. Startup sweep animation on launch. Double-tap to reset. Theme-aware colors (cream/charcoal for dark/light).

**Password field safety.** AccessibilityService detects `node.isPassword`. Bubble refuses to record when password field focused, shows toast + brief flash.

**GDPR/CCPA data rights.** Export/Delete buttons in Profile overlay (signed-in only). Export serializes Firestore doc via Android share sheet. Delete calls backend `delete_data` endpoint (hard delete, immediate), signs user out, reloads.

**Inline Guide.** Moved from `?` button overlay to collapsible GUIDE category after SETTINGS. 7 accordion sections inside: How It Works, Bubble, Layers, Smart Tone, Situations, DAF, Having Trouble. Matches category pattern.

### Design Overhaul

**Warm dark palette (dark mode).** Navy blue backgrounds reverted to Лаврентий's warm dark (`#1A1A1E` family). Cream `#C8B48A` for category headers. Muted copper `#C49A6C` for sub-labels. Orange `#EE5A24` reserved for data/active states only. All previous orange headers/labels swapped out.

**Light mode vintage paper.** Darker tan background `#D4C8B0` (leather desk), cream raised cards, burnt umber text `#5C3A1F`, dark copper sub-labels. Inverted hierarchy from dark mode.

**Toggle refinements.** All MaterialSwitch toggles scaled 0.75x, copper thumb, warm dark track. Dark mode toggle styled to match. Includes bubble menu's Live Transcribe toggle.

**Icon redesign.** Launcher icon now matches floating bubble — flat aluminum knob with orange WM, no shadow artifacts.

### Commits — Session 5
```
e99f3a5 Warm dark mode overhaul + Лаврентий intelligence port
c11767e Light mode vintage paper aesthetic + toggle refinements
```

### Design Limitations Documented

Android XML drawables cannot replicate Лаврентий's CSS effects:
- No multi-layer box-shadows (CSS stacks 5, Android gets 1 elevation)
- No backdrop blur / glassmorphism (needs RenderEffect API 31+)
- No repeating patterns (CSS `repeating-linear-gradient`, Android needs bitmap tiles)
- No pseudo-elements (`::before`/`::after` for overlays)
- No inset shadows (`box-shadow: inset`)
- No multi-layer text-shadow (CSS stacks for glow, Android gets 1)

**Solution path:** Jetpack Compose migration closes most gaps. Custom Canvas views for remaining effects. PNG slices as fallback for pixel-perfect elements.

## Session 6 — April 4-5, 2026

### What We Did

**Palette continuation from Session 5's warm dark overhaul.** Previous session shipped the warm dark backgrounds and 3-tier accent hierarchy (cream / copper / orange). This session took the decisions deeper into the UI.

### Chip Row Redesign

Replaced the 4-chip row (`MODE`/`LAYER`/`SITUATION`/`TONE`) with 3 purposeful chips:

| Chip | Label | Values | Purpose |
|------|-------|--------|---------|
| 1 | `OUTPUT` | `INSTANT` / `STANDARD` | Pipeline bypass toggle |
| 2 | `BUBBLE` | `SUMMON` | Tap to `startForegroundService(action="SHOW")` on BubbleService — revives bubble if hidden |
| 3 | `FAST MODE` | `ON` / `OFF` | Skip Falcon validation |

- `OUTPUT` replaced `MODE` because "fast mode" is also a mode — the label was ambiguous
- `BUBBLE` is cream-colored (matches logo color `#C8B48A`), distinct from maroon toggle chips — it's an action, not a state
- Active-state background `chip_bg_active.xml` (maroon fill `#40B55050`) when non-default value selected

### Header Badges (LAYER / TONE / SITUATION)

Category headers now show current state inline using `SpannableString`:
- `LAYER     ·   transcribe`
- `TONE      ·   professional`
- `SITUATION ·   default (everyday)`

Implementation: `setHeaderWithBadge()` in MainActivity uses:
- `\t` separator + `TabStopSpan.Standard(110dp)` for pixel-precise badge alignment across all 3 categories (works regardless of base name length)
- `TypefaceSpan("monospace")` on badge portion only — base name stays `sans-serif-light`
- `ForegroundColorSpan(0xFFB55050)` + `RelativeSizeSpan(0.75f)` for maroon, smaller badge
- Each category's values space-padded to the longest one's length for consistent widths within the category

Roman numerals (`Ⅰ`/`I̲̅`) attempted but abandoned — Unicode forms render as plain letters in most fonts, combining overlines/underlines look crooked. Settled on descriptive text only (`transcribe` / `reconstruct` / `profile` / `stutter`).

### Sound-Wave Logo Bars

`LogoBarsView` — custom `LinearLayout` subclass flanking the WiM logo in the brand plate. 7 bars per side, 14 total.

**Animation:**
- `ObjectAnimator.ofPropertyValuesHolder` + `Keyframe.ofFloat` (5-point sequence) on `scaleY` per bar
- 7 unique keyframe patterns + 7 unique durations (750-1300ms)
- Staggered `currentPlayTime` offsets = **phase-shift trick** (bars start mid-loop, not synchronized from frame 1)
- Right side mirrored (heights array reversed) so tallest bars face logo on both sides
- 400ms offset on mirrored side = left/right desync

**Heights (mobile-scaled from desktop spec 10→56px):** `5, 10, 17, 24, 28, 21, 13` dp — peak at index 4, tapering

**Gradient:** orange `#EE5A24` → maroon `#B55050` (vertical), 2dp wide bars, rounded corners

**Recording reactivity via RecordingStateBus:**
- New `RecordingStateBus` singleton (subscribe/emit pattern, thread-safe via synchronized listeners)
- `BubbleService.isRecording` wrapped in setter that emits on every transition (4 call sites auto-emit via property setter)
- `LogoBarsView.onAttachedToWindow()` subscribes, brightens to 0.95 alpha + ×0.55 speed multiplier on `active=true`
- `onDetachedFromWindow()` unsubscribes — self-managing lifecycle, no MainActivity wiring needed

### Bubble Color Themes

Three metal palettes exist as drawable variants:

| Theme | Active? | Bezel file | Knob file |
|-------|---------|-----------|-----------|
| Bronze | ✓ active | `bubble_bg_idle.xml` | `bubble_inner_knob.xml` |
| Aluminum | variant | `bubble_bezel_aluminum.xml` | `bubble_knob_aluminum.xml` |
| Gunmetal | variant | `bubble_bezel_gunmetal.xml` | `bubble_knob_gunmetal.xml` |

**Bronze palette** (warm brushed copper, matches tube amp aesthetic):
- Bezel outer: `#E6D4B0 → #9A7C54`, stroke: maroon `#B55050`
- Bezel inner: `#CCAD83 → #7A5C3C`
- Knob shadow: `#2A1E14`, side: `#A6875C → #6F5030`, top: `#C4A574 → #8E6E48`

**Aluminum palette** (original, cool silver): unchanged from prior design, preserved for user choice.

**Gunmetal palette** (new, dark brushed steel):
- Bezel: `#7A7C85 → #2E2E36` (outer), `#555560 → #1E1E24` (inner)
- Knob: shadow `#0A0A0E`, side `#4A4A54 → #26262C`, top `#5A5A64 → #38383E`

All 3 share: maroon stroke, maroon tick, identical structure & dimensions. **Selector UI not yet built** — swap requires editing drawable references or runtime `setBackgroundResource()` based on preference.

### Timer Refinements

- Color: `#B55050 → #9A4040` — same hue, ~15% darker, visually distinct from bezel stroke
- Weight: removed `android:textStyle="bold"`, now normal monospace
- **Cap at 99:59** — `timerRunnable` checks `elapsed >= 5999`, displays `"99:59"` and **stops re-posting itself** (no more timerHandler.postDelayed). User must reset session (which resets `sessionStartTime`) to resume counting from zero.

### Twist Mode — Voice-Triggered Knob Rotation

**Concept:** The bubble already *looks* like a volume knob. Now it *acts* like one.

**Voice triggers** (strict prefix match on transcript's first words):
- `settings size` / `knob size` → SIZE mode
- `settings opacity` / `knob opacity` → OPACITY mode (label: `DIM`)

**Detection path:** `detectTwistTrigger(rawText)` called at the very top of `processText()`, before COMMAND mode, before INSTANT mode, before the paste logic. If triggered, `enterTwistMode(mode)` fires and the transcript is **never pasted**.

**Flow:**
1. Save `preTwistSize` + `preTwistOpacity` for revert
2. Bubble enlarges to clarifier size (150% + 100% opacity) for ergonomic twisting
3. Swap label: `SIZE` or `DIM` in cream
4. Hide timer
5. Rotate tick to match current value's angle
6. Start 6-second timeout (canceled on any touch)
7. Touch handler routed to `handleTwistTouch()` while `twistMode != NONE`

**Angle math:**
```kotlin
val angleRad = atan2(dx.toDouble(), (-dy).toDouble())
val angleDeg = Math.toDegrees(angleRad).toFloat().coerceIn(-90f, 90f)
// 0° = 12 o'clock, -90° = 9 o'clock, +90° = 3 o'clock
val normalized = (angleDeg + 90f) / 180f
val value = (min + normalized * (max - min)).toInt()
```

**Arc constraint:** 9→12→3 (180° total, clamped — finger can't physically twist through 6 o'clock while holding phone).

**Tick rotation:** `rotateTick(degrees)` sets `pivotX = tick.width/2`, `pivotY = tick.height/2 + 16*dp` (16dp = distance from tick's vertical center to knob's center). The tick rotates around the knob's center, not its own.

**Confirm UX on release:**
- Green ✓ checkmark appears on knob (`twistConfirmCheck` TextView, 22sp, `#4CAF50`, `match_parent` gravity center)
- Tick hidden, label hidden during confirm
- Tap on knob (within ~20% of view width radius) = **commit** (prefs already saved during twist, just exit)
- Tap outside knob = **revert** to `preTwistSize`/`preTwistOpacity`
- 5s timeout = revert

**Exit cleanup:** `exitTwistMode(commit)` restores visibility of tick/label/timer, resets tick rotation to 0°, calls `setBubbleIdle()`.

### Noted But Deferred

- **Twist mode: layer** — would need 4-position snap (labels at 12/3/6/9 o'clock). Deferred per George's suggestion ("may be too much").
- **Press+twist without voice** — alternative entry for accessibility or voice-shy environments
- **Numeric feedback on knob during twist** — user's finger covers it anyway

### Architectural Insight Saved

`project_wim_bubble_is_the_app.md` memory — the WiM bubble is the only always-visible UI during real dictation (user is in Messenger/Gmail, MainActivity is backgrounded). Recording-reactive UI (like LogoBarsView's active state) is mostly invisible in production; that work belongs on the bubble overlay itself.

### What Worked
- TabStopSpan for badge alignment was pixel-perfect and font-independent — zero fiddling
- Phase-shift animation via `currentPlayTime` eliminated startup jank on 14 simultaneous animators
- Property-setter-based event emission (RecordingStateBus hooks into `isRecording` setter, no per-call-site edits)
- Voice trigger detection at `processText()` entry point caught all 3 ASR paths in one check

### What Didn't Work
- **Unicode Roman numerals (`Ⅰ` U+2160)** — Android fonts render them as plain letters, no bars
- **Combining diacritics for Roman numeral bars (`I\u0305\u0332`)** — uneven, crooked rendering
- **Ephemeral conclusion:** Roman numerals with proper bars need custom Canvas drawing or vector drawables, neither is justified for the visual payoff

### API Routing Overhaul (Late Session 6)

**Transcription model swap.** `whisper-1` → `gpt-4o-transcribe` in `WhisperClient.kt`. One-line change on the `/v1/audio/transcriptions` model field. gpt-4o-transcribe is a **separate purpose-built audio model** (not a flavor of gpt-4o) with better WER on disfluent speech.

**Lesson:** Multiple AI sessions (this one + desktop Claude) all assumed gpt-4o-transcribe was "gpt-4o that happens to transcribe" based on similar naming. It's actually a separate model with different endpoint (`/v1/audio/transcriptions` vs `/v1/chat/completions`), different architecture. Verify model facts via WebSearch for anything with version suffixes — don't infer from name.

**Gemini 2.5 Pro as L2/L3 reconstruction engine.** George has Gemini credits sitting idle from marketing work (switched to Sonnet 4.6 for that). Repurposing for WiM:

```
L2/L3:  audio → gpt-4o-transcribe → GEMINI 2.5 PRO → gpt-4o Falcon → paste
L4:     audio → gpt-4o-transcribe → gpt-4o           → gpt-4o Falcon → paste
```

**Why Gemini for L2/L3:**
- Input tokens ~50% cheaper than gpt-4o
- Extended reasoning benefits general text cleanup
- Cross-provider validation bonus (Gemini writes, GPT grades → different biases)
- Bypasses backend tier gate (your own account, your own key)

**Why NOT Gemini elsewhere:**
- **L4** stays gpt-4o — research-validated for clinical stutter work
- **Falcon** stays gpt-4o — fresh-context validation works better than same-provider self-grading; 5-token yes/no is cheap anyway

**Implementation (Android):**
- `WimApp.kt` — added `geminiApiKey()` prefs getter
- `ReconstructClient.kt` — new `callGemini()` private function, hits `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent` with `x-goog-api-key` header
- `ReconstructClient.reconstructDirect()` — routing branch at top: if `layer in {2,3}` and Gemini key exists, try Gemini → Falcon → return; on Gemini failure, graceful fallback to existing OpenAI path
- Gemini API key injected into `SharedPreferences` via `adb shell run-as sed` (no Settings UI field yet)

**Gotcha:** Gemini branch lives in `reconstructDirect`, which only runs on the **direct API path** (signed-out / local key). Signed-in users go through backend Cloud Function, bypassing Gemini entirely. To cover both paths, also add Gemini branch to `lavrentiy/wim/api/main.py`.

### Backend Tier Gate Discovered

Cloud Function at `bakers-agent.cloudfunctions.net/wim-reconstruct` has a 3-tier system:
```python
TIERS = {
    "invite": {"max_layer": 2, "daily_limit": 30,      "name": "Free"},
    "basic":  {"max_layer": 2, "daily_limit": 200,     "name": "Basic ($5.99)"},
    "pro":    {"max_layer": 4, "daily_limit": 999999,  "name": "Pro ($14.99)"},
}
```

New users default to `invite` tier (shown as "Free") — L4 blocked. George's own Firestore `users/{uid}` doc has `tier: "invite"`, which is why his L4 calls via the backend path have been 403'd. Not legacy code — it's his real monetization design. Fix is to upgrade his own doc to `"pro"` via Firebase console or gcloud CLI.

### Desktop Parity (Лаврентий)

Same 3 changes needed in Python engine:
1. NEW: `gemini_client.py` with `call_gemini(system_prompt, user_text, temperature, api_key)` function
2. EDIT: `reconstruct_intent()` gets layer-based branch routing L2/L3 to Gemini with try/except fallback
3. EDIT: Add `GEMINI_API_KEY` alongside `OPENAI_API_KEY` in env/config
4. EDIT: Add model name to status line logging — e.g. `[gemini-2.5-pro] recon:791ms` vs `[gpt-4o] recon:1203ms`

Desktop Claude appears to have already done some of this — a George test at 03:54 showed `recon:791ms` which is consistent with Gemini timing (gpt-4o typically 1200-2500ms for similar input).

### Commits — Session 6

`74ba23f` — Voice-triggered twist mode + timer cap + Session 6 log (earlier)
`(uncommitted)` — gpt-4o-transcribe swap + Gemini L2/L3 routing + README updates

## Session 7 — April 6, 2026

### Desktop Session Sync

George ran a marathon 12-hour desktop session on Лаврентий that produced 28 changes. This session reviewed all 28, identified which applied to WiM Android, and ported the 4 that did.

### Changes Ported from Desktop (4 items)

**1. Removed Gemini 2.5 Pro from L2/L3 reconstruction.**
Gemini was leaking chatbot responses ("I'm sorry, I can't assist", "Could you clarify") into reconstruction output. All reconstruction now routes through gpt-4o only.
- Deleted `callGemini()` method from `ReconstructClient.kt`
- Deleted Gemini routing branch in `reconstructDirect()` (was lines 339-370)
- Deleted `geminiApiKey()` from `WimApp.kt`
- Zero Gemini references remain in codebase

**2. Added post-reconstruction LLM leak filter.**
19-pattern filter catches GPT chatbot responses and meta-commentary that occasionally leak through reconstruction. Patterns include: "i'm sorry", "i apologize", "as an ai", "as a language model", "please clarify", "i'd be happy to help", "no transcription", "unable to transcribe", bracketed hallucination markers, etc. If any pattern matches, output falls back to pre-cleaned raw text. Also skips profile learning on leak detection to avoid poisoning corrections.
- Added to `BubbleService.processText()` between `ReconstructClient.reconstruct()` return and `injectText()`
- Patterns ported 1:1 from Лаврентий's `_LLM_LEAK_PATTERNS` list

**3. Added length explosion guard.**
If reconstruction output is >2.5x longer than input (word count), GPT fabricated content instead of cleaning speech. Falls back to raw text. Same 2.5x threshold as desktop. The "hallucination of the millennium" on desktop was a full fabricated paragraph about GitHub/Whisper/README from a 5-word input — this guard catches that class of failure.
- Added to `BubbleService.processText()` alongside the leak filter
- Only fires when input is 3+ words (avoids false positives on very short utterances)

**4. Hardened DafPlayer.stop() with thread join.**
Desktop had a bug where `daf_stop()` could return before Windows audio devices were fully released, causing the next `daf_start()` to fail or produce echo artifacts. Same bug class exists on Android: `stop()` set `running=false` and nulled the thread, but the thread's `finally` block (which calls `recorder.release()` and `track.release()`) might still be executing.
- `stop()` now captures thread reference, nulls the field, then calls `interrupt()` + `join(200)` 
- 200ms ceiling prevents blocking the UI thread if cleanup hangs
- Android's `AudioTrack.stop()` is synchronous (~50ms), so 200ms is generous

### Already Done (confirmed in code)

- **Expanded Whisper hallucination patterns (45+)** — `WhisperClient.kt:42-101` ✓
- **Anti-censorship instruction** — `ReconstructClient.kt:196-199` ✓  
- **gpt-4o-transcribe for L1** — `WhisperClient.kt:124` ✓

### Not Applicable to WiM (21 items)

Desktop-only: Inno Setup installer, VBS launcher, splash.html tic-tac-toe, portable folder deletion, GitHub release cleanup, release notes, dashboard startup grace period, tic-tac-toe game, X close button, multi-temp Whisper (conditional + parallelized), SileroVAD pre-filter (log-only), DAF auto-start removal from high_stress (WiM never had it), CDP helpers, Jana's laptop setup, Gmail drafts, Inno Setup installation, competitor analysis docs, academic papers, 72-test pipeline suite PDF.

### Bug Fix: Falcon Prompt Label Mismatch

`CLAUDE.md` spec says Falcon labels should be `"Original:"` / `"Reconstruction:"` but `ReconstructClient.kt` was using `"Raw:"` / `"Reconstructed:"`. Fixed to match desktop canonical labels.

### Onboarding Analysis

Current onboarding (`OnboardingActivity.kt`) is disabled (`MainActivity.kt:65`) and has 6 structural problems:
1. No `ScrollView` — permissions screen overflows on phones smaller than Pixel 7
2. Sign-in hint renders above permission rows (layout order bug)
3. All 5 permissions required before proceeding (hard gate)
4. `BubbleDemoScript` is 50+ seconds (11 acts) and silently fails if overlay permission not granted
5. No back navigation — hardware back exits activity
6. All programmatic Views, no XML layouts

**Decision:** Don't fix the existing onboarding. Redesign as 3-screen flow: Welcome → Permissions (step-by-step, one at a time) → Go. No demo script. Bubble teaches itself through use.

### Competitor Research

Researched Wispr Flow, SuperWhisper, Otter.ai websites. Key finding: the highest-rated competitor (SuperWhisper 4.9/5) has the least onboarding — download and press a key combo. Wispr Flow (2.7/5) has the most friction. **Note:** website research only — George confirmed Wispr Flow has an animated in-app onboarding that the website doesn't show. Need to screenshot the actual Android app via Android-MCP.

### Android-MCP Setup

Added `android-mcp` (CursorTouch) to Claude Code MCP settings. George's Samsung `R5CWB08K38H` connected via USB, ADB verified working. ADB path: `C:\Users\georg\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

Purpose: (1) screenshot competitor onboarding flows on George's phone, (2) automated testing of WiM onboarding during rebuild, (3) full pipeline testing via custom intents once WimTestReceiver is built.

### Android LLM Control Capability Map

Claude Desktop produced a comprehensive capability map covering the full ceiling of LLM-to-Android control:
- **ADB channel** (~200-2000ms/action): input injection, app lifecycle, screenshots, UI hierarchy dump, file system access via `run-as`, logcat streaming, audio/sensor control
- **Accessibility channel** (~50ms/action): element discovery by ID/text, click/scroll/set-text (including Unicode), gesture injection via `dispatchGesture()`, global actions, window enumeration
- **Intent channel** (~10ms/action): 12 custom intents to be added (SET_TONE, SET_LAYER, INJECT_RAW, INJECT_AUDIO, GET_STATE, GET_LAST_RESULT, RESET_PROFILE, EXPORT_SESSIONS, TOGGLE_DAF, TRIGGER_BUBBLE, SIMULATE_ONBOARDING)

Key insight: `INJECT_RAW` enables testing all 26 DisfluencyFilter patterns × 4 layers × 4 tones × 3 situations = 1,248 test cases without audio hardware. `pm clear` + `am start .OnboardingActivity` enables clean-slate onboarding testing.

Hard limits: can't inject mic audio via ADB (need app-level mock), `uiautomator dump` is slow (800ms-2s) and can't see inside custom Views, `input text` can't handle Unicode (need Accessibility `ACTION_SET_TEXT`).

### WiM MCP Server (Claude Desktop — parallel session)

Claude Desktop built an MCP server (`mcp-server/`) that extracts WiM's pure-logic algorithms as 7 callable tools:
1. `reconstruct` — raw text → clean text via backend or direct OpenAI (LLM)
2. `disfluency_filter` — strip stutter fragments, repeats, fillers (no LLM)
3. `block_hallucination_filter` — remove Whisper hallucinations (no LLM)
4. `phonetic_risk` — Brown's 4-feature score for one word (no LLM)
5. `predict_triggers` — score all words in text, flag high-risk (no LLM)
6. `compute_risk_flags` — check reconstruction quality (no LLM)
7. `compute_wer` — Word Error Rate (no LLM)

Lives in worktree `practical-golick`. 5 of 7 tools are zero-cost (pure regex and math). Architecture: device stays capture layer, MCP server becomes the brain's API. Any MCP client (Claude Desktop, Claude Code, therapist tools, Android Studio Gemini) can call these.

### Commits — Session 7
```
6073c2b Remove Gemini routing, add LLM leak filter + length guard, harden DAF stop
```

## Instructions for Next Model

**READ THIS FIRST.** George iterates fast via voice (Wispr/Lavrentiy at 182 WPM). His messages are phonetic, messy, and context-dependent. Extract intent, not spelling.

### Current State
- App builds and installs via `./gradlew.bat assembleDebug` + ADB
- Phone: Samsung `R5CWB08K38H`, connected via USB, ADB at `C:\Users\georg\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- ADB screenshots: `adb shell "screencap -p //sdcard//screen.png"` then `adb pull` (double-slash to prevent Git bash path mangling)
- **Multi-display warning:** Always use `//sdcard//` not `/sdcard/`. The screencap warning about multiple displays is cosmetic — the screenshot still works.
- Android-MCP: configured in `~/.claude/settings.json`, requires USB connection + USB debugging
- Lavrentiy desktop: now uses Inno Setup installer (`Lavrentiy-Setup-v1.1.0.exe`), VBS launcher, Edge --app mode. Portable folder deleted.
- Lavrentiy dashboard CSS: `C:\Users\georg\Documents\GitHub\lavrentiy\dashboard.html` — THE source of truth for design

### Known Issues
1. ~~**Backend tier gate:**~~ **FIXED** (Session 7 desktop) — George's Firestore `tier` changed from "free" to "pro". L4 via backend no longer 403s.
2. **Design mismatch:** Android XML cannot match Lavrentiy CSS depth/texture. Needs Jetpack Compose migration or custom Canvas views.
3. **Kotlin hardcoded colors:** 23 occurrences of `0xFFEE5A24` that don't respond to theme switching. Need refactor to `getColor(R.color.copper_gold)`.
4. **ProGuard strips logs:** `isMinifyEnabled = true` only on `release`, but verify debug builds have full logging.
5. **Sticky Settings header:** One-directional (can go down, can't go up) — implemented via `translationY` in scroll listener but needs testing.
6. **Lavrentiy F9 stuck recording:** Fixed with toggle safety + 3-second startup guard + 30-second timeout. Fix is in root `lavrentiy.py` but NOT in `portable/engine/lavrentiy.py` — needs to be copied.

### Design Workflow (DO THIS)
1. **Screenshot phone** → look at actual app
2. **Screenshot Lavrentiy** → compare with phone
3. **Identify specific differences** → list them
4. **Fix in code** → build → install → verify
5. **Do NOT create HTML mockups** — they diverge from the real app and cause confusion

### George's Rules
- Full filesystem access. No confirmation needed for reversible actions.
- Commit after every meaningful change. Don't batch.
- Push to main directly (no PRs for this repo).
- Don't ask "what do you want to do next" — decide and do it.
- Don't praise. Engage with the work.

## Still TODO

### From Session 6 (updated Session 7)
- ~~Gemini in backend Cloud Function~~ — **REMOVED**: Gemini removed from all paths (Session 7). Chatbot responses were leaking through.
- ~~Gemini API key UI field~~ — **REMOVED**: no longer needed.
- ~~Model-name logging~~ — **REMOVED**: single model (gpt-4o) everywhere, no routing ambiguity.
- ~~George's own Firestore doc upgrade~~ — **DONE** (Session 7 desktop): tier changed from "free" to "pro".
- **Bubble theme selector UI** — user-facing control to switch between bronze/aluminum/gunmetal. Drawables exist, swap mechanic not wired.
- **Twist mode: layer support** — snap-to-4-quadrants at 12/3/6/9 o'clock with I/II/III/IV labels. Deferred until twist feel is validated.
- **Twist mode: press+twist without voice** — alternative entry for accessibility
- **Twist mode: real-world testing** — verify trigger phrases at 182 WPM, ambient noise, various accents

### From Session 7
- **Onboarding rebuild** — current 6-screen flow is disabled and broken (see Session 7 log). Redesigning as 3-screen flow: Welcome → Permissions (step-by-step) → Go.
- **WimTestReceiver** — broadcast receiver for 12 custom intents (SET_TONE, SET_LAYER, INJECT_RAW, GET_LAST_RESULT, TRIGGER_BUBBLE, etc.). Being built by parallel Claude Desktop session. Enables full LLM-driven pipeline testing.
- **Android-MCP integration** — MCP server added to Claude Code settings. Enables LLM-controlled device interaction (screenshots, taps, UI hierarchy, logcat) for automated onboarding testing and competitor app analysis.
- **Falcon prompt label fix** — verify desktop uses "Original:"/"Reconstruction:" consistently (WiM now matches)

### Carried Forward
- One-directional sticky Settings header — verify/improve
- Design: Jetpack Compose migration for proper Лаврентий visual parity
- Design: custom Canvas views for nixie tube stat cells (or keep VU meters which replaced them)
- TurboTax L4 engagement (code exists, needs testing with working L4)
- Bluetooth mic SCO (code exists, needs testing)
- Test suites for WiM Android (WimTestReceiver will enable this)
- Play Store listing and screenshots
- Port trigger_words, onset_weights, covert_profile from Лаврентий
- Copy Lavrentiy F9 fix to `portable/engine/lavrentiy.py`
- Voice Profile Insights dashboard (Wispr Flow-inspired concept, location TBD)
- Gamification features in GUIDE category
