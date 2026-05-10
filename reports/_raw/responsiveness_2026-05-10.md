# WiM Android — Responsiveness Audit
**Date:** 2026-05-10
**Auditor:** Claude Code (Sonnet 4.6 1M)
**Files examined:**
- `app/src/main/assets/onboarding.html`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_bouncer.xml`
- `app/src/main/res/layout/bubble.xml`
- `app/src/main/res/mipmap-*/` (all density buckets)

---

## PART 1 — onboarding.html Viewport Analysis

### Viewport meta tag
**PASS.** Line 5:
```
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
```
Correct tag. `user-scalable=no` is present — acceptable for a controlled onboarding slider flow.

### Width strategy
**MOSTLY PASS.** The layout structure uses `vw`/`vh` units, percentages, and `max-width` guards throughout:
- Slider: `width: 600vw` (6 tiles × 100vw) — correct, scales to any width (line 61)
- Each tile: `width: 100vw; height: 100vh` — correct (lines 68–69)
- Tile padding: `40px 28px` (lines 71–72) — **FIXED px**. On a 280px Z Flip cover screen this consumes 56px of the 280px, leaving only 224px usable width. Functional but tight.

**Fixed-px elements to note:**
- `.dash-frame` max-width: `340px` (line 149) — fine, it's a max-width cap
- `.phone-mock` width: `220px` fixed (line 219) — **P2 flag** (see below)
- `.layers-panel` max-width: `300px` (line 249) — fine
- `.tones-grid` max-width: `300px` (line 279) — fine
- `.console-mock` max-width: `320px` (line 307) — fine
- `.perm-list` max-width: `320px` (line 321) — fine
- `.action-btn` max-width: `300px` (line 121) — fine, it's a max cap not a fixed width
- `body::before` stripe gradient uses `px` values (lines 54–55) — decorative only, no layout impact

### At 360px viewport (Galaxy S standard)
**PASS overall.** All content-bearing containers use `width: 100%` with `max-width` guards. The slider tiles fill 100vw. The only potential squeeze is inside Tile 2.

### At 280px viewport (Z Flip 7 cover screen)
**P2 — Tile 2 phone mock clips.** `.phone-mock` is `width: 220px` hardcoded (line 219). On a 280px viewport with `40px 28px` tile padding that leaves `280 - 56 = 224px` usable. The `220px` mock fits by 4px — barely, with no margin. The `.wim-bubble` is `position: absolute; bottom: -10px; right: -10px` (lines 236–237), which pushes outside the mock's bounds by 10px on each side. On a 280px screen, the bubble protrudes into the 4px margin zone and likely clips against the tile edge.

The Z Flip 7 main display (22:9 foldable unfolded) is ~390dp wide — no special issues there. The cover screen is where the 220px phone-mock would be a real problem if onboarding ever surfaces there (it shouldn't, but worth noting).

### Font sizes
**MIXED.** Most text uses `px` (CSS pixels), not `em`/`rem`:
- `.tile-title`: `font-size: 22px` (line 104)
- `.tile-sub`: `font-size: 13px` (line 110)
- `.action-btn`: `font-size: 15px` (line 125)
- `.logo`: `font-size: 52px` (line 144)

These are CSS `px`, not Android `sp`. In a WebView, CSS `px` does scale with Android's font size accessibility settings IF `WebSettings.setTextZoom()` is left at default (100). If the app forces `setTextZoom(100)` or similar, accessibility font scaling is broken. No audit of the Kotlin side was done here — this is a risk flag, not a confirmed bug.

`em`/`rem` throughout would be cleaner and honor the user's accessibility font size preference without any Kotlin changes.

### Action button tap targets
**PASS.** `.action-btn` has `padding: 16px` (line 122). Total tap height is ~15px font + 32px padding = ~47px rendered height. Meets Android's 48dp minimum by ~1px margin. Acceptable.

`.skip-btn` has only `padding: 8px` (line 137) — tap height ~12px + 16px = ~28px. **P1 — below 48dp minimum.**

### Tile title max-width guard
**MIXED.** `.tile-title` (line 103) has no `max-width` constraint. On wide screens this is fine. On narrow screens `text-align: center` keeps it readable. However the longest tile title is "4 layers of intelligence" (Tile 3, line 410) — 24 characters. At 22px in Limelight, this spans roughly 280px. At 280px viewport with 56px padding it would be 224px available — this line may wrap to two lines. Not catastrophic (it wraps cleanly), but "4 layers of" / "intelligence" is an awkward break. **P2.**

### Swipe gesture edge exclusion
**PASS.** Lines 538–539 exclude 40px edge zones from swipe detection to avoid conflicting with Samsung gesture navigation. This is exactly right.

---

## PART 2 — Native XML Layout Analysis

### activity_main.xml

**Overall structure:** `FrameLayout` > `LinearLayout` (vertical, `paddingStart/End: 14dp`) > fixed top + `ScrollView` bottom. This is the correct pattern — content never clips.

**Fixed dp widths used as icon columns (safe):**
- `36dp` for layer Roman numeral column (lines 440, 540, 594, 648, 985, 1038, 1091, 1144, 1197) — used as `layout_width` on a `TextView` with adjacent `layout_weight="1"` filler. This pattern is safe: the 36dp column is a label column, the flex column takes remaining space. At 360dp screen minus 28dp padding = 332dp usable. 36dp column is fine.
- `20dp` for permission check icons — same safe pattern in bouncer.xml.

**VU Meter row (activity_main.xml lines 155–182):** Four `VuMeterView` elements with `layout_weight="1"` each and fixed `layout_height="72dp"`. Equal-weight distribution is correct — they compress proportionally. The `72dp` height is fixed but vertical, no horizontal overflow risk. Fine.

**Tile control row (lines 209–387):** Two `FrameLayout` tiles side by side with `layout_weight="1"` each, `layout_height="68dp"` (tileLayer/tileTone) and `52dp` (tilePermissions/tileSettings). Weight-1 split is correct. Fine at all widths.

**No hardcoded full-width px values found** in activity_main.xml. All row widths are `match_parent` or `layout_weight`.

**ScrollView:** Present at line 188 with `layout_weight="1"` to fill remaining vertical space. `fillViewport="true"` ensures content extends if short. The scrollable area holds Layer/Tone/Permissions/Settings expandable sections. Content is always reachable. Fine.

**Z Flip 7 main display (22:9, ~390dp wide):** The 14dp horizontal padding leaves 362dp usable. All weight-based layouts adapt fine. No assumptions of wider screens found. The 72dp VU meter height row is fixed — on a very tall 22:9 screen this takes a smaller fraction of the vertical height, which is actually better (more content visible below). Fine.

**Font sizes:** All native text views use `sp` units throughout (12sp, 13sp, 14sp, 15sp, 18sp, 22sp, 26sp, 48sp). No `px` found for font sizes in any of the three XML layout files. **PASS.**

### activity_bouncer.xml

**Overall structure:** `FrameLayout` > `ScrollView` > `LinearLayout (match_parent, wrap_content)` > multiple page `LinearLayout`s. Correct scrollable structure. `fillViewport="true"` on the ScrollView (line 10). All pages hidden except pagePermissions on launch. Fine.

**P1 — Fixed 110dp gesture label column (Page 4, "Meet the Bubble"):**
Lines 616, 640, 664, 688, 712, 735 — six gesture rows each have a `TextView` with `layout_width="110dp"` for the gesture name ("Tap once", "Hold", "Double-tap", etc.), followed by a `layout_width="wrap_content"` TextView for the action description. There is no `layout_weight` on the action column.

At 360dp screen: `360dp - 28dp padding - 28dp padding = 304dp` usable row width. Further minus `14dp paddingStart + 14dp paddingEnd` inside each row = `276dp` for content. The 110dp label + unconstrained action text. If the action string is long, it truncates against the row's end padding. This is not an overflow, but the action descriptions have no `layout_weight="1"` to flex — they use `wrap_content`, meaning if the string is long and no `maxLines`/`ellipsize` is set, it can push outside the visible row area on small screens. **P1.**

On a **Z Flip 7 main display (~390dp):** usable content = ~306dp. The 110dp fixed column leaves 196dp for the action description — enough for most strings. At 360dp it leaves 166dp — tighter but probably sufficient for these short strings. Recommend adding `layout_weight="1"` to the action column to make it flex-safe.

**Copper divider:** `layout_width="50dp"` (lines 52, 351, 453, 581) — decorative element, centered, no overflow risk.

**Page heights:** All pages use `wrap_content` height. ScrollView handles overflow. No fixed-height pages. Fine.

### bubble.xml

**Structure:** `FrameLayout` 120dp × 120dp root. This is an overlay window sized by `WindowManager.LayoutParams` at runtime, not by the XML's root dimensions directly — but the XML root dimensions do constrain the bubble's layout space.

**Bubble dimensions:**
- Root: 120dp × 120dp (lines 3–4) — clipChildren/clipToPadding=false to allow pulse rings and regen button to extend outside
- Bezel: 72dp × 72dp (lines 43–44)
- Inner: 44dp × 44dp (lines 140–141)
- Pulse rings: 64dp × 64dp (lines 14–15, 24–25, 32–33)
- Regen button: 48dp × 48dp touch target, `layout_marginBottom="-17dp"` (line 124) pushing it below the bezel — extends to ~72+17=89dp from center, within 120dp root. Fine.
- Clarifier halves: 36dp × 72dp each (lines 64–65, 73–74)

**Z Flip 7 fit:** The main Z Flip 7 display is ~390dp wide at ~22:9. A 120dp overlay window is 31% of that width — reasonable. The bubble's absolute-positioned overlay is draggable, so it adapts to any screen size by user placement. No issues.

**Cover screen consideration:** Z Flip 7 cover display is ~260dp wide. A 120dp bubble there would be 46% of the width — very large, potentially obstructive. However, the cover screen runs a limited app set and WiM's overlay service likely isn't surfaced there. Not a current bug, but worth flagging if multi-window support is added.

**Font sizes in bubble.xml:** `bubbleLabel` uses `10sp` (line 170), `bubbleTime` uses `8sp` (line 181), bezel icons use `14sp` (lines 91, 107), `twistConfirmCheck` uses `22sp` (line 200). All sp. **PASS.**

**Touch targets:**
- `bubbleBezel` is 72dp × 72dp — exceeds 48dp minimum. Fine.
- `bezelRegen` FrameLayout is 48dp × 48dp (lines 120–121) — meets minimum. Fine.
- `bezelYes`/`bezelNo` are 18dp × 24dp (lines 84–85, 99–100) but are `clickable=false/focusable=false` — they're visual only, taps go to the parent bezel which handles gesture routing. Fine.

---

## PART 3 — Density Bucket Coverage

**Mipmap directories present:**
- `mipmap-mdpi` — contains `ic_launcher_foreground.png`
- `mipmap-hdpi` — contains `ic_launcher_foreground.png`
- `mipmap-xhdpi` — present (inferred from directory listing pattern)
- `mipmap-xxhdpi` — present
- `mipmap-xxxhdpi` — present
- `mipmap-anydpi-v26` — contains `ic_launcher.xml` and `ic_launcher_round.xml`

**Adaptive icon structure (mipmap-anydpi-v26/ic_launcher.xml):**
```xml
<adaptive-icon>
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

