# WiM Brand Tokens

Authoritative source for every skill and reviewer in this plugin. If a skill's
output disagrees with this file, the output is wrong. Edit this file to retarget
the plugin at a different voice-to-intent Android app.

## Identity
- **App name:** WiM (What I Meant)
- **Positioning:** Voice-to-intent, NOT voice-to-text. "Grammarly for voice."
- **Audience term:** "speech disfluency" — NEVER write "stutter" in user-facing copy
- **Persona:** Mr. DNA — a Jurassic Park tour-guide bubble. Walks the user through
  the app with cheerful, high-confidence narration. Always present unless explicitly
  excluded.

## Type
- **Logo font:** Limelight — reserved for the WiM wordmark / Mr. DNA character elements only.
  Do NOT use for slide titles or screen headers.
  Bundled in `app/src/main/assets/limelight.ttf` (WebView) and `app/src/main/res/font/limelight.ttf`.
- **Slide/screen title font:** Geist Bold (`TitleFamily` in WimTheme.kt — `R.font.geist_bold`).
  Used for all onboarding `Text(fontFamily = TitleFamily, ...)` slide headings.
  Reason: onboarding screens don't show the WiM logo, so using the logo font on every header
  adds no brand signal and reads visually heavy (George, 2026-06-25).
- **Body font:** Plus Jakarta Sans. Bundled in `app/src/main/res/font/plus_jakarta_sans.ttf` (regular) and `plus_jakarta_sans_medium.ttf` (medium). Fallback chain: Outfit, Roboto, -apple-system, sans-serif.
- **Mono font:** JetBrains Mono. Fallback: Roboto Mono, monospace.

## Color (warm vintage paper aesthetic)

### Light mode (`app/src/main/res/values/colors.xml`)
| Token | Hex | Use |
|---|---|---|
| bg_base | #FFD4C8B0 | App background |
| bg_surface | #FFE8DDC4 | Cards |
| bg_elevated | #FFF0E8D8 | Elevated cards / raised UI |
| copper_gold | #FF8A5A2F | Sub-labels, accent strokes |
| orange_label | #FF963611 | Active data, callouts only — WCAG AA 4.65:1 on bg_base (darkened from #C94A1F for contrast) |
| section_header | #FF5C3A1F | Headers (paired with Limelight) |
| text_primary | #FF2A2420 | Body text |
| text_bright | #FF1A1612 | Emphasis |
| text_secondary | #FF6B5D4A | Secondary text |
| text_dim | #FF5A5040 | Tertiary text — WCAG AA 5.22:1 on bg_base (darkened from #8A7D68 for contrast) |
| brand_dark | #FF1A1A1E | Identity plate — stays dark in both modes |
| red_primary | #FFB91C1C | Errors, destructive |
| green_active | #FF16A34A | Success, recording-active |

### Dark mode (`app/src/main/res/values-night/colors.xml` and `onboarding.html` `:root`)
| Token | Hex | Use |
|---|---|---|
| bg | #1A1A1E | App background |
| surface | #222226 | Cards |
| raised | #2A2A2F | Raised UI |
| sunken | #18181C | Inset surfaces |
| copper | #C49A6C | Sub-labels |
| orange | #EE5A24 | Active data only |
| cream | #C8B48A | Headers |
| text | #D4D0C8 | Body |
| text_bright | #F0ECE4 | Emphasis |
| text_secondary | #908880 | Secondary text — WCAG AA 4.96:1 on bg (brightened from #8A8078 for contrast) |
| text_dim | #A09888 | Tertiary text |
| green | #16A34A | Success |
| red | #B91C1C | Errors |
| maroon | #D06060 | Chip values — WCAG AA 4.60:1 on bg (brightened from #B55050 for contrast) |

Use exactly these hex values. Do not approximate, do not round, do not "improve."

### Bubble menu surface (legacy — extraction-grandfathered 2026-05-25)
The floating bubble's menu overlay is rendered against a per-theme metal surface
(bronze hex / aluminum hex / gunmetal carbon weave) regardless of system day/
night mode, so it uses its own text palette. These three were inline in
`BubbleService` before brand discipline tightened; tokenized here so the
extraction to `BubbleMenu.kt` could land without the brand guard blocking on
pure relocation.

| Token | Hex | Use |
|---|---|---|
| menu_brand_cream_bright | #FFE6D4B0 | Active chip / on-fill value text on gunmetal |
| menu_on_fill_bronze | #FF1F1208 | Section labels + slider mini-labels on bronze hex surface |
| menu_on_fill_aluminum | #FF0A0A12 | Same role on aluminum hex surface |

Cream itself (`#FFC8B48A`) is the dark-mode `cream` token above. Do NOT use the
three legacy tokens for any new surface — extend the canonical palette instead.

### Send disclaimer (legacy — extraction-grandfathered 2026-05-25)
The "About to send" confirmation overlay (`BubbleSendDisclaimer`) is built
programmatically in Kotlin with its own palette. These three were inline in
`BubbleService.showSendDisclaimer` before brand discipline tightened;
tokenized here so the extraction could land without the brand guard blocking
on pure relocation.

| Token | Hex | Use |
|---|---|---|
| disclaimer_bg | #FF1E1E22 | Send-disclaimer dialog background |
| disclaimer_body | #FFB8B8B0 | Body text on disclaimer dialog |
| disclaimer_send_green | #FF5AD15F | "SEND IT" button label |

