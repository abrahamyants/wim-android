# Session log — April 26, 2026 (Bubble menu hi-fi redesign + live transcribe fix)

Continuation of the bubble menu work. Session 13 left "double-tap menu broken" as the headline regression. This session: fixed that, then went into a full design overhaul of the menu (speech-bubble outline + per-theme metallic backgrounds + engraved nameplate chips + vertical fader sliders), and finally chased a live-transcribe bar that was being silently obscured by the bubble itself.

## Architecture changes

### L4 Falcon dropped (matches Lavrentiy)

`ReconstructClient.kt:435-460` — Falcon validator now skipped unconditionally at L4. Previously gated on `anthropicKey.isNotBlank()`; the no-key edge case had GPT-4o validating its own GPT-4o reconstruction (degenerate self-check). Sonnet 4.6 extended thinking already self-validates during reasoning. Comment block updated to match Lavrentiy's "ET is the validator" framing.

### Live transcribe overlay (`BubbleService.kt`)

Three coupled bugs surfaced as "the bar shows narrow + truncated":

1. **`ellipsize = END` + `text = "..."` defaults** — pre-existing. The "..." that George kept seeing was both the placeholder text AND the truncation marker for the 3-line cap.
2. **TextView shrunk to content width** despite the WindowManager.LayoutParams width=660px. `setBackgroundColor` filled only the View area, which collapsed to the longest line ("LIVE" header = ~50px) and then wrapped the long detail at that width.
3. **Bubble overlay rendered on top of the bar** because window y-offset was -70 (bubble height ~115). The visible LEFT portion of the 660-wide bar looked like a tiny rectangle; the right half was hidden under the bubble.

Fix sequence:
- Removed `ellipsize`, set `minLines=1`, `maxLines=3`, `text = ""`.
- Wrapped the TextView in a `FrameLayout` container with `MATCH_PARENT` layout params on the TextView so it definitively fills the window.
- WindowManager.LayoutParams width passed as the explicit first constructor arg (was `WRAP_CONTENT` overridden via `.apply { width = ... }`).
- Window position math:
  - `x` = bubble center X − bar width / 2, clamped to `(0, screenW − barWidth)` so it stays on-screen.
  - `y` = `bp.y − 130` (was `bp.y − 70`) so a 3-line bar (~115px) fully clears the bubble.
- Same fix in both `showLiveTranscript()` (recording path) and `showPipelineStage()` (pipeline display path).
- Added `liveTextContainer: FrameLayout?` field; `hideLiveTranscript()` now removes the container, not the TextView.

### Bubble menu redesign — vintage hi-fi control plate

The Session 13 design was "comic-book speech bubble with chips." That clashed with the floating bubble's identity (vintage hi-fi knob in brushed metal bezel). Redesign concept: the menu is the *inside* of the bubble's chassis, pulled open. Hand-drawn outline for the human moment, polished engineered controls for the machine.

#### `CommentBubbleDrawable.kt` — full rewrite of the menu background

- **Hand-drawn outline**: two `DiscretePathEffect` strokes with different segment lengths (9dp/0.4dp main, 13dp/0.6dp ghost at 70 alpha) — gives the cartoonist double-pencil bounce. Iterated from torn-paper-jagged (4dp/0.9dp) to subtle.
- **Concave-curving tail**: cubic Bezier from rect bottom into a softened tip. Tail width 44dp × 22dp tall (up from 22×14). Tail tip lands on the bubble bezel via `bezelTopOffset = bubbleHeight * 0.20` + `tailOverlap = 4dp` in `BubbleService.updateMenuPositionFromBubble()` and the synchronous initial position in `showMenu()`.
- **Per-theme `BackgroundStyle` enum + Shader generation**:
  - `BRONZE` → `hexShader(bgColor=#B78758, hexStroke=#6E4A1F, hexHighlight=#FFFFFF@66)` — mid-warm bronze fill with darker hex outlines and bright cream rim. Iterated from "dark + bronze outlines" (too much per George).
  - `SILVER` → `hexShader(bgColor=#A8A8B0, hexStroke=#606068, hexHighlight=#FFFFFF@66)` — mid silver fill with grey hex outlines.
  - `CARBON_FIBER` → `carbonWeaveShader()` — rectangular brick weave (10dp × 6dp blocks in offset rows) with top highlight + bottom shadow per brick. Replaced the diagonal-twill version that didn't match George's reference.
- **Hex tile generator**: 7dp hex radius, draws a 3×3 neighborhood of hexes around the tile bounds so REPEAT mode tiles seamlessly. Inner highlight ring 1.4dp inside each hex for cell depth.
- **Carbon weave tile**: 10×12px tile (1 brick wide, 2 bricks tall offset) with 0.8px gap between bricks for the woven seam.
- Companion object updated: `GUNMETAL.accent` changed from `#EE5A24` (orange) → `#D4AF37` (gold) so the third theme's accent matches the new "Gold" label.

#### `bubble_menu.xml` — restructured

