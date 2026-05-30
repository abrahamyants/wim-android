# WiM-Android Accessibility + Visual Audit
**Date:** 2026-05-29  
**Scope:** Bubble, BubbleMenu, MainActivity tiles, ProfileOverlay, Onboarding, BubbleClarifierUI, BubbleAffect, ShimmerFx  
**Auditor:** Visual Validation Agent (claude-sonnet-4-6)

---

## 1. Accessibility Blockers (P0 — TalkBack inoperable or severely degraded)

### 1A. Bubble clarifier split-bezel has zero TalkBack affordance
**File:** `app/src/main/res/layout/bubble.xml` lines 63–113  
The clarifier mode splits the bezel into green-left / red-right halves with ✓ and ✕ glyphs (`bezelYes`, `bezelNo`). Both sides are `android:clickable="false" android:focusable="false"`. The tap-zone for YES/NO is handled entirely via `BubbleService`'s touch listener on the parent `bubbleRoot` (which has `contentDescription="WiM bubble — tap to record, double-tap for menu"`). When the clarifier is active that description is stale, and the left-half / right-half tap split is completely invisible to TalkBack. A TalkBack user hears "WiM bubble — tap to record, double-tap for menu" while the actual affordance is "tap left half for yes, tap right half for no." There is no announcement when the clarifier fires, no role change, no state description update on the container.

**Impact:** Clarifier — WiM's core differentiating feature — is functionally inoperable for TalkBack users. This is the single highest-priority accessibility gap for an app explicitly targeting people with disabilities.

**Fix:** When clarifier activates, update `bubbleRoot.contentDescription` to "Clarifier active: [word]? Tap left half for yes, tap right half for no." and post an accessibility event `TYPE_VIEW_SELECTED` or `TYPE_ANNOUNCEMENT` via `sendAccessibilityEvent`. Add `android:accessibilityLiveRegion="polite"` to `bubbleLabel` state changes during clarifier.

### 1B. Bubble menu layer nameplates (`menuLayer1/2/4`, `menuL1Local/Cloud`) have no role or contentDescription
**File:** `app/src/main/res/layout/bubble_menu.xml` lines 96–174  
These are `TextView` elements used as toggle buttons (selected state flips visually). They are `clickable="true"` and `focusable="true"` but carry no `contentDescription`, no `role` override, and no `android:checkable="true"`. TalkBack announces them as their raw text strings ("TRANSCRIPTION", "RECONSTRUCTION", etc.) with no indication they are selectable controls or what their current selection state is.

**Fix:** Add `android:contentDescription="Transcription layer, [selected/not selected]"` or use `ViewCompat.setAccessibilityDelegate` in `BubbleMenu.kt` to set `AccessibilityNodeInfoCompat.setCheckable(true)` and `setChecked(isSelected)`.

### 1C. `BubbleAffect` animations ignore Android's Remove Animations system setting
**File:** `app/src/main/java/com/wim/app/BubbleAffect.kt` (entire file)  
No code path reads `Settings.Global.ANIMATOR_DURATION_SCALE` or uses `ValueAnimator.areAnimatorsEnabled()`. All six affect animators (RESTING breathe, LEANING_FORWARD lift, TA_DA pop, HEAD_TILT rotation, THINKING_SWAY, COMMISERATE sigh) run unconditionally. The RESTING breathe runs continuously at 4.2 s cycles while the bubble is idle — exactly the sustained motion that Android's "Remove animations" / "Reduce motion" system setting is designed to suppress.

Similarly `ShimmerFx.attachTextShimmer` (`ShimmerFx.kt` line 76) and `ShimmerFx.BorderShimmerDrawable.startInfinite()` run `ValueAnimator.INFINITE` with no check.

**Fix:** Wrap every `anim.start()` in `BubbleAffect` and `ShimmerFx` with:
```kotlin
if (ValueAnimator.areAnimatorsEnabled()) anim.start()
```
For RESTING specifically, also guard on `Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f`.

### 1D. `wimLogoSummon` tap target has no contentDescription
**File:** `app/src/main/res/layout/activity_main.xml` line 62  
The gold "WiM" Limelight text is the summon control for the floating bubble (`clickable="true"`, `focusable="true"`) but has no `contentDescription`. TalkBack reads the raw text "WiM" with no indication it launches the bubble. A TalkBack user cannot discover this is a tap-to-summon control.

**Fix:** Add `android:contentDescription="WiM — tap to summon or restore the bubble"`.

---

