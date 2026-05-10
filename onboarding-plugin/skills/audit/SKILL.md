---
name: audit
description: Audits existing WiM onboarding files (onboarding.html, OnboardingActivity.kt, any *Screen.kt under onboarding/) against brand.local.md and surfaces every drift — wrong fonts, approximated colors, "stutter" instead of "speech disfluency", tween easing on Mr. DNA, missing Lottie assets, duplicated CSS, hardcoded strings that should be string resources, missing permission status checks. Triggered when the user asks to audit, check, review, or "find what's wrong with" the onboarding flow. Reports findings as a prioritized punch list, not a wall of text.
---

# audit

Audit the WiM onboarding implementation against the encoded brand and architectural rules.

## Trigger phrases
- "audit the onboarding"
- "what's wrong with the onboarding flow"
- "check onboarding for brand drift"
- "review the existing onboarding screens"
- "find issues in the welcome flow"

## What to check

### Files in scope
- `app/src/main/assets/onboarding.html`
- `app/src/main/assets/onboarding.js` (if present)
- `app/src/main/assets/onboarding.css` (if present)
- `app/src/main/java/com/wim/app/**/onboarding/**/*.kt`
- `app/src/main/res/values/colors.xml` and `app/src/main/res/values-night/colors.xml`
- `app/src/main/res/raw/mrdna_*.json` (Lottie assets)
- `app/src/main/res/font/*` (Limelight presence)

### Drift checks (in priority order)

**P0 — brand integrity**
1. Limelight on every header. Grep for headers that use Outfit/Roboto/system default. List them with file:line.
2. "Stutter" anywhere in user-facing copy. Grep for the word in strings.xml, onboarding.html, *.kt. List file:line.
3. Color values match `brand.local.md` exactly. Grep for hex values not in the canonical list. Common drift: `#FFD4C8B0` written as `#D4C8B0` (alpha dropped) or `#D5C9B1` (rounded).
4. Mr. DNA persona present where expected. Onboarding screens that mention WiM by name without the bubble are usually missing the persona.

**P1 — animation correctness**
5. `tween()` easing applied to anything tagged Mr. DNA. Grep `tween` in `onboarding/**/*.kt` and flag every match for review.
6. Lottie compositions referenced but the JSON file does not exist in `res/raw/` or `assets/`. Glob `mrdna_*.json` and cross-check with `LottieCompositionSpec.RawRes(R.raw.mrdna_*)` references.
7. Animations on layout properties (`width`, `height`, `top`, `left`) instead of `transform`/`opacity`. CSS-only check; warn with file:line.

**P2 — architectural correctness**
8. Permission status checks missing. The onboarding flow must check `Settings.canDrawOverlays`, accessibility-enabled, mic permission, battery-optimization-ignored, and Samsung-specific status on resume. If any is missing, the "Done" state will never light up.
9. `BubbleService` not invoked from interactive demo. Demo screens that mock the response are wrong — flag them.
10. Hardcoded strings instead of `R.string.*`. Cheap to skip, expensive when localization gets added later.

**P3 — quality polish**
11. Duplicated CSS rules in `onboarding.html`. Common after iterating tile-by-tile.
12. Unused CSS classes. Grep declared `.foo` against used `class="foo"`.
13. Tile count in `.slider { width: Xvw }` mismatched with actual `<div class="tile">` count.

## Output format

Report as prioritized punch list:

```
P0 — brand integrity
  ✗ onboarding.html:142 — heading uses font-family: Outfit, should be Limelight
  ✗ OnboardingActivity.kt:88 — string "Help with stutter" should be "speech disfluency"
  ✗ colors.xml:14 — copper_gold is #FF8B5A2F, brand.local.md says #FF8A5A2F (1 hex off)
  ✓ Mr. DNA present on tile 1, 3, 4

P1 — animation
  ✗ MrDnaBubble.kt:45 — tween(durationMillis=300) used; Mr. DNA must use spring()
  ⚠ onboarding.html:201 — animating top property; switch to transform: translateY

P2 — architecture
  ✗ InteractiveDemoScreen.kt:120 — fakes ASR result with hardcoded string; must call BubbleService
  ✓ Permission status checks present for all 5 cards

P3 — polish
  ⚠ onboarding.html — 3 unused CSS classes (.legacy-card, .v1-tile, .old-cta)
```

Use `✗` for must-fix, `⚠` for should-fix, `✓` for "checked, clean."

Do not produce a wall of text. Do not write a narrative report. The user reads punch lists; they don't read essays.

## How to run

For each check, use Grep / Glob / Read directly. Do not delegate to a sub-agent — the audit IS the agent's role; nesting another agent for a Grep is waste.

If the user wants the issues fixed, ask once: "Fix the P0 items now?" — if yes, fix them and re-run the audit. Do not auto-fix without asking.

## Quality bar
- All P0 items reported with file:line
- No false positives — verify each finding by reading the actual line, not just the grep match
- Re-runs are quick — cache nothing
