# WCAG 2.2 Accessibility Audit — WiM Android
**Date:** 2026-05-10  
**Auditor:** Visual Validation Expert (Claude)  
**Scope:** 8 target files — bubble.xml, activity_main.xml, activity_bouncer.xml, bubble_menu.xml, profile_overlay.xml, onboarding.html, colors.xml (light + dark)

---

## CONTRAST RATIO REFERENCE TABLE

All ratios computed via WCAG luminance formula. WCAG 2.2 AA thresholds:
- Normal text (< 18sp regular / < 14sp bold): 4.5:1
- Large text (>= 18sp regular / >= 14sp bold): 3:1
- UI components and graphical objects: 3:1

### Dark mode (bg_base #1A1A1E)

| Color | Hex | Ratio | Pass/Fail |
|---|---|---|---|
| section_header (cream) | #C8B48A | 8.55:1 | PASS |
| copper_gold | #C49A6C | 6.76:1 | PASS |
| orange_label | #EE5A24 | 5.06:1 | PASS |
| text_primary | #D4D0C8 | 11.28:1 | PASS |
| text_bright | #F0ECE4 | 14.72:1 | PASS |
| text_dim | #A09888 | 6.07:1 | PASS |
| text_secondary | #8A8078 | **4.49:1** | **BORDERLINE FAIL** (needs 4.5:1) |
| chip_value | #B55050 | **3.49:1** | **FAIL** normal text |
| green_active | #16A34A | 5.26:1 | PASS |

### Dark mode surface (#222226)

| Color | Hex | Ratio | Pass/Fail |
|---|---|---|---|
| text_dim | #A09888 | 5.54:1 | PASS |
| text_secondary | #8A8078 | **4.11:1** | **FAIL** |
| copper_gold | #C49A6C | 6.18:1 | PASS |

### Light mode (bg_base #D4C8B0)

| Color | Hex | Ratio | Pass/Fail |
|---|---|---|---|
| section_header (brown) | #5C3A1F | 6.10:1 | PASS |
| copper_gold | #8A5A2F | **3.54:1** | **FAIL** normal text |
| orange_label | #C94A1F | **2.83:1** | **FAIL** |
| text_dim | #8A7D68 | **2.43:1** | **FAIL** |
| text_secondary | #6B5D4A | **3.86:1** | **FAIL** |
| chip_value | #6C2A2A | 6.34:1 | PASS |
| green_active | #16A34A | **1.99:1** | **FAIL** |

### Light mode card bg (#F5EEE0)

| Color | Hex | Ratio | Pass/Fail |
|---|---|---|---|
| text_dim | #8A7D68 | **3.49:1** | **FAIL** |
| text_secondary | #6B5D4A | 5.53:1 | PASS |
| text_primary | #2A2420 | 13.26:1 | PASS |
| copper_gold | #8A5A2F | 5.07:1 | PASS |
| orange_label | #C94A1F | **4.06:1** | **FAIL** normal text |
| green_active | #16A34A | **2.85:1** | **FAIL** |

### Bubble knob interior (approximated #2A2A2F)

| Color | Hex | Ratio | Pass/Fail |
|---|---|---|---|
| bubbleLabel (hardcoded) | #555560 | **1.94:1** | **FAIL** |
| bubbleTime (hardcoded) | #9A4040 | **2.16:1** | **FAIL** |

### Onboarding HTML — UI components

| Pair | Ratio | Pass/Fail |
|---|---|---|
| text_muted #8A8078 on bg #1A1A1E | **4.49:1** | **FAIL** (0.01 below threshold) |
| maroon perm TAP text #B55050 on raised #2A2A2F | **2.87:1** | **FAIL** |
| green check #16A34A on raised #2A2A2F | **4.33:1** | **FAIL** |
| inactive dot #333338 on bg #1A1A1E (UI component) | **1.38:1** | **FAIL** (3:1 needed) |
| active dot #B55050 on bg #333338 | **2.53:1** | **FAIL** (UI component) |
| demo-clean-text #4ade80 on console #3A1A1A | 8.98:1 | PASS |

---

## FINDINGS BY WCAG CRITERION

---

### 1.1.1 Non-text Content

