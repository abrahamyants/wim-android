# Defensive Publication — Bubble-Resident Cross-Application Voice Send via Accessibility-Mediated Synthetic Tap with Two-Stage Commit

**Date of public disclosure:** 2026-05-01
**Author:** George Abrahamyants, JD
**Repository:** github.com/gugosf114/wim-android
**Reference implementation:** WiM Android, see "Reference Implementation" section below
**License:** Creative Commons Zero (CC0 1.0 Universal). The author dedicates the technical combination disclosed herein to the public domain to the maximum extent permitted by applicable law. CC0 does not grant a patent license; in addition, the author makes no patent claim and reserves no patent rights over the combination described below. This publication is intended to constitute prior art under 35 U.S.C. § 102(a) and the equivalent novelty bars of jurisdictions recognizing public disclosure.

---

## Scope Note

This disclosure concerns one specific technical combination: bubble-resident cross-application voice send via accessibility-mediated synthetic tap with a two-stage commit affordance. It does NOT concern the WiM project's separate per-word bidirectional clarifier method (see `docs/patent_claim_chain.md` for the claim-chain treatment of that distinct subject matter). The author has scoped the disclosure narrowly to preserve filing options on adjacent subject matter.

---

## Abstract

A method for cross-application voice dictation and send commit on a mobile operating system, in which a persistent system-level overlay ("bubble"):

1. captures voice input,
2. transcribes and reconstructs the input via a language model,
3. injects the reconstructed text into the focused text field of an arbitrary host application via the operating system's accessibility framework,
4. enters a time-limited "send-ready" affordance state visible on the bubble itself,
5. upon a single user tap anywhere within the bubble's outer radius during the send-ready window (where the tap is short — under 300 ms — and the bubble is not in a recording state), programmatically actuates the host application's native send control via an accessibility-mediated synthetic tap,
6. without instantiating an input method editor (IME), without registering a per-application integration, and without modifying the host application's window focus, scroll position, or cursor position other than the act of sending itself.

The combination eliminates the user's need to physically tap the host application's send button, completing the entire dictate-and-commit input loop through a single accessibility-sized affordance under explicit user control.

---

## Problem Addressed

Existing voice dictation systems on mobile operating systems terminate at text injection. The user must subsequently locate and tap the host application's native send control to commit the message. Where the host application's send control is small, distant, or context-sensitive, this terminating tap presents a nontrivial accessibility barrier for users with:

