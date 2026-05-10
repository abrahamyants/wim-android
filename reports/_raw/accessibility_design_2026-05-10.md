# WiM Android — Accessibility & Design Audit
**Date:** 2026-05-10
**Auditor:** Visual Validation Expert (Claude Sonnet 4.6)
**Scope:** layout XML, strings.xml, colors.xml, values-night/colors.xml, drawable vs drawable-night coverage, onboarding.html, brand.local.md

---

## SEVERITY LEGEND
- **[CRIT]** — Blocks TalkBack use entirely, or is a WCAG AA hard fail
- **[HIGH]** — Significant accessibility gap; fails Android 48dp touch-target minimums or WCAG AA contrast
- **[MED]** — Degrades TalkBack experience or creates confusion; contrast borderline
- **[LOW]** — Design polish, terminology, or missing-but-not-breaking items

---

## 1. ACCESSIBILITY — CONTENT DESCRIPTIONS

### [CRIT-A1] Bubble root has no accessible label — TalkBack cannot announce it
**File:** `app/src/main/res/layout/bubble.xml`, line 2-8
The `FrameLayout` (`bubbleRoot`) is the primary interactive surface for the entire overlay. It receives touch gestures (tap, hold, double-tap, drag) but has no `android:contentDescription`. TalkBack users will hear the bare class name or nothing meaningful. This is the most-used element in the entire app.
**Required fix:** Add `android:contentDescription="@string/bubble_cd"` to the root FrameLayout and add that string. Example value: `"WiM bubble — tap to record, double-tap for settings"`. Also needs `android:importantForAccessibility="yes"`.

### [CRIT-A2] Bubble inner elements have no accessibility role — state changes are invisible to TalkBack
**File:** `app/src/main/res/layout/bubble.xml`, lines 77-107 (bezelYes/bezelNo), lines 150-161 (bubbleLabel), lines 163-173 (bubbleTime)
The clarifier YES/NO checkmark and X views (`bezelYes`, `bezelNo`) are `TextView` elements that convey a binary decision (confirm/reject the clarified word) but have `android:clickable="false"` and `android:focusable="false"`. TalkBack cannot interact with them. The action is instead routed through a touch handler on the parent — but the parent has no content description either (see CRIT-A1). A blind user cannot perform the clarifier gesture.
`bubbleLabel` (shows "IDLE", "REC", "DONE" etc.) changes state programmatically with no `AccessibilityEvent.TYPE_VIEW_SELECTED` or `announceForAccessibility()` documented in the layout. The layout alone cannot fix this, but the static label text `android:text="IDLE"` in XML will be read at launch even if the live state changes are announced elsewhere.

### [HIGH-A3] bezelRegen ImageView has a contentDescription but is 14x14dp — both too small and potentially invisible to TalkBack when state changes
**File:** `app/src/main/res/layout/bubble.xml`, lines 112-122
`bezelRegen` has `android:contentDescription="Regenerate"` (pass on description), but at `14dp × 14dp` it is far below the Android minimum of 48dp × 48dp for interactive touch targets. The element is `android:clickable="true"` and `android:focusable="true"`. A user with large text or motor impairment cannot reliably tap a 14dp target.
**Required fix:** Wrap in a 48dp minimum touch-target container or use `android:minWidth`/`android:minHeight` with transparent padding to reach 48dp. Do not change the visual size if aesthetics require it — pad the touch area transparently.

### [HIGH-A4] profileClose (✕) TextView has no contentDescription
**File:** `app/src/main/res/layout/profile_overlay.xml`, lines 39-47
The close button for the entire profile overlay panel is a `TextView` with `android:text="✕"`. It has no `android:contentDescription`. TalkBack will read the Unicode character name ("multiplication sign" or similar), not "Close profile". For a user navigating by accessibility focus, this element is functionally unlabeled.
**Required fix:** Add `android:contentDescription="@string/profile_close"` and add that string.

### [HIGH-A5] Color picker `pickerBronze`, `pickerAluminum`, `pickerGunmetal` have no contentDescription
**File:** `app/src/main/res/layout/bubble_color_picker.xml`, lines 42-47, 100-105, 155-160
Three tappable `FrameLayout` elements are the only affordance for selecting bubble color. They have `android:clickable="true"` and `android:focusable="true"` but no `android:contentDescription`. A blind user cannot distinguish or activate these elements.
**Required fix:** Add `android:contentDescription` to each. The string resources `bubble_color_bronze`, `bubble_color_silver`, `bubble_color_gunmetal` already exist in strings.xml — use them here.