**P3 — Foreground is a PNG, not a vector.** The adaptive icon foreground points to `@mipmap/ic_launcher_foreground` — a raster PNG in each density bucket, not a vector drawable. For Android 8.0+ (API 26+) adaptive icons, the foreground CAN be a PNG if all density buckets are present, which they are (mdpi through xxxhdpi). This is acceptable but not ideal. A vector drawable foreground (`@drawable/ic_launcher_foreground_vector`) would eliminate density artifacts entirely and simplify the asset pipeline.

**Coverage verdict:** All five standard density buckets are present. Adaptive icon XML covers API 26+. Older devices (pre-Oreo) fall back to individual PNG density buckets. **PASS for functional coverage; P3 optimization to convert foreground to vector.**

**In-app drawables:** Not audited at this layer — would require reviewing `res/drawable/` directory. Recommended as a follow-up if Play Store submission requires density verification.

---

## Summary by Priority

### P0 — Layout breaks or content clips at 360dp width
**None found.** All layouts use `match_parent`, `layout_weight`, or `max-width`-guarded containers. Scrollable fallback exists everywhere.

### P1 — Issues at 360dp that hurt usability

**1. Skip button tap target too small (onboarding.html, line 137)**
`.skip-btn { padding: 8px }` yields ~28px tap height — 20dp below Android's 48dp minimum. On a 360dp screen this is a fat-finger fail. Fix: increase to `padding: 16px 12px` or convert to a visible secondary button matching `.action-btn` sizing.

