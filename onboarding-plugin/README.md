# wim-onboarding plugin

Generates animated, interactive onboarding flows for voice-to-intent Android apps. Encodes WiM's brand tokens (Limelight, warm-vintage-paper palette, copper accents), the Mr. DNA bubble persona, the five-card permission flow, and animation primitives (Lottie + Compose + spring physics).

Lives inside `wim-android/onboarding-plugin/` so it ships and versions with the app.

## Components

| Type | Name | Purpose |
|---|---|---|
| Skill | `design-screen` | Generate a new onboarding screen (WebView tile or Compose) |
| Skill | `add-animation` | Wire Lottie / Compose / spring motion correctly |
| Skill | `permission-flow` | Generate one of the 5 canonical permission cards |
| Skill | `interactive-demo` | Real ASR + L4 demo screen — not a mock |
| Skill | `audit` | Punch-list audit of existing onboarding files |
| Agent | `onboarding-design-reviewer` | Adversarial brand check (Limelight, colors, "speech disfluency", Mr. DNA presence) |
| Agent | `animation-validator` | Persona uses spring not tween, Lottie assets exist, no layout-property animations |

## Brand tokens

Authoritative file: `brand.local.md`. Edit it to retarget the plugin at a different app — every skill and agent reads from this file.

## Activation

This plugin is project-local to `wim-android/`. Claude Code auto-discovers `.claude-plugin/plugin.json` when invoked from inside the repo. To use:

```
cd C:\Users\georg\Documents\GitHub\wim-android
claude
```

Then trigger via natural language ("add an onboarding screen for the bubble overlay permission") or by name ("/wim-onboarding:design-screen").

## Why this plugin exists

WiM's onboarding has fixed structure (5 permission cards, 1 interactive demo, ~3 value-prop screens, persona always present) with changing details. Without encoded patterns, every iteration risks brand drift — Limelight gets quietly swapped for Roboto, "stutter" creeps back in, an approximated hex value lands in `colors.xml`. The skills generate; the agents enforce.