### [HIGH-A6] menuThemeBronze/menuThemeAluminum/menuThemeGunmetal are 26x26dp color swatches with contentDescriptions but below 48dp touch target
**File:** `app/src/main/res/layout/bubble_menu.xml`, lines 192-211
The three theme swatches already have `android:contentDescription` (strings referenced correctly — this is a pass on labeling). However, at `26dp × 26dp`, they fail the Android 48dp minimum interactive target size. The swatches sit inside a LinearLayout with no additional padding. A motor-impaired user will miss them.
**Required fix:** Add `android:minWidth="48dp"` and `android:minHeight="48dp"` (transparent overflow) or pad the parent row.

### [HIGH-A7] Bubble menu Live toggle (MaterialSwitch) has no label linked via labelFor or contentDescription
**File:** `app/src/main/res/layout/bubble_menu.xml`, lines 61-75
`menuLiveLabel` (TextView "LIVE") and `menuLiveToggle` (MaterialSwitch) are sibling views in a LinearLayout. There is no `android:labelFor="@+id/menuLiveToggle"` on the label, and the switch has no standalone `android:contentDescription`. TalkBack will read the switch without context.
**Required fix:** Add `android:labelFor="@+id/menuLiveToggle"` to `menuLiveLabel`, or set a content description on the switch itself.

### [HIGH-A8] VuMeterView custom views have no contentDescription in layout
**File:** `app/src/main/res/layout/activity_main.xml`, lines 154-179
Four `VuMeterView` instances (`vuSessions`, `vuWpm`, `vuCleanup`, `vuScore`) are custom views with no `android:contentDescription` in the layout. If the custom view class does not implement `onInitializeAccessibilityNodeInfo()` internally, TalkBack cannot announce their meaning or current values. This is a stats display visible to sighted users that is completely opaque to TalkBack.
**Required fix:** Either add `android:contentDescription` to each in layout, or verify and cite where `onInitializeAccessibilityNodeInfo()` is implemented in `VuMeterView.kt`. This audit cannot confirm the Kotlin implementation from XML alone — flag as requiring code-level verification.

### [HIGH-A9] BezelStageArcView custom view has no contentDescription
**File:** `app/src/main/res/layout/bubble.xml`, lines 50-55
`BezelStageArcView` is a custom view that renders pipeline-stage arcs (Transcribing → Reconstructing → Verifying → Injecting). No `android:contentDescription` is set in layout. The state it conveys (what pipeline stage is active) is critical feedback for users who cannot see the visual arcs.

### [HIGH-A10] AmbientOrbsView missing importantForAccessibility="no"
**File:** `app/src/main/res/layout/activity_main.xml`, lines 12-15
`AmbientOrbsView` is a decorative parallax background element. It is not interactive. Without `android:importantForAccessibility="no"`, TalkBack may focus on it and read the class name. Decorative views should always be explicitly excluded from the accessibility tree.