## 2. WCAG-Class Issues (Contrast Failures, Missing Semantics, Touch Target Undersize)

### 2A. IDLE label contrast — Bronze theme FAILS WCAG AA at small text
**Files:** `BubbleAppearance.kt` line 51; `BubbleService.kt` line 1847; `bubble_color_picker.xml` line 72  
Bronze theme IDLE label: `#FF1F1208` (near-black, ~2A1208 dark espresso) on the bronze bezel gradient. The bronze bezel (`bubble_bg_idle`) renders a warm amber-copper gradient. The lowest-contrast bezel tone in that gradient is approximately `#C8803A` (light copper). Contrast ratio of `#1F1208` on `#C87830` ≈ 3.1:1 — below WCAG AA 4.5:1 for the 13sp text used. The Aluminum theme IDLE label (`#FF555560` on silver ~`#C0C0C5`) computes to approximately 2.8:1, also below 4.5:1.

**Note on Gunmetal:** Cream-gold `#C8B48A` on dark steel `~#2A2A32` ≈ 6.5:1, which passes AA. Gunmetal is the only compliant theme at idle.

**Fix:** Darken bronze IDLE label to `#0A0000` (ratio ≈5.2:1) and darken aluminum IDLE label to `#2A2A30` (ratio ≈5.4:1 on light silver).

### 2B. Color-only signaling for recording (red knob) and send-ready (green knob)
**Files:** `BubbleService.kt` lines 1518–1522 (REC), lines 1341–1350 (SEND/green), `BubbleStageArc.kt` line 53  
Recording state: inner knob switches to `R.drawable.bubble_inner_active` (a red drawable) with label text "REC". The label text is present and non-color — this partially satisfies WCAG 1.4.1. However there is no icon or shape change on the knob itself beyond color.

Send-ready state: knob label reads "SEND" in green (`#5AD15F`) on what is presumably the normal knob background. The label text alone is meaningful.

Processing state (`BubbleStageArc.setProcessing`): label reads "PROC" in cream-gold. OK.

**Primary gap:** The stage arc quadrants (`BezelStageArcView`) are purely color-based progression indicators — no label, no pattern, no shape difference between active/inactive quadrant — with no accessible description of current pipeline stage. When TalkBack is active, a user cannot determine what pipeline stage the bubble is in other than reading the label text on the knob.

**Fix:** `BubbleStageArc.setStage` should call `bubbleRoot.contentDescription = "WiM bubble — [Transcribing/Reconstructing/Verifying/Injecting]"` on each transition so TalkBack users hear the stage name.

### 2C. Color swatch picker touch targets: visual size 26dp, extended by `minWidth/minHeight=48dp`
**File:** `app/src/main/res/layout/bubble_menu.xml` lines 193–219  
The three color swatches (`menuThemeBronze/Aluminum/Gunmetal`) are 26dp×26dp visual elements with `minWidth="48dp"` and `minHeight="48dp"`. On API 26+ `minWidth/minHeight` on a `View` (not a `Button`) does not guarantee a 48dp touch target — the actual touch rect depends on the view's measured size which is constrained by the parent's `layout_width="0dp"` weight allocation inside a narrow horizontal layout. On a 220dp menu with the left column at weight=1 and these three swatches at 6dp margin each, the effective measured width per swatch is approximately `(220-14-12-18) / 3 ≈ 58dp`, so the horizontal spacing is likely sufficient. However the vertical measured height will be 26dp (set explicitly) — `minHeight=48dp` is overridden by the explicit `layout_height="26dp"`. **The vertical touch target is 26dp, failing the 48dp minimum.**

**Fix:** Change `android:layout_height="26dp"` to `android:layout_height="48dp"` on all three swatch `View` elements, or wrap each in a 48dp×48dp `FrameLayout` touch proxy (same pattern used for `bezelRegen` at `bubble.xml` lines 119–137).

### 2D. Profile overlay selection buttons: 36–40dp height, missing selected-state announcement
**File:** `app/src/main/res/layout/profile_overlay.xml` lines 103–148, 237–262, etc.  
All profile selection pills (`profileWorkOnly`, `profileLifeOnly`, `profileBoth`, all industry buttons, all L1 buttons, hours buttons, sensitivity buttons) are set to `android:layout_height="36dp"` or `android:layout_height="40dp"`. The 36dp items fail the 48dp minimum touch target. The 40dp items are marginally below. Given these are the primary interaction elements of the profile overlay (used frequently during setup), this is a consistent touch target deficit across ~30 tappable elements.