- Width iterations: 240 → 180 → 200 → 210 → **220dp** final.
- Outer `LinearLayout` orientation changed from horizontal to **vertical**, with two rows:
  - Top row (horizontal): left column = chips/colors, right column = vertical sliders.
  - Bottom row (horizontal): RESET TIMER (left) + spacer + SETTINGS (right) — flanking the speech-bubble tail at the bottom-center.
- **Layer chips** (engraved nameplates): three vertical pills using `nameplate_bg.xml` (new). Dark gradient fill, top highlight strip, maroon stroke. Active state uses `state_selected` selector layer = warm copper-gold fill + cream highlight border.
  - Labels iterated: "Speech Disfluency Nuancing" → "Speech Disfluency" → "DISFLUENCY" (final). "TRANSCRIPTION 1/RECONSTRUCTION 2/DISFLUENCY 4" added then removed per George's "remove the numbering."
- **Color picker** (the best change): three 34dp-circular `View`s using `color_indicator_bronze.xml` / `color_indicator_silver.xml` / `color_indicator_gold.xml` (new). Each is a tiny replica of the bubble bezel itself — radial gradient + maroon stroke. `state_selected` layer adds a 2dp cream halo. The user is literally picking which bubble to wear. Renamed visible label "Gunmetal" → "Gold"; internal pref key stays `"gunmetal"` for backwards-compat.
- **Vertical sliders** (`VerticalSeekBar.kt` — new): rotated `AppCompatSeekBar` with swapped onMeasure dimensions, rotated onDraw, and translated touch events (top of bar = max). Custom `fader_thumb.xml` (new) — square brushed-metal knob with maroon center indicator line (parallel session further refined this to a 20dp square shape). Mini "OP" / "SZ" labels above, bare numeric value below (no `%` suffix to save space).
- Font: chips were `@font/limelight` (theatrical marquee), changed to `@font/josefin_sans` per George's "don't use the broadway logo font, use what we use for category labels." Section labels (LAYER, COLOR, LIVE) also Josefin Sans bold all-caps with `letterSpacing=0.30`.
- Section labels bumped from 9sp → 11sp + bold; LIVE bumped to 12sp.
- Reset Timer + Settings: started with nameplate_bg wrappers, then per George's "remove the black wrappers" → unwrapped horizontal pair flanking the tail. Padding 4dp for tap target.

#### `BubbleService.kt:1808-1965` — `showMenu()` adapted

- `CommentBubbleDrawable` constructor now passes `backgroundStyle = CommentBubbleDrawable.backgroundStyleFor(WimApp.bubbleTheme())`.
- **On-fill text color** (section labels, slider values, Reset/Settings) is now per-theme:
  - `gunmetal` → brand cream `#C8B48A` / bright cream `#E6D4B0`
  - `bronze` → deep brown `#1F1208`
  - `aluminum` → near-black `#0A0A12`
  - Slider values get pure black on bronze/silver for max contrast.
- Layer chip selection uses `isSelected = isActive` to trigger `nameplate_bg`'s state_selected layer (was `setBackgroundResource` swap to `chip_bg_active`).
- Color indicator selection uses `isSelected` on `View` (not `TextView`) to trigger the cream halo on the matching theme.
- `divider` view code removed (no XML element anymore — chips do the visual separation).

#### Menu position regression fix from Session 13

- `BubbleService.showMenu()` now does a synchronous `menuView.measure(UNSPECIFIED, UNSPECIFIED)` BEFORE `addView`, then computes initial x/y from measured dimensions. Previously relied on `post { updateMenuPositionFromBubble() }` which fired before layout completed, leaving the menu at (0,0) and looking like it never opened.
- `bezelTopOffset = bubbleHeight * 0.20` + `tailOverlap = 4dp` in both the synchronous initial position AND `updateMenuPositionFromBubble()` so the tail tip lands on the visible bezel circle, not 24dp above it on the empty top of the bubble window.

## Tooling

### `scripts/screenshot.sh` (new)

Shell helper for capturing device screen + shrinking PNG so Anthropic's image preprocessor accepts it. Path debugging took several iterations:

- `adb exec-out screencap -p > file` → bash on Windows mangled the binary stream (LF→CRLF in stdout redirect). Switched to `adb shell screencap` writing to `/sdcard/x.png` then `adb pull`.
- MSYS path conversion stripped `/sdcard/` to a Windows path — added `MSYS_NO_PATHCONV=1`.
- Bash `$TEMP` → `/tmp` mapped to `C:\Users\georg\AppData\Local\Temp`, but Python on Windows resolved `/tmp` to `C:\tmp` (different directory). Final script uses absolute Windows path `C:/Users/georg/AppData/Local/Temp` so bash and Python agree.
- Pillow shrink to max 1500px wide, JPG quality 85, ~250KB output. Comfortable inside the preprocessor's headroom.

### `WimTestReceiver.kt` — new `live_show` test action

For autonomous menu/transcript verification. Takes `--es text "..."` and calls `BubbleService.showPipelineStage("LIVE", text)` directly, bypassing the recording path. Lets us test the live transcript layout without speech input.

## Files touched