### [MED-A11] All bouncer permission rows convey status only via color (copper gold vs red chip_value)
**File:** `app/src/main/res/layout/activity_bouncer.xml`, lines 88-299
Each permission row has a `permOverlayCheck` (TextView "!") that changes color from chip_value (#6C2A2A light / #B55050 dark = danger) to something else (presumably green) when granted. This is a color-only status indicator. There is no shape change, icon change, or text content change visible in the layout. A colorblind user cannot distinguish granted from not-granted from the `!` mark alone.
The `bouncer_tap` label text also uses only color to distinguish status. String text changes (e.g., "TAP" vs "✓ DONE") would address this — verify if done in code.

### [MED-A12] Onboarding bouncer page selection elements have no accessibility semantics for state
**File:** `app/src/main/res/layout/activity_bouncer.xml`, `pageTier` (lines 332-431), `pageTone` (lines 433-559)
`optionFree`, `optionUpgrade`, `optionWork`, `optionPersonal`, `optionBoth` are LinearLayouts used as radio-button-like selectors. None has `android:checked`, `role=radio`, or any accessibility state. TalkBack cannot convey which option is selected.

### [MED-A13] profile_overlay.xml interactive "selector" TextViews have no role or checked state
**File:** `app/src/main/res/layout/profile_overlay.xml`
Across the profile overlay, selectable option TextViews (`profileWorkOnly`, `profileLifeOnly`, `profileBoth`, industry pickers, `l1NativeYes`, `l1NativeNo`, hour pickers, clarifier sensitivity pickers) are plain TextViews acting as toggle buttons. None has `android:role` (not available in XML; must be set via `ViewCompat.setAccessibilityDelegate`) or any state announced to TalkBack. Selected state is indicated only by background drawable change.

### [MED-A14] accordion item has no expanded/collapsed state announcement
**File:** `app/src/main/res/layout/manual_accordion_item.xml`, lines 9-17
`accordionHeader` is a LinearLayout that toggles `accordionContent` visibility. No `android:accessibilityLiveRegion` or state announcement is set. TalkBack cannot detect that content below has appeared or disappeared.

### [MED-A15] widget_layout.xml contentDescription is hardcoded English, not a string resource
**File:** `app/src/main/res/layout/widget_layout.xml`, line 13
`android:contentDescription="Tap to record"` — hardcoded. Should reference `@string/widget_cd` or similar so it participates in localization. The app has values-es, values-fr, values-pt, values-ru directories.

### [LOW-A16] onboarding.html WebView has no ARIA roles on interactive elements
**File:** `app/src/main/assets/onboarding.html`, lines 389, 758+
Buttons (`action-btn`, `skip-btn`) are HTML `<button>` elements — good. However, the `.layer-row` clickable divs (tile 3) and `.tone-card` divs (tile 4) have no `role="button"` or `role="radio"` ARIA attributes. Android's WebView accessibility relies on HTML semantics. Div-based clickables are invisible to TalkBack inside WebView unless explicitly annotated.

### [LOW-A17] pulse_rings.xml decorative rings not excluded from accessibility tree
**File:** `app/src/main/res/layout/pulse_rings.xml`, lines 8-30
Three decorative `View` elements used for animation effects. None has `android:importantForAccessibility="no"`. Should be excluded.

---

## 2. TOUCH TARGET SIZE VIOLATIONS

### [HIGH-T1] bezelRegen — 14x14dp (minimum is 48dp)
See A3 above.
**File:** `bubble.xml` line 113-114.

### [HIGH-T2] Color swatches in bubble_menu.xml — 26x26dp
See A6 above.
**File:** `bubble_menu.xml` lines 192-211.

### [HIGH-T3] Profile industry picker TextViews — 36dp height
**File:** `app/src/main/res/layout/profile_overlay.xml`, lines 218-243
All nine industry TextViews (`indFinance`, `indLegal`, `indMedical`, etc.) are 36dp height. Android minimum is 48dp. These are fully interactive tap targets.

### [HIGH-T4] Profile usage / L1 / hours / sensitivity selector TextViews — 36-40dp height
**File:** `app/src/main/res/layout/profile_overlay.xml`, lines 95-131, 293-296, 362-368, 620-654
Usage mode (`profileWorkOnly`, `profileLifeOnly`, `profileBoth`) at 40dp. L1 native Yes/No at 36dp. Hours at 36dp. Clarifier sensitivity at 36dp. All are tap targets below 48dp minimum.

### [HIGH-T5] Vocabulary/Corrections/Trigger ADD buttons — 36dp height
**File:** `app/src/main/res/layout/profile_overlay.xml`, lines 419-434, 511-526, 578-593
`vocabAddBtn`, `corrAddBtn`, `triggerAddBtn` — all `android:layout_height="36dp"`. These are the primary action buttons for the most-used profile editing functions.

### [HIGH-T6] Layer sub-option chips in bubble_menu.xml — 22dp height
**File:** `app/src/main/res/layout/bubble_menu.xml`, lines 119-144
`menuL1Local` and `menuL1Cloud` are 22dp height. Critically undersized for the source-selector function they serve.

### [HIGH-T7] Layer nameplate rows in bubble_menu.xml — 26dp height
**File:** `app/src/main/res/layout/bubble_menu.xml`, lines 94-171
`menuLayer1`, `menuLayer2`, `menuLayer4` layer nameplates are 26dp height. These are tappable to switch the active layer.

### [MED-T8] bouncer permission rows — 34dp height
**File:** `app/src/main/res/layout/activity_bouncer.xml`, lines 79-119 and throughout
`permOverlay`, `permMic`, `permBattery`, `permNotifications` all use `android:layout_height="34dp"`. Below 48dp. `permAccessibility` uses `wrap_content` which may be taller; verify at runtime.

### [MED-T9] profileClose (✕) — no explicit size, only paddingStart=16dp
**File:** `app/src/main/res/layout/profile_overlay.xml`, lines 39-47
The close button has `android:textSize="22sp"` and padding, but no explicit `minWidth`/`minHeight` to guarantee 48dp. On smaller text scale settings, the rendered height may fall below 48dp.

### [MED-T10] menuOpenSettings in bubble_menu.xml — only 4dp padding, no explicit size
**File:** `app/src/main/res/layout/bubble_menu.xml`, lines 317-327
`menuOpenSettings` (TextView "SETTINGS") has `android:padding="4dp"` only. At 11sp text that is approximately 14dp text height + 8dp padding = ~22dp total. Below 48dp minimum.

---

## 3. COLOR CONTRAST — WCAG AA

**Reference background:** `bg_base` light = #FFD4C8B0 (warm parchment). `brand_dark` = #FF1A1A1E.

### Contrast calculation methodology
Using relative luminance formula (WCAG 2.1). Values below use APCA-equivalent approximations for the exact hex pairs. WCAG AA requires 4.5:1 for normal text (<18pt / <14pt bold), 3:1 for large text (>=18pt or >=14pt bold).

### [HIGH-C1] `text_dim` (#8A7D68 light) on `bg_base` (#D4C8B0) — FAILS AA for body text
Light mode: foreground #8A7D68, background #D4C8B0.
Relative luminance of #8A7D68 ≈ 0.228. Relative luminance of #D4C8B0 ≈ 0.617.
Contrast ratio ≈ (0.617 + 0.05) / (0.228 + 0.05) ≈ **2.40:1**.
WCAG AA requires 4.5:1 for normal text. This fails for every body/subtitle text using `@color/text_dim` — which includes ALL profile selector TextViews, ALL layer subtitles, ALL bouncer subtitle copy, and ALL gesture-list values. This is extremely widespread. The `text_dim` token is used in at least 40+ text elements across 6 layout files.

### [HIGH-C2] `text_secondary` (#6B5D4A light) on `bg_base` (#D4C8B0) — borderline/fail
Foreground #6B5D4A ≈ luminance 0.120. Background #D4C8B0 ≈ 0.617.
Ratio ≈ (0.617 + 0.05) / (0.120 + 0.05) ≈ **3.93:1**.
Fails AA (4.5:1) for normal-weight text. Passes only for large text (3:1 threshold). Used for secondary labels and descriptions in multiple layouts.

### [HIGH-C3] `section_header` (#5C3A1F light) on `bg_base` (#D4C8B0) — passes for large, fails for small
Foreground #5C3A1F ≈ luminance 0.057. Background #D4C8B0 ≈ 0.617.
Ratio ≈ (0.617 + 0.05) / (0.057 + 0.05) ≈ **6.24:1**. Passes AA at all sizes.
However, `section_header` is also used on the `bouncer_title_full` TextView at 11sp (not large text), which passes at this contrast.

### [MED-C4] `copper_gold` (#8A5A2F light) on `bg_base` (#D4C8B0) — borderline for small text
Foreground #8A5A2F ≈ luminance 0.095. Background #D4C8B0 ≈ 0.617.
Ratio ≈ (0.617 + 0.05) / (0.095 + 0.05) ≈ **4.61:1**. Passes AA for normal text (barely — margin of 0.1). Any slight rendering variation or screen brightness drop could drop this below threshold. Used extensively as sub-labels and tile chevrons.

### [HIGH-C5] `bubbleLabel` text #FF555560 on bubble inner knob background
**File:** `app/src/main/res/layout/bubble.xml`, line 155
`bubbleLabel` uses hardcoded `android:textColor="#FF555560"` at 10sp. The bubble inner knob background is `@drawable/bubble_inner_knob` — a drawable whose actual color cannot be determined from XML alone without reading the drawable file, but the gray-violet #555560 on what is expected to be a warm dark metal surface is likely a low-contrast combination. At 10sp this is the smallest text in the UI and the most critical state indicator ("IDLE", "REC", etc.).
**Required:** Verify contrast of #555560 against the actual rendered bubble_inner_knob background. If it is a dark warm surface (~#2A2010 to #3A3020), contrast would be approximately 3.5:1 — failing AA for 10sp text.

### [HIGH-C6] Onboarding.html `.junk` text (`color: var(--maroon)` = #B55050) on `var(--console-bg)` = #3A1A1A
**File:** `app/src/main/assets/onboarding.html`, line 212
Foreground #B55050 on background #3A1A1A.
#B55050 luminance ≈ 0.116. #3A1A1A luminance ≈ 0.011.
Ratio ≈ (0.116 + 0.05) / (0.011 + 0.05) ≈ **2.72:1**. Fails AA at 11px (approximately 8sp). The strikethrough junk text in the demo is unreadable at this contrast, which defeats its purpose.

### [MED-C7] Dark mode `text_dim` (#A09888) on `bg_base` (#1A1A1E) — verify for small text
Dark: foreground #A09888 ≈ luminance 0.347. Background #1A1A1E ≈ luminance 0.013.
Ratio ≈ (0.347 + 0.05) / (0.013 + 0.05) ≈ **6.29:1**. Passes. Dark mode text_dim is actually fine.

### [MED-C8] `brand_subtitle` (#88FFFFFF = ~53% white) on `brand_dark` (#1A1A1E)
The "What I Meant" subtitle uses `brand_subtitle` (#88FFFFFF) at 10sp.
53% white over #1A1A1E: effective color ≈ #8E8E8E. Luminance ≈ 0.280. Background ≈ 0.013.
Ratio ≈ (0.280 + 0.05) / (0.013 + 0.05) ≈ **5.24:1**. Passes AA for small text. Fine.

---

## 4. BUBBLE OVERLAY — TALKBACK ACCESS

### [CRIT-B1] BubbleService window type TYPE_ACCESSIBILITY_OVERLAY / TYPE_APPLICATION_OVERLAY — TalkBack may not reach it
This is an architectural issue that cannot be fully assessed from XML alone, but it is the highest-stakes accessibility concern in the app. The bubble is displayed as a `WindowManager` overlay window. On Android 12+, overlay windows launched by non-system accessibility services can be unreachable by TalkBack's own accessibility traversal because TalkBack itself is an overlay. The bubble must set `WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL` and handle a11y through `AccessibilityNodeProvider` or by setting its window type to `TYPE_ACCESSIBILITY_OVERLAY` with the `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` flag to be reachable.
No evidence in XML that this is addressed. Requires code-level review of `BubbleService.kt`.

### [HIGH-B2] No TalkBack announcement when bubble state transitions (IDLE → REC → DONE)
The `bubbleLabel` text changes but there is no `android:accessibilityLiveRegion` set on the label or parent. State transitions emit no announcement to TalkBack. A blind user recording with the bubble has no audio feedback that recording has started or stopped (beyond the TTS announcement, if any, which is separate functionality).
**Required:** Set `android:accessibilityLiveRegion="polite"` on `bubbleLabel`, or add `announceForAccessibility()` calls in `BubbleService`.

---

## 5. DESIGN QUALITY — LAYOUT STRUCTURE

### [HIGH-D1] Deep LinearLayout nesting — activity_main.xml
**File:** `app/src/main/res/layout/activity_main.xml`
The layout uses nested LinearLayouts up to 6 levels deep in places (outer LinearLayout → ScrollView → LinearLayout → layer card LinearLayout → inner LinearLayout → TextView). For a screen with 4 layer rows, 4 VuMeter tiles, 2 dual control rows, and a scrollable body, ConstraintLayout would eliminate 3+ nesting levels and improve measure/layout pass performance. The activity_main.xml layout at 1100+ lines is a direct consequence of LinearLayout's verbosity.
Note: on Android, LinearLayout nesting beyond 4 levels incurs exponential measure cost. This is not a hard failure but a significant performance flag.

### [MED-D2] profile_overlay.xml industry row TextViews are inline single-line XML
**File:** `app/src/main/res/layout/profile_overlay.xml`, lines 218-243
Nine TextViews are written as single-line XML attributes with no indentation. This is a maintenance issue (zero readability) and makes accessibility attributes impossible to add quickly. It does not break functionality but is a code-quality concern.

### [LOW-D3] bubble_menu.xml uses hardcoded fixed width 220dp
**File:** `app/src/main/res/layout/bubble_menu.xml`, line 10
`android:layout_width="220dp"` is hardcoded. On tablets or large-form-factor foldables, this menu will appear disproportionately small. On very small devices (e.g., 4-inch phones), 220dp may not leave enough margin. Should be constrained as a percentage or max-width.

### [LOW-D4] activity_main.xml uses hardcoded paddingTop="48dp" instead of WindowInsets
**File:** `app/src/main/res/layout/activity_main.xml`, line 23
`android:paddingTop="48dp"` is hardcoded to approximate the status bar height. On devices with a tall status bar (dynamic island-style notches, or tablets with different inset heights), this will either clip content or leave a gap. Should use `android:fitsSystemWindows="true"` on the root or handle `WindowInsetsCompat` in code.

---

## 6. TEXT TRUNCATION RISKS

### [MED-TR1] tileLayerValue and tileToneValue have no ellipsize
**File:** `app/src/main/res/layout/activity_main.xml`, lines 244-250, 294-300
`tileLayerValue` and `tileToneValue` show dynamic runtime values (layer/tone names). At `android:textSize="13sp"` and `android:layout_width="wrap_content"` inside a fixed-height `68dp` FrameLayout tile with text wrapping, long values could overflow. Add `android:ellipsize="end"` and `android:maxLines="1"` and `android:layout_width="0dp"` with weight.

### [MED-TR2] bouncer_recon_before string (long sentence) on a TextView with no ellipsize
**File:** `app/src/main/res/layout/activity_bouncer.xml`, line 828-833
The "You say" text `bouncer_recon_before` is 83 characters of messy speech. On a 320dp screen with 14dp padding on each side (292dp usable), at 13sp this will wrap to 4+ lines. The layout uses `android:lineSpacingExtra="4dp"` but no `android:maxLines` cap. This is acceptable behavior but on a 375dp viewport may look crowded. No critical fix needed but flag for testing on small devices.

### [LOW-TR3] accordionTitle has no ellipsize and no maxLines
**File:** `app/src/main/res/layout/manual_accordion_item.xml`, lines 38-43
Dynamic title text could overflow the 52dp header height if very long. Add `android:maxLines="1"` and `android:ellipsize="end"`.

---

## 7. NIGHT-MODE DRAWABLE COVERAGE

### [HIGH-NM1] Majority of drawables have no night-mode override
**drawable/** contains 80 items. **drawable-night/** contains only 12 items:
- daf_seekbar_track.xml
- daf_seekbar_thumb.xml
- card_bg_selected.xml
- sessions_mesh_bg.xml
- brand_plate_bg.xml
- card_bg.xml
- card_bg_pressable.xml
- grille_pattern_bg.xml
- glass_panel_bg_pressable.xml
- glass_panel_bg_dark_pressable.xml
- bg_app.xml
- card_bg_input.xml

**Missing night-mode variants include (non-exhaustive):**
- `bubble_bg_idle.xml` — the main bubble bezel appearance in idle state (used in bubble.xml line 41 and bubble_color_picker.xml line 45)
- `bubble_inner_knob.xml` — inner knob appearance
- `bubble_bg_recording.xml` — recording state
- `bubble_bg_processing.xml` — processing state
- `bubble_bezel_aluminum.xml`, `bubble_knob_aluminum.xml`
- `bubble_bezel_gunmetal.xml`, `bubble_knob_gunmetal.xml`
- `nameplate_bg.xml` — used in bubble menu for all layer/source toggle chips
- `glass_panel_bg.xml` (the non-pressable variant)
- `chip_bg.xml`, `chip_bg_active.xml`
- `clarifier_half_yes.xml`, `clarifier_half_no.xml`
- `badge_bg.xml`, `badge_bg_red.xml`
- `icon_circle.xml` (used in accordion items)
- `guide_btn_bg.xml`
- `knob_tick.xml`
- `fader_thumb.xml`

Since `values-night/colors.xml` defines a completely different palette, any drawable that hardcodes a light-mode color without a night variant will render incorrectly in dark mode. The bubble (the primary UI surface) is particularly at risk if `bubble_bg_idle.xml` uses light-mode warm gold values.

---

## 8. BRAND COMPLIANCE

### [HIGH-BR1] `manual_accordion_item.xml` uses `@color/red_bright` for both subtitle and body text — not a brand-approved use of red
**File:** `app/src/main/res/layout/manual_accordion_item.xml`, lines 49-51 and 79-81
`accordionSubtitle` and `accordionBody` both use `@color/red_bright` (#DC2626). Per brand.local.md, `red_primary` (#B91C1C) is for "errors, destructive." In the Operations Guide (manual accordion), the subtitle and body text are informational content, not errors. This appears to be a placeholder/default value left from development. The actual guide content should use `text_primary` or `text_secondary`.

### [MED-BR2] `bubble_color_picker.xml` "PICK A BUBBLE" title is a hardcoded string, not a string resource
**File:** `app/src/main/res/layout/bubble_color_picker.xml`, line 18
`android:text="PICK A BUBBLE"` — hardcoded in XML. Should be `@string/bubble_pick_yours` (which exists in strings.xml at line 84).

### [MED-BR3] `bouncer_free_desc` and `bouncer_upgrade_desc` use the word "disfluencies" (correct) but `bouncer_upgrade_desc` also says "Full speech disfluency support" — good, but verify no "stutter" appears anywhere
**File:** `app/src/main/res/values/strings.xml`
Searched: no "stutter" appears in user-facing strings. This is a pass. The brand rule "never write 'stutter' in user-facing copy" is observed throughout strings.xml. The bubble_menu.xml uses `DISFLUENCY` as a layer label via `@string/bubble_menu_disfluency` — correct.

### [LOW-BR4] `activity_bouncer.xml` uses `android:fontFamily="sans-serif-light"` — not a brand font
**File:** `app/src/main/res/layout/activity_bouncer.xml`, lines 348, 448, 577, 803, 931, 1053
Six section title TextViews in the bouncer use `android:fontFamily="sans-serif-light"` (system Roboto Light). brand.local.md specifies Limelight for display headers and Outfit as body. Roboto Light is explicitly in the forbidden list ("Roboto / Inter / SF Pro as display font → Limelight for headers, full stop"). These are large 22sp titles — they should use `@font/limelight`.
Note: Limelight is decorative and may not have a "light" weight variant. If the intent is "light weight section title," Outfit at regular weight is the correct brand-compliant fallback.

### [LOW-BR5] `bouncerLogo` and `finalLogo` in activity_bouncer.xml do not explicitly specify `@font/limelight`
**File:** `app/src/main/res/layout/activity_bouncer.xml`, lines 35-38, 1033-1040
Both logo TextViews have no `android:fontFamily` attribute. Comments say "Limelight font set programmatically." This is documented intentional behavior, but it means if the programmatic setter in the Activity fails or is forgotten, the brand font degrades silently. Recommend adding `android:fontFamily="@font/limelight"` as the XML default.

### [LOW-BR6] `onboarding.html` tile-title uses Outfit, not Limelight, for section titles
**File:** `app/src/main/assets/onboarding.html`, lines 102-108
`.tile-title { font-family: 'Outfit', sans-serif; }` — section titles like "Your voice, any app" use Outfit at 22px. Limelight is only used for the WiM logo display. brand.local.md says "Limelight for headers." This is ambiguous depending on whether "tile-title" counts as a "header" — Outfit as body/title is listed as the body font. This is a judgment call; the intent may be that Limelight is only for the WiM brand word itself. Flag for design decision.

---

## 9. FOCUS ORDER / TALKBACK TRAVERSAL

### [MED-FO1] profile_overlay.xml has no explicit `android:traversalAfter`/`android:traversalBefore` ordering
**File:** `app/src/main/res/layout/profile_overlay.xml`
The profile overlay has a complex vertical structure with ScrollView. TalkBack follows DOM order top-to-bottom, which is correct here. However, the `profileClose` button is at the top-right in layout but should ideally be reachable last (or via a dedicated shortcut) so users don't accidentally close the overlay before reading content. Consider `android:accessibilityTraversalBefore` or announcing it last.

### [MED-FO2] bubble_menu.xml: left column and right column (fader sliders) are in a horizontal LinearLayout — TalkBack traversal will zigzag
**File:** `app/src/main/res/layout/bubble_menu.xml`, lines 41-306
The menu has a two-column layout (nameplates left, faders right). TalkBack traversal in a horizontal LinearLayout reads left-column element, then right-column element, then back to left-column, alternating. This zigzag is confusing for screen reader users. Consider `android:traversalAfter` attributes or restructuring for logical order.

---

## 10. SUMMARY TABLE

| ID | File | Line(s) | Severity | Issue |
|---|---|---|---|---|
| A1 | bubble.xml | 2-8 | CRIT | Bubble root has no contentDescription |
| A2 | bubble.xml | 77-107 | CRIT | Clarifier YES/NO inaccessible to TalkBack |
| A3 | bubble.xml | 112-122 | HIGH | bezelRegen 14x14dp — below 48dp minimum |
| A4 | profile_overlay.xml | 39-47 | HIGH | profileClose ✕ has no contentDescription |
| A5 | bubble_color_picker.xml | 42-160 | HIGH | All three picker FrameLayouts missing contentDescription |
| A6 | bubble_menu.xml | 192-211 | HIGH | Theme swatches 26x26dp — below 48dp minimum |
| A7 | bubble_menu.xml | 61-75 | HIGH | Live toggle missing labelFor linkage |
| A8 | activity_main.xml | 154-179 | HIGH | VuMeterView custom views no contentDescription in layout |
| A9 | bubble.xml | 50-55 | HIGH | BezelStageArcView no contentDescription |
| A10 | activity_main.xml | 12-15 | HIGH | AmbientOrbsView missing importantForAccessibility=no |
| A11 | activity_bouncer.xml | 88-299 | MED | Permission status conveyed by color only |
| A12 | activity_bouncer.xml | 332-559 | MED | Option selectors have no checked/role semantics |
| A13 | profile_overlay.xml | throughout | MED | All selector TextViews have no accessible state |
| A14 | manual_accordion_item.xml | 9-17 | MED | No expand/collapse announcement |
| A15 | widget_layout.xml | 13 | MED | Hardcoded English contentDescription |
| A16 | onboarding.html | throughout | LOW | Div-based clickables missing ARIA roles |
| A17 | pulse_rings.xml | 8-30 | LOW | Decorative rings not excluded from a11y tree |
| B1 | BubbleService (code) | — | CRIT | Overlay window a11y reach — architectural risk |
| B2 | bubble.xml | 150-161 | HIGH | No liveRegion on state label |
| C1 | colors.xml + layouts | — | HIGH | text_dim light mode fails WCAG AA (2.40:1) |
| C2 | colors.xml + layouts | — | HIGH | text_secondary light mode borderline (3.93:1) |
| C5 | bubble.xml | 155 | HIGH | bubbleLabel #555560 at 10sp — verify contrast |
| C6 | onboarding.html | 212 | HIGH | .junk text 2.72:1 on console bg |
| D1 | activity_main.xml | throughout | HIGH | LinearLayout nesting 6+ deep |
| NM1 | drawable-night/ | — | HIGH | 68 of 80 drawables have no night variant |
| T1-T10 | multiple | — | HIGH/MED | Touch targets below 48dp minimum (11 instances) |
| BR1 | manual_accordion_item.xml | 49,79 | HIGH | red_bright on informational text — wrong token |
| BR2 | bubble_color_picker.xml | 18 | MED | Hardcoded "PICK A BUBBLE" string |
| BR4 | activity_bouncer.xml | 348,448,577,803,931,1053 | LOW | sans-serif-light used for display headers |
| BR5 | activity_bouncer.xml | 35-38, 1033-1040 | LOW | Limelight set only programmatically, not in XML |

---

## 11. PRIORITIZED FIX ORDER

**Sprint 1 — Unblock TalkBack entirely:**
1. A1: Add contentDescription to bubbleRoot and wire a11y delegate
2. A5: Add contentDescription to all three color picker FrameLayouts (strings exist; 3-line fix)
3. A4: Add contentDescription to profileClose
4. B2: Add accessibilityLiveRegion to bubbleLabel
5. A10: Add importantForAccessibility="no" to AmbientOrbsView and decorative rings

**Sprint 2 — Touch target compliance:**
6. T1/A3: Fix bezelRegen touch target (transparent padding wrapper)
7. T4, T5: Fix profile selector heights to 48dp minimum
8. T6, T7: Fix bubble_menu nameplate chip heights
9. T3: Fix industry picker heights

**Sprint 3 — Contrast and color-only states:**
10. C1: Evaluate whether text_dim can be darkened in light mode (or whether the light mode palette is intentional enough to accept the contrast gap)
11. C6: Increase .junk text opacity or change color in onboarding.html
12. A11: Add text or icon change to permission rows for color-independent status

**Sprint 4 — Night mode drawables:**
13. NM1: Audit bubble drawables specifically (bubble_bg_idle, bubble_inner_knob, nameplate_bg) for dark-mode correctness; add night variants where colors hardcode light-palette values

**Sprint 5 — Brand and polish:**
14. BR1: Fix accordion text colors to text_primary
15. BR2: Replace hardcoded "PICK A BUBBLE" with @string reference
16. BR4: Replace sans-serif-light with @font/limelight in bouncer section titles