#### FAILURE — bubble_menu.xml: Layer nameplates lack accessible names
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 95–107, 148–159, 161–172  
`menuLayer1`, `menuLayer2`, `menuLayer4` are `TextView` elements that function as toggle/select buttons (clickable via code). No `contentDescription` is present. The Roman numeral labels (TRANSCRIPTION, RECONSTRUCTION, DISFLUENCY) are the only identity, and there is no role conveyed. If the code attaches click listeners to these, they expose no accessible name beyond their text content — the text itself is fine, but selected/unselected state is conveyed purely via visual nameplate styling (`nameplate_bg` state_selected). State is not exposed programmatically.  
**Severity:** Failure (4.1.2 also implicated)

#### FAILURE — bubble_menu.xml: `menuL1Local` / `menuL1Cloud` toggle buttons
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 119–145  
These two `TextView` elements act as exclusive option buttons but carry no `contentDescription`, no `android:role`, and visual selection state only via drawable. A TalkBack user hears "LOCAL" / "CLOUD" text read but receives no state (selected/unselected).  
**Severity:** Failure (4.1.2 also implicated)

#### FAILURE — bubble.xml: `BezelStageArcView` has no accessibility attributes
**File:** `app/src/main/res/layout/bubble.xml`  
**Lines:** 55–60  
Custom view `BezelStageArcView` renders the pipeline-stage arc segments (Transcribing/Reconstructing/Verifying/Injecting). No `contentDescription` or `importantForAccessibility="no"` is set. If it conveys state, that state is invisible to screen readers.  
**Severity:** Failure — either add `contentDescription` updated by code, or mark `importantForAccessibility="no"` if purely decorative

#### ADVISORY — bubble.xml: `bubbleTick` View has no accessibility attribute
**File:** `app/src/main/res/layout/bubble.xml`  
**Lines:** 147–153  
The knob tick mark is decorative. It is not marked `importantForAccessibility="no"`. TalkBack may announce it as an unlabeled view.  
**Severity:** Advisory — add `importantForAccessibility="no"`

#### FAILURE — activity_main.xml: `VuMeterView` widgets have no contentDescription
**File:** `app/src/main/res/layout/activity_main.xml`  
**Lines:** 155–181  
Four `VuMeterView` custom views (`vuSessions`, `vuWpm`, `vuCleanup`, `vuScore`) display live statistics. None have `contentDescription` or `importantForAccessibility="no"`. A screen reader user cannot determine what these meters show.  
**Severity:** Failure

#### FAILURE — activity_main.xml: Layer numeral `TextView` elements are purely visual labels
**File:** `app/src/main/res/layout/activity_main.xml`  
**Lines:** 439–447, 539–547, 593–600 (and Layer IV equivalent)  
Roman numeral "I", "II", "III", "IV" as standalone `TextView` elements are read literally by TalkBack as Roman letters, not layer numbers. No `contentDescription` disambiguates "I" from the letter I.  
**Severity:** Advisory

#### ADVISORY — activity_main.xml: Screw decorative `View` elements not marked
**File:** `app/src/main/res/layout/activity_main.xml`  
**Lines:** 103–137  
Four screw decorations are plain `View` elements with no `importantForAccessibility="no"`. They are purely decorative.  
**Severity:** Advisory

#### FAILURE — onboarding.html: Tile 6 permission icons are emoji, not accessible
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 700–707  
JS renders permission rows with `perm-icon` containing either `✓` or `!` as raw emoji/glyphs with no `aria-label` or `aria-hidden`. The status div shows "TAP" or `✓` similarly. Screen readers will announce these inconsistently.  
**Severity:** Failure — add `aria-hidden="true"` to decorative glyphs; use `aria-label` on the row or status element

#### ADVISORY — onboarding.html: `wim-bubble` div on Tile 2 has no `aria-label`
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 401  
The floating bubble demo element `<div class="wim-bubble">WM</div>` has no `aria-label`. "WM" is read literally.  
**Severity:** Advisory — add `aria-label="WiM bubble"` or `aria-hidden="true"` if decorative

---

### 1.3.1 Info and Relationships

#### FAILURE — activity_bouncer.xml: Permission status conveyed only visually via "!" / checkmark text color
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 88–95, 133–138, 182–187, 226–231, 268–273  
`permOverlayCheck`, `permAccessibilityCheck`, `permMicCheck`, `permBatteryCheck`, `permNotificationsCheck` display "!" in red/orange or a checkmark. The granted/not-granted state change is conveyed by text color change only — no `contentDescription` update, no `accessibilityLiveRegion`, no state attribute. Code may update the text to "✓" but the relationship between the check column and the row label is not programmatically expressed via `android:labelFor` or grouping.  
**Severity:** Failure (1.4.1 also implicated)

