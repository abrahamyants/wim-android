---
name: add-animation
description: Adds animation to a WiM onboarding screen — Lottie sequences for the Mr. DNA bubble persona, Compose AnimatedVisibility/updateTransition/AnimatedContent for screen transitions and reactive UI, or spring-physics motion for the bubble's signature wobble. Triggered when the user asks to animate an element, add a Lottie file, make something bounce/wobble/fade, animate a transition, or "make it move." Always uses spring physics for the persona; tween easing is forbidden for Mr. DNA motion.
---

# add-animation

Add animation to onboarding UI using the right primitive for the job.

## Decision tree
```
Is this Mr. DNA's body motion (wobble, breathing, idle sway)?
  → Spring physics. NEVER tween. NEVER Lottie for this — it's interactive physics.

Is this an illustrated sequence (Mr. DNA waving, a permission flow demo, a hand pointing)?
  → Lottie JSON. Place under app/src/main/res/raw/, reference via LottieCompositionSpec.RawRes.

Is this a UI transition (screen-in, fade, content swap)?
  → Compose: AnimatedVisibility, AnimatedContent, Crossfade, or updateTransition.

Is this in the WebView (onboarding.html)?
  → CSS transitions for UI; Lottie via lottie-web for persona sequences. JS in onboarding.js.
```

## Trigger phrases
- "animate the bubble doing X"
- "add a Lottie for Y"
- "make this fade in / slide in / crossfade"
- "give the bubble a wobble"
- "transition between [card A] and [card B]"
- "make it spring"

## Lottie integration (Compose)

```kotlin
@Composable
fun MrDnaBubble(mood: MrDnaMood) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(when (mood) {
            MrDnaMood.Idle      -> R.raw.mrdna_idle
            MrDnaMood.Excited   -> R.raw.mrdna_excited
            MrDnaMood.Curious   -> R.raw.mrdna_curious
            MrDnaMood.Satisfied -> R.raw.mrdna_satisfied
        })
    )
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        modifier = Modifier.size(160.dp)
    )
}
```

Required dependency: `implementation("com.airbnb.android:lottie-compose:6.4.0")` in `app/build.gradle.kts`. If the Lottie JSON file does not exist in `res/raw/`, output a TODO and stop — do not invent one. Lottie generation is a designer task; the plugin can prepare the binding but never the asset.

## Lottie integration (WebView, onboarding.html)

```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/bodymovin/5.12.2/lottie.min.js"></script>
<script>
  lottie.loadAnimation({
    container: document.querySelector('.persona[data-mood="excited"]'),
    renderer: 'svg',
    loop: true,
    autoplay: true,
    path: 'mrdna_excited.json'  // bundled in assets/
  });
</script>
```

Bundle the JSON in `app/src/main/assets/`. Same rule: never fabricate Lottie JSON.

## Spring physics for Mr. DNA wobble (Compose)

```kotlin
val bubbleOffset by animateDpAsState(
    targetValue = if (isPressed) 4.dp else 0.dp,
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
)
```

Damping ratios:
- `DampingRatioHighBouncy` — flamboyant, use sparingly
- `DampingRatioMediumBouncy` — Mr. DNA default
- `DampingRatioLowBouncy` — tight, almost flat

Stiffness:
- `StiffnessHigh` — fast snap
- `StiffnessMedium` — Mr. DNA wobble
- `StiffnessLow` — lazy, organic
- `StiffnessVeryLow` — drift

## Compose transitions

```kotlin
AnimatedVisibility(
    visible = isCardShown,
    enter = slideInVertically { it } + fadeIn(),
    exit  = slideOutVertically { -it } + fadeOut()
) {
    PermissionCard(...)
}

AnimatedContent(
    targetState = currentScreen,
    transitionSpec = {
        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
    }
) { screen ->
    when (screen) { /* … */ }
}
```

## CSS transitions (WebView)
The existing `onboarding.html` uses `transition: transform 0.5s cubic-bezier(0.25, 0.46, 0.45, 0.94)` for tile slides. Match that easing for any new tile-level transitions to keep the slider feel consistent.

For persona micro-motion in WebView, prefer Lottie over CSS keyframes — keyframes drift visually from the Compose spring once you migrate.

## Quality bar
- Persona motion: spring only, never tween
- Lottie JSON files referenced must exist on disk — verify with Glob/Read before declaring done
- 60fps target — avoid animating layout properties (`width`, `height`, `top`, `left`); animate `transform` and `opacity`
- Hand off to `animation-validator` agent for review

## Handoff
Invoke `animation-validator` after the animation is in place. It checks for tween-on-persona, missing Lottie assets, layout-property animation, and frame-rate hazards.
