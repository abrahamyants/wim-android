# WiM Android — Design Quality Review
**Date:** 2026-05-10
**Reviewer:** Claude Sonnet 4.6 (1M context)
**Scope:** bubble.xml, activity_main.xml, activity_bouncer.xml, onboarding.html, colors.xml (light + night), drawable/ and drawable-night/

---

## P0 — Polish-Breakers

### P0-1: Unauthorized font in activity_main.xml — Geist is not in the brand system
**File:** `app/src/main/res/layout/activity_main.xml` lines 239, 250, 290, 300, 401, 461, 507, 547, 600, 650, 699, 707, 750, 787, 954 (and many more throughout)
**What:** The tile labels (LAYER, TONE), layer row numerals (I, II, III, IV), and all section headers use `@font/geist` and `@font/geist_bold`. The sub-option chips (layer1Local, layer1Cloud) use `@font/josefin_sans` (line 508, 520). Neither font appears in `brand.local.md`. The brand spec is explicit: **Limelight for display headers, Outfit/Plus Jakarta Sans for body**. Roboto is the system fallback only.
**Impact:** Every tile header, every layer row numeral, and both sub-option chips render in off-brand fonts. The native dashboard looks different from both the onboarding.html (which uses Limelight + Outfit correctly) and brand.local.md.
**Fix:** Replace `@font/geist` and `@font/geist_bold` with `@font/limelight` for headers (LAYER, TONE, PERMISSIONS, SETTINGS) and `@font/plus_jakarta_sans` for tile value text. Replace `@font/josefin_sans` with `@font/plus_jakarta_sans` on the nameplate sub-chips.

---

### P0-2: `glass_panel_bg.xml` uses an off-brand dark blue — hardcoded `#2A2A38`
**File:** `app/src/main/res/drawable/glass_panel_bg.xml` line 6
**What:** `solid android:color="#FF2A2A38"` is a cool blue-grey that does not exist in either light or dark colors.xml. The dark mode bg_surface_light is `#2A2A2F` (warm neutral). The `#2A2A38` blue tint will be visible wherever `glass_panel_bg` is used — notably the Settings user block (`activity_main.xml` line 1279: `android:background="@drawable/glass_panel_bg"`). On a warm-paper background this will read as a noticeable blue cast.
**No night variant exists** for `glass_panel_bg` — the drawable-night directory does not contain it — so it renders identically in both modes, which is wrong for light mode (it should resolve to a warm cream surface, not a dark glass slab).
**Fix:** Replace the hardcoded hex with `@color/bg_surface_light` and provide a `drawable-night/glass_panel_bg.xml` that resolves to `@color/bg_surface_light` as well (the night value `#2A2A2F` is correct; the light value of `bg_surface_light` is `#FFEDE4D0`, which is the right warm cream).

---

### P0-3: Bubble inner knob has no drawable-night/ variant
**Files:** `app/src/main/res/drawable/bubble_inner_knob.xml` (exists), `app/src/main/res/drawable-night/bubble_inner_knob.xml` (absent)
**What:** `bubble_inner_knob.xml` uses a warm bronze gradient (`#FFC4A574` to `#FF8E6E48`) that is appropriate for light mode. In dark mode the system falls back to this same drawable — the bronze knob face against a dark `#1A1A1E` background is too light and loses the sense of depth that makes the dark-mode bubble feel premium. The dark-mode `bubble_bg_idle.xml` correctly uses `#FF333338` to `#FF1A1A1E` for the inner well; the knob should follow.
**Fix:** Add `drawable-night/bubble_inner_knob.xml` with a dark-adjusted gradient: outer shadow `#FF0A0A0E`, side wall `#FF4A3828`, face `#FF3A2C1E` to `#FF1E180E` — warm dark leather that sits correctly on the dark bezel.

---

