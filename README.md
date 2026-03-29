# WiM — What I Meant

**Voice-to-intent reconstruction for Android.** Speak messy, get clean text.

WiM captures your voice, transcribes it on-device, and optionally reconstructs it through GPT to produce clean, polished text — injected directly into whatever text field you're typing in.

## How It Works

1. Enable the WiM accessibility service
2. A floating bubble appears over all apps
3. Tap the bubble to start recording, tap again to stop
4. Your speech is transcribed, cleaned, and pasted into the active text field

## Architecture

- **On-device ASR** — Android SpeechRecognizer, zero cost, zero latency, continuous listening
- **GPT reconstruction** — OpenAI API (gpt-4o-mini for L2-3, gpt-4o for L4)
- **Falcon validation** — Meaning-preservation check after reconstruction
- **Accessibility injection** — Clipboard + ACTION_PASTE into any text field
- **Floating bubble** — TYPE_APPLICATION_OVERLAY foreground service with pulse ring animations

## Layers

| Layer | Name | What It Does | Cost |
|-------|------|-------------|------|
| I | Transcribe | On-device ASR + disfluency filter | Free |
| II | Reconstruct | GPT cleans grammar, applies tone | API |
| III | Profile | + personal vocabulary and corrections | API |
| IV | Stutter | + clinical stutter reconstruction | API |

## Features

- **4 tone settings** — Casual, Professional, Friend, Formal
- **3 situation modes** — Default, High Stress, Reading
- **DAF** — Delayed Auditory Feedback (30-300ms configurable)
- **Profile learning** — Auto-learns corrections from usage, persists across sessions
- **Disfluency filtering** — Stutter fragments, word/phrase repetitions, prolongations, fillers, false starts
- **Falcon validation** — GPT meaning-check rejects bad reconstructions
- **Risk flags** — Critical token retention, length delta, short output detection
- **OCD redo detection** — Warns on 3+ consecutive similar recordings
- **Session history** — SQLite storage of all recordings with metrics
- **Firebase Auth** — Google Sign-In with backend proxy (no API key needed for consumers)

## Two API Paths

1. **Signed in with Google** — Requests go through Cloud Function backend at `bakers-agent.cloudfunctions.net`. Server holds the API key.
2. **Local API key** — Direct OpenAI calls. Enter your `sk-proj-...` key in the app settings.

## Build & Deploy

No IDE needed. From terminal:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew.bat installDebug
```

Builds and installs directly to a connected phone via USB debugging.

## Relationship to Lavrentiy

WiM is the consumer mobile face of the [Lavrentiy voice reconstruction engine](https://github.com/gugosf114/lavrentiy). Same reconstruction philosophy, simplified for phone use. WiM implements ~20% of Lavrentiy's feature surface — the core pipeline (record, transcribe, reconstruct, inject) plus Android-specific features (floating bubble, on-device ASR, Firebase auth). Lavrentiy's clinical/analytics stack (paralinguistic detection, prosodic analysis, covert avoidance, exposure scoring, etc.) is not in WiM.

## Tech Stack

- Kotlin, Android SDK 35 (min SDK 26)
- Firebase Auth + Google Identity
- OpenAI API (Whisper + GPT)
- OkHttp for networking
- SQLite for session history
- SharedPreferences for profile and settings

## Permissions

- **Overlay** — Floating bubble over other apps
- **Microphone** — Voice recording
- **Accessibility** — Text field detection + text injection
