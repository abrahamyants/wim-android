# Session log — April 30, 2026 — WiM end-to-end UX overhaul (frozen layout, bubble visuals, drag zones)

Single long day, one /compact mid-session. MainActivity reorganization, bubble menu redesign, bubble-visual fixes, pipeline-stage animation rebuild, live-transcript routing, profile chip-pill overlay, drag-zone indicators + menu-trigger tightening at the close.

---

## 1. MainActivity layout overhaul

### Frozen top + frozen bottom + scrolling middle
Restructured `activity_main.xml` into three regions:
- **Frozen top**: brand plate + `statsRow` (today header, timer, ↺ reset)
- **Scrolling middle**: all expandable section content (Layer / Tone / Permissions / Settings / Guide / Sessions / Profile)
- **Frozen bottom**: Sign-in card + Footer

Frozen bottom was getting eaten by the Android nav bar on Samsung — added `ViewCompat.setOnApplyWindowInsetsListener` on `frozenBottom` to push the bottom padding by `systemBars().bottom`. Confirmed visible across button-nav and gesture-nav modes.

### Dual tile rows
Collapsed Layer + Tone into a single row of two tiles, same for Permissions + Settings. Each tile retains its own color/shape conventions but they share horizontal real estate. Halves the perceived vertical clutter on first paint.

### Section-overlay system (reparenting)
Tapping a section header now reparents that section's content (`contentLayer`, `contentTone`, `contentPermissions`, `contentSettings`, `contentGuide`) into a slide-in full-screen overlay with a title row + `✕ close` button. The original section view is hidden; on close, content is reparented back. Same pattern for the Guide overlay (`showGuideOverlay`) — Footer Guide tap reparents `contentGuide` (the rich `buildGuideContent` output, not a placeholder).

This kept Layer/Tone/Permissions/Settings headers `visibility="gone"` in the XML — they're surfaced via tile taps that call `openSectionOverlay`, no longer via inline expand/collapse.

### Profile overlay → chip-pill conversion
`profile_overlay.xml` Vocabulary / Corrections / Triggers were `EditText` lists. Replaced with a `ChipGroup` per category + an "add" row. New `ProfileManager` helpers:
- `removeVocabulary(word)`, `removeCorrection(from)`, `addTriggerWord(word)`, `removeTriggerWord(word)`
- `writeTriggerWords()` shared writer

XML required `xmlns:app` for `app:singleLine="false"` on ChipGroups.

Pills are styled `card_bg` (matches Work/Life/Industry selectors) with `text  ✕` label and tap-to-remove. Same drawable so the design language is consistent across the overlay.

### Sessions section default-expanded
Forced `WimApp.prefs.edit().putBoolean("sessions_section", true).apply()` on activity start so the Today/Sessions panel is visible without a tap. Today header text + timer scaled to 15sp, ↺ to 21sp (50% larger than the previous 10sp / 14sp).

### Footer reorganization
Footer is a `FrameLayout` with `footerProfile` (left) + WM logo + Privacy + `footerGuide` (right). User explicitly requested **"guide, guide, guide"** (replacing what was a Sign-in shortcut). Footer profile/guide items get click handlers that open their respective overlays.

---

## 2. Bubble menu redesign

### COLOR row inline
Was a vertical block with three swatches stacked. Re-flowed to single row: `LAYER` label + three swatches (26dp each) horizontal. Reclaims ~80dp of menu height.