#### FAILURE — activity_bouncer.xml: `autoSendAck` checkbox is a `LinearLayout` pretending to be a checkbox
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 955–985  
A `LinearLayout` with `clickable="true"` and `focusable="true"` contains a `TextView` displaying `☐` (checkbox glyph). This is not a real `CheckBox` — it has no `android:checked` attribute, no role, and state (checked/unchecked) is conveyed only by swapping glyph text. TalkBack announces it as a generic clickable element with no checked state.  
**Severity:** Failure — replace with `CheckBox` widget or add `android:accessibilityRole="checkbox"` and code-managed `ViewCompat.setStateDescription()`

#### FAILURE — bubble_menu.xml: Vertical sliders lack programmatic label association
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 237–268, 279–311  
`menuOpacitySeek` (VerticalSeekBar) and `menuSizeSeek` are not linked to their labels `menuOpacityLabel` / `menuSizeLabel` via `android:labelFor`. TalkBack announces the SeekBar position but not its semantic name.  
**Severity:** Failure — add `android:labelFor="@id/menuOpacitySeek"` on the label; or set `contentDescription` on the SeekBar

#### FAILURE — profile_overlay.xml: Selection-group buttons (usage/industry/sensitivity) have no programmatic group structure
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 94–138, 211–250, 620–660  
Multiple rows of `TextView` elements function as radio-button groups (`profileWorkOnly`/`profileLifeOnly`/`profileBoth`, industry chips, sensitivity chips). None use `RadioGroup`/`RadioButton`, none set `android:accessibilityRole`, and selected state is visual only. The relationship between group label ("MOSTLY USE FOR") and the buttons is not programmatic.  
**Severity:** Failure

#### FAILURE — profile_overlay.xml: `balanceSeek` slider has no programmatic label
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 160–168  
SeekBar `balanceSeek` has no `labelFor` from any containing label. The `W`/`L` flanking labels are standalone `TextView` elements with no `android:labelFor` association.  
**Severity:** Failure

#### FAILURE — onboarding.html: Progress dots convey position only by color/shape, not text
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 81–92, 501–511  
Progress dots change `background-color` and `width` to indicate current tile. No `aria-label`, no `aria-current`, no role attribute. The counter `tile-counter` text ("1 / 6") updates but is not announced via `aria-live`.  
**Severity:** Failure — add `role="tablist"` on `.dots`, `role="tab"` + `aria-selected` on each dot, or `aria-live="polite"` on the counter

---

### 1.3.3 Sensory Characteristics

#### FAILURE — activity_bouncer.xml: "Tap" instruction relies solely on action label
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 111–118, 160–167 (all `permXxxTap` views)  
The word "TAP" appears as the only affordance for granting a permission. No shape, no position-independent instruction. While "tap" is a generic gesture label rather than a sensory characteristic per se, these `TextView` elements have no role (they are not `Button`), so the instruction that "tap this" grants the permission cannot be understood without spatial context.  
**Severity:** Advisory — label the whole row as a button

#### FAILURE — onboarding.html: "TAP" status label in permission rows
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 705–708  
Permissions not yet granted show "TAP" in maroon. The instruction "TAP" relies on positional/color context (right side, red) to mean "tap this row to grant". No alternative text conveys the action label in a context-independent way.  
**Severity:** Advisory

---

### 1.4.1 Use of Color

#### FAILURE — activity_bouncer.xml: Permission granted/not-granted state conveyed by color alone
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 88–95 (and all `permXxxCheck` views)  
"!" in orange vs "✓" in a different color is the only indicator of permission state. The glyph changes but there is no accessible state change — TalkBack does not read "granted" or "not granted."  
**Severity:** Failure

#### FAILURE — profile_overlay.xml: Selection state of option buttons conveyed by background color only
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 101–137, all selection group rows  
The selected option among Work/Life/Both, industry tags, sensitivity buttons is shown via `card_bg` drawable state_selected color change. No text change, no `contentDescription` update, no `stateDescription`.  
**Severity:** Failure

#### FAILURE — onboarding.html: Granted/not-granted state conveyed by `perm-row.granted` class (color + background change only)
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 700–709  
Green background + green text = granted; default = not granted. No text or ARIA attribute communicates the binary state.  
**Severity:** Failure

---

### 1.4.3 Contrast (AA) — Normal Text

#### FAILURE — bubble.xml: `bubbleLabel` hardcoded color fails contrast
**File:** `app/src/main/res/layout/bubble.xml`  
**Line:** 169  
`android:textColor="#FF555560"` on a `textSize="10sp"` label. Background is `bubble_inner_knob` drawable. Approximated background from dark palette is approximately #2A2A2F. Computed ratio: **1.94:1**. Required: 4.5:1 for normal text.  
**Severity:** Failure — fix hardcoded color to use a theme token with sufficient contrast