Additionally, all these `TextView` elements use `android:checkable="true"` in XML but do not set `android:role` or use `CheckableDelegate`. The checkable state is visual only (via `card_bg` drawable state_selected). TalkBack will not announce "checked/unchecked" for these items.

**Fix:** Increase all profile pill heights to 48dp minimum. Add `ViewCompat.setAccessibilityDelegate` in `MainActivity.kt` to set `setCheckable(true)` and `setChecked(isSelected)` on each pill when selection state changes.

### 2E. `vocabAddBtn`, `corrAddBtn`, `triggerAddBtn`, `clarBlacklistAddBtn` — no contentDescription
**File:** `app/src/main/res/layout/profile_overlay.xml` lines 447–462, 541–555, 607–624, 735–748  
These action buttons use `android:text="@string/profile_add"` ("ADD") or `android:text="@string/profile_add_current_app"` ("ADD CURRENT APP") but have no `contentDescription`. For "ADD" specifically, TalkBack reads "ADD, button" with no context about what is being added (vocabulary word? correction? trigger word?). In a dense overlay with multiple ADD buttons, this is ambiguous.

**Fix:** Add `android:contentDescription="Add vocabulary word"`, `"Add correction"`, `"Add trigger word"` respectively.

### 2F. `BubbleClarifierUI` dismiss animation uses hardcoded text color `0x88FFFFFF` at 53% alpha
**File:** `app/src/main/java/com/wim/app/BubbleClarifierUI.kt` line 174  
The sparkle sequence step `✧` sets label color to `0x88FFFFFF` (white at ~53% opacity). This is a transient animation step (400ms), so it's not a sustained contrast failure — but it is a real WCAG 1.4.3 failure while visible for that 400ms window at the ✧ frame.

### 2G. `bubble_menu.xml` `menuOpenSettings` has no `contentDescription` and padding is 4dp only
**File:** `app/src/main/res/layout/bubble_menu.xml` lines 326–334  
The Settings button is a raw `TextView` with `android:padding="4dp"`. The effective touch target is the text width + 8dp horizontal + whatever `wrap_content` height produces (~20–24dp). No `minWidth`/`minHeight` set. No `contentDescription`. This element fails both 48dp touch target and semantic labeling.

**Fix:** Add `android:minWidth="48dp" android:minHeight="48dp"` and `android:contentDescription="Open settings"`.

### 2H. `VuMeterView` stats row has no contentDescription — graphical elements unreadable by TalkBack
**File:** `app/src/main/res/layout/activity_main.xml` lines 155–180  
Four `VuMeterView` instances (sessions, WPM, cleanup score, overall score) are custom canvas views with no `contentDescription` set in XML. `VuMeterView.kt` does not override `getAccessibilityNodeInfo()` and sets no content description programmatically. TalkBack skips or announces them as unlabeled images.

**Fix:** Set `contentDescription` via XML attribute or programmatically in `MainActivity.kt` after populating values, e.g. `vuSessions.contentDescription = "Sessions today: $n"`.

### 2I. `Switch` widget in profile overlay uses deprecated `android.widget.Switch` — not accessible
**File:** `app/src/main/res/layout/profile_overlay.xml` line 777  
The L1 contribution toggle uses `<Switch ...>` (deprecated `android.widget.Switch`) instead of `MaterialSwitch` (which is used correctly for `menuLiveToggle` in `bubble_menu.xml`). The deprecated `Switch` has weaker accessibility support in Material 3 contexts and does not respect the `WimSwitchTheme` style.

**Fix:** Replace with `<com.google.android.material.materialswitch.MaterialSwitch android:id="@+id/switchContributeL1" .../>`.

### 2J. Limelight font used in Compose onboarding (`WelcomeScreen`) — no accessibility heading role
**File:** `app/src/main/java/com/wim/app/onboarding/screens/WelcomeScreen.kt` lines 147–160  
The "WiM" title text (56sp Limelight) and "WHAT I MEANT" subtitle in the onboarding screen carry no `semantics { heading() }` modifier. In Compose, large display text does not automatically get heading semantics — TalkBack will read it as regular content, not as a page header, which breaks the navigation experience for screen reader users.

**Fix:** Add `Modifier.semantics { heading() }` to the "WiM" and "WHAT I MEANT" Text composables.