### P0-4: Onboarding.html Tile 4 (Tones) shows 4 tone cards; native Bouncer only offers 2
**Files:** `app/src/main/assets/onboarding.html` lines 443–461; `app/src/main/res/layout/activity_bouncer.xml` (pageTone); `app/src/main/res/layout/activity_main.xml` line 812 ("Friend and Formal removed — 2 tones only")
**What:** The HTML onboarding presents Casual, Professional, Friend, and Formal (4 cards). The native Bouncer pageTone offers Work, Personal, Both — a completely different schema. The main dashboard comment confirms "Friend and Formal removed — 2 tones only." The HTML is three product decisions behind. A user who goes through HTML onboarding will expect 4 tones, then arrive in an app that shows only Casual and Professional. This is a functional trust break, not merely visual.
**Fix:** Update Tile 4 in onboarding.html to show only the current 2 tones (Casual / Professional) with their correct descriptions ("text msg energy" / "email clean").

---

## P1 — Notable Issues

### P1-1: Hardcoded cream hex in activity_main.xml status labels instead of color token
**File:** `app/src/main/res/layout/activity_main.xml` lines 1021, 1075, 1129, 1182 (overlayPermStatus, accessibilityPermStatus, micPermStatus, batteryPermStatus, notifPermStatus)
**What:** `android:textColor="#FFC8B48A"` is hardcoded inline for all five permission status labels ("✗ TAP TO GRANT"). This value matches `section_header` in dark mode and `bg_base` in light mode — it was probably set for dark mode only. In light mode `#C8B48A` is the background color, not a text color, so this text will be nearly invisible against the card (which is also warm paper tones).
**Fix:** Replace with `android:textColor="@color/section_header"`. In dark mode that resolves to `#C8B48A` (correct). In light mode it resolves to `#5C3A1F` (dark brown, readable on cream).

---

### P1-2: Bubble label and timer use hardcoded hex instead of color tokens
**File:** `app/src/main/res/layout/bubble.xml` lines 169, 183
**What:** `bubbleLabel` uses `android:textColor="#FF555560"` (line 169) — a cool grey with a blue tint, not in either colors.xml. `bubbleTime` uses `android:textColor="#FF9A4040"` (line 183) — approximates `chip_value`/`red_primary` but is not an exact brand token match. The bubble is the primary UI surface; hardcoded colors here mean dark mode will not adapt correctly.
**Fix:** `bubbleLabel` → `@color/text_secondary`. `bubbleTime` → `@color/chip_value`.

---

### P1-3: `twistConfirmCheck` uses hardcoded Android green `#4CAF50`
**File:** `app/src/main/res/layout/bubble.xml` line 198
**What:** `android:textColor="#FF4CAF50"` is the Material Design green, not the brand's `green_active` (`#16A34A`). These are visually distinct (Material green is brighter and more saturated). The difference is visible at any size.
**Fix:** `android:textColor="@color/green_active"`.

---

### P1-4: Onboarding bouncer logo on Page 1 has no Limelight font set in XML — relies on programmatic assignment
**File:** `app/src/main/res/layout/activity_bouncer.xml` line 32–38
**What:** The `bouncerLogo` TextView has no `android:fontFamily` attribute. The comment says "Limelight font set programmatically." This is fragile — if the Activity fails to apply the typeface before the view renders (e.g., slow inflate on low-end device), users will briefly see the WiM logo in Roboto/system font, breaking the first impression. The `finalLogo` on Page 7 (line 1164–1172) has the same gap.
**Fix:** Add `android:fontFamily="@font/limelight"` directly to `bouncerLogo` and `finalLogo` in XML. The programmatic call can remain as a safety net.

---

### P1-5: `onboarding.html` tile-3 (Layers) uses `var(--maroon)` for active layer numeral glow — orange is the active-data color
**File:** `app/src/main/assets/onboarding.html` lines 265–271
**What:** `.layer-row.active` gives the numeral a maroon (`#B55050`) glow. The brand spec says maroon is for "chip values" and orange (`#EE5A24`) is for "active data only." An actively selected layer is an active-data state, so the active indicator should pulse orange. The current maroon-glow is semantically correct per the chip-value rule, but visually inconsistent with how the native dashboard highlights selection (copper `◉` pill).
**Fix:** Change `.layer-row.active .layer-num` glow to `rgba(238,90,36,0.4)` with color `var(--orange)` to align with the active-data semantic. Or match native dashboard: remove glow and use the `◉` copper indicator instead.