- **motor impairments** (essential tremor, Parkinson's disease, ALS, post-stroke spasticity, repetitive strain injury) where targeting small UI elements is unreliable;
- **anxiety conditions** where keyboard-and-button interaction is itself a stress trigger;
- **speech disfluencies** where the latency between dictation completion and physical commit creates exposure time during which the user may abandon the message.

Voice-assistant systems (e.g., Siri, Google Assistant, Alexa) address adjacent cases by accepting "send" as a voice command, but require host-application registration via a vendor-specific assistant SDK and operate via deep-link or intent dispatch rather than direct UI actuation, limiting coverage to a discrete set of integrated applications.

Input method editor (IME) voice keyboards (e.g., Google Voice Typing, Gboard voice input) operate inside the IME container and depend on either user keyboard tap or vendor-specific voice commands to commit; they are not bubble-resident.

Macro automation tools (e.g., Tasker, AutoInput) can simulate taps on send buttons via the accessibility framework, but require per-application user configuration of triggers and target nodes, and are not voice-input-paired or transcription-paired.

User-control concern: market discourse around the strongest commercial competitor (Wispr Flow) indicates that users actively prefer to remain the agent of the final send action, citing a desire to "be the one hitting 'send' on the final word." The disclosed combination addresses this concern directly by requiring an explicit user tap on the bubble's send-ready state during a bounded time window. The user remains the agent of the send; the disclosed combination only relocates the tap target from the host application's send control to the bubble's send-ready affordance.

---

## The Disclosed Combination

The disclosed method is the simultaneous combination of the following six elements operating together:

### 1. Bubble-resident overlay

A persistent system-level overlay window (Android `TYPE_APPLICATION_OVERLAY`, equivalent on other platforms) renders the dictation and commit affordances. The overlay maintains an independent Z-order from the host application; the host application's window focus, scroll position, and IME state are not modified by the overlay's lifecycle.

### 2. Cross-application generic targeting

The system targets the operating system's currently-focused editable text node, identified through the accessibility framework's focused-node query. No per-application registration is required. The system functions in any host application that exposes editable text nodes through the accessibility framework, including applications not known to the system at install time.

### 3. Accessibility-mediated text injection

Reconstructed text is written to the focused text field via the accessibility framework's programmatic text-write action (Android `AccessibilityNodeInfo.ACTION_SET_TEXT`, equivalent on other platforms). Text injection does not invoke clipboard paste, IME composition, or simulated keyboard events.

### 4. Send-ready affordance state on the bubble

Following successful injection, the overlay transitions into a time-limited "send-ready" visual state. In the disclosed embodiment: a 5-second window during which the bubble's label changes (to "SEND" rendered in a distinctive "ready to send" green hue, `0xFF5AD15F`) and a per-second countdown indicator appears on the bubble face. The state is visible on the bubble itself, not on the host application. Expiration of the window without user action returns the bubble to its idle state, with no send action taken.

### 5. Accessibility-mediated synthetic tap on host application's native send control

Upon a single user gesture (in the disclosed embodiment: a tap on the bubble during the send-ready window), the system locates the host application's native send control through a multi-strategy node search:

- **(a)** `AccessibilityNodeInfo.performAction(ACTION_CLICK)` on the matched node, walking up to the nearest clickable ancestor;
- **(b)** on `ACTION_CLICK` returning `false`, dispatching a synthetic touch gesture at the matched node's screen-center coordinates via the accessibility framework's gesture-dispatch API (Android `AccessibilityService.dispatchGesture()`);
- **(c)** on no node match, capturing the host application's full accessibility tree to a diagnostic log for offline matcher development.

Send-control matching is fundamentally generic. The default matcher locates any node in the host application's accessibility tree whose content-description contains "Send", "Send message", or "Send SMS" (substring match, case-insensitive). This generic mechanism succeeds for the majority of host applications that follow Android accessibility best practices, including applications not known to the system at install time.

Per-application matcher branches exist as exception handling for the small set of host applications where the generic content-description match does not succeed — most notably Google Messages, whose migration to Jetpack Compose left the Send button with an EMPTY content-description and only a Compose testTag (`Compose:Draft:Send`) as a stable identifier. Where exception handling is required, the per-application branches define a priority order of identifiers to try before falling back to the generic match. The current branches:

```
com.google.android.apps.messaging → "Compose:Draft:Send" testTag | content-desc "Send*"
com.samsung.android.messaging     → content-desc "Send*" | :id/send_btn | :id/send
com.google.android.gm             → content-desc "Send" | :id/send
com.whatsapp                      → :id/com.whatsapp:id/send | content-desc "Send"
org.telegram.messenger            → content-desc "Send*"
org.thoughtcrime.securesms        → content-desc "Send"
default (any other application)   → content-desc "Send" | "Send SMS" | "Send message"
```

Compose-framework testTag matching (e.g., `Compose:Draft:Send`) is performed via tree-walk literal comparison rather than `findAccessibilityNodeInfosByViewId()`, the latter of which expects fully-qualified `package:id/name` resource identifiers and frequently misses Compose testTags. This Compose-aware matching is the operative workaround for Google Messages and is generally applicable to any host application whose Send control is rendered through Jetpack Compose with an empty content-description.

### 6. Host-window selection precedence

The send-control search prefers, in order: (a) the editable's own window, (b) any visible window matching the editable's package, (c) any visible non-overlay non-systemui window. Naive "first non-overlay window" selection has been observed to incorrectly select the system navigation bar's window (e.g., `com.android.systemui` showing Back / Recents / Home nodes); the precedence chain prevents this failure mode.

---

## Reference Implementation

A working reference implementation is published in the WiM Android application source tree at:

**Repository:** `github.com/gugosf114/wim-android`
**Files:**
- `app/src/main/java/com/wim/app/WimAccessibilityService.kt` — `clickSendButton()`, `findSendNodeForApp()`, `findByResourceIdExact()`, host-window selection precedence
- `app/src/main/java/com/wim/app/BubbleService.kt` — send-ready state machine, bubble color transition, 5-second availability window, tap-to-send gesture handler
- `app/src/main/AndroidManifest.xml` — accessibility service declaration with `canPerformGestures="true"`

---

## Verified Operation

The reference implementation has been verified working on a Samsung SM-F731U (Android 14, One UI 6.x) in the following host applications (logcat verification dated April 2026):

- Google Messages (`com.google.android.apps.messaging`)
- Samsung Messages (`com.samsung.android.messaging`)
- Gmail (`com.google.android.gm`)
- WhatsApp (`com.whatsapp`)
- Telegram (`org.telegram.messenger`)
- Signal (`org.thoughtcrime.securesms`)

---

## Accessibility Implications

The disclosed combination provides accessibility benefits not collectively achieved by any single existing voice dictation, voice assistant, IME, or macro automation system known to the author:

- **Motor accessibility**: the bubble's send-ready affordance can be sized at any scale (the disclosed embodiment uses 72 dp); the host application's send control is typically constrained to the application's design system and may be 24–48 dp. Users with tremor, spasticity, or coordination impairments benefit from the larger and consistently-positioned target.

- **Anxiety / executive-function accommodation**: keyboard interaction is fully optional through the entire dictate-and-commit loop. Users for whom keyboard or precise UI interaction is itself a barrier complete the loop without any keyboard or host-app touch.

- **Speech-disfluency accommodation**: the send-ready countdown window provides explicit composure time at the moment of commitment, between dictation completion and message dispatch. Users who experience pre-utterance blocks or post-utterance regret-cycles benefit from a deliberate, non-rushed commit gesture distinct from the dictation gesture itself.

The disclosed combination preserves user agency over the send action: the send-ready window requires an affirmative user tap and expires without action if the user does not tap. The system does not auto-send without explicit user input.

---

## Prior Art Positioning

The author has reviewed the following adjacent prior art and concludes it does not anticipate the disclosed combination:

| Reference | Date | Relation to disclosed combination |
|---|---|---|
| Voice assistants — Siri, Google Assistant, Alexa | various | Send via vendor SDK deep-link / intent dispatch; not accessibility-mediated synthetic tap; not cross-application generic; no per-utterance review-then-commit affordance |
| Wispr Flow (Wispr AI, Inc.) — commercial product | 2024–2026 | Floating bubble dictation; IME-resident Command Mode; pastes text and requires user manual tap on host application's send control. Public market discourse confirms Wispr deliberately omits auto-send; users prefer explicit final send |
| Wispr AI patent US 12,374,317 B2 (granted July 2025; application 2024/0221741 A1) | filed Jul 25, 2023 | Covers multi-modal inputs (gestures, facial expressions, tone) combined with speech for input-system improvement. Does not concern accessibility-mediated synthetic tap on host application's send control |
| kafkasl/phone-whisper — open-source GitHub repository | created March 12, 2026 | Persistent overlay (`SYSTEM_ALERT_WINDOW`) + on-device sherpa-onnx ASR + ACTION_SET_TEXT injection + clipboard fallback. Does NOT include bubble-resident send-ready state or accessibility-mediated synthetic tap on host send control. (Note: this repository is sometimes mis-dated in earlier secondary sources to "Late 2023 / Early 2024"; primary source verification at the GitHub repository confirms March 2026 creation date) |
| Google Voice Typing / Gboard voice input | various | IME-resident; "send" is user keyboard tap or Gboard-specific voice command; not bubble-resident; not cross-application generic at the bubble layer |
| Tasker / AutoInput / equivalent macro tools | various | Can synthesize taps on send controls; requires per-application user configuration of triggers and target nodes; not voice-paired; not transcription-paired |
| US20150314454A1 — Persistent Companion Device | various | Development platform for persistent companion device with active state across environments; does not disclose accessibility-mediated synthetic tap on host application send control |
| US10692504B2 / US20120245944A1 — Active Input Elicitation | various | Active vs. passive voice agents framework; does not concern bubble-resident send-ready state or per-application synthetic-tap matchers |
| US10573312B1 — Multi-pass ASR transcription generation | various | Multi-pass disagreement for backend transcription selection; not concerning send commit |
| US5799276A — Voice recognition with word-level confidence flagging | 1996/97 | Word-level confidence flags within primary application window; not concerning bubble-resident commit affordance or cross-application synthetic tap |
| IBM US6006183 — per-word voice dictation confidence display | 1997, expired Dec 2017 | Confidence display only; not concerning send commit or accessibility-mediated synthetic tap |
| Accessibility framework patents broadly (screen reader implementations, switch-control implementations) | various | Provide programmatic UI actuation primitives; do not pair them with bubble-resident voice dictation, reconstruction, and a two-stage commit affordance |

The author makes no claim of priority over the individual elements (overlay windows, accessibility framework, voice transcription, language model reconstruction, synthetic-tap dispatch, per-application accessibility node matching). The disclosed novelty is the simultaneous combination of all six elements in section "The Disclosed Combination" above as a coherent input-and-commit method.

---

## Design-Around Pre-emption

The author acknowledges and explicitly publishes the following alternative implementations that would achieve a substantially similar user experience without instantiating the disclosed combination. This pre-emption is intended to broaden the prior-art surface so that none of these alternatives is later patented as a workaround:

1. **Custom-IME hybrid**: a competitor could build a passive custom Input Method Editor that becomes the default keyboard upon dictation, handles the SEND_READY affordance within the IME canvas, and routes the send tap through the IME's own input dispatch rather than via accessibility synthetic tap. This trades one user-permission burden (accessibility) for another (default IME selection) and is hereby disclosed as an alternative implementation of the same input-and-commit objective.

2. **Transparent Activity overlay**: a competitor could spawn a transient transparent Activity over the host application during the send-ready window, present the commit affordance within that Activity, and dispatch the send via the host application's exposed `Intent` interface or via accessibility from the Activity's process. This Activity-based variant is hereby disclosed as an alternative implementation.

3. **Voice-only commit**: a competitor could omit the visual SEND_READY state and listen for a voice command ("send", "commit", or equivalent) on the same speech-recognition channel as the dictation, dispatching the send via the same accessibility-mediated synthetic-tap chain. This voice-only commit variant is hereby disclosed as an alternative implementation.

4. **Long-press commit**: a competitor could replace the send-ready time-window with a long-press gesture on the bubble's idle state, where a sustained press past a threshold triggers the same accessibility-mediated synthetic-tap chain. This long-press variant is hereby disclosed as an alternative implementation.

5. **Notification-action commit**: a competitor could surface the SEND_READY affordance as a Notification action (Android `Notification.Action`) rather than as a bubble-face state, with the action's `PendingIntent` invoking the same accessibility-mediated synthetic-tap chain on resolution. This notification-action variant is hereby disclosed as an alternative implementation.

The author further discloses that any combination of the six core elements of section "The Disclosed Combination" with any of the above alternative commit-affordance surfaces is part of this disclosure.

---

## Authorship and AI-Assistance Note

In accordance with USPTO guidance on AI-assisted inventions (Federal Register, February 13, 2024), the author affirms significant human contribution to the disclosed combination. Specifically: the architectural decision to route send commit via accessibility framework rather than IME or Intent dispatch; the per-application send-control matchers; the host-window selection precedence chain (developed in response to an observed `com.android.systemui`-misselection failure mode); the SEND_READY two-stage commit UX rationale; the five-second availability-window tuning; and the CC0 / no-patent-reservation publication strategy were all originated by the author. AI coding assistants contributed implementation drafting and code transcription under the author's direction.

---

## License and Attribution

This disclosure is released under **Creative Commons Zero (CC0 1.0 Universal)**. The author dedicates the technical combination disclosed herein to the public domain to the maximum extent permitted by applicable law. CC0 does not grant a patent license; the author additionally and explicitly makes no patent claim and reserves no patent rights over the combination described in section "The Disclosed Combination" or in section "Design-Around Pre-emption" above.

This publication is intended to constitute prior art under 35 U.S.C. § 102(a) and the equivalent novelty bars of jurisdictions recognizing public disclosure as a novelty bar.

The reference implementation source code in the WiM Android repository is separately licensed under the WiM Android application's own license (see repository).

**Permanent record:** this file is committed to `github.com/gugosf114/wim-android/docs/auto_send_disclosure.md` on 2026-05-01. The Git commit hash provides an independently-verifiable timestamp of authorship.

---

*George Abrahamyants, JD — May 1, 2026*