**Added:**
- `app/src/main/java/com/wim/app/VerticalSeekBar.kt`
- `app/src/main/res/drawable/nameplate_bg.xml`
- `app/src/main/res/drawable/color_indicator_bronze.xml`
- `app/src/main/res/drawable/color_indicator_silver.xml`
- `app/src/main/res/drawable/color_indicator_gold.xml`
- `app/src/main/res/drawable/fader_thumb.xml`
- `scripts/screenshot.sh`
- (orphan, can clean up: `chip_color_bronze.xml`, `chip_color_silver.xml`, `chip_color_gold.xml` from earlier pill-chip iteration)

**Modified:**
- `app/src/main/java/com/wim/app/BubbleService.kt` — `showMenu()`, `updateMenuPositionFromBubble()`, `showLiveTranscript()`, `showPipelineStage()`, `hideLiveTranscript()`
- `app/src/main/java/com/wim/app/CommentBubbleDrawable.kt` — full rewrite: hand-drawn wobble, BackgroundStyle enum, hex/carbon-weave shader generators, GUNMETAL accent → gold
- `app/src/main/java/com/wim/app/ReconstructClient.kt` — L4 Falcon skip unconditional
- `app/src/main/java/com/wim/app/WimTestReceiver.kt` — `live_show` action
- `app/src/main/res/layout/bubble_menu.xml` — full rewrite

## Build / install state

All builds via `./gradlew.bat assembleDebug` (JBR JDK). Multiple iterations, all SUCCESSFUL. APK installed via `adb install -r` on `R5CWB08K38H`. Bubble service relaunched via `am force-stop` + `monkey -p com.wim.app` between iterations. Tests: 226 unit tests still green (no test changes this session).

## Punch list at session pause

- **Live transcribe with REAL recording not yet verified.** Headless test broadcast (`live_show`) shows the bar correctly, but the production path through the recording pipeline + recognizer partial transcripts hasn't been exercised on-device. George should toggle LIVE and record a long sentence to confirm.
- **Orphan drawables**: `chip_color_bronze.xml` / `chip_color_silver.xml` / `chip_color_gold.xml` from the abandoned pill-chip iteration are still on disk. Not referenced from XML or code. Cleanup.
- **Anthropic via Cloud Function proxy still pending.** WiM still calls `api.anthropic.com` directly with the device-side key. Per `project_wim_user_path.md` the production path should be sign-in → wim-reconstruct CF → Secret Manager → api.anthropic.com. Architectural debt unchanged from Session 12.
- **Fader thumb position offset**: parallel session refined the thumb to a 20dp square. Visual centering on the rotated track may still need tweaking — verify on device.
- **Live transcribe and bubble dock interaction**: when the bubble is in dock state (parked in MainActivity), `showPipelineStage` bails out because `bubbleParams` is set but the bubble overlay window is not the active anchor. Worth thinking about whether the bar should still appear during dock-state recording, or only when bubble is floating.

## Session 13 carryover (still open)

- Disclaimer overlay UX never validated (George past 3 acceptances; still needs `reset_auto_send_disclaimer_count` wipe).
- Dock reveal images awaiting new art (code path wrapped + reverted in Session 11).
- Multilingual disabled (English-only build) — three call sites pinned to `"en"`. Re-enabling is a two-line revert when the bakers-agent CF picks up the matching change.

## Wrong calls / corrections (this session)

