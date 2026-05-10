---
name: design-screen
description: Generates a new WiM onboarding screen scaffold matching the warm-vintage-paper brand and Mr. DNA bubble persona. Triggered when the user asks to add an onboarding screen, intro tile, value-prop card, "what does WiM do" explainer, or any new step in the welcome flow. Outputs the WebView HTML pattern (extending app/src/main/assets/onboarding.html) and/or a Compose alternative for native migration. Always reads brand.local.md first as the authoritative token source. Hands off to onboarding-design-reviewer agent before declaring done.
---

# design-screen

Generate a complete WiM onboarding screen following the encoded brand and existing patterns.

## Mandatory prerequisite
Read `brand.local.md` (sibling to this plugin's `.claude-plugin/`) before generating anything. It is authoritative for fonts, colors, terminology, and persona. If a value is not in `brand.local.md`, ask — do not invent.

## Trigger phrases
- "add an onboarding screen for X"
- "make a new tile that explains Y"
- "design the screen where the user sees Z"
- "intro card for [feature]"
- "value-prop card for the welcome flow"

## Inputs to gather (one direct question, do not menu)
1. **Purpose** — what concept does this screen teach (e.g., "the bubble overlays other apps", "you can swap a single word")
2. **Position** — first / middle / last in flow, or "after permission card X"
3. **Output format** — WebView (extends `onboarding.html`), native Compose, or both. Default: WebView, since current onboarding lives there.
4. **Mr. DNA presence** — yes by default. User must explicitly opt out.

If the user does not specify, propose a sharp default and proceed. Do not gate on confirmation (per `feedback_jump_to_action`).

## WebView output
Append a new `<div class="tile">…</div>` block to `app/src/main/assets/onboarding.html`. Do this:

1. **Locate the slider container** — it currently has `width: 600vw` for 6 tiles. Increment to match the new tile count (e.g., `700vw` for 7 tiles).
2. **Add a new progress dot** in `.progress`.
3. **Reuse existing CSS classes** — `.tile`, `.heading`, `.body`, `.callout`, `.persona`. Never invent new classes unless the screen genuinely needs them.
4. **Limelight for the heading** — the `.heading` class already binds it. Confirm `font-family: 'Limelight'` resolves.
5. **Brand colors only** — use CSS custom properties already defined in `:root` (`--orange`, `--copper`, `--cream`, etc.). Hardcoded hex values are forbidden in tile CSS — they'd drift from `colors.xml`.
6. **Mr. DNA insertion** — if persona is on, place `<div class="persona" data-mood="[mood]"></div>` at the top of the tile. Mood values: `idle`, `excited`, `curious`, `satisfied`. The Lottie binding lives in `onboarding.js`.

## Compose output
Create `app/src/main/java/com/wim/app/onboarding/<ScreenName>Screen.kt`. Pattern:

```kotlin
@Composable
fun <ScreenName>Screen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_base))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MrDnaBubble(mood = MrDnaMood.Excited)  // omit if persona is off
        Text(
            text = "<heading>",
            fontFamily = FontFamily(Font(R.font.limelight)),
            fontSize = 32.sp,
            color = colorResource(R.color.section_header)
        )
        Text(
            text = "<body>",
            fontFamily = FontFamily.Default,  // Outfit fallback
            fontSize = 16.sp,
            color = colorResource(R.color.text_primary)
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = onContinue) { Text("Continue") }
    }
}
```

If `R.font.limelight` does not exist, output a `// TODO: add limelight.ttf to res/font/` comment and tell the user explicitly. Do not silently fall back to Roboto.

## Quality bar (self-check before handing off)
- Limelight on every header text element
- "Speech disfluency" if disfluency is mentioned at all — never "stutter"
- Mr. DNA's motion uses spring physics, not tween
- Color values match `brand.local.md` exactly (no approximations)
- Mentally render at 375dp width — does the heading wrap badly? does the persona crowd the text?
- Bold, not vanilla — per `feedback_bold_design`

## Handoff
After generating output, invoke the `onboarding-design-reviewer` agent on the modified files. Do not skip — visual review misses brand drift that a structured audit catches.