#### FAILURE — bubble.xml: `bubbleTime` hardcoded color fails contrast
**File:** `app/src/main/res/layout/bubble.xml`  
**Line:** 183  
`android:textColor="#FF9A4040"` at `textSize="8sp"`. Approximate ratio against knob background: **2.16:1**. Required: 4.5:1.  
**Severity:** Failure

#### FAILURE — Light mode: `text_dim` fails on `bg_base` and card background
**Files:** `app/src/main/res/values/colors.xml`; all layouts using `@color/text_dim` in light mode  
`text_dim` (#8A7D68) on `bg_base` (#D4C8B0): **2.43:1**. On card bg (#F5EEE0): **3.49:1**. Both fail 4.5:1.  
This color is used pervasively for body copy throughout `activity_bouncer.xml` (lines 61, 102, 149, 190, 238, 280), `bubble_menu.xml` (line 68), `profile_overlay.xml` (lines 109, 115, 122, etc.).  
**Severity:** Failure (high breadth — affects most body text in light mode)

#### FAILURE — Light mode: `text_secondary` fails on `bg_base`
**Files:** `app/src/main/res/values/colors.xml`; `activity_bouncer.xml` subtitle at line 42  
`text_secondary` (#6B5D4A) on `bg_base` (#D4C8B0): **3.86:1**. Fails 4.5:1 for normal-sized text (11sp used at line 42 of bouncer).  
**Severity:** Failure

#### FAILURE — Light mode: `orange_label` (#C94A1F) fails on both backgrounds
**Files:** `app/src/main/res/values/colors.xml`  
On `bg_base` (#D4C8B0): **2.83:1**. On card (#F5EEE0): **4.06:1**. Both fail. Used in bouncer subtitle text at 11sp (lines 109–110, 203, 246, 289).  
**Severity:** Failure

#### FAILURE — Light mode: `green_active` fails on all light backgrounds
**Files:** `app/src/main/res/values/colors.xml`; `activity_bouncer.xml` line 1123 (Done button text)  
`green_active` (#16A34A) on `bg_base`: **1.99:1**. On card: **2.85:1**. Fails as text in both cases. Also used as the "after" reconstruction text on bouncer page 5 (line 866 `@color/green_active` at 13sp).  
**Severity:** Failure

#### FAILURE — Dark mode: `chip_value` (#B55050) fails on `bg_base` (#1A1A1E)
**Files:** `app/src/main/res/values-night/colors.xml`; `activity_bouncer.xml` lines 93, 117, 209, etc.  
Ratio: **3.49:1** against dark bg. Used for the "!" and "TAP" indicators in the permission list (13sp text). Fails 4.5:1.  
**Severity:** Failure

#### FAILURE — Dark mode: `text_secondary` (#8A8078) on surface (#222226)
**Files:** `app/src/main/res/values-night/colors.xml`; bouncer page indicator at 12sp  
Ratio: **4.11:1**. Fails 4.5:1.  
**Severity:** Failure

#### BORDERLINE FAILURE — Onboarding HTML: `text_muted` (#8A8078) on bg (#1A1A1E)
**File:** `app/src/main/assets/onboarding.html`  
**Line:** 97 (`.tile-counter` and `.logo-sub`)  
Ratio: **4.49:1** — 0.01 below the 4.5:1 threshold. Fails at 12px / 12sp non-bold text.  
**Severity:** Failure (barely, but below threshold)

#### FAILURE — Onboarding HTML: Permission status "TAP" text (#B55050) on raised surface (#2A2A2F)
**File:** `app/src/main/assets/onboarding.html`  
**Line:** 338 (`.perm-status.tap`)  
Ratio: **2.87:1**. Required: 4.5:1 (12px normal weight text).  
**Severity:** Failure

#### FAILURE — Onboarding HTML: Green check (#16A34A) on raised surface (#2A2A2F)
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 335–336  
Ratio: **4.33:1**. Required: 4.5:1. Fails for 13px normal text.  
**Severity:** Failure

---

### 1.4.11 Non-text Contrast (UI Components)

#### FAILURE — Onboarding HTML: Progress dot inactive state fails 3:1
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 85–88  
Inactive dot background `#333338` on page bg `#1A1A1E`: **1.38:1**. Required 3:1 for UI components.  
**Severity:** Failure — increase inactive dot color to at least 3:1

#### FAILURE — Onboarding HTML: Active dot (#B55050) on surrounding bg (#333338) fails 3:1
**File:** `app/src/main/assets/onboarding.html`  
**Lines:** 89–92  
Ratio: **2.53:1**. Required: 3:1 for UI indicator.  
**Severity:** Failure

#### ADVISORY — bubble_menu.xml: Color swatches need 3:1 against adjacent background
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 193–218  
`menuThemeBronze`, `menuThemeAluminum`, `menuThemeGunmetal` are 26dp x 26dp visual-only selectors. The actual drawable colors are unknown without reading the drawables, but small circular swatches against a dark menu background should be verified. `contentDescription` is present (good), but the selected state of the active theme is not programmatically exposed.  
**Severity:** Advisory (verify drawable contrast; add selected state)

---

### 2.1.1 Keyboard / Switch Access

#### FAILURE — activity_bouncer.xml: Permission rows are `LinearLayout` with no keyboard/switch role
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 78–119, 121–168, 170–212, 213–256, 258–299  
`permOverlay`, `permAccessibility`, `permMic`, `permBattery`, `permNotifications` are `LinearLayout` containers that presumably receive click listeners in code. They have no `focusable="true"`, no `clickable="true"` set in XML. Without explicit focusability, switch access and keyboard navigation cannot target them.  
**Severity:** Failure — add `android:clickable="true" android:focusable="true"` to each permission row container

#### FAILURE — activity_bouncer.xml: Option buttons (Free/Upgrade, Work/Personal, etc.) are `LinearLayout` without explicit focusability
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 357–405 (`optionFree`, `optionUpgrade`), 458–533 (`optionWork`, `optionPersonal`, `optionBoth`)  
These selection options are `LinearLayout` elements presumably receiving click listeners from code. None declare `android:focusable="true"` or `android:clickable="true"` in XML.  
**Severity:** Failure

#### FAILURE — bubble_menu.xml: Layer nameplates `menuLayer1`, `menuLayer2`, `menuLayer4` are not declared focusable/clickable in XML
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 95–172  
These act as interactive selectors but have no `focusable="true"` or `clickable="true"` declared.  
**Severity:** Failure

#### FAILURE — bubble_menu.xml: `menuOpenSettings` `TextView` is not declared focusable/clickable
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 326–333  
Settings link `TextView` has no `focusable="true"` or `clickable="true"` in XML.  
**Severity:** Failure

#### FAILURE — profile_overlay.xml: Selection option TextViews not declared focusable
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 101–137, 224–249, 293–342, 370–373, 626–660  
The industry chips, usage mode buttons, hour buttons, language buttons, and sensitivity buttons are all plain `TextView` elements without `focusable="true"`.  
**Severity:** Failure (high breadth)

---

### 2.4.3 Focus Order

#### ADVISORY — activity_bouncer.xml: Page indicator `bouncerPageIndicator` is in the focus order despite being non-interactive
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 310–318  
The page indicator text ("1 / 6") will receive TalkBack focus but is purely informational. Consider `importantForAccessibility="no"` or ensure it reads as an accessible live region instead.  
**Severity:** Advisory

#### ADVISORY — activity_main.xml: `wimLogoSummon` is the primary action but visually appears as a header
**File:** `app/src/main/res/layout/activity_main.xml`  
**Lines:** 62–73  
The brand logo text "WiM" is `clickable="true"` and `focusable="true"` but has no `contentDescription` describing its action (summon/restore bubble). TalkBack reads "WiM" with no hint that it is interactive or what it does.  
**Severity:** Failure (4.1.2 also implicated) — add `contentDescription="Summon WiM bubble"` or equivalent

---

### 2.4.6 Headings and Labels

#### FAILURE — activity_main.xml: Tile controls (`tileLayer`, `tileTone`, `tilePermissions`, `tileSettings`) have no accessible labels describing their action
**File:** `app/src/main/res/layout/activity_main.xml`  
**Lines:** 215–386  
These `FrameLayout` tiles are interactive (clickable + focusable) but their child `TextView` labels ("LAYER", "TONE", "PERMISSIONS", "SETTINGS") are standalone text inside the container — not associated as the accessible name of the container itself. TalkBack will traverse into the children rather than announcing the tile as a single actionable unit.  
**Severity:** Failure — add `contentDescription` to each tile FrameLayout, or use `android:labelFor` from a label to the container

#### FAILURE — profile_overlay.xml: Section headers are visual only, not announced as headings
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 78–86, 196–202, 274–285, 348–356, 377–387, 445–454, 537–546, 602–612, 665–677  
Section header `TextView` elements (copper_gold, textAllCaps, geist_bold) structure the form. None use `android:accessibilityHeading="true"`. Screen reader users cannot navigate by headings.  
**Severity:** Failure — add `android:accessibilityHeading="true"` to each section header

---

### 2.5.3 Label in Name

#### FAILURE — activity_bouncer.xml: `autoSendAck` row has no accessible name matching visible content
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 955–985  
The clickable `LinearLayout` checkbox row has no `contentDescription`. TalkBack will announce the child `TextView` text (the checkbox glyph + the acknowledgment text), but the composed accessible name of the container is not explicitly set.  
**Severity:** Advisory (borderline — the child text is readable but the checked state is not)

---

### 2.5.8 Target Size (Minimum 24x24dp)

#### FAILURE — activity_bouncer.xml: Permission check `TextView` elements are 20dp wide with no minimum height
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 88–95, 133–138, 182–187, 226–231, 268–273  
`permOverlayCheck` and siblings are `layout_width="20dp"` with no `minHeight`. The containing row is 34dp height, but the check indicator itself has a 20dp width that is below the 24dp minimum. The parent row is the actual click target (if made clickable), so the row target size matters — rows at 34dp height meet the minimum vertically, but the row `LinearLayout` itself must be `clickable="true"` for this to apply.  
**Severity:** Advisory (if parent row is the target, height is borderline at 34dp; explicit `minHeight="48dp"` recommended for comfort)

#### FAILURE — bubble_menu.xml: `menuOpenSettings` tap target is undersized
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 326–333  
`menuOpenSettings` has `android:padding="4dp"` on a small `TextView`. No `minWidth` or `minHeight` is set. Total tappable area depends on text size (11sp) + 4dp padding all sides — well under 48x48dp recommended and likely under 24dp in both dimensions.  
**Severity:** Failure — add `android:minWidth="48dp" android:minHeight="48dp"`

#### FAILURE — profile_overlay.xml: `vocabAddBtn`, `corrAddBtn`, `triggerAddBtn` are 36dp height
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 425–440, 519–532, 584–599  
All three add buttons are `layout_height="36dp"`. WCAG 2.2 §2.5.8 minimum is 24dp (met), but Android Material recommendation and WCAG comfort guidance is 48dp. At 36dp these are borderline — technically meets the 24dp absolute floor but misses comfort targets.  
**Severity:** Advisory

#### FAILURE — profile_overlay.xml: Industry/language/usage/hours TextViews are 36dp height
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 103–137, 224–249, 300–331, 370–373  
Same issue — 36dp height across all option buttons. Meets 24dp floor but is below 48dp comfort target. Given there are 3–4 buttons in one horizontal row at full width, the width per button can be small on narrow screens (< 100dp each on a 360dp screen).  
**Severity:** Advisory

---

### 3.2.1 On Focus

#### ADVISORY — bubble_menu.xml: `menuLiveToggle` is a `MaterialSwitch` — no unexpected behavior expected
No finding. `MaterialSwitch` behaves predictably on focus.

#### ADVISORY — activity_bouncer.xml: Advancing to next page on option selection
Pages 2–4 in the bouncer advance immediately when an option (`optionFree`, `optionWork`, etc.) is tapped. This constitutes an automatic context change on interaction. While this is common onboarding UX, WCAG 3.2.1 requires that context changes not occur on focus alone — if these fire on click (not focus), this is technically compliant. If any advance on focus, it would be a failure.  
**Severity:** Advisory (verify in code that page advance is on click, not on focus)

---

### 3.3.1 Error Identification

#### FAILURE — profile_overlay.xml: `indOtherInput` text field has no visible error state
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 253–269  
`EditText` for "Other" industry has a hint but no error handling in the layout. If left empty and saved, no accessible error message would be presented. (Layout-level finding; may be handled in code.)  
**Severity:** Advisory — ensure any validation error is announced via `setError()` which provides accessible text

#### FAILURE — profile_overlay.xml: `vocabAddInput`, `corrAddFrom`, `corrAddTo`, `triggerAddInput` have no error identification
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 409–424, 476–491, 501–516, 568–583  
These `EditText` fields have hint text but no accessible error-state mechanism declared in XML.  
**Severity:** Advisory

---

### 4.1.2 Name, Role, Value

#### FAILURE — bubble.xml: `twistConfirmCheck` has no accessible name
**File:** `app/src/main/res/layout/bubble.xml`  
**Lines:** 192–204  
`twistConfirmCheck` shows "✓" to confirm a twist gesture. It is `clickable="false"` and `focusable="false"` which is correct for a transient visual indicator. However if it becomes visible and TalkBack is active, a raw "✓" with no context will be announced. Mark `importantForAccessibility="no"` if purely decorative.  
**Severity:** Advisory — add `importantForAccessibility="no"`

#### FAILURE — activity_bouncer.xml: `btnNextToBubbleDemo`, `btnNextToFinal`, `btnAutoSendNext`, `btnLaunchBubble`, `btnSamsungOpenSettings`, `btnSamsungDone`, `btnSamsungSkip` are `LinearLayout` buttons without declared roles
**File:** `app/src/main/res/layout/activity_bouncer.xml`  
**Lines:** 756–771, 888–904, 992–1009, 1202–1217, 1091–1107, 1110–1125, 1128–1141  
All "next" and action buttons are `LinearLayout` elements containing a `TextView`. None have `android:clickable="true"` or `android:focusable="true"` declared in XML (though the code likely adds click listeners). No role is exposed to accessibility services. TalkBack will not announce these as buttons.  
**Severity:** Failure — add `android:clickable="true" android:focusable="true"` and `contentDescription` to each, or migrate to `Button`/`MaterialButton`

#### FAILURE — profile_overlay.xml: `exportDataBtn` and `deleteDataBtn` have no `contentDescription`
**File:** `app/src/main/res/layout/profile_overlay.xml`  
**Lines:** 744–763  
Both `TextView` buttons carry their text ("Export Data", "Delete Data") which is readable by TalkBack, so name is present. However `deleteDataBtn` has `textColor="@color/red_hot"` — this color conveys destructive meaning, which is color-only (1.4.1 also implicated). Accessible name is technically present via text.  
**Severity:** Advisory for `deleteDataBtn` — add explicit `contentDescription` warning ("Delete all data — this cannot be undone") or at minimum ensure confirmation dialog exists

#### FAILURE — bubble_menu.xml: Color theme swatches have `contentDescription` but no selected/unselected state
**File:** `app/src/main/res/layout/bubble_menu.xml`  
**Lines:** 193–218  
`menuThemeBronze`, `menuThemeAluminum`, `menuThemeGunmetal` have `contentDescription` present (good). Selected state (which theme is active) is purely visual. No `stateDescription` or checked state is exposed.  
**Severity:** Failure — expose selected state via `ViewCompat.setStateDescription(view, "selected")` / `ViewCompat.setChecked()`

---

## SUMMARY BY SEVERITY

### Failures (must fix for WCAG 2.2 AA)

| # | Criterion | File | Key Issue |
|---|---|---|---|
| F-01 | 1.1.1 / 4.1.2 | bubble_menu.xml:95–172 | Layer nameplates and L1 source toggles: no accessible name or state |
| F-02 | 1.1.1 | bubble.xml:55 | BezelStageArcView: no accessibility handling |
| F-03 | 1.1.1 | activity_main.xml:155–181 | VuMeterView x4: no contentDescription |
| F-04 | 1.3.1 | activity_bouncer.xml:955–985 | autoSendAck: fake checkbox, no role or state |
| F-05 | 1.3.1 | bubble_menu.xml:248–300 | Sliders not associated with labels |
| F-06 | 1.3.1 | profile_overlay.xml | Selection groups not programmatic |
| F-07 | 1.4.1 | activity_bouncer.xml | Permission state by color alone |
| F-08 | 1.4.1 | profile_overlay.xml | Selection state by color alone |
| F-09 | 1.4.3 | bubble.xml:169 | bubbleLabel #555560 — **1.94:1** (need 4.5:1) |
| F-10 | 1.4.3 | bubble.xml:183 | bubbleTime #9A4040 — **2.16:1** |
| F-11 | 1.4.3 | colors.xml light | text_dim on bg_base — **2.43:1** (widespread) |
| F-12 | 1.4.3 | colors.xml light | text_secondary on bg_base — **3.86:1** |
| F-13 | 1.4.3 | colors.xml light | orange_label on bg_base — **2.83:1** |
| F-14 | 1.4.3 | colors.xml light | green_active on bg_base — **1.99:1** |
| F-15 | 1.4.3 | colors.xml dark | chip_value on bg_base — **3.49:1** |
| F-16 | 1.4.3 | colors.xml dark | text_secondary on surface — **4.11:1** |
| F-17 | 1.4.3 | onboarding.html | text_muted — **4.49:1** (0.01 below) |
| F-18 | 1.4.3 | onboarding.html | perm TAP text — **2.87:1** |
| F-19 | 1.4.3 | onboarding.html | perm check green — **4.33:1** |
| F-20 | 1.4.11 | onboarding.html | inactive dot — **1.38:1** (need 3:1) |
| F-21 | 1.4.11 | onboarding.html | active dot — **2.53:1** (need 3:1) |
| F-22 | 2.1.1 | activity_bouncer.xml | Permission rows: no focusable/clickable in XML |
| F-23 | 2.1.1 | activity_bouncer.xml | Option buttons: no focusable/clickable in XML |
| F-24 | 2.1.1 | bubble_menu.xml | Layer nameplates: no focusable/clickable in XML |
| F-25 | 2.1.1 | bubble_menu.xml | menuOpenSettings: no focusable/clickable in XML |
| F-26 | 2.1.1 | profile_overlay.xml | All option TextViews: no focusable |
| F-27 | 2.4.6 | activity_main.xml | Tile containers have no accessible label |
| F-28 | 2.4.6 | profile_overlay.xml | Section headers not marked as headings |
| F-29 | 2.5.8 | bubble_menu.xml:326 | menuOpenSettings tap target undersized |
| F-30 | 4.1.2 | activity_bouncer.xml | All "Next/Launch" LinearLayout buttons: no role |
| F-31 | 4.1.2 | activity_main.xml:62 | wimLogoSummon: no contentDescription for action |
| F-32 | 4.1.2 | bubble_menu.xml:193–218 | Color swatches: selected state not exposed |
| F-33 | 1.1.1 | onboarding.html:700–709 | Permission icons/glyphs not accessible |
| F-34 | 1.3.1 | onboarding.html:501–511 | Progress dots convey position by color only |

### Advisories (recommended improvements)

| # | Criterion | File | Key Issue |
|---|---|---|---|
| A-01 | 1.1.1 | bubble.xml:147 | bubbleTick not marked importantForAccessibility="no" |
| A-02 | 1.1.1 | activity_main.xml:103–137 | Screw decorations not marked decorative |
| A-03 | 1.1.1 | onboarding.html:401 | wim-bubble div: no aria-label |
| A-04 | 1.1.1 | bubble.xml:192 | twistConfirmCheck: mark importantForAccessibility="no" |
| A-05 | 1.3.3 | activity_bouncer.xml | "TAP" instruction needs role context |
| A-06 | 2.4.3 | activity_bouncer.xml:310 | Page indicator in focus order |
| A-07 | 2.5.8 | profile_overlay.xml | Add buttons and option buttons at 36dp (below 48dp comfort) |
| A-08 | 3.2.1 | activity_bouncer.xml | Verify option-tap advance is click not focus |
| A-09 | 3.3.1 | profile_overlay.xml | EditText fields: verify setError() used for validation |
| A-10 | 4.1.2 | profile_overlay.xml:744–763 | deleteDataBtn: add destructive-action warning in description |

---

## CRITICAL REMEDIATION PRIORITIES

1. **Light mode contrast is broken across the board.** `text_dim` (2.43:1), `orange_label` (2.83:1), `green_active` (1.99:1) all fail by wide margins on the warm paper background. Every screen using these in light mode is non-compliant for sighted users with low vision. Darken each token specifically for light mode.

2. **Bubble label text is hardcoded to near-invisible values** — `#555560` at 10sp and `#9A4040` at 8sp render at under 2:1 contrast inside the knob. These should be replaced with theme color tokens and the token values verified.

3. **`chip_value` in dark mode** (#B55050 at 3.49:1) is used for the permission "!" and "TAP" affordances — the exact UI elements guiding first-time setup. Fixing this is critical for the onboarding funnel.

4. **None of the "button" LinearLayouts in the bouncer declare `clickable="true"` or `focusable="true"` in XML.** If this is handled entirely in code, Switch Access and some TalkBack modes may still miss them depending on the API level. Declare in XML.

5. **The autoSendAck fake checkbox** (line 955–985) is the consent gate for auto-send. A user on Switch Access or TalkBack cannot determine whether they have checked or unchecked consent. This is a compliance risk beyond accessibility — it implicates informed consent.

6. **Profile overlay selection groups** (industry, usage, sensitivity) have zero programmatic state exposure. A TalkBack user can hear the options but cannot confirm which is selected.