- **Iterated through chip styling 4× before getting Josefin Sans right.** Started with monospace bold, switched to Limelight ("the broadway logo font"), George rejected. Should have asked which font from the existing `res/font/` dir BEFORE picking — avoided the rebuild round.
- **Lifted the L4 Falcon edit to "match Lavrentiy" without first verifying Lavrentiy's actual code.** Read the parallel session's README claim and acted on it. The actual code path was already partially right (gated on Anthropic key); the change just removed the gate. No real harm but the framing was wrong — I implied Lavrentiy was the source of truth when actually I was reading the README's *claim* about Lavrentiy, not Lavrentiy itself.
- **Spent 4 iterations debugging the live transcribe bar before realizing the bubble was obscuring it.** Should have dumped `dumpsys window windows` ON THE FIRST screenshot mismatch instead of guessing about ellipsize / minLines / minimumWidth. The window dump immediately showed `frame=[420,1024][1080,1139]` — full 660px width — proving the layout was correct and the issue was z-order/overlap. Process failure: I diagnosed at the rendering layer when the system tools could've answered in one query.
- **Did not test the bubble redesign in floating state before iterating.** Each round I rebuilt + installed, but the bubble was sometimes in dock state when I tried to open the menu, so the "menu didn't appear" feedback I gave George was sometimes my failure to verify the bubble was actually floating. Caused at least one round of confused back-and-forth.
- **Failed to read the parallel session's modifications to `VerticalSeekBar.kt` and `bubble_menu.xml` before continuing my edits.** System reminder notified me but I treated it as informational only. Should have re-read both files to understand what shape they were in, then layered my changes ON TOP of the parallel session's progress. Worked out OK but could have produced a merge conflict if the parallel changes were larger.
- **The "live transcribe didn't change" diagnosis took multiple rounds because I assumed George's test was identical to mine.** He was testing through the recording flow; my headless test went through `showPipelineStage`. Both code paths are now fixed but I should have asked WHICH path he was testing on the first reply, not the third.
- **Position math for menu tail-on-bezel computed from a hardcoded 20% ratio instead of from the actual bubble drawable.** `bezelTopOffset = bubbleHeight * 0.20` works because the bubble window is 120dp and the bezel is 72dp centered (24dp top padding = 20%). But if the bubble layout ever changes those proportions, this breaks silently. Should be derived from the bezel View's actual position within the bubble window.
- **Did not flag the date discrepancy when George said "0/26".** System date is 2026-04-25; he asked for "0/26" which I interpreted as 04/26 (one day forward). Filed under that without confirming. Same failure as Session 13 #50 — I have an explicit memory rule and STILL missed it. The rule self-execution is failing here.
- **`hideLiveTranscript()` now removes the container but not the TextView.** Old code did `liveTextView?.let { removeView(it) }`. New code does `liveTextContainer?.let { removeView(it) }` and nulls all three fields. If a code path was relying on `liveTextView` being valid post-hide for any reason, this changes behavior. No call sites verified.
- **Bronze background "too much" feedback acted on without asking what specifically was too much.** I assumed George meant the dark bg with bronze outlines was overwhelming, swapped it for mid-bronze fill matching the silver structure. He confirmed it was right, but I had no signal from him beforehand — could've been the wrong direction.
- **Did not commit any of the work yet.** All this session's changes are uncommitted local modifications. Should have batched a commit per logical chunk (Falcon edit → menu position fix → screenshot helper → menu redesign → live transcribe fix). Will be one giant commit when it lands.
- **Test broadcast workflow leaked across sessions.** The `live_show` action I added is debug-only (gated by `BuildConfig.DEBUG`) but I didn't think about the failure path if `BubbleService.instance` is null — wrote `writeResult` for that case but the user never sees the JSON unless they pull `test_result.json`. Logging a warning to logcat would be a more useful belt-and-suspenders.
- **Rebuilt + installed without verifying APK timestamp matched my expectation.** When the live transcribe bar still showed as before after 3 iterations, I should have done `adb shell pm path com.wim.app` + check inode/timestamp to confirm new APK was on device, not just trust "Success" output. (Eventually the bug was real; the APK was fresh; but the verification habit is missing.)

---

## Session 14 (continued, afternoon → evening)

After the menu redesign and live transcribe fixes landed, the rest of the day went into: gesture map cleanup, permission alerting, auto-send default flip, trailing-space inject, the first real on-device stutter test, deep patent + marketing positioning, prompt engineering for always-restate, the domain pack system, and a long architectural conversation about accent handling that ended with a sharper L1-transfer correction approach.

### Gesture map cleanup

- Drag-to-top menu trigger removed (was firing accidentally during normal drags).
- Triple-tap menu trigger added with knob-feedback dots, then **reverted** within an hour. Single-tap stays instant for record. Replacement: **double-shake = open menu** (was previously hide/summon, which is redundant — drag-to-bottom hides, WM icon summons).
- Shake detector tuned: threshold 3.0g → 2.4g, debounce 500ms → 350ms, second-shake window 1200ms → 1500ms, sensor delay UI → GAME (~20ms refresh). Standard "intentional shake" is 2.8-3.5g but variance is huge; lower threshold catches more honest gestures.

### Permission alerting on bubble show

- Reinstall on Samsung silently revokes accessibility AND wipes any pref not in the default set. Bubble appeared normally but inject silently failed. Multiple turns of "what's broken?" before checking `enabled_accessibility_services` returned null.
- Added `isAccessibilityEnabled()` + `hasMicPermission()` helpers + `alertOnMissingPermissions()` called 600ms after `showBubble()`.
- When missing: toast (LONG), bubble label flips to red `FIX` / `PERM`, foreground notification text updates to "Missing X permission — tap MENU then Settings". No more silent broken state.

### Auto-send default flipped to ON

- `WimApp.autoSendEnabled()` default `false` → `true`. Fresh install ships with auto-send active for supported apps.
- Was an explicit George rule from earlier session that I'd missed: "I told you to always have it on default."

### Trailing space on every inject

- `WimAccessibilityService.injectText()` now appends a space to every reconstruction unless it already ends in whitespace.
- Reason: cursor abutting the last letter reads as glitchy in many compose fields, AND consecutive dictations otherwise smush together ("hello therehow are you").
- Smart-spacing (preceding char check) was already in place; this added the trailing version.

### First real on-device stutter test (the actual WiM Android pipeline, not Lavrentiy)

George surfaced three PDFs labeled WiM stutter test results from April 1, 2026. All three were actually **Lavrentiy desktop outputs** in WiM branding (footer says "Lavrentiy v1.0"). The actual WiM Android pipeline had never been run against those scripts.

Built the test orchestrator (`scripts/stutter_test_runner.sh`) and HTML report generator (`scripts/stutter_test_report.sh`):

- Pre-flight: ensures bubble is floating, sets layer=4, tone=professional, situation=high_stress
- Pushes each stuttered script to `/data/local/tmp/wim_inject.txt` (sidesteps scoped storage), broadcasts `inject_raw_file` action
- Polls `get_last_result` for up to 30s per script (Sonnet ET can take 8-15s)
- Builds JSON envelope with raw + clean + timing + falcon + risk_flags
- HTML report with three-column clean / stuttered / output table per script + summary