Disclaimer title uses the `cream` token (`#FFC8B48A`) and CANCEL uses the
existing `8A7D68` (approved). Do NOT use the three legacy disclaimer tokens
for any new surface — extend the canonical palette instead.

### Clarifier UI (legacy — extraction-grandfathered 2026-05-25)
The clarifier overlay (auto-size question word on the knob + ✓/✕ acknowledgment
flash) uses its own dark-on-light palette inline. These two were inline in
`BubbleService.showClarifier`/`dismissClarifier` before brand discipline
tightened; tokenized here so the extraction to `BubbleClarifierUI.kt` could
land without the brand guard blocking on pure relocation.

| Token | Hex | Use |
|---|---|---|
| clarifier_label_dark | #FF444450 | Question word on the bubble knob |
| clarifier_no_red | #FFCC4444 | "✕" acknowledgment color + shadow tint |

Yes-answer color uses the existing `orange_label` token (`#FF963611`); shadow
glows use the standard `FFFFFF` neutral with explicit alpha. Do NOT use the
two legacy clarifier tokens for any new surface.

### Bubble appearance (legacy — extraction-grandfathered 2026-05-25)
The bubble's idle-label color is theme-conditional. Two of the three theme
branches use approved tokens (`cream` #C8B48A on gunmetal, `bronze on-fill`
#1F1208 on bronze hex); the aluminum branch uses one legacy hex tokenized
here so the `applyBubbleTheme` extraction to `BubbleAppearance.kt` could
land without breaking pure relocation.

| Token | Hex | Use |
|---|---|---|
| idle_label_aluminum | #FF555560 | IDLE label color on aluminum bezel |

Do NOT use this legacy token for any new surface.

### Permission warning (legacy — extraction-grandfathered 2026-05-25)
When required permissions (accessibility, mic) are missing, the bubble
label flips to "FIX" / "PERM" in a vivid alert red so the user notices.
Brighter than `red_primary` so it reads on both light and dark bezels.

| Token | Hex | Use |
|---|---|---|
| permission_alert | #FFE05050 | "FIX" / "PERM" labels on the bubble when permissions are missing |

Do NOT use this legacy token for any new surface.

### Stage arc + processing (legacy — extraction-grandfathered 2026-05-25)
The bezel renders a 4-quadrant stage arc during pipeline work (transcribe →
reconstruct → verify → inject) in a theme-conditional color. Bronze gets a
warm deep maroon; aluminum needs a much darker arc to read against the
light silver bezel. The PROC label uses a bright cream-gold so it reads on
both red and green knob backgrounds during recording/processing transitions.

| Token | Hex | Use |
|---|---|---|
| stage_arc_bronze | #FF7A1F1F | Bezel stage arc on bronze theme |
| stage_arc_aluminum | #FF2A0606 | Bezel stage arc on aluminum theme |
| proc_label_cream | #FFFFD580 | "PROC" label color during processing |

Gunmetal stage arc uses the standard `cream` token (#C8B48A). Do NOT use
the three legacy tokens for any new surface.

## Permission cards — required set (5)
Cards must appear in this order so the demo can paint and listen as soon as possible:

1. **Overlay** — `SYSTEM_ALERT_WINDOW`. Settings → "Display over other apps" → enable WiM. The bubble is the app; without this, nothing draws.
2. **Accessibility** — Settings → "Installed services" → WiM. Bubble reads what you say from the active text field.
3. **Microphone** — runtime permission `RECORD_AUDIO`. Voice in.
4. **Battery optimization** — exempt WiM from Doze. Without this, the bubble dies in background.
5. **Samsung sleep / OEM kill** — Samsung-only. Settings → Battery → Background usage limits → Deep sleeping apps → remove WiM. Conditional: only show on Samsung devices.

## Animation stack
- **Lottie** (`com.airbnb.android:lottie-compose`) — illustrative motion. Mr. DNA idle states, success cues, persona expressions. Lottie JSON files live under `app/src/main/res/raw/` and are referenced via `LottieCompositionSpec.RawRes`.
- **Compose** primitives — `AnimatedVisibility`, `updateTransition`, `AnimatedContent`, `Crossfade`. Screen transitions, reactive UI changes.
- **Spring physics** — `spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)` for the Mr. DNA bubble's signature wobble. Tween easing is forbidden for persona motion.
- **Vector drawables** — icons. Never raster.

## Onboarding architecture
- **Existing implementation:** `app/src/main/assets/onboarding.html` — 6-tile WebView slider, dark-mode brushed-metal background, CSS-driven transitions.
- **Migration target:** native Compose under `app/src/main/java/com/wim/app/onboarding/`. Both implementations must use identical hex values. Compose screens use `TitleFamily` (Geist Bold) for slide headings; Limelight is logo-only.
- New screens default to extending `onboarding.html` unless the user explicitly requests Compose. Mixed mode is supported.

## Forbidden
- "Stutter" in user-facing strings → replace with "speech disfluency"
- Limelight as a slide/screen title font → use `TitleFamily` (Geist Bold) for onboarding headings; Limelight is for logo / Mr. DNA only
- Material defaults without explicit override → must look like WiM, not generic Material
- Color approximations → only the hex values listed above
- `tween` easing for Mr. DNA persona → spring only
- Generic UI defaults (per `feedback_bold_design` — push creative)

## Mobile viewport
Every screen must be mentally tested at 375dp width before being declared done
(per `feedback_always_check_mobile`).
