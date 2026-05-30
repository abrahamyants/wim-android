# WiM: Voice typing built for speech disfluencies

## What WiM does

WiM (What I Meant) is a voice-typing app for Android, designed for people whose speech includes blocks, repetitions, prolongations, false starts, or fillers — and for anyone who finds standard dictation rushed and inaccurate.

You tap a floating bubble. You speak however you naturally speak. WiM cleans up the transcript and drops the polished text into whatever app you're typing in — Gmail, WhatsApp, Messages, Signal, Slack, anything with a text field.

## Who it's for

- People who stutter or have other speech disfluencies who want a dictation tool that doesn't time out mid-block
- Speech-language pathologists looking for a tool to recommend to clients
- People with accents that mainstream dictation engines mis-transcribe
- Anyone who'd rather speak than type, without the friction of pausing perfectly

## How the layers work

WiM has four cleanup layers. You pick how much help you want.

- **Layer 1 — Transcribe only.** Fully on-device, fully offline, free, zero data leaves your phone. Uses a small local speech model (whisper-tiny.en, ~40 MB) and a deterministic disfluency filter.
- **Layer 2 — Reconstruct.** Adds cloud-based grammar cleanup and tone shaping (Casual / Professional / Friend / Formal).
- **Layer 3 — Profile.** Adds your personal vocabulary, learned corrections, and voice patterns the app learns over time.
- **Layer 4 — Clinical.** Cloud Claude Sonnet 4.6 with extended thinking reconstructs intent through heavier disfluencies, covert avoidance, blocks, and ASR transcription failures. Built with the speech-disfluency community in mind.

L1 is the default and free. L2 – L4 use cloud providers (OpenAI, Anthropic) for transcription and reconstruction and require an internet connection.

## Why people pick WiM

- **The microphone never times out on you mid-block.**
- **The bubble follows you across apps.** You don't have to leave the app you're typing in.
- **Auto-send taps Send for you.** When enabled, WiM clicks the host app's Send button via the accessibility framework after pasting — no extra tap.
- **A clarifier flags low-confidence words.** When the transcription is uncertain, the bubble offers tap-to-swap alternatives for individual words instead of failing silently.
- **Three bubble themes.** Bronze, aluminum, gunmetal — swap whichever feels right.
- **Twist mode.** Voice command turns the bubble into a volume knob for size and opacity.
- **Local L1 is offline-first.** No internet required for default transcription.

## Privacy

The default Layer 1 layer runs fully on your device. Nothing is transmitted, nothing is logged, no account required. If you select Layer 2, 3, or 4, audio and transcripts are sent to OpenAI and/or Anthropic for cloud processing — this is explicit, on you, and reversible by switching back to L1.

Optional Google sign-in enables cross-device sync of your learned vocabulary and corrections. You can delete all of that data from in-app Settings at any time.

Full Privacy Policy: github.com/gugosf114/wim-android/blob/main/play_store/privacy_policy.md

## Languages

WiM ships in English. Spanish, Russian, Portuguese, and French are supported for the app's own interface; voice input on L1 local is English-only at launch. Additional input languages are on the roadmap.

## Pricing

WiM is free to download and use indefinitely on Layer 1 (local, offline, fully featured for transcription). Layers 2 – 4 (cloud reconstruction, profile learning, clinical mode) are part of a one-time $3.99 unlock — no subscriptions, no in-app upsells.

## Permissions

- **Microphone** — to record your voice when you tap the bubble.
- **Display over other apps** — for the floating bubble.
- **Accessibility Service** — to read which text field you're typing into and to paste cleaned text into that field. Used only for dictation paste and (optional) Auto-Send. Never used to monitor other apps' content.
- **Foreground service** — keeps the bubble alive while you dictate.
- **Notifications** — Android 13+ requirement for foreground services.

## Built by

WiM is an independent project by an Armenian-American builder in Daly City, California. It exists because mainstream dictation tools don't handle disfluent speech and the builder needed one that does. Feedback and bug reports welcome: gugosf@gmail.com.