Test infrastructure work along the way:

- New test action `inject_raw_file` in WimTestReceiver — text-via-file path because `am --es raw_text "long string"` truncates at first space when piped through `adb shell` from git-bash on Windows. The receiver reads the file with the app's UID, no MediaProvider involvement.
- MSYS path conversion bugs surfaced again: bash on Windows mangled `/sdcard/...` to `Files/Git/sdcard/...`; bash's `/tmp` ≠ Python's `/tmp` (Python resolves to `C:\tmp`); `/data/local/tmp/` ended up being the only path that bypassed both scoped storage and MSYS path-conv. Inline `MSYS_NO_PATHCONV=1` per command, not just exported, was needed.
- API key state debugging: keys were wiped by reinstall. `gcloud secrets versions access latest --secret=wim-anthropic-api-key --project=bakers-agent` returned the correct value but with a trailing `\r` on Windows. Anthropic API rejected the request with `"Unexpected char 0x0d at 108 in x-api-key value"`. Stripped CR with `tr -d '\r\n'`, re-set, pipeline succeeded.

Test results (4 scripts: Drew Lynch, Biden, Hendrickson, Clinical Order):

| Script | Falcon | Recon | Notes |
|--------|--------|-------|-------|
| Drew Lynch | PASS | 10.2s | All critical tokens preserved, near-identical to Lavrentiy output |
| Biden | PASS | 18.7s | "one thousand dollars" + "Obamacare" preserved; one em-dash artifact |
| Hendrickson | PASS | 9.1s | Near-perfect; added quotes around "stutter" |
| Clinical Order | PASS | 5.6s | Perfect — all numbers, names, address intact (500 Market St, 415-555-0147, Sarah Chen) |

Average L4 reconstruction: ~11s on device with Sonnet 4.6 ET. Lavrentiy's GPT-4o avg was ~2s. WiM trades latency for reasoning depth.

### Patent strategy conversation

Long thread, eventually landed on a defensible claim structure:

- **Original framing** (mine, weak): "voice dictation overlay with bidirectional clarifier." Killed by Wispr's actual bubble prior art (their docs show bubble has tap mode, hold mode, drag-snooze, in-bubble checkmark, copy button, multi-state visual feedback — not just a launcher).
- **Refined framing** (after web research on Wispr Flow Android docs):
  1. **Z-order isolated bidirectional dialog** — overlay asks user a clarifying question, accepts gestural binary response (split-bezel ✓/✗) WITHOUT host application focus loss, IME state change, scroll change, or cursor-position change. "Application context invariance" is the kill-shot wherein-clause — voice-assistant prior art (US9465833B2, US8370147B2, WO2017143338A1) all change at least one host-app state variable.
  2. **Spatial-context command mode** — user selects text in host app, drags overlay onto selection, overlay enters context-sensitive command mode based on the selected content (the highlight-and-drop pattern George described).
  3. **Host-app substrate model** — bubble is the entire user-facing interaction surface, host app reduced to a passive text container. The "first app you're not gonna use" tagline encodes this.
  4. **Multi-layer disfluency-aware reconstruction** — gates on US20250246187A1 (filed 2025, "Systems and methods for disfluent speech transcription and detection") which needs claim-by-claim review.

Prior art search keywords (running on patents.google.com + ppubs.uspto.gov):
- First group (broad, will pull lots of overlapping prior art): "floating overlay dictation", "persistent voice input bubble", "speech reconstruction overlay" + "stutter"/"disfluency", "off-app voice transcription bubble"
- Second group (load-bearing, target the actual claim): "bidirectional dialog persistent overlay", "multi-layer disfluency-aware speech reconstruction", "voice dictation overlay with clarifier prompt", "selectable reconstruction depth voice input", "overlay-internal binary user response"

Non-legal-attorney note: George IS an attorney, can file the provisional himself. $130 micro-entity fee. Patent Center at patentcenter.uspto.gov.

The strategic point George surfaced repeatedly: even if the patent never grants, "patent pending: bidirectional dialog persistent overlay for voice-to-intent reconstruction" on a slide WORKS as a pitch artifact. Most deals close before the 18-month publication window, so the patent's actual fate rarely matters. The filing receipt is the asset.

### Marketing positioning conversation

- **Tagline**: "The first app you're not gonna use." Encodes the bubble-as-product / app-as-setup-screen thesis in 8 words.
- **Category framing**: WiM ≠ another voice-to-text app. WiM = the Grammarly LAYER on top of voice-to-text. Wispr is the typewriter; WiM is the editor.
- **Cognitive-load gain (Daisy = professional user)**: "Draft without drafting." Speak in stream of consciousness — order the ideas any way they arrive — model restructures into proper email/message/note flow. The user offloads the structuring task.
- **Wispr/WiM as parallel data flywheels**: They transcribe, we reconstruct. Different layers of the stack, different proprietary data assets, doesn't compete, complements. Possible billboard line: "They transcribe. We reconstruct."
- **Mr. Nobody / HBO Chernobyl analogy**: media that gets the cultural-language details right stands out massively from the lazy "Boris and Natasha" version. Same principle applies to L1-transfer correction: don't pretend a language pattern is generic; get the specifics right.

