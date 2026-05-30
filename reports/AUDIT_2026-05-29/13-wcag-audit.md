# WCAG 2.2 AA Audit — wim-android

**Date:** 2026-05-29
**Auditor:** Claude (accessibility-compliance:wcag-audit-patterns + screen-reader-testing)
**Scope:** wim-android live UI surfaces — Bouncer onboarding, MainActivity, Bubble + Clarifier overlays, Bubble menu, Profile overlay
**Standard:** WCAG 2.2 Level AA + Android touch-target guidance (48dp) + WCAG 2.5.5 (24×24 CSS px minimum)
**Context:** Accessibility is dual-axis here — the app SERVES users with speech disfluencies (the primary audience), AND it uses `AccessibilityService` as its primary text-injection mechanism. Both axes are evaluated.

---

## Summary by violation count

| Guideline | Count | Severity |
|---|---|---|
| 2.5.5 / 2.5.8 Target Size | 9 | **Critical** |
| 1.4.3 Contrast (Minimum) | 6 | **Critical** |
| 4.1.2 Name, Role, Value | 8 | **Serious** |
| 2.4.7 Focus Visible | 6 | **Serious** |
| 3.3.2 Labels or Instructions | 4 | **Serious** |
| 2.3.3 / 2.2.2 Animation / Motion | 4 | **Serious** |
| 1.3.1 Info & Relationships | 3 | Moderate |
| 2.1.1 Keyboard | 2 | Moderate |
| 1.4.11 Non-text Contrast | 2 | Moderate |
| 4.1.3 Status Messages | 1 | Moderate |

**Net verdict: FAIL — multiple critical AA blockers. WiM cannot ship to Play Store accessibility-compliant in current state.**

---

## CRITICAL findings (block Play Store accessibility flag)

### V1 — WCAG 2.5.5 Target Size (Level AAA, but Android baseline = 48dp Material)
**Severity: Critical.** Multiple primary tap targets fall under the 48dp Material minimum AND the 24×24 CSS px AA minimum is technically met for some — but the bigger problem is the spacing offset clause. Most of these have NO 24px clearance to adjacent targets.

| File | Line | Element | Height | Issue |
|---|---|---|---|---|
| `res/layout/activity_bouncer.xml` | 261 | `@id/permOverlay` row | **34dp** | Critical permission CTA. Adjacent rows have only 2dp gap (line 267). |
| `res/layout/activity_bouncer.xml` | 360 | `@id/permMic` row | **34dp** | Same row pattern. |
| `res/layout/activity_bouncer.xml` | 407 | `@id/permBattery` row | **34dp** | Same. |
| `res/layout/activity_bouncer.xml` | 454 | `@id/permNotifications` row | **34dp** | Same. |
| `res/layout/activity_bouncer.xml` | 1007-1175 | 7× gesture rows | **34dp** | Display-only but `focusable` defaults can vary. |
| `res/layout/profile_overlay.xml` | 107, 123, 139 | Usage mode chips | **40dp** | Under 48dp; touch-listener attaches at chip level (chip `clickable=true`). |
| `res/layout/profile_overlay.xml` | 237-262 | 9× industry chips | **36dp** | All under both 40dp and 48dp. |
| `res/layout/profile_overlay.xml` | 315-316, 343-360 | L1 language chips | **36dp** | Same pattern. |
| `res/layout/profile_overlay.xml` | 391-394, 654, 669, 684 | Hours + sensitivity chips | **36dp** | Same. |
| `res/layout/profile_overlay.xml` | 435, 446, 502, 543, 596, 608 | Vocab/correction/trigger ADD inputs + buttons | **36dp** | EditText + ADD button row both 36dp. |
| `res/layout/profile_overlay.xml` | 739 | `@id/clarBlacklistAddBtn` | **36dp** | Single-line CTA. |
| `res/layout/activity_main.xml` | 324, 359 | `@id/tilePermissions` / `@id/tileSettings` | **52dp** | OK. (kept for reference) |
| `res/layout/bubble_menu.xml` | 99, 152, 165 | Layer nameplates (Transcribe / Reconstruct / Disfluency) | **26dp** | Far under minimum. These are the bubble's primary controls. |
| `res/layout/bubble_menu.xml` | 124, 138 | L1 Local/Cloud chips | **22dp** | Smallest violation; primary toggle. |
| `res/layout/bubble_menu.xml` | 251, 293 | Opacity + Size faders | **26dp** wide × 170dp tall | Width is the touch dimension perpendicular to thumb travel — far below 48dp. |
| `res/layout/bubble_menu.xml` | 334 | `@id/menuOpenSettings` | text + 4dp pad | Effective ~24dp; under minimum. |