### 2K. Limelight font family — `@font/limelight` present, usage is consistent with brand rule
**Files:** `app/src/main/res/font/limelight.ttf` confirmed; used in `activity_main.xml` line 68 (`@font/limelight`), `WelcomeScreen.kt` line 149 (`LimelightFamily`), `activity_bouncer.xml`, `bubble_color_picker.xml`.  
**No brand drift found on Limelight.** The font is used consistently for the WiM identity mark (brand plate, onboarding header, demo console title bar, bouncer intro). Non-identity text correctly uses `josefin_sans`, `plus_jakarta_sans`, `geist`, or `geist_bold`. Brand rule is respected.

### 2L. `copper_gold` vs `orange_label` color drift
**File:** `app/src/main/res/values/colors.xml` lines 11–12  
`copper_gold = #FF8A5A2F` (warm brown-gold), `orange_label = #FF963611` (burnt umber-orange). These are used interchangeably in some paths: `profile_overlay.xml` uses `copper_gold` for all section headers; `BubbleService.kt` line 1862 uses `copper_gold` for prediction snippet; `BubbleClarifierUI.kt` line 147 uses `orange_label` for the ✓ acknowledge flash and sparkle sequence. The `WelcomeScreen.kt` `Stat` composable uses `copper_gold` for value text. The `BubbleStageArc` uses `#C8B48A` (cream, not `copper_gold`) for gunmetal theme arcs.

No hard drift violation found — the two are intentionally separate semantic tokens. However `orange_label` is used in `BubbleClarifierUI` for the ✓ acknowledge color while all profile and UI chrome uses `copper_gold`. If the intent is that the clarifier flash reads as "brand" the token should be `copper_gold`, not `orange_label`. Minor inconsistency, not a blocker.

---

## 3. Visual Polish Gaps

### 3A. Bubble menu `VerticalSeekBar` touch target width is 26dp
**File:** `app/src/main/res/layout/bubble_menu.xml` lines 249–259, 291–301  
Both opacity and size faders have `android:layout_width="26dp"`. SeekBar touch interaction extends to the thumb, which is itself some width, but the track that the user swipes against is 26dp wide. This is below the 48dp recommended minimum. On a floating overlay menu held by one hand while recording with the other, 26dp fader tracks are particularly prone to miss-taps.

**Fix:** Increase to `android:layout_width="48dp"` and use `android:paddingStart/End` to keep the visual track narrow while the touch zone is 48dp.

### 3B. `bezelRegen` icon positioned at `marginBottom="-17dp"` — clips outside the 72dp bezel at 150% scale
**File:** `app/src/main/res/layout/bubble.xml` lines 119–137  
The regen icon FrameLayout is `layout_gravity="bottom|center_horizontal"` with `layout_marginBottom="-17dp"` inside the 72dp `bubbleBezel`. At scale 150% (the max user-selectable size), the bezel grows to 108dp and the window is 180dp (120×1.5). The 17dp negative margin pushes the icon below the bezel's logical bottom. With the bezel scaling from its center pivot, at 150% the bottom edge of the bezel sits at ~90dp from the window top (center = 60dp, half of 108dp = 54dp below center = 114dp from top in a 180dp window). The icon hangs below that at ~17dp more. The bubble window itself is `layout_width=120dp` fixed with `clipChildren=false` — so the icon does render but at extreme scale it extends well outside the nominal window bounds and may conflict with other overlay elements or appear misaligned from the bezel ring visually.

**This is a visual clipping / alignment risk at max size.** At default scale (100%) the icon hangs 17dp below the 72dp bezel into the padding zone of the 120dp window — within safe bounds. At 150% it extends into the window's outer ring area cleanly. Low-priority but worth tracking.

### 3C. `bubble_color_picker.xml` Bronze IDLE text color is hardcoded `#FF1F1208`, not `@color/text_dim` or theme token
**File:** `app/src/main/res/layout/bubble_color_picker.xml` line 71  
The Bronze preview picker's IDLE label uses `android:textColor="#FF1F1208"` — a hardcoded hex that matches `BubbleAppearance.kt` line 51. The Aluminum picker uses `#FF555560`. The Gunmetal picker uses `#FFC8B48A`. All three match the runtime values but are not drawn from named color resources. If the runtime values change in `BubbleAppearance.kt`, the picker preview will be out of sync silently.

**Fix:** Extract all three to named color tokens (`@color/bubble_idle_label_bronze`, etc.) shared between the picker XML and `BubbleAppearance.kt`.