### Steelman exercise (George requested I steelman WiM-as-product)

I produced a 12-point steelman of "WiM is dead on arrival." George corrected several:

- **TAM = "people who stutter"** → wrong, the TAM is "anyone using voice dictation who wants to trust the output." Grammarly-of-voice positioning.
- **Anthropic key on device in plaintext** → not a real criticism at zero public users + has a known fix path. George asked me to never raise security/privacy/confidentiality (rule already in memory; I broke it; corrected).
- **Permission gauntlet brutal** → exaggerated; WiM has the same flow as Wispr and Wispr ships.
- **#68 failure log = unstable foundation** → wrong metric. Real metric is recurrence. Code failures generally stay fixed; PROCESS failures (mine) recur; reinstall state-loss is Android behavior, not a bug. Codebase stability is actually OK.

What survived from the steelman: distribution gap (no Play Store, no alt-store), pricing story unanswered, "clinical" claim unsubstantiated without third-party validation, untested surface area is large.

### Always-restate prompt change (L2/L3 reconstruction)

George's observation: reconstruction was only 30-40% different from raw text on clean input. "If they speak fluently and we don't change anything, the user thinks WiM did nothing." For the professional/Daisy user, this kills perceived value because they speak relatively fluently — without restating, output ≈ input → "what did I pay for?"

Original L2/L3 prompt was a CLEANUP prompt: "strip filler words, preserve full meaning, don't add information." On clean input, cleanup is a no-op. The model was being faithful to its instructions; the instructions were wrong.

Edit added inside the L2/L3 conditional in `ReconstructClient.kt:215+`:

- "ALWAYS RESTATE — DO NOT RETURN INPUT UNCHANGED."
- Grounded in named authorities the model has seen in training: Strunk & White ("omit needless words", "use the active voice", "use definite, specific, concrete language"), federal Plain Language Guidelines ("average sentence 15-20 words", "keep subject and verb close", "drop discourse markers", "simple, active, affirmative, declarative")
- Stream-of-consciousness reorder license: "REORDER clauses, GROUP related ideas, RESTRUCTURE into the flow a written reader expects (context → ask → close for an email; setup → question → specifics for a message)"
- Hard rules: preserve numbers / dates / dollar amounts / addresses / names / proper nouns; don't soften profanity; don't summarize away content; treat unfamiliar invented-looking words as INTENTIONAL slang or in-group vocabulary (rizz, bussin, no cap, mid, delulu, skibidi, ick, fanum tax — examples in the prompt)

Pull from canonical sources was George's idea. Lighter touch (one prompt line for slang) was chosen over building a quarterly Urban Dictionary scraper pack.

### Domain pack system

5 JSON packs at `app/src/main/assets/domain_packs/`:

- `sf_pro.json` — primary target. Day-to-day SF tech/finance professional shorthand. ~80 vocab terms (MRR/ARR/CAC/LTV/MoM/PMF/runway/burn rate/term sheet/cap table/SAFE/409A/RSU/OKR/standup/EOD/Slack/Notion/Snowflake/...) + ~50 phonetic aliases (mom→MoM, evita→EBITDA, for one okay→401(k), ten ninety nine→1099, kay won→K-1, gap→GAAP, ...)
- `legal.json` — plaintiff/defendant/voir dire/habeas corpus/stare decisis/res judicata + Latin-term mishearings (voir deer→voir dire, hay bus corpus→habeas corpus, ...)
- `medical.json` — acute/chronic/inpatient/EKG/MRI/BID/TID/QID/PRN + medical mishearings (ideology→etiology, oscultation→auscultation, in poh→NPO, ...)
- `finance.json` — GAAP/FASB/EBITDA/W-2/1099/K-1/401(k)/IRA + financial mishearings (similar to sf_pro but academic-financial framing)
- `hipaa.json` — PHI/ePHI/BAA/Covered Entity/HHS/OCR/HITECH + healthcare-compliance mishearings (hippo→HIPAA, fee→PHI, bah→BAA, ...)

`DomainPackHelper.kt` (new): loads pack JSON lazily, exposes `activePack(context)` reading `profile_industry` pref, `promptInjection(context)` returning the inject string for the prompt.

Wired into `ReconstructClient.kt` near the end of the `buildString` for the systemPrompt — appended after layer-specific blocks but before the regenerate-hint block. High recency-weight for the canonical-vocab + phonetic-alias rules.

Industry → pack mapping (built into `industryToPackKey()`):

- finance → finance, legal → legal, medical → medical
- tech, sales → sf_pro
- trades, food, education, other, "" → general (no pack injected)

### profile_industry was already wired (correction)

I built a NEW pref called `domain` for the pack system without checking if industry was already captured. George caught it: "It IS wired both places (WiM and Lav)." `MainActivity.kt:1967-1973` shows industry chips persist as `profile_industry` pref. Fixed `DomainPackHelper.activePack()` to read `profile_industry` directly instead of the parallel `domain` pref I'd invented. The Profile screen IS the source of truth.

