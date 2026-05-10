# Facehugger bubble positioning — feature spec

**Status:** concept articulated 2026-05-01, not yet implemented. Targeted for inclusion in the clarifier-scope provisional patent application as a dependent claim.

## Problem statement

Even WiM's creator and primary daily user instinctively reaches for the Gboard keyboard mic icon instead of the WiM floating bubble at the moment of voice input. Universal muscle-memory friction. Real adoption blocker: if the builder defaults to the OEM voice affordance, every new user will too.

## Concept — Facehugger-Send (lead)

The bubble physically jumps onto the host application's Send button when SEND_READY state enters. User tap on the bubble dispatches a synthetic tap at the same coordinates (existing `clickSendButton()` mechanism in `WimAccessibilityService`). The bubble parasitically attaches to the host app's send control — facehugger metaphor (Alien). Host app doesn't know.

- **Trigger:** `setBubbleSendReady()` fires (`BubbleService.kt:1338`)
- **Target:** `WimAccessibilityService.findSendNodeForApp(root, pkg)` already returns the Send node's screen bounds (used today by `clickSendButton()` for synthetic-tap dispatch — `WimAccessibilityService.kt:483`)
- **Mechanism:** `windowManager.updateViewLayout(bubbleView, params)` with x/y set to the Send node's center coordinates
- **Save:** pre-jump position into `preJumpX` / `preJumpY` fields on `BubbleService` before the jump
- **Exit:** SEND_READY exit (tap or 5s expire) — `revertSendReadyRunnable` snaps the bubble back to its previous position via `windowManager.updateViewLayout` with the saved pre-jump coordinates

## Related variant — IME mic position

Bubble auto-positions over the IME's voice-input mic icon (Gboard / Samsung Keyboard / etc.) whenever the IME becomes visible. Same architectural mechanism, different trigger and target.

- **Trigger:** IME visibility change (accessibility event `TYPE_WINDOW_STATE_CHANGED` for IME window, or `onWindowsChanged()` callback)
- **Target:** accessibility tree probe of the IME's mic node + bounds. Fall back to "near keyboard top-right" heuristic if the IME doesn't expose its internal nodes to external accessibility services.
- **Mechanism:** same `windowManager.updateViewLayout` with mic-node-center coordinates
- **Exit:** IME hidden — bubble returns to user's default position

Not the lead concept (Facehugger-Send is the lead), but worth shipping together as a parallel feature with the same opt-in toggle.

## Implementation gotchas

1. **Z-order vs. IME.** `TYPE_APPLICATION_OVERLAY` can sit ABOVE or BELOW the keyboard depending on Android version + flags. Verify on Samsung SM-F731U (One UI 6.x): does the bubble currently render on top of Gboard when Gboard opens? If not, fix-first item.
2. **IME accessibility node exposure.** Some IMEs hide their internal node tree from external accessibility services. Gboard typically exposes; Samsung Keyboard may or may not. Test before relying on it.
3. **User control vs. auto-position.** Users who've manually positioned their bubble (Session 11 drag-positioning) will hate it suddenly moving. Opt-in Settings toggle, default OFF in v1, default ON in v2 after watching 5-10 users without regression.
4. **Pre-jump position save.** Bubble must save its current x/y BEFORE jumping, so it can return to that position on exit. Add fields on `BubbleService`: `private var preJumpX = 0` and `private var preJumpY = 0`.
5. **Animation.** A 200-300ms animated translation (rather than instant teleport) is gentler UX. Use `ValueAnimator` against `WindowManager.LayoutParams.x` and `.y`, calling `windowManager.updateViewLayout` on each frame.

## Patent claim language (for provisional)

Both belong as **dependent claims** under the bubble-as-app architecture independent claim in `docs/patent_claim_chain.md`.

**Facehugger-Send (lead):**

> wherein, upon entering a send-ready state, the persistent overlay programmatically repositions to coincide with the screen bounds of the host application's identified send control, and upon user tap on the relocated overlay, dispatches a synthetic tap at said coordinates via the accessibility framework's gesture-dispatch API.

**IME mic position (variant):**

> wherein the persistent overlay self-positions to coincide with the host application's accessibility-discoverable voice-input control when said control becomes visible, and reverts to a user-configured default position when said control is no longer visible.

## Cross-disability framing (for grant pitch / LinkedIn)

- **Motor accessibility** (Parkinson's, ALS, tremor, spasticity, RSI): bubble is intentionally larger than the OEM send button (~72dp bezel vs typical 24–48dp send icon). Larger target = higher tap success rate.
- **Anxiety / executive function**: zero ambiguity about which control to tap. The bubble physically covers the OEM send button.
- **Cognitive load** (post-concussive, ADHD, working memory): no search for "where is send" — it's right where the bubble is, every time, in every app.
- **Speech disfluency**: composure window (5s SEND_READY) preserved; visual stability of the affordance reduces dual-task cognitive load.

## Origin note

Concept articulated by George Abrahamyants on 2026-05-01 during the auto-send disclosure conversation. Trigger: George observed his own thumb instinctively reaching for the Gboard mic icon instead of the WiM floating bubble — even as the app's creator and primary daily user. The friction is universal; the auto-positioning hijacks the muscle memory.

Facehugger metaphor (Alien) credited to George — the bubble parasitically attaches to the host app's send control, becomes the send affordance, and the user interacts with the bubble instead of the host's UI.