### Theme: bronze / silver / gunmetal
Discovered the third swatch was using `color_indicator_gold` (didn't exist properly) — created `color_indicator_gunmetal.xml` mirroring the bronze/aluminum structure with the gunmetal palette. User correction: **"the third color swatch is gunmetal, not gold."**

### LIVE row tightening
`marginBottom=0dp` on LIVE row. `LAYER` label `marginBottom=2dp`. `DISFLUENCY` nameplate `marginBottom=6dp`. Density bumped without losing breathing room.

### L1 CLOUD row → TRANSCRIPTION sub-menu
`L1 CLOUD` row was confusing as a top-level toggle next to LIVE. Removed entirely. Replaced with: tap the `TRANSCRIPTION` nameplate → `AlertDialog` (TYPE_APPLICATION_OVERLAY) for L1 Source picker (`Local Whisper` vs `API Whisper`). Tested with same audio through both paths — API faster, but local is the default so the toggle is opt-in.

### Menu transparency
User: **"i meant the menu backgroiund transparent not the floating bubble"** — reverted the bubble opacity change (back to 100% default in `WimApp.bubbleOpacity()`), applied the 25%-less-transparent rule to the menu background only.

---

## 3. Bubble visual fixes

### Bubble-size slider — fixed-window + bezel-scale
The size slider in Settings did NOT visibly resize the bubble. Cause: window-size scaling caused drift; bezel was inheriting wrong dimensions.

Fix: window is **fixed at 180dp**. The bezel inside scales (with center pivot) to `sizePct / 100f`. Logcat confirmed window stays put while bezel grows / shrinks correctly.

```kotlin
fun updateBubbleAppearance() {
    val view = bubbleView ?: return
    val opacity = WimApp.bubbleOpacity().coerceIn(20, 100) / 100f
    val scale = WimApp.bubbleSize().coerceIn(70, 150) / 100f
    val bezel = view.findViewById<FrameLayout>(R.id.bubbleBezel) ?: return
    bezel.alpha = opacity
    if (bezel.width > 0) {
        bezel.pivotX = bezel.width / 2f
        bezel.pivotY = bezel.height / 2f
    }
    bezel.scaleX = scale
    bezel.scaleY = scale
}
```

### SEND-ready countdown
After tap-to-inject, the inner knob shows `Ns` countdown (5 → 0) before reverting. `SEND_READY_TIMEOUT_MS = 5000L`, `sendReadyTickRunnable` updates `bubbleTime` every 250ms.

Bug surfaced: the session timer (`timerRunnable`, fires every 1s, writes `bubbleTime`) was overwriting the countdown text every second — the user saw `5 → session-time → 4 → session-time → 3`. Fixed by guarding `timerRunnable`:

```kotlin
if (isSendReady) {
    timerHandler.postDelayed(this, 1000)
    return
}
```

`bubbleTime` is `visibility="gone"` by default in `bubble.xml` — only made visible during the SEND-ready countdown.

### Recording / Processing — bezel preserves theme
Recording was switching the bezel to `bubble_bg_recording.xml` (a separate red drawable), wiping the user's chosen theme color. Removed the `setBackgroundResource` calls in `setBubbleRecording` and `setBubbleProcessing`. Now only the inner knob changes (and the label switches to `PROC` in processing). Bezel keeps bronze / aluminum / gunmetal at all times.

### Comment-bubble drawable cleanup
`CommentBubbleDrawable` had a hex/weave shader overlay that was muddying the surface. Set `shaderForBounds` to always return `null`. Added a `drawOutline: Boolean = false` constructor param so the live-transcript bar can render without strokes. Bumped palette fill alpha from `0x80` → `0xC0` (50% → 75% opaque) — readable text without tinting the underlying app.

```
BRONZE   fill = 0xC0C4A574
ALUMINUM fill = 0xC0C8C8CE
GUNMETAL fill = 0xC02E2E36
```

---

## 4. Pipeline-stage animation rebuild

### Problem
L2 / L3 / L4 turbo-text animation was bouncing between 2 and 4 lines (text grew, comment bubble re-laid-out, scroll jumped, repeat). User: **"It goes from two lines to four lines, then up and down repeatedly. Let's make it something smooth and premium-looking."**

### Fix — locked 3 lines with three stacked animations
1. **Tape reel** — `◉⊚⊙⊚` cycling top-line indicator
2. **Typewriter dots** — second-line `…` builds and clears in cadence
3. **Breathing color** — fill color animates between the theme tone and a +12% lightness shift, 1.6s cycle

`showPipelineStage` lays out exactly 3 lines (`minLines = maxLines = 3`). Cross-fade transitions between stage labels. No layout pass triggers between updates because text length stays constant.

User confirmed: **"all, you know, three. Heaviest but unmissable."** No measurable latency hit; animations run on the bubble's existing `Choreographer` frame callback that was already firing for the bezel pulse.

---

## 5. Live transcript routing

### Problem
Live transcription wasn't appearing under L1 Local Whisper. Cause: `whisper-tiny` doesn't emit partials — only finals. Sherpa-onnx is one-shot.

### First attempt (rejected)
Added a `● Recording…` placeholder while the engine ran. User: **"Who the fuck needs recording? This is the live transcription."**

### Second attempt (accepted, with caveat)
When LIVE is on AND not L4 AND L1-pack doesn't need cloud, route through Android `SpeechRecognizer` (`OnDeviceRecognizer`) which DOES emit partials. Reverts to whisper-tiny / cloud only when LIVE is off.

### Trade-off accepted
Google ASR chirp returns when LIVE is on (Samsung's mandatory `partial_results` chirp). User: **"that's all right, that's okay."** Chunked-whisper streaming proposed and declined as too CPU-heavy for the win.

### Live-transcript bar growth
2 lines minimum (placeholder), grows to 6 lines max as text streams in. Uses `CommentBubbleDrawable` with opaque fill + `drawOutline=false` so the bar reads cleanly above the bubble bezel. User had originally said "two lines"; turned out they meant 2 lines of TEXT, not 2 visual outlines — clarified mid-stream.

---

## 6. Drag-zone indicators + menu-trigger tightening (close of session)

### Problem
Drag-to-top opens the menu. Threshold was 80dp from screen top — fired accidentally during normal repositioning. User: **"I am seeing that I'm triggering the settings more often than I want to. How to make it harder to trigger, you know?"**

### Fix
1. **Threshold tightened**: top trigger 80dp → 40dp. Bubble's top edge has to be jammed near the screen top before menu fires.
2. **Visual zone indicators** — slim bronze gradient bands fade in only while the user is dragging:
   - Top: `▲   M E N U`, 68dp tall
   - Bottom: `▼   H I D E`, 108dp tall
   - Both at 0.55 alpha by default (faint marker)
3. **Threshold-locked brightening** — when the bubble's top crosses into either trigger zone, the corresponding band animates to alpha 1.0. Visual is exact: bright = release here will fire.
4. **Auto cleanup**: bands fade out on release; also removed in `hideBubble()` and `onDestroy()`.
5. **Pass-through touch**: `FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS` so bands never block the drag.

Constants:
```kotlin
private val MENU_TRIGGER_DP = 40
private val HIDE_TRIGGER_DP = 80
```

Both `updateZoneIndicators` and the ACTION_UP trigger check use these constants — visual and trigger stay in lockstep.

---

## Files modified this session

- `app/src/main/java/com/wim/app/BubbleService.kt` (largest delta)
- `app/src/main/java/com/wim/app/CommentBubbleDrawable.kt`
- `app/src/main/java/com/wim/app/MainActivity.kt`
- `app/src/main/java/com/wim/app/ProfileManager.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/bubble.xml`
- `app/src/main/res/layout/bubble_menu.xml`
- `app/src/main/res/layout/profile_overlay.xml`
- `app/src/main/res/drawable/color_indicator_gunmetal.xml` *(new)*

---

## Wrong calls / corrections

- **#92 — First bubble-resize fix kept window-resize scaling.** User said `still doesn't resize at all`. The scale call was correct but the window was also resizing, causing center drift that masked the bezel scale visually. Fixed by locking window to 180dp and only scaling the bezel — should have been the first move, not the second.
- **#93 — "● Recording…" placeholder for live transcript.** User wanted live TEXT, not a recording-state status label. Reflexive UX-pattern dump from common voice apps; missed that WiM's whole pitch is `voice-to-intent, not voice-to-text` — and the LIVE bar's job is the TEXT, not the state. Rerouted through Google ASR for partials.
- **#94 — Pipeline animation 2↔4 line bounce shipped at all.** Should have spotted the layout-pass-per-update problem before claiming it was working. Locked 3-line cross-fade was the fix; the bug was visible the first frame of the first L2 test.
- **#95 — `color_indicator_gold` drawable referenced without verifying it existed.** Created `color_indicator_gunmetal.xml` AFTER user pointed out the third theme is gunmetal, not gold. Brand-consistency rule violation: should have read the existing color_indicator_*.xml files before referencing one by name.
- **#96 — Drag-to-top initial threshold (80dp) wasn't tested with the user's normal repositioning gesture.** Symmetric with the bottom (which IS reasonable at 80dp because pulling off-screen is deliberate), but the top is where users naturally drift while moving the bubble around. Should have noticed the asymmetry of *intent* vs *gesture range* before shipping the symmetric value.
- **#97 — `bubbleTime` countdown vs session-timer race wasn't caught at write time.** Two runnables writing the same TextView, no guard. Trivial bug, surfaced as soon as the user looked at the countdown. Should have grep'd `bubbleTime` references before adding a third writer.
- **#98 — Menu's `L1 CLOUD` row shipped twice as a top-level toggle before being removed entirely.** Renamed once (from `L1 SRC`), then removed when the user said it's confusing. Sub-menu via `TRANSCRIPTION` nameplate is the right placement; should have proposed that on first design pass.

---

## Open at session end

1. **Phone-side validation of drag-zone indicators.** Built and ready (`app-debug.apk`); device wasn't connected at end of session. Push pending physical re-plug.
2. **Live transcript chirp** is accepted as trade-off for the moment, not silenced. Chunked-whisper streaming explicitly declined; revisit only if user complains.
3. **Bubble unified build phases** still per the dispatch — substrate from prior sessions remains in place; nothing in this session disturbed FSM or persona observer code paths.
4. **README Session 17 entry** + this log are the only doc artifacts; commit pending.
5. **WiM `Build & Test` workflow status** — last run was Session 16's CI fix (`e52c6c9`), should still be green; no infra changes this session.

---

## Linked-worktree note

`git status` showed only this session's edits in `Documents/GitHub/wim-android/`. Other worktrees (`claude/great-chaplygin`, `claude/practical-golick`, etc.) carrying parallel-agent work are still on disk; no signal that any of them landed during today's window. The 8 modified files in this working tree all trace to today's session's described work — no surprise interleaving like Session 16 had.
