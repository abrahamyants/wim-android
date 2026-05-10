---
name: interactive-demo
description: Generates an interactive demo segment for WiM onboarding where the user actually speaks and the Mr. DNA bubble responds in real time, demonstrating voice-to-intent reconstruction. Triggered when the user asks to add a "talk to the bubble" moment, a hands-on demo, a "try it now" segment, or any onboarding screen where the user produces audio input. Wires up the real ASR + L4 reconstruct path used in production, not a fake mock — the demo IS the product. Hands the result through the bubble's clarifier UI to teach mechanic and value in one shot.
---

# interactive-demo

Generate an interactive demo screen where the user speaks and Mr. DNA reacts using the real ASR + reconstruction pipeline. The demo is not a simulation — it uses the same `BubbleService` and `wim-reconstruct` Cloud Function that prod uses, so what the user experiences in onboarding is what they get every day.

## Why "real, not mock"
The asymmetric trust argument that makes WiM defensible (per `project_wim_positioning`) requires the demo to *actually work*. A fake mock that always succeeds teaches nothing — the user learns the value when they speak with their real disfluency and watch the system handle it. If the real path can't be invoked at onboarding time (e.g., not signed in yet), surface that as a real prompt, not a fake bubble response.

## Trigger phrases
- "add a hands-on demo"
- "try-it-now segment"
- "let the user talk to the bubble"
- "interactive moment in onboarding"
- "screen where the user actually uses WiM"

## Architecture

```
[User speaks]
  → BubbleService.startListening()  // real recording
    → Layer 1 (Google native ASR, local) — produces raw transcript
    → Layer 4 (wim-reconstruct Cloud Function) — produces cleaned intent
  → MrDnaBubble shows raw → cleaned via clarifier UI
  → User sees the diff with their own voice
```

Onboarding must invoke `BubbleService` directly — do not duplicate the recording path. If `BubbleService` requires permissions that have not been granted yet, the demo screen must follow the permission cards in flow order.

## Inputs needed
1. **Position** — must come AFTER overlay + accessibility + microphone permission cards. Battery optimization can be deferred to after the demo.
2. **Sign-in state** — is the user already signed into Google? L4 requires it. If not, show the sign-in prompt before the demo, not as a surprise mid-demo.
3. **Demo prompt** — what should we ask the user to say? Default: "Try saying something messy — repeat a word, stumble, whatever feels natural."

## What to generate

### Compose screen (preferred)
File: `app/src/main/java/com/wim/app/onboarding/InteractiveDemoScreen.kt`

```kotlin
@Composable
fun InteractiveDemoScreen(
    onComplete: () -> Unit,
    viewModel: InteractiveDemoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_base))
            .padding(28.dp)
    ) {
        MrDnaBubble(mood = when (state) {
            is DemoState.Idle      -> MrDnaMood.Curious
            is DemoState.Listening -> MrDnaMood.Excited
            is DemoState.Done      -> MrDnaMood.Satisfied
            is DemoState.Error     -> MrDnaMood.Curious
        })
        Text(
            text = "TRY IT",
            fontFamily = FontFamily(Font(R.font.limelight)),
            fontSize = 32.sp,
            color = colorResource(R.color.section_header)
        )
        Text(
            text = "Try saying something messy — repeat a word, stumble, whatever feels natural. Tap the bubble to start.",
            fontSize = 16.sp,
            color = colorResource(R.color.text_primary),
            modifier = Modifier.padding(vertical = 16.dp)
        )
        AnimatedContent(targetState = state) { s ->
            when (s) {
                is DemoState.Idle -> RecordButton(onClick = viewModel::startRecording)
                is DemoState.Listening -> WaveformDisplay(amplitude = s.amplitude)
                is DemoState.Done -> DiffDisplay(raw = s.raw, cleaned = s.cleaned)
                is DemoState.Error -> ErrorCard(message = s.message, onRetry = viewModel::startRecording)
            }
        }
        Spacer(Modifier.weight(1f))
        if (state is DemoState.Done) {
            Button(onClick = onComplete) { Text("Got it — keep going") }
        }
    }
}
```

### ViewModel
File: `app/src/main/java/com/wim/app/onboarding/InteractiveDemoViewModel.kt`

```kotlin
@HiltViewModel
class InteractiveDemoViewModel @Inject constructor(
    private val bubbleService: BubbleServiceClient,
    private val reconstructClient: WimReconstructClient
) : ViewModel() {
    private val _state = MutableStateFlow<DemoState>(DemoState.Idle)
    val state: StateFlow<DemoState> = _state

    fun startRecording() = viewModelScope.launch {
        _state.value = DemoState.Listening(amplitude = 0f)
        bubbleService.startListening()
            .onEach { event -> /* update amplitude */ }
            .collect { result ->
                when (result) {
                    is AsrResult.Final -> {
                        val cleaned = reconstructClient.reconstruct(result.transcript)
                        _state.value = DemoState.Done(raw = result.transcript, cleaned = cleaned)
                    }
                    is AsrResult.Error ->
                        _state.value = DemoState.Error(result.message)
                }
            }
    }
}

sealed class DemoState {
    object Idle : DemoState()
    data class Listening(val amplitude: Float) : DemoState()
    data class Done(val raw: String, val cleaned: String) : DemoState()
    data class Error(val message: String) : DemoState()
}
```

The `BubbleServiceClient` and `WimReconstructClient` interfaces should already exist — search the project before stubbing them. If they don't, output the interface and tell the user explicitly that the prod-side wiring is missing.

### Diff display
The "raw → cleaned" comparison is the payoff. Use a typewriter animation on the cleaned text (300ms per character, easing out) so the user *sees* the cleanup happen:

```kotlin
@Composable
fun DiffDisplay(raw: String, cleaned: String) {
    Column {
        Text(
            text = "You said:",
            color = colorResource(R.color.text_dim),
            fontSize = 12.sp
        )
        Text(
            text = raw,
            color = colorResource(R.color.text_primary),
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Text(
            text = "What you meant:",
            color = colorResource(R.color.copper_gold),
            fontSize = 12.sp
        )
        TypewriterText(
            text = cleaned,
            color = colorResource(R.color.section_header),
            fontFamily = FontFamily(Font(R.font.limelight)),
            fontSize = 20.sp,
            charDelay = 30.milliseconds
        )
    }
}
```

## Failure modes — handle, don't hide
- **No mic permission** — re-route to permission card 3, do not crash
- **No network for L4** — show "WiM needs the internet for this — try again when you're connected." Do not pretend to succeed offline.
- **L4 rejects (auth)** — re-route to sign-in
- **Empty / silence** — bubble shows "I didn't hear anything," prompts retry

Never fake a result. Per `feedback_heirloom_failure_honesty` and the WiM equivalent, silent passes are worse than visible failures.

## Quality bar
- Real ASR + real L4 — verify by reading `BubbleService.kt` to confirm the methods exist with the signatures above
- Mr. DNA mood transitions follow user state
- Brand colors only
- Failure paths visible, not hidden
- Typewriter animation for the cleaned text (the payoff moment)

## Handoff
Run `onboarding-design-reviewer` for brand check, `animation-validator` for the typewriter timing.