---

### P1-6: Bouncer permission rows have inconsistent heights — some 34dp, one wrap_content
**File:** `app/src/main/res/layout/activity_bouncer.xml` lines 81 (34dp), 122 (wrap_content), 171 (34dp), 220 (34dp), 261 (34dp)
**What:** The Accessibility permission row (line 122) uses `wrap_content` height while all other rows use a fixed `34dp`. With a two-line subtitle (name + sub-label in a nested LinearLayout), this row will be taller than its siblings, breaking the visual rhythm of the permission grid. A user scanning down the list will see an inconsistent row gap.
**Fix:** Set the Accessibility row height to `android:layout_height="wrap_content"` consistently across all rows (and add `android:minHeight="34dp"` to preserve the baseline height for single-line rows), or set all rows to 48dp which already accounts for multi-line content.

---

### P1-7: `onboarding.html` slider uses `cubic-bezier(0.25, 0.46, 0.45, 0.94)` for tile transitions
**File:** `app/src/main/assets/onboarding.html` line 63
**What:** The horizontal slide between tiles uses a tween-style cubic-bezier easing. `brand.local.md` line 71 forbids tween easing for Mr. DNA persona motion ("Tween easing is forbidden for persona motion"). The tile transitions are not persona motion, but the animated demo on Tile 1 — the `.pulse-in` logo animation (line 349–352) — uses `cubic-bezier(0.18, 0.89, 0.32, 1.27)` which is close to a spring but is still a CSS tween, not a spring physics simulation. Since the logo IS the Mr. DNA entry point, this technically violates the rule.
**Fix:** The tile slider easing is acceptable (it's navigation, not persona). The `.pulse-in` for the WiM logo should either use a CSS animation that more closely mimics spring physics (several keyframes that overshoot and settle) or accept the current cubic-bezier as "close enough" — but document the exception.

---

### P1-8: `bubble_inner_knob.xml` uses no color tokens — fully hardcoded gradients
**File:** `app/src/main/res/drawable/bubble_inner_knob.xml`
**What:** All four layers in the knob use raw hex values (`#FF2A1E14`, `#FFA6875C`, `#FF6F5030`, `#FFC4A574`, `#FF8E6E48`). This is not a violation per se (drawables routinely use raw hex for gradients that don't map 1:1 to semantic tokens), but combined with the missing night variant (P0-3), the knob is the single biggest dark-mode gap on the primary UI surface.

---

### P1-9: Bouncer Page 7 ("You're Set") uses `bouncer_slide_6_6` indicator string on what the layout calls Page 7
**File:** `app/src/main/res/layout/activity_bouncer.xml` lines 1221–1227
**What:** `pageFinal` (Page 7 in the layout — counting pages 1, 2, 3, 4, 5, 6, 6b, 7) shows `@string/bouncer_slide_6_6`. If the Samsung page (6b) is shown, this is the 8th visible screen but still reads "6 / 6." If Samsung is hidden it is the 7th. Either way the counter is wrong the moment 6b was added. Users on Samsung devices will see "6 / 6" before the Samsung screen and then again on the final screen — a stutter in the progress arc.
**Fix:** The page indicator strings need an audit pass now that 6b exists. Either dynamically update the counter in code, or rename the string reference on `pageFinal` to match the actual visible slide index per device type.

---

## P2 — Refinements

### P2-1: `text_white` token in light colors.xml is mapped to dark brown `#1A1612`
**File:** `app/src/main/res/values/colors.xml` line 29
**What:** `<color name="text_white">#FF1A1612</color>` — the name implies white text but the value is near-black. In dark mode it resolves to `#FFFFFFFF` (line 29 of values-night/colors.xml), which is correct. The light-mode value being near-black is presumably intentional (you don't use white text on a cream background) but the token name is confusing for anyone reading the code. If any view references `@color/text_white` on a dark surface in light mode, they get dark text unexpectedly.
**Fix:** Rename to `text_on_dark` or `text_inverted` to make intent legible.

---

### P2-2: `orange_amber` dark-mode value is a fully-saturated orange `#FFA500` — not a warm amber
**File:** `app/src/main/res/values-night/colors.xml` line 19
**What:** `orange_amber` is `#FFFFA500` in dark mode vs `#FFD97706` in light mode. `#FFA500` is pure HTML orange, which is cooler and more saturated than the warm amber the token name implies. If anything uses `orange_amber` at runtime the dark mode will look jarring next to the copper/cream palette. The light value `#D97706` (warm amber) is correct.
**Fix:** Change dark-mode `orange_amber` to `#FFD97706` or `#FFCA8A04` (matches the `yellow_gold` value) to stay within the warm spectrum.

---

### P2-3: `onboarding.html` Tile 2 bubble uses text "WM" instead of "WiM"
**File:** `app/src/main/assets/onboarding.html` line 401
**What:** `<div class="wim-bubble">WM</div>` — the floating bubble in the phone mock shows "WM" without the lowercase "i". At 11px font-size this is barely legible anyway, but the brand name is "WiM" with a lowercase i. Even at small sizes the brand character matters.
**Fix:** Change to "WiM" and accept that at 11px/48dp it will be tight — or switch to the `W` initial only, which is cleaner at that size.

---

### P2-4: Bouncer intro text uses `@color/text_dim` (line 63) while page body copy elsewhere uses `@color/text_dim` — but the subtitle directly above uses `@color/text_secondary`
**File:** `app/src/main/res/layout/activity_bouncer.xml` lines 42–48 (subtitle, `text_secondary`) vs line 63 (intro, `text_dim`)
**What:** Two adjacent body-level text blocks use different tokens. `text_secondary` is `#6B5D4A` (light) / `#8A8078` (dark). `text_dim` is `#8A7D68` (light) / `#A09888` (dark). Both are secondary-level text, but the inconsistency will be perceivable as uneven contrast between the tagline and the intro paragraph — the intro appears slightly lighter than the tagline immediately above it, which reverses the expected reading hierarchy (tagline should be lighter since it is metadata).
**Fix:** Standardize body copy in the bouncer to `@color/text_secondary` for the subtitle and `@color/text_dim` for the longer intro paragraphs, which is the right hierarchy (subtitle slightly bolder, body slightly dimmer).

---

### P2-5: Missing night variants for several drawable assets used on primary surfaces
**File:** `app/src/main/res/drawable-night/` — absent: `bubble_inner_knob.xml` (P0-3), `glass_panel_bg.xml` (P0-2), `card_bg_pressable.xml` (present in night), `grille_pattern_bg.xml` (present in night — confirmed absent in night dir), `screw_gold_small.xml`, `screw_gold.xml`, `knob_tick.xml`, `pulse_ring.xml`, `pulse_ring_outer.xml`
**What:** Cross-referencing drawable/ vs drawable-night/ shows 22 files in night vs 78 in day. The screws (`screw_gold_small.xml`, `screw_gold.xml`) are gold on a dark brand plate — they may be intentionally the same in both modes since the brand plate is always dark (`brand_dark #1A1A1E`). The `knob_tick.xml`, `pulse_ring.xml`, and `pulse_ring_outer.xml` have no night variants, meaning the bubble's tick mark and pulse rings draw from the same assets in both modes. For the tick mark this is acceptable if its color is hardcoded to copper. For pulse rings it needs verification.
**Fix (scoped):** Prioritize `glass_panel_bg.xml` and `bubble_inner_knob.xml` (both P0 or P1 above). Accept screws as mode-invariant (brand plate is always dark). Audit `knob_tick.xml` and `pulse_ring.xml` colors to confirm they don't need night variants.

---

### P2-6: Bouncer Samsung page (pageSamsungSleep) padding inconsistency
**File:** `app/src/main/res/layout/activity_bouncer.xml` lines 1053–1058
**What:** `bouncer_samsung_body` uses `paddingStart="4dp"` / `paddingEnd="4dp"`, while all other body text on adjacent pages uses `paddingStart="16dp"` / `paddingEnd="16dp"`. The Samsung body text will appear flush to the card edges with no breathing room, noticeably different from the intro copy on pages 5 and 6.
**Fix:** Change the Samsung body padding to `paddingStart="16dp"` / `paddingEnd="16dp"`.

---

### P2-7: Onboarding.html `.console-bg` color `#3A1A1A` is defined locally, not from the token set
**File:** `app/src/main/assets/onboarding.html` line 36
**What:** `--console-bg: #3A1A1A` is a custom maroon-tinted dark used for the demo console. It is warmer than `var(--sunken)` (`#18181C`) and does not correspond to any token in `brand.local.md` or `values-night/colors.xml`. For a decorative console mock this is acceptable, but if the console mock ever needs to be replicated in native Android it will require a manual color match.
**Recommendation:** Document this value in brand.local.md as `console_mock_bg` or align it to `active_bg` (`#3A2020`) which is already in the token set and is visually similar.

---

### P2-8: MaterialSwitch in activity_main.xml has no tint override — will render in Material default purple/green
**File:** `app/src/main/res/layout/activity_main.xml` lines 888, 1633, 1657, 1683, 1705, 1759, 1783 (all MaterialSwitch instances)
**What:** No `app:thumbTint`, `app:trackTint`, or style override is applied to any toggle switch. Material 3 defaults will render the "on" state in your theme's primary color — which unless explicitly set to `green_active` will likely resolve to Material purple or system-accent. On a warm copper/cream dashboard this is a foreign color injection that visually breaks the palette.
**Fix:** Apply a ColorStateList tint to all switches: track off → `@color/text_secondary`, track on → `@color/green_active`, thumb → white. This can be done globally via a `style` entry in `res/values/styles.xml` targeting `Widget.MaterialComponents.CompoundButton.Switch`.

---

## Summary Table

| ID | Surface | Issue | Severity |
|----|---------|-------|----------|
| P0-1 | activity_main.xml | Geist / Josefin Sans — off-brand fonts throughout dashboard | P0 |
| P0-2 | drawable/glass_panel_bg.xml | Off-brand blue `#2A2A38`, no night variant | P0 |
| P0-3 | drawable/ | Missing bubble_inner_knob night variant — primary bubble UI | P0 |
| P0-4 | onboarding.html Tile 4 | 4 tones shown; app has 2 — trust break at first launch | P0 |
| P1-1 | activity_main.xml | Hardcoded cream hex for perm status labels — invisible in light mode | P1 |
| P1-2 | bubble.xml | Hardcoded hex for bubbleLabel / bubbleTime — no dark-mode adaptation | P1 |
| P1-3 | bubble.xml | Material green `#4CAF50` on twistConfirmCheck instead of `green_active` | P1 |
| P1-4 | activity_bouncer.xml | Limelight font not set in XML for logos — relies on programmatic apply | P1 |
| P1-5 | onboarding.html | Active layer glow in maroon; orange is the active-data semantic | P1 |
| P1-6 | activity_bouncer.xml | Inconsistent row heights (34dp vs wrap_content) on perm grid | P1 |
| P1-7 | onboarding.html | Logo pulse-in uses CSS tween, not spring — minor rule proximity issue | P1 |
| P1-8 | drawable/ | bubble_inner_knob fully hardcoded — dark mode depth loss | P1 |
| P1-9 | activity_bouncer.xml | Page counter string stale after 6b Samsung page was added | P1 |
| P2-1 | values/colors.xml | `text_white` named opposite of its light-mode value | P2 |
| P2-2 | values-night/colors.xml | `orange_amber` is `#FFA500` pure orange, not warm amber | P2 |
| P2-3 | onboarding.html | Bubble in Tile 2 shows "WM" not "WiM" | P2 |
| P2-4 | activity_bouncer.xml | Adjacent body text uses text_secondary vs text_dim inconsistently | P2 |
| P2-5 | drawable-night/ | 56 drawables lack night variants; screw/pulse ring audit needed | P2 |
| P2-6 | activity_bouncer.xml | Samsung page body padding 4dp vs 16dp on all other pages | P2 |
| P2-7 | onboarding.html | `console-bg` color undefined in token set | P2 |
| P2-8 | activity_main.xml | MaterialSwitch has no tint — will render in Material default purple | P2 |