**Note on `bubble.xml`:** the `bezelRegen` icon at line 122 IS correctly wrapped in a 48×48dp FrameLayout — that's the right pattern. The bubble_menu.xml nameplates need the same treatment.

**Recommendation:** Wrap all nameplates / chips in 48dp `FrameLayout` containers with `minHeight="48dp"` while keeping the visual height at 26-36dp. The bubble menu's right-column faders need `padding` to expand their hit region without changing visual.

---

### V2 — WCAG 1.4.3 Contrast (Minimum) — 4.5:1 for text < 18pt, 3:1 for larger
**Severity: Critical.** Eyeballed contrast ratios using the documented hex values in `res/values/colors.xml` and `res/values-night/colors.xml`. Several text colors over the warm-paper background fall under threshold.

| Theme | Foreground | Background | Ratio | Context | Required |
|---|---|---|---|---|---|
| **Light (bronze paper)** | `#FF6B5D4A` (text_secondary) | `#FFD4C8B0` (bg_base) | ≈ **3.4:1** | "Sub-label" sub-text in bouncer (line 224 etc.) | 4.5:1 — **FAIL** |
| **Light** | `#FF5A5040` (text_dim) | `#FFD4C8B0` (bg_base) | ≈ **4.2:1** | Body text in bouncer (line 241, all `card_bg` rows) | 4.5:1 — **FAIL** |
| **Light** | `#FF8A5A2F` (copper_gold) at `textSize=9sp`–`11sp` | `#FFEDE4D0` (`card_bg`) | ≈ **3.9:1** | Subtitle text on permission cards (lines 291, 343) | 4.5:1 — **FAIL** for <18pt |
| **Light** | `#FF8A5A2F` (copper_gold) at `textSize=13sp` headers | `#FFD4C8B0` | ≈ 3.4:1 | Profile section headings (e.g., line 80) — bold but < 18pt | 4.5:1 — **FAIL** |
| **Dark mode** | `#FFC49A6C` (copper_gold) at 9-11sp | `#FF1A1A1E` (bg_base) | ≈ **6.8:1** | OK — passes |
| **Dark mode** | `#FF908880` (text_secondary) | `#FF1A1A1E` | ≈ **4.7:1** | Borderline pass for body |
| **Dark mode** | `#88FFFFFF` (brand_subtitle, "What I Meant" tagline) | `#FF1A1A1E` (brand plate) | effective alpha ≈ **5.1:1** | Pass with margin |
| **Bubble label** | `#FF1F1208` IDLE on bronze knob (`bubble_color_picker.xml:71`) | bronze fill | ≈ 4.3:1 | Body — **FAIL** |
| **Bubble label** | `#FF555560` IDLE on aluminum knob (`bubble_color_picker.xml:127`) | silver fill | ≈ 2.8:1 | Bubble color **FAIL** (Aluminum theme — explicitly called out in the scope) |
| **Profile overlay** | `#FFAADDFF` "W" + `#FFFFCC88` "L" (lines 167, 185) on `#FF1A1A1E` | dark bg | OK | — |
| **Profile balance value** | `text_secondary` 10sp on dark | OK in dark, FAIL in light | — | — |
| **Auto-send checkbox glyph ☐** | `text_dim` 22sp on `card_bg_pressable` | light bg | borderline | Large enough — passes 3:1 |

**Recommendation:** Darken `text_dim` to ≥ `#FF45382C` and `text_secondary` to ≥ `#FF504432` for light mode. Aluminum bubble's IDLE label needs `#FF333340` minimum.

---

### V3 — WCAG 4.1.2 Name, Role, Value
**Severity: Serious.** TalkBack can't announce these correctly.