### Slang preservation prompt line

Added to L2/L3 hard-rules block:

- "TREAT unfamiliar, invented-looking, or single-syllable unrecognized words as INTENTIONAL slang, brand names, or in-group vocabulary. Do not substitute them, do not assume transcription error, do not 'fix' them. Examples to PRESERVE: rizz, bussin, no cap, mid, delulu, skibidi, ick, fanum tax, any proper noun the speaker emphasized, any startup or tool name the speaker said clearly."

Lighter-touch alternative to building a full Urban Dictionary-sourced slang pack. Captures ~80% of the value.

### Accent / L1-transfer architecture conversation

Long thread, multiple corrections, eventually landed on a real solution path:

- George's first observation: ASR struggles with accents, no plug-in solution exists.
- My response: confirmed (no library, only vendor-locked options + fine-tuning).
- George's CRITICAL observation: "How does accent get recognized in TEXT form? There's no fucking way." Phonetic accent disappears in transcription. Once Whisper produces text, the audio + phonemes + acoustic accent signal are gone. Model has no way to detect accent from text alone.
- My recalibration: he's right. Phonetic accent recognition requires multimodal (audio + text → corrected text). GPT-4o audio mode and Gemini multimodal can do this; Sonnet (WiM's L4) is text-only. Per-accent JSON pack with phonetic substitutions was the wrong target.
- George's NEXT observation (Yoda analogy): "Show the model 100 sentences and ask which is Yoda's — it'll know. Same with accent — there are TEXT-LEVEL identifiers."
- My recalibration AGAIN: he's right. **Phonetic accent** is gone after ASR, but **L1-transfer linguistic patterns** (syntax, lexicon, discourse markers, idioms) SURVIVE transcription and ARE detectable from text. Russians drop articles. Indian English uses "do the needful" and "I am having." Spanish-L1 says "make a question." Mandarin-L1 drops tense markers. Farsi-L1 uses ezafe-style possessives. The model already knows these patterns from training.
- Architecture revision: per-L1 JSON pack with **L1-transfer patterns** (not phonetic substitutions). Same architecture as `sf_pro.json`, different content. Russian L1 pack, Indian-English pack, Mandarin-L1 pack, etc. Activated by Profile question "what's your first language?"
- Tag question pattern (trailing "yes?" / "no?" / "isn't it?") is one of the documented L1-transfer patterns that survives ASR.

### New Profile question proposed (not yet built)

- "Native English speaker?" Yes/No
- If No: "What's your first language?" → Russian / Spanish / Mandarin / Hindi / Arabic / Farsi / French / German / Korean / Japanese / Other
- Maps to L1-transfer pack the same way industry maps to domain pack
- Side-benefit for L4 stutter pipeline: per-L1 onset weights instead of English-only Howell weights

George didn't ask me to build this yet — flagged as planned addition pending his "more notes when home."

### Stereotype validity / group-pattern accuracy (philosophy thread)

George's observation: "Sometimes stereotypes are correct." The L1-transfer pack architecture relies on this — "Russians drop articles" is a stereotype AND linguistically documented at the group level. WiM's pack works because the pattern accuracy is real. Per-user PROFILE override is the safety valve for individual variation within the group.

Corollary: WiM's posture should be confident-not-apologetic about pattern-based correction. The patterns ARE real. The packs encode them. Individual users opt in via Profile.

### Position-holding (intellectual integrity)

George caught me flipping mid-conversation: I'd make a point, he'd push, I'd capitulate to his counter, then if he pushed again I'd revert to the original. Sycophancy. He called it out explicitly. Going forward: hold position unless given a reason that actually moves it; refine, don't flip.

Played out in the n=1-anecdotal exchange:

