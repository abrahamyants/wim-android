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
- **GPT reconstruction** — OpenAI API (gpt-4o-mini for L2-3, gpt-4o for L4)
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

### Design Limitations Documented

Android XML drawables cannot replicate Лаврентий's CSS effects:
- No multi-layer box-shadows (CSS stacks 5, Android gets 1 elevation)
- No backdrop blur / glassmorphism (needs RenderEffect API 31+)
- No repeating patterns (CSS `repeating-linear-gradient`, Android needs bitmap tiles)
- No pseudo-elements (`::before`/`::after` for overlays)
- No inset shadows (`box-shadow: inset`)
- No multi-layer text-shadow (CSS stacks for glow, Android gets 1)

**Solution path:** Jetpack Compose migration closes most gaps. Custom Canvas views for remaining effects. PNG slices as fallback for pixel-perfect elements.

## Instructions for Next Model

**READ THIS FIRST.** George iterates fast via voice (Wispr/Lavrentiy at 182 WPM). His messages are phonetic, messy, and context-dependent. Extract intent, not spelling.

### Current State
- App builds and installs via `./gradlew.bat assembleDebug` + ADB
- Phone: Samsung `R5CWB08K38H`, connected via USB, ADB at `C:\Users\georg\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- ADB screenshots: `adb shell "screencap -p //sdcard//screen.png"` then `adb pull` (double-slash to prevent Git bash path mangling)
- **Multi-display warning:** Always use `//sdcard//` not `/sdcard/`. The screencap warning about multiple displays is cosmetic — the screenshot still works.
- Lavrentiy desktop: `C:\Users\georg\Documents\GitHub\lavrentiy\portable\START.bat` — uses `desktop.py` wrapper, NOT `lavrentiy.py` directly
- Lavrentiy dashboard CSS: `C:\Users\georg\Documents\GitHub\lavrentiy\dashboard.html` — THE source of truth for design

### Known Issues
1. **Backend tier gate:** Cloud Function at `bakers-agent.cloudfunctions.net` rejects L4 calls with `403: Layer 4 requires Pro tier`. George's Firestore `tier` field is "Free". Fix: update Firestore document or bypass tier check.
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
- **Backend tier fix** — update Firestore to allow L4 for George's account, or bypass tier check
- One-directional sticky Settings header — verify/improve
- Design: Jetpack Compose migration for proper Лаврентий visual parity
- Design: custom Canvas views for nixie tube stat cells
- Onboarding — quick vs full setup (code exists, needs testing)
- TurboTax L4 engagement (code exists, needs testing with working L4)
- Bluetooth mic SCO (code exists, needs testing)
- Test suites for WiM Android
- Play Store listing and screenshots
- Port trigger_words, onset_weights, covert_profile from Лаврентий
- Copy Lavrentiy F9 fix to `portable/engine/lavrentiy.py`