| File | Line | Issue |
|---|---|---|
| `res/layout/bubble.xml` | 84-113 | `bezelYes` / `bezelNo` clarifier glyphs (✓ / ✕) — `clickable=false`, `focusable=false`, no contentDescription. The CLARIFIER tap target is the WHOLE bubble — but TalkBack users have no way to know which half they're tapping. **The clarifier is the patent-claim differentiator and it's invisible to TalkBack.** |
| `res/layout/bubble.xml` | 165-177 | `bubbleLabel` has `accessibilityLiveRegion="polite"` ✓ — but no `contentDescription` for the LIVE STATE ("LISTENING", "RECONSTRUCTING") to TalkBack. The text is announced (live region) but the role context "WiM bubble status:" is missing. |
| `res/layout/activity_main.xml` | 62 | `@id/wimLogoSummon` — the brand plate's gold WiM letters ARE the summon button. `contentDescription` references `app_short_name` ("WiM") — TalkBack reads "WiM, button" but doesn't announce that this BUTTON SPAWNS THE BUBBLE. |
| `res/layout/activity_main.xml` | 1467-1490 | `colorBronze`/`colorAluminum`/`colorGunmetal` 28dp swatches — NO `contentDescription`. TalkBack announces "button, unlabeled". |
| `res/layout/profile_overlay.xml` | 96-148 | Usage mode chips (`@id/profileWorkOnly` etc.) declare `android:checkable="true"` but no `android:stateListAnimator` and no announce-on-toggle. TalkBack says "Work, checkbox" without checked state. |
| `res/layout/profile_overlay.xml` | 222-262 | Industry chips — same `checkable=true` pattern, 9 unlabeled-as-checkable targets. |
| `res/layout/activity_main.xml` | 2440 | `@id/profileStrip` "your_profile" — clickable LinearLayout, no `contentDescription`. The "▶" glyph at line 2449 conveys CTA-ness visually only. |
| `res/layout/activity_bouncer.xml` | 814-817, 836-840, 869-882 | Onboarding chip rows — 24 `TextView`s with `clickable=true` and no `checkable=true` or `contentDescription`. TalkBack can't tell selected from unselected. |

**Recommendation:** For the bubble clarifier, split the touch surface into two virtual sub-views via `AccessibilityNodeInfo` virtual children OR add a `contentDescription` that flips to "Approve {word}" / "Reject {word}" when the clarifier shows. For checkable chips, add `setRoleDescription` via `ViewCompat.setAccessibilityDelegate`.

---

### V4 — WCAG 2.4.7 Focus Visible
**Severity: Serious.** The card / nameplate selectors only define `state_pressed` and `state_selected`, not `state_focused`. A keyboard/D-pad/TalkBack focus traversal of MainActivity or Profile overlay has NO visible focus indicator.