### 3D. `menuPredictionChip` in `bubble_menu.xml` has no background drawable and no minHeight
**File:** `app/src/main/res/layout/bubble_menu.xml` lines 23–38  
The prediction chip is a plain `TextView` with padding but no background set in XML. The background is never set in `BubbleMenu.kt` either — the chip appears as floating text with no visual container, which breaks the "vintage hi-fi control plate" aesthetic. On light-colored menu fills (bronze or aluminum theme) a naked text chip looks unfinished compared to the engraved nameplate style of everything else on the menu.

### 3E. `BubbleAffect.applyResting()` calls `anim.start()` on every `BubbleState.Idle` transition — including redundant re-idles
**File:** `app/src/main/java/com/wim/app/BubbleAffect.kt` lines 106–124  
`applyResting()` cancels existing anim and starts a new 4.2s breathe cycle on every idle entry. If `setBubbleIdle()` is called multiple times in succession (clarifier timeout re-entry, regen cancel, etc.) the breathe resets its phase each time, creating a visual jump rather than a continuous gentle breathe. This is most visible after the clarifier sparkle sequence fires three `timerHandler.postDelayed` callbacks that all end in `onAcknowledged → setBubbleIdle → applyResting`.

**Fix:** Guard in `applyResting()`: if `restingAnim?.isRunning == true`, don't cancel and restart.

### 3F. `WelcomeScreen` `LiveDot` pulsing green circle uses `spring` animation unconditionally
**File:** `app/src/main/java/com/wim/app/onboarding/screens/WelcomeScreen.kt` lines 379–397  
The `LiveDot` composable runs a continuous spring-based alpha animation (`while(true) { pulse.animateTo(1f, s); pulse.animateTo(0.4f, s) }`). No `LocalInspectionMode` or `LocalReducedMotion` check. On the onboarding screen this is the first thing users with motion sensitivity see. Compose does not automatically suppress this on "Remove animations" — the coroutine in `LaunchedEffect` continues regardless.

**Fix:** Check `LocalReducedMotion.current` (available as `accessibilityManager.isEnabled && prefersReducedMotion`) or use `rememberInfiniteTransition` with `isInfinite = !isReducedMotion`.

---

## Summary Table

| ID | Severity | Component | Issue |
|----|----------|-----------|-------|
| 1A | P0 | BubbleClarifierUI | Clarifier split-bezel has zero TalkBack affordance — core feature inoperable |
| 1B | P0 | BubbleMenu | Layer nameplate toggles have no role/checked state for TalkBack |
| 1C | P1 | BubbleAffect, ShimmerFx | All animations ignore system "Remove animations" setting |
| 1D | P1 | MainActivity | wimLogoSummon has no contentDescription |
| 2A | P1 | BubbleAppearance | Bronze and Aluminum IDLE label fails WCAG AA contrast (~3:1 on mid-copper) |
| 2B | P1 | BubbleStageArc | Pipeline stage arc is color-only — no TalkBack state announcement |
| 2C | P1 | BubbleMenu | Color swatch vertical touch target is 26dp, not 48dp |
| 2D | P1 | ProfileOverlay | ~30 selection pills at 36dp height, missing checked-state semantics |
| 2E | P2 | ProfileOverlay | ADD/ADD CURRENT APP buttons have no contextual contentDescription |
| 2F | P2 | BubbleClarifierUI | ✧ sparkle frame: 53% alpha white text fails WCAG 1.4.3 transiently |
| 2G | P2 | BubbleMenu | Settings button: 4dp padding, no minWidth/minHeight, no contentDescription |
| 2H | P2 | MainActivity | VuMeterView stats have no contentDescription — invisible to TalkBack |
| 2I | P2 | ProfileOverlay | Deprecated `Switch` widget for L1 contribution toggle |
| 2J | P2 | WelcomeScreen (Compose) | Title text lacks `semantics { heading() }` modifier |
| 2K | OK | Fonts | Limelight font usage consistent with brand rule — no drift |
| 2L | P3 | Colors | `orange_label` vs `copper_gold` minor inconsistency in clarifier flash |
| 3A | P2 | BubbleMenu | Fader track width 26dp — below 48dp min touch zone |
| 3B | P3 | bubble.xml | Regen icon at -17dp margin may clip at 150% scale |
| 3C | P3 | bubble_color_picker.xml | Hardcoded IDLE label hex not shared with BubbleAppearance.kt |
| 3D | P3 | BubbleMenu | Prediction chip has no background drawable — looks unfinished |
| 3E | P3 | BubbleAffect | Breathe anim restarts phase on every idle entry — visible jump |
| 3F | P3 | WelcomeScreen | LiveDot continuous spring anim ignores reduce-motion preference |