**2. Bouncer "Meet the Bubble" gesture list — action column not flex-safe (activity_bouncer.xml, lines 621–627, 641–647, 665–671, etc.)**
Six rows have a `110dp` fixed gesture-name column and a `wrap_content` action column with no `layout_weight`. At 360dp screens with heavy padding, the action descriptions have ~166dp available. Long strings risk silent truncation if `maxLines` is not set. Add `layout_weight="1"` to all six action TextViews. Current action strings appear short enough to fit, but the architecture is fragile.

### P2 — Issues on Z Flip 7 form factor specifically

**1. Tile 2 phone mock at 280px cover screen (onboarding.html, lines 219, 236–237)**
`.phone-mock { width: 220px }` is a hardcoded fixed width. On a 280px cover screen viewport (56px consumed by tile padding), usable space is 224px. The mock fits by 4px, and the `.wim-bubble { right: -10px; bottom: -10px }` absolute child extends beyond that margin. Visual clipping is likely on the Z Flip 7 cover screen if onboarding is ever triggered there.
Fix: Change `.phone-mock { width: min(220px, calc(100% - 40px)) }`.

**2. Tile title possible line break at 280px (onboarding.html, line 104)**
"4 layers of intelligence" at 22px Limelight in 224px usable space may force an awkward two-line break on the cover screen. Low-priority since onboarding shouldn't trigger on the cover display, but defensible fix is adding `max-width: 100%` and `word-break: keep-all` to `.tile-title`.