| File | Line | Issue |
|---|---|---|
| `res/drawable/card_bg_pressable.xml` | full file | Only `state_pressed` defined. Missing `state_focused` and `state_focused="true" state_pressed="false"` items. |
| `res/drawable/card_bg.xml` | full file | Static shape — used as background on focusable elements (e.g., bouncer permission rows line 266, all profile chips). No focus state. |
| `res/drawable/nameplate_bg.xml` | 6-49 | Only `state_selected` + default. Bubble menu LAYER chips (the bubble's primary control surface) have no focus indicator. |
| `res/layout/activity_main.xml` | all `clickable=true` rows | Layer 1/2/3/4 cards (lines 429-580), Tone rows, Permissions tiles — none specify a `foreground` or focused-state background. |
| `res/layout/profile_overlay.xml` | 117, 132, 147 + 28 others | All chips inherit static `card_bg`. No focus ring. |
| `res/layout/bubble_menu.xml` | 96, 149, 162 | LAYER chips with `nameplate_bg` — no focus state. |

**Recommendation:** Add `state_focused` items to all three selectors. Pattern: bright copper-gold 2dp inner stroke + 1dp brightening shadow for focus, distinct from `state_selected` (which is the burnt-umber dark fill).

---

### V5 — WCAG 3.3.2 Labels or Instructions
**Severity: Serious.** Form inputs without explicit programmatic labels.

| File | Line | Element | Issue |
|---|---|---|---|
| `res/layout/profile_overlay.xml` | 266 | `@id/indOtherInput` EditText | Only `hint` ("Type your industry") — no `labelFor` from a TextView, no `android:hint` set as `android:accessibilityLabel`. TalkBack reads only the hint, which disappears once typing. |
| `res/layout/profile_overlay.xml` | 431 | `@id/vocabAddInput` | Same — hint-only labeling. The section heading "VOCABULARY" (line 401) lives in a sibling block, not associated via `labelFor`. |
| `res/layout/profile_overlay.xml` | 500, 524 | `@id/corrAddFrom` / `@id/corrAddTo` | Two EditTexts separated by a "→" arrow. The semantic relationship (substitute X with Y) is visual only — TalkBack reads them as two unrelated text fields. |
| `res/layout/profile_overlay.xml` | 592 | `@id/triggerAddInput` | Same hint-only pattern. |
| `res/layout/activity_bouncer.xml` | 853-855 | `@id/qWord1` / `qWord2` / `qWord3` | "3 names or words voice apps mangle for you" — heading is a separate TextView with no `labelFor`. Each input identical hint. TalkBack reads three identical "edit, names" without ordinal context. |
| `res/layout/activity_bouncer.xml` | 912 | `@id/qLangOther` | Hint-labeled only. |
| `res/layout/bubble_menu.xml` | 61-77 | `@id/menuLiveLabel` correctly uses `labelFor="@id/menuLiveToggle"` ✓ — this is the right pattern to apply everywhere else. |

---

### V6 — WCAG 2.3.3 (Animation from Interactions) + 2.2.2 (Pause, Stop, Hide)
**Severity: Serious.** WiM has multiple infinite animations with no respect for system reduced-motion preference.

| File | Line | Animation | Issue |
|---|---|---|---|
| `BubblePulseRings.kt` | 17-67 | 3× concentric pulse rings — INFINITE loops, 1200ms cycle each | No check for `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or `AccessibilityManager.areAnimatorsEnabled()`. WCAG 2.3.3 + Android best practice both require respecting reduced motion. |
| `BubbleAffect.kt` | 106-124 | `applyResting()` — INFINITE breathe loop on bezel scale (4200ms reverse cycle) | Same — always-on idle animation. Triggers on every state transition to `Idle`. |
| `ShimmerFx.kt` | 75-83 | Text shimmer sweep — `ValueAnimator.INFINITE`, 3000ms linear interpolator | No reduce-motion gate. Multiple call sites attach shimmer to section headers. |
| `BubbleAffect.kt` | 196-202 | `THINKING_SWAY` arm + repeat sequence on Reconstructing > 1.5s | No reduce-motion gate. |
| `AmbientOrbsView` (`activity_main.xml:12`) | layout ref | Parallax orbs that reposition on accelerometer | This is `importantForAccessibility="no"` ✓ but the motion itself can trigger vestibular issues. |
| `BezelStageArcView.kt` | 88-92, 113-145 | `postInvalidateOnAnimation()` loop during stage fade + clarifier entry flash | Cosmetic stage transitions still draw if reduce-motion is set. |

**Recommendation:** Add `WimApp.respectReducedMotion()` helper that wraps `AccessibilityManager.areAnimatorsEnabled()` (API 33+) and `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (older). Gate every infinite ValueAnimator behind it. Static target-state pose when reduced.

---

### V7 — WCAG 1.3.1 Info and Relationships
**Severity: Moderate.**

| File | Line | Issue |
|---|---|---|
| `res/layout/profile_overlay.xml` | 87, 214, 299, 375, 409, 477, 570, 638, 714 | `accessibilityHeading="true"` on section headers ✓ — good pattern. |
| `res/layout/activity_main.xml` | 393-410 | LAYER section header — missing `accessibilityHeading="true"`. Compare profile pattern. |
| `res/layout/activity_bouncer.xml` | all 7 page headers | No `accessibilityHeading` on page-level titles ("LET'S SET IT UP", "Choose your setup", etc.). TalkBack heading nav doesn't work in the bouncer. |
| `res/layout/activity_main.xml` | 1380-1402, 1424-1446 | SeekBar rows for Opacity/Size — label TextView NOT bound via `labelFor` to the SeekBar. The pair reads as "Opacity, label" then "SeekBar, 70". |

---

### V8 — WCAG 2.1.1 Keyboard / 2.1.2 No Keyboard Trap
**Severity: Moderate.**

| File | Line | Issue |
|---|---|---|
| `BubbleService.kt` | 724-1061 | Bubble touch handler implements TAP / DOUBLE_TAP / HOLD / DRAG / TWIST gestures via raw `setOnTouchListener` — NO equivalent keyboard/D-pad/TalkBack-action surface. A TalkBack user can swipe to the bubble (`importantForAccessibility="yes"`) but has no way to invoke "double-tap" (they get long-press on the bubble = system context menu, not double-tap-to-open-menu). |
| `BubbleClarifierUI.kt` | 67-189 | The clarifier tap target (left half = approve, right half = reject) is invisible to TalkBack — there's no `AccessibilityAction.ACTION_CLICK` with custom labels exposed for the two outcomes. |
| `activity_bouncer.xml` | 904 | `@id/qLangSpinner` is hidden behind `visibility=gone` until "No, my first is..." chip is tapped. The chip swap to spinner doesn't move focus — keyboard user has to hunt. |

**Recommendation:** For the bubble, register custom `AccessibilityAction`s: ACTION_CLICK = "Record", ACTION_LONG_CLICK = "Open menu", + custom actions "Approve clarifier" / "Reject clarifier" while in `BubbleState.Clarifying`.

---

### V9 — WCAG 1.4.11 Non-text Contrast
**Severity: Moderate.**

| File | Line | Issue |
|---|---|---|
| `res/drawable/nameplate_bg.xml` | 38 | Default nameplate stroke = `#33C8B48A` (20% alpha cream on dark) — < 3:1 against the dark gradient body. The chip is barely visible at rest. |
| `res/layout/activity_main.xml` | 35 | `border_subtle` = `#25000000` (15% alpha black). Used as card outline in light mode — under 3:1. |
| `res/layout/bubble.xml` | 84-113 | Clarifier ✓/✕ glyphs `textColor="#FFFFFFFF"` on green/red split bezel. White on `clarifier_half_yes` and `clarifier_half_no` — need to verify those drawables hit 3:1. |
| `BezelStageArcView.kt` | 41 | `DIM_ALPHA = 0x66` (40%) for inactive arc segments. Against dim bezel, ratio < 3:1 — they're meant to be subtle but WCAG draws the line at 3:1 for UI components. |

---

### V10 — WCAG 4.1.3 Status Messages
**Severity: Moderate.**

| File | Line | Issue |
|---|---|---|
| `BubbleService.kt` | 252-254 | "Tap a text field — your text is on the clipboard" — fired via `Toast.makeText` with `LENGTH_LONG`. Toasts in API 30+ default to `TYPE_INFO` and are NOT announced to TalkBack reliably across OEM forks. Should use `View.announceForAccessibility()` from the bubble's live region instead. |

---

## TalkBack walkthrough findings (simulated)

### Bouncer onboarding (`BouncerActivity`)
- ✓ Permission rows have `contentDescription` matching the visible text — TalkBack announces correctly
- ✗ "Tap" affordance hint (e.g., `@id/permOverlayTap`) is a separate TextView with no announcement role — TalkBack reads "Overlay, draw over apps, Tap" as three siblings, not "Overlay, button"
- ✗ The "!" marker (`@id/permOverlayCheck`, line 273) IS the permission state but reads as literal "exclamation". Should change `contentDescription` based on granted/missing state
- ✗ Page indicator (line 506) reads as "Slide 1 of 6" but page TRANSITIONS don't trigger `announceForAccessibility` — TalkBack user doesn't know page changed
- ✗ Pages have no `accessibilityHeading` on titles — heading-nav broken

### MainActivity
- ✓ Brand plate WiM button is clickable + focusable
- ✗ The VuMeter (`activity_main.xml:155-180`) is decorative chart — `importantForAccessibility` NOT set to `no`. TalkBack visits 4 unlabeled custom views.
- ✗ The "wimLogoSummon" gold WiM letters are the bubble summon — `contentDescription` is just "WiM". Should be "Summon bubble"
- ✗ Layer cards (Transcribe / Reconstruct / Profile / Disfluency) — TalkBack reads "I, Transcribe, On-device" as three children. Should consolidate via custom view delegate.

### Bubble overlay (the primary product surface)
- ✓ Root has `contentDescription="WiM bubble — tap to record, double-tap for menu"` ✓
- ✓ `bubbleLabel` has `accessibilityLiveRegion="polite"` ✓
- ✗ Live region only fires when text content changes — the recording-state COLOR changes don't trigger announcement
- ✗ NO TalkBack-equivalent for the clarifier approve/reject gesture (left half / right half tap) — **this is the patent-claim differentiator and it's blocking for the actual target user (stuttering TalkBack users do exist)**
- ✗ `bezelRegen` icon (line 122) is properly 48dp ✓ with `contentDescription` ✓ — good pattern, follow it elsewhere
- ✗ No haptic feedback for screen-reader users when state changes; sighted users get visual cues, blind users get nothing

### Profile overlay
- ✓ `accessibilityHeading="true"` on section heads — heading nav works ✓
- ✓ `accessibilityLiveRegion="polite"` on chip groups — selection changes announced ✓
- ✗ Chip selection state not exposed despite `checkable=true` — needs `AccessibilityNodeInfoCompat.setChecked` in a delegate
- ✗ ADD-row pattern (vocab/correction/trigger) — three identical visual rows in a vertical list; TalkBack user has no way to distinguish them without reading the section heading first

---

## Accessibility-as-mechanism (WiM's dual role)

WiM's `WimAccessibilityService` (`AndroidManifest.xml:138-147`) declares:
- `accessibilityEventTypes="typeWindowStateChanged|typeViewFocused|typeViewTextChanged"`
- `canRetrieveWindowContent="true"`
- `canPerformGestures="true"`

This is correctly minimal. Two concerns:

1. **Password field detection works** (`WimAccessibilityService.kt:149-155`, `:118-121`) — explicit `node.isPassword` check, dictation disabled. **But** there's no equivalent for "secure field via app declaration" (some apps set `IME_FLAG_NO_PERSONALIZED_LEARNING` without password type). Recommend extending to check `node.inputType` flags AND host-app blacklist.

2. **The `accessibility_description` string** (`strings.xml:7`) reads:
   > "WiM detects text fields to show the voice dictation bubble and pastes transcribed text into the active field."

   This is honest and Play Store compliant. ✓

3. **Hint vs text disambiguation** (`WimAccessibilityService.kt:212-215, 262-268`) — already handles Samsung Messages hint-as-text bug. Defensive ✓.

---

## What's done WELL (preserve)

- `bubble.xml:9-10` — root contentDescription + importantForAccessibility ✓
- `bubble.xml:177` — accessibilityLiveRegion="polite" on bubbleLabel ✓
- `bubble.xml:120-137` — bezelRegen wrapped at 48dp with 14dp visual icon — exact correct pattern, use everywhere ✓
- `bubble_menu.xml:71` — labelFor pattern on Live toggle ✓
- `bubble_menu.xml:197-219` — color swatches have `minWidth/minHeight=48dp` ✓
- `profile_overlay.xml:48-49` — close button minWidth/minHeight 48dp ✓
- `profile_overlay.xml:87, 214, 299, 375, 409, 477, 570, 638, 714` — accessibilityHeading="true" ✓
- `profile_overlay.xml:101, 228, 313, 389, 652` — accessibilityLiveRegion="polite" on chip groups ✓
- Multi-language strings.xml (en/es/fr/pt/ru) ✓
- Dark mode contrast generally passes ✓
- Password field detection in accessibility service ✓

---

## Recommended remediation order (P0 → P2)

### P0 — Ship-blockers
1. Touch targets in bubble menu (V1) — wrap all nameplates in 48dp containers
2. Light-mode text contrast (V2) — darken text_dim and text_secondary to ≥ 4.5:1
3. Clarifier TalkBack support (V3 + V8) — virtual sub-views or custom actions
4. Focus-visible states (V4) — add `state_focused` to all three card/nameplate selectors

### P1 — Pre-launch
5. Reduce-motion gating for breathe + pulse + shimmer (V6)
6. Form labels via `labelFor` (V5)
7. Aluminum bubble label contrast (V2 row 9)

### P2 — Polish
8. Heading marks on bouncer + MainActivity LAYER (V7)
9. Status-message announcement for clipboard-fallback Toast (V10)
10. VuMeterView marked `importantForAccessibility="no"` (TalkBack noise reduction)