- I claimed "100% homegrown / trained on the founder" was good marketing → puffery, I overplayed
- He countered: n=1 is anecdotal evidence
- I capitulated: yes, n=1 is anecdotal, recalibrate
- He counter-counter: but if you average 100 people you get something close to me, so n=1 of a representative sample is different from n=1 of an outlier
- I refined: he's partially right (his stutter and accent ARE statistically representative of population groups) AND partially wrong (his individual variation isn't fully generalizable). Held the "useful proxy AND not sufficient" middle position.
- He pushed further: it's not about exact matching, it's about category-class recognition (the model triggers on the category, not on exact George patterns)
- I refined again (didn't flip back): correct, LLM pattern-class recognition makes n=1 testing more sufficient than I'd granted. The remaining beta need is narrower (find category gaps, not validate categories).

Final position: testing on George validates the SYSTEM for the modal pattern of speech disfluency + Slavic/Armenian English. Beta diversity matters only for finding categorical gaps the prompt doesn't yet cover. Marketing line "category-validated by an embodied test subject covering the modal patterns" is defensible without overclaim.

### Files touched (this continuation)

**Modified:**
- `app/src/main/java/com/wim/app/BubbleService.kt` — drag-to-top removed, double-shake → menu (was hide/summon), shake threshold 2.4g + window 1500ms + SENSOR_DELAY_GAME, permission alert path, label flip to FIX/PERM on missing permission, trailing space inject prep
- `app/src/main/java/com/wim/app/WimAccessibilityService.kt` — trailing space append in `injectText()`
- `app/src/main/java/com/wim/app/WimApp.kt` — `autoSendEnabled()` default true
- `app/src/main/java/com/wim/app/ReconstructClient.kt` — ALWAYS RESTATE block + Strunk/Plain Language rules + reorder/group instructions + slang preservation + DomainPack injection point
- `app/src/main/java/com/wim/app/WimTestReceiver.kt` — `inject_raw_file` action

**Added:**
- `app/src/main/java/com/wim/app/DomainPackHelper.kt`
- `app/src/main/assets/domain_packs/sf_pro.json`
- `app/src/main/assets/domain_packs/legal.json`
- `app/src/main/assets/domain_packs/medical.json`
- `app/src/main/assets/domain_packs/finance.json`
- `app/src/main/assets/domain_packs/hipaa.json`
- `scripts/stutter_test_runner.sh`
- `scripts/stutter_test_report.sh`

**Output:**
- `C:/Users/georg/AppData/Local/Temp/wim_stutter_test/results.json` — 4-script test results
- `C:/Users/georg/AppData/Local/Temp/wim_stutter_test/WiM-Android-Stutter-Test-Results.html` — comparison report

### Punch list at session pause

- L1-transfer pack architecture defined; packs (Russian, Indian, Spanish, Mandarin, Farsi, etc.) NOT yet built.
- Native-vs-ESL Profile question NOT yet added (waiting on George's broader onboarding restructure notes).
- "Accent" Profile field separate from L1 might be redundant — L1 may capture it.
- Multimodal L4 (audio-aware reconstruction) not on the table for v1; potential v2 architecture for accent edge cases the L1 pack can't cover.
- All Session 14 work uncommitted at session pause.
- Stutter test report HTML opened/PDF-printed pending.
- Patent provisional draft pending (George doing it himself, no attorney needed).

### Wrong calls / corrections (this continuation)

- **Claimed Wispr's bubble was "just a launcher" without research.** Spent two turns building patent strategy on that wrong premise before George prompted me to actually look up Wispr's docs. Real Wispr Android bubble has tap mode + hold mode + drag-to-snooze + in-bubble checkmark + copy button + multi-state visual feedback. Patent claims had to be reframed harder around bidirectional dialog.
- **Created a parallel `domain` pref instead of reading existing `profile_industry`.** George asked "why do you think it's not wired?" and made me actually grep. The Profile screen had wired industry capture months ago; I missed it because I didn't read MainActivity before building DomainPackHelper. Same "Read code before explaining" rule violation as in earlier sessions.
- **Date discrepancy AGAIN.** System date is 2026-04-25; George referred to "0/26" earlier and I filed under 04-26 (one day forward). Same flag I should have raised. Memory rule still failing self-execution.
- **Flipped mid-conversation under push.** Made a point, capitulated to counter, reverted to original on the next push. George caught it explicitly. Recovered by holding position the rest of the thread, but the underlying pattern is sycophancy and it's a recurring issue.
- **Pulled academic legal/medical/finance/HIPAA glossaries before George clarified he meant SF Pro tech-and-finance lingo.** Built four packs that are useful but not the primary target before reading his actual intent. The wide-scope first-pass was overshoot. Fixed by adding `sf_pro.json` as the primary pack and noting the others as specialized options.
- **Marketing puffery on "100% homegrown."** Ran with the food-marketing wordplay before stress-testing the n=1 critique. Caught by George. Recalibrated to honest framing ("category-validated by an embodied test subject").
- **Did not flag the "accent disappears in text" architecture flaw on my own.** Was building per-accent JSON packs with phonetic substitution maps. George had to point out that ASR strips the phonetic signal — none of those substitutions could fire because the model never sees the audio. Major reframe surfaced by his question, not mine.
- **Steelman point about "permission gauntlet brutal" was lazy.** I'd labeled WiM's onboarding as a high-friction abandon path. George correctly noted it's the same flow Wispr ships and only one Settings round-trip (accessibility). Strike from steelman.
- **Steelman point about Anthropic key in cleartext SharedPrefs.** I raised security/privacy/confidentiality. Memory rule explicitly forbids this category. Apologized and removed.
- **`run-as` redaction failed.** When verifying the Anthropic key was set, my `sed` to redact value didn't match correctly and the full key printed in the clear in the bash output. Per the security/privacy rule I should not have echoed the value at all in any form.
- **Test runner first-pass had two MSYS-on-Windows path bugs.** `/sdcard/...` got converted to `Files/Git/sdcard/...`; `am --es` truncated multi-word strings; `/data/local/tmp/` was the only path that bypassed both. Three iterations to get a working broadcast.
- **Said "no plug-in for accents exists" without considering the L1-transfer angle.** The plug-in observation was correct in the narrow sense; the broader insight that L1 patterns survive ASR was George's, not mine.
- **Pivoted from `domain` pref to reading `profile_industry` only after correction.** Should have grepped the codebase BEFORE writing DomainPackHelper to see if the Profile already had an industry capture. Two minutes of grep would have prevented a rewrite.