### P3 — Optimization opportunities

**1. CSS font sizes in px, not em/rem (onboarding.html, lines 104, 110, 125, 144, etc.)**
All WebView text uses CSS `px`. If the host app leaves `WebSettings.textZoom` at default, Android's accessibility font scaling applies. If the app forces `setTextZoom(100)`, users who need larger text get nothing. Migrating to `rem` with `html { font-size: 16px }` as the base would future-proof this.

**2. Launcher foreground is raster PNG across 5 density buckets (mipmap-*/ic_launcher_foreground.png)**
Five separate PNGs maintained in parallel. A single `res/drawable/ic_launcher_foreground.xml` vector would eliminate these and scale perfectly at all densities including xxxhdpi+ devices like the Fold series. Effort: low if the current foreground graphic can be reproduced as a vector.

**3. `user-scalable=no` in onboarding.html viewport (line 5)**
Disables pinch-to-zoom. Acceptable for a slider flow but conflicts with accessibility guidelines (WCAG 1.4.4 Resize Text). For production Play Store submission under Android accessibility requirements, this may be flagged. Consider allowing zoom or increasing base font sizes to reduce need.

---

## Files referenced
- `app/src/main/assets/onboarding.html` — lines 5, 61, 68-72, 104, 110, 121, 125, 137, 144, 149, 219, 236–237, 249, 279, 307, 321, 538–539
- `app/src/main/res/layout/activity_main.xml` — lines 155–182, 209–312, 314–387, 440–688, 985–1237
- `app/src/main/res/layout/activity_bouncer.xml` — lines 52, 90, 104, 134, 141, 351, 453, 581, 616–735
- `app/src/main/res/layout/bubble.xml` — lines 3–4, 14–15, 43–44, 84–85, 99–100, 120–121, 140–141, 170, 181, 200
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — lines 3–5
