# WiM Onboarding Brand Audit — 2026-05-10

Source of truth: `onboarding-plugin/brand.local.md`
Files audited:
- `app/src/main/assets/onboarding.html`
- `app/src/main/java/com/wim/app/BouncerActivity.kt`
- `app/src/main/java/com/wim/app/OnboardingActivity.kt`
- `app/src/main/res/layout/activity_bouncer.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/strings.xml`

---

## P0 — must fix

**P0-1**
`onboarding.html:103` — `.tile-title` class uses `font-family: 'Outfit', sans-serif`.
This class is the heading element on Tiles 2–5 ("Your voice, any app", "4 layers of intelligence", "Your tone, your rules", "It learns you", "Almost there").
Expected: `font-family: 'Limelight', cursive` per brand.local.md "Display font: Limelight … key headers."
Outfit is the body font. Using it on headings is a P0 violation.

**P0-2**
`onboarding.html:429` — Layer IV label reads `"Stutter"` in visible, user-facing markup:
```
<div class="layer-name">Stutter</div>
```
brand.local.md: "Audience term: 'speech disfluency' — NEVER write 'stutter' in user-facing copy."
Expected: `"Speech Disfluency"` (or equivalent approved label).

**P0-3**
`activity_bouncer.xml:346`, `448`, `576`, `800`, `933`, `1054` — All 6 page-heading TextViews in BouncerActivity use `android:fontFamily="sans-serif-light"`.
These are the primary heading elements on pages 2–7 (pageTier, pageTone, pageBubble, pageRecon, pageAutoSend, pageFinal).
Expected: `android:fontFamily="@font/limelight"` + typeface set programmatically (as is already done for `bouncerLogo` and `finalLogo`).
`sans-serif-light` is a system font — exactly the "Material defaults without explicit override" violation flagged in brand.local.md.

---

## P1 — should fix

**P1-1 — Mr. DNA absent from entire onboarding.html**
No `<div class="persona">`, no `MrDnaBubble(...)`, no persona element exists anywhere in `onboarding.html` (all 6 tiles).
brand.local.md: "Persona: Mr. DNA … Always present unless explicitly excluded."
The legacy WebView flow has zero persona presence — not even a static bubble glyph.
Flag: confirm intentional exclusion or add persona to at minimum Tile 1 (welcome) and Tile 6 (permissions).

**P1-2 — Mr. DNA absent from all 7 pages of BouncerActivity**
`activity_bouncer.xml` has no persona element on any of the 7 pages (pagePermissions through pageFinal).
Same requirement applies to the active (native) onboarding path.
Flag: same question — intentional or missing?

**P1-3 — OnboardingActivity is dead code but still declared and compiled**
`AndroidManifest.xml:43–46` declares `OnboardingActivity` with `android:exported="false"`.
`MainActivity.kt:95–97` routes first-run to `BouncerActivity` exclusively.
No code path in the app starts `OnboardingActivity`.
`OnboardingActivity` sets `onboarding_complete = true` (line 125) — if it were somehow reachable, it would complete onboarding without going through BouncerActivity's tier/tone/auto-send pages, leaving those prefs unset.
Recommendation: remove `OnboardingActivity` from Manifest and delete the class, or add an explicit comment that it is intentionally kept as a dead fallback.

**P1-4 — Samsung deep-sleep gate missing from both onboarding paths**
brand.local.md permission set (item 5): "Samsung sleep / OEM kill — Samsung-only. Settings → Battery → Background usage limits → Deep sleeping apps → remove WiM. Conditional: only show on Samsung devices."
`BouncerActivity.kt` tracks 5 permissions: overlay, accessibility, mic, battery, notifications. Samsung sleep is absent.
`onboarding.html` fallback JS (lines 721–728) lists: overlay, mic, accessibility, battery, notifications. Samsung sleep absent.
Neither path implements the Samsung-specific intent nor the `Build.MANUFACTURER == "samsung"` conditional.

---

## P2 — polish

**P2-1 — onboarding.html animation: `.wim-bubble` uses CSS `ease-in-out` tween, not spring**
`onboarding.html:245`: `@keyframes float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }`
Applied with `animation: float 3s ease-in-out infinite` (line 243).
brand.local.md: "Spring physics … for the Mr. DNA bubble's signature wobble. Tween easing is forbidden for persona motion."
This is WebView, not Compose, so Compose spring APIs are unavailable — but the float animation on the `.wim-bubble` element is the nearest equivalent and uses a tween curve.
Flag for awareness; full spring in WebView would require a JS physics library.

**P2-2 — onboarding.html tile-title has no max-width at 375dp**
`.tile-title` (line 102): no `max-width` set. At 375dp with 28px padding per side the usable width is ~319dp.
Title "4 layers of intelligence" (Tile 3) is 27 characters. At 22px/700 weight it will likely wrap to 2 lines. With Limelight applied (P0-1 fix) the wrap risk increases — Limelight is a display font with tighter tracking than Outfit.
Recommend: add `max-width: 300px; margin-left: auto; margin-right: auto;` to `.tile-title`.

**P2-3 — BouncerActivity page headings: no max-width guard at 375dp**
`activity_bouncer.xml` page headings at 22sp without `maxWidth`. Same wrap risk as P2-2 at narrow viewport.

---

## Manifest / entry-point determination

**Active entry point:** `MainActivity` (LAUNCHER activity) → on first run (`onboarding_complete == false`) routes to `BouncerActivity` and calls `finish()`. BouncerActivity is the active onboarding.

**OnboardingActivity status:** Dead code. No code path starts it. It remains declared in `AndroidManifest.xml:43–46` with `exported="false"`, which means it cannot be started by external intents but can be started by internal code. Since no internal code does so, it is unreachable at runtime. The class and manifest entry should be removed.

---

## Clean checks

- All hex values in `app/src/main/res/values/colors.xml` match `brand.local.md` exactly (alpha channels present, no off-by-one values).
- No user-facing "stutter" strings in `strings.xml` (English).
- `bouncerLogo` (line 41) and `finalLogo` (line 139) in `BouncerActivity.kt` correctly use Limelight via `Typeface.createFromAsset`.
- `onboarding.html` correctly loads Limelight via `@font-face` and applies it to `.logo` (line 144) and `.dash-titlebar-logo` (line 164).
- `onboarding.html` dark-mode CSS variables match `brand.local.md` dark-mode token table exactly.
- BouncerActivity implements overlay, accessibility, mic, battery, notifications — 4 of the 5 canonical gates (Samsung sleep is the missing fifth — flagged P1-4).
- No `colorScheme.*` Material defaults found in either onboarding Kotlin file.
