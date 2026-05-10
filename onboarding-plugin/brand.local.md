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
- **Display font:** Limelight (gold treatment for the WiM logo and key headers).
  Bundled in `app/src/main/assets/limelight.ttf` (WebView) and `app/src/main/res/font/limelight.ttf`
  (native — verify presence before referencing in Compose).
- **Body font:** Outfit. System fallback chain: Roboto, -apple-system, sans-serif.
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
- **Migration target:** native Compose under `app/src/main/java/com/wim/app/onboarding/`. Both implementations must use identical hex values and the same Limelight headers.
- New screens default to extending `onboarding.html` unless the user explicitly requests Compose. Mixed mode is supported.

## Forbidden
- "Stutter" in user-facing strings → replace with "speech disfluency"
- Roboto / Inter / SF Pro as display font → Limelight for headers, full stop
- Material defaults without explicit override → must look like WiM, not generic Material
- Color approximations → only the hex values listed above
- `tween` easing for Mr. DNA persona → spring only
- Generic UI defaults (per `feedback_bold_design` — push creative)

## Mobile viewport
Every screen must be mentally tested at 375dp width before being declared done
(per `feedback_always_check_mobile`).
