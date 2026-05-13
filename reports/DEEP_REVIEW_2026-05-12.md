# Deep Review — wim-android — 2026-05-12

Audit lead: Claude Opus 4.7 (1M ctx) + 5 sub-agents (architecture critic, backend architect, comment-rot scanner, lav-wim parity, onboarding design reviewer) + Gemini 3.1-pro-preview uncorrelated second opinion.

Builds on the 2026-05-10 sweep (`DEEP_REVIEW_2026-05-10.md` + 24 raw reports under `_raw/`). 13 remediation commits landed between 2026-05-10 13:25 and 18:13; the "b" raw reports (`*_2026-05-10b.md`) reflect post-remediation HEAD. This pass:

1. **Validated** each 2026-05-10 P0/P1 against current HEAD (fixed / still open / regressed).
2. **Extended** with analyses not in the prior sweep — architecture critique, backend audit, comment-rot, parity refresh, onboarding remediation validation.
3. **Cross-checked** with Gemini 3.1-pro-preview for uncorrelated bug classes.

**Blocked:** Phase 2 e2e on Z Flip 7 via `WimTestReceiver` broadcasts — device disconnected from USB after pre-flight. Phase 4 visual regression likewise blocked. Findings below are static-source only.

---

## TOP MUST-FIX — Ranked by Production Impact

| # | Title | Severity | Source | Where |
|---|---|---|---|---|
| 1 | Firestore `daily_count` never actually resets — paying users locked out forever after first quota | **P0** | Gemini | `lavrentiy/wim/api/main.py:95-99` |
| 2 | No `WakeLock` during 60-70s L4 reconstruction — screen-off suspends CPU mid-call | **P0** | Gemini | `BubbleService.kt:1564-1898` |
| 3 | Clarifier word-swap offsets misalign — Whisper-text offsets applied to GPT-reconstructed text, corrupts user input on approve | **P0** | Claude b-report | `BubbleService.kt:4072` × `MultiPassDecoder.kt:116` |
| 4 | `turboTaxJob` ghost loop not cancelled in reconstruction catch — corrupts next recording's stage labels indefinitely | **CRITICAL** | Claude b-report | `BubbleService.kt:3795-3799` + 4094-4135 |
| 5 | Reconstructed text silently destroyed when focused field disappears mid-reconstruction — no clipboard fallback | **P1** | Gemini | `WimAccessibilityService.kt:215-219` |
| 6 | `clarifier_sensitivity = "off"` permanently sticky on installs that stored the old default — clarifier disabled forever | **P1** | Claude b-report | `WimApp.kt:101-114` + no migration at 207-286 |
| 7 | `MultiPassDecoder` last-wins logprob collision — repeated words (the disfluency target) get wrong confidence signal | **P1** | Claude b-report | `MultiPassDecoder.kt:130-137` |
| 8 | `ProfileManager` unsynchronized mutations from Firestore listener vs reconstruction read — rare `ConcurrentModificationException` | **P1** | Claude b-report | `ProfileManager.kt` (no `synchronized`/`@Volatile`/`ConcurrentMap`) |
| 9 | Hallucination filter discards "ok"/"no"/"you"/all 2-char utterances — short replies silently dropped | **P1** | Claude b-report | `BubbleService.kt:1682-1689` |
| 10 | Anthropic ET extinction — if 8000-budget thinking exhausts without emitting a text block, throws and falls back to raw input | **P2** | Gemini | `ReconstructClient.kt:728-736` |
| 11 | Mr. DNA persona absent from BOTH onboarding paths — brand spec says "always present unless explicitly opted out" | **P2** | Onboarding reviewer | `onboarding.html`, `activity_bouncer.xml` (all 7 pages) |
| 12 | Plus Jakarta Sans used 28× in `activity_bouncer.xml` but NOT in `brand.local.md` allowlist — single-PR brand violation introduced during remediation | **P2** | Onboarding reviewer | `activity_bouncer.xml`, `res/font/plus_jakarta_sans*.ttf` |

---

## Cross-Cutting Architecture — The Source of the Race-Bug Cluster

### 8 mutable booleans parallel to FSM

`BubbleService.kt` carries `isRecording`, `isClarifierActive`, `commandModeArmed`, `holdModeActive`, `facehuggerActive`, `processed`, `isRegenInFlight`, `isSendReady` as plain mutable fields — none synchronized with `BubbleStateBus`. The FSM was retrofitted, never made authoritative. Every "X and Y simultaneously active" race bug in the b-reports (WAV race during MultiPassDecoder, legacy-clarifier vs FSM clarifier, command-mode vs reconstruction, demo path violating matrix) descends from this one structural decision.

### Single highest-leverage change

Extract `RecordingSession.kt` (~600 LOC) owning the recording → reconstruction → inject lifecycle. That alone retires orphaned `turboTaxJob`, WAV race, stale-paste race, command-mode-vs-recon. All collapse to "previous session was cancelled before next one started" — a single `currentSession?.cancel()` call in `startListening`.

Full decomposition:
- `RecordingSession.kt` (~600) — one utterance lifecycle; owns wav + recon job + clarifier eval
- `BubbleUiController.kt` (~500) — view layer; observes `BubbleStateBus.state` as Flow
- `BubbleTouchController.kt` (~400) — gestures, twist, hold-mode, drag, facehugger
- `BubbleService.kt` shrinks to <500 — foreground-service lifecycle, notifications, wiring

### Reorder fix for P0 #3 — Clarifier should run *before* inject

Today's order: GPT reconstruction → inject → MultiPassDecoder (3 cloud calls, 3-6s) → clarifier flags appear over already-pasted text. This ordering *creates* the offset misalignment by design. Pre-inject, you have one canonical string (the GPT output) where word indices mean the same thing to both `Clarifier.evaluate` and `swapWordInLastInjection`. The fix isn't a Whisper↔GPT alignment step (large blast radius) — it's a reorder. Run MultiPassDecoder in parallel with the GPT call.

### `forceTo()` is corrosive

`BubbleState.kt` exposes `forceTo()` for "I know better than the matrix." Used at `BubbleService.kt:248-251` (demo bypass), `processTextForTest`, `setBubbleIdle()` at line 2101. Every site is a place where observers subscribed to `state` see an impossible transition — exactly what affect / UI observers are not designed to handle.

Replace with `@VisibleForTesting internal` legal-chain walker. If production has a legitimate jump need (e.g., recovery from `Failed`), add it to the legal matrix.

### Over-engineering smells in Phase 0-7

- **TranscriptBarController** (168 lines) — priority queue infrastructure for 5 writers when only 2 exist. STREAMING/STREAK enums never wired. Replace with 30 lines observing state Flow.
- **IdleTimer** (50 lines) — `Map<String, Runnable>` wrapper around `Handler.postDelayed` for **one caller** (`ClarifierController` with single ID `"clarifier-auto-dismiss"`). Inline.
- **SpeechRecognizerL1 interface** (111 lines incl. `WhisperApiL1` adapter) — single implementation. `WhisperLocalEngine` doesn't even implement this interface — bypassed via separate path. Prophylactic abstraction.

### `turboTax` ghost loop is a `Flow`

`while(true) { showStage(); delay(2000) }` requires someone to remember to call `.cancel()` in every error path. Replace with:
```kotlin
flow { while (true) { emit(elapsedSec); delay(2000); elapsedSec += 2 } }
    .launchIn(scope)
```
Cancellation becomes structural — automatic on scope cancel. Fixes silent_failures #1 by construction (no more "did the catch block remember to cancel?").

---

## Backend — `wim-reconstruct` Cloud Function

`lavrentiy/wim/api/` is GPT-4o-only for all four layers. Verified by grep: zero `anthropic|secretmanager|claude|sonnet|whisper-1|verbose_json` references in the source tree. Secret Manager `wim-anthropic-api-key` is decorative — function has no Anthropic codepath. `whisper-1 verbose_json` route doesn't exist on the server either.

This means **L4 Sonnet ET + L4 Whisper-1 are device-direct** (`ReconstructClient.kt:706-719` calls `https://api.anthropic.com/v1/messages` directly with `claude-sonnet-4-6` + 8000 budget). CLAUDE.md framing "signed-in path = wim-reconstruct" is L2/L3-only true. Worth correcting the primer.

### Backend findings (Claude backend agent + Gemini verification)

- **P0 Firestore `daily_count` never resets** (Gemini, VERIFIED) — `main.py:95-99`. Two `transaction.update()` calls on same ref within one transaction; the second's `firestore.Increment(1)` REPLACES the first's `daily_count: 0` reset (Firestore Python SDK transaction merge semantics). Local Python `count = 0` returns the correct day-1 numbers, but the stored value keeps growing forever. After ~`daily_limit` total calls across the lifetime of the account, every subsequent request returns 429. Live in production right now.
- **P1 No OpenAI SDK timeout** — defaults to 600s. Cloud Function timeout is 60s. Slow OpenAI call burns the function quota and returns an opaque 500. No `max_retries`, no exponential backoff, no model fallback. Fix: `openai.OpenAI(api_key=API_KEY, timeout=20.0, max_retries=2)` + CF `--timeout=30s --min-instances=1`.
- **P1 Unhandled exceptions → raw 500s no body** — `main.py:228-247` (`reconstruct_intent` call has no try/except). Client cannot distinguish retry-now from terminal failure. The `command` path at 184-208 *does* have try/except — apply the same pattern.
- **P1 Refutation of CLAUDE.md claim**: `language_code` IS consumed server-side (`main.py:241` → `reconstruct.py:614-704`). CLAUDE.md:61 "dead path while client-side multilingual is off" is stale. Multilingual L4 is unblocked server-side — only client-side 3-site pin stops it.
- **P2 Token cap 1000 truncates L4 outputs** — `reconstruct.py:881`. Long L4 utterances cut mid-sentence. Bump to 4000 for L4.
- **P2 `falcon_validate` still callable** — `reconstruct.py:724-783` dead-code reachable via `main.py:235` default `mode="SAFE"`. Burns extra GPT-4o roundtrip every SAFE call.
- **P2 No structured logging / trace IDs** — no observability. Add `logging.basicConfig(level=logging.INFO)` and emit JSON logs with `uid`, `layer`, `latency_ms`, `model`, `error_code`.
- **P3 Pin `gpt-4o` model version** — `reconstruct.py:30` defaults to bare `gpt-4o`; pin to `gpt-4o-2024-11-20` to avoid silent model drift.

### Decision needed

Leave L4 device-direct (current production — Sonnet ET works, key handling acknowledged as device-held) OR migrate to server-mediated (consume `wim-anthropic-api-key` from Secret Manager, add `anthropic` to CF requirements, route `/reconstruct?layer=4` to Sonnet). Implications either way are functional, not blocking.

---

## Confirmed FIXED since 2026-05-10 sweep

The 13 remediation commits closed these prior P0/P1 findings:

- ✓ "Stutter" in `onboarding.html:429` — replaced with "Speech Disfluency"
- ✓ `bubble.xml` zero accessibility — `contentDescription="@string/bubble_cd_root"` + `importantForAccessibility="yes"` on root + `accessibilityLiveRegion="polite"` at line 177
- ✓ `PredictionEngine.recordCompletion` adaptive cap — now `takeLast(MAX_HISTORY_PER_PKG)` at `PredictionEngine.kt:116` (was `.take(64)` keeping oldest)
- ✓ `triggerClarifierForTest` FSM transition — uses `forceTo()` at `BubbleService.kt:250` (still architecturally questionable — see `forceTo()` corrosive note above)
- ✓ Falcon parity gap — `ReconstructClient.kt:678` literally `return true`, matches `lavrentiy.py:3420`
- ✓ Tile-title font — `onboarding.html:103` now `font-family: 'Limelight'`
- ✓ Bouncer headings (now 7 pages, Samsung-sleep added) — `activity_bouncer.xml:361,467,601,857,992,1102,1256` all `@font/limelight`
- ✓ Samsung deep-sleep gate — `BouncerActivity.kt:155-171, 253-267, 286-308` conditional Samsung route

---

## Confirmed STILL OPEN — by Category

### Code Quality (from Claude b-reports, verified live in HEAD)
- P0 Clarifier offset misalignment — see TOP MUST-FIX #3
- CRITICAL turboTaxJob ghost loop — see TOP MUST-FIX #4
- HIGH MultiPassDecoder last-wins — see TOP MUST-FIX #7
- HIGH ProfileManager unsync — see TOP MUST-FIX #8
- HIGH clarifier_sensitivity sticky — see TOP MUST-FIX #6
- P1 Hallucination filter over-aggressive — see TOP MUST-FIX #9
- P1 Concurrent recording stale-text overwrite — `BubbleService.kt:1564-1764` + `3768-4096`. New recording can be clobbered by an in-flight reconstruction from the previous one.
- P1 `OkHttpClient` per command-transform call — `BubbleService.kt:3487-3489, 3518`. Unreleased thread pools accumulate.
- P1 `Bluetooth SCO BroadcastReceiver` registered but never unregistered — `BubbleService.kt:456-471`
- P1 `AudioPlaybackCallback` registered but never unregistered — `BubbleService.kt:397-406`
- P1 `processTextForTest` can fire during active recording — `BubbleService.kt:311-318`
- P1 Legacy clarifier + FSM clarifier can be active simultaneously — `BubbleService.kt:134/2306` vs FSM
- P1 `lastWavFile` race during MultiPassDecoder run — `BubbleService.kt:3878-3893` vs 1727
- P1 `Falcon` returns true on all exceptions — `ReconstructClient.kt:694-697` (stub, but pattern persists)
- P1 `ClarifierController.approve()` advances cycle on failed swap — `ClarifierController.kt:83-97`
- P1 `BubbleService.instance` TOCTOU — multiple sites
- P1 `sessionHistory.record()` logs incorrect delivery when a11y service null — `BubbleService.kt:3808-3821`
- P1 `showBubble` main `addView` lacks try/catch — `BubbleService.kt:1176`
- P1 `Clarifier.evaluate` allocates `vocabulary.map` per word — `Clarifier.kt:127` (~6000 string allocs / reconstruction)
- P1 `languageCode` hardcoded to "en" — `BubbleService.kt:3697` ignores `WimApp.appLanguage()`

### Architecture (Claude architecture critic — new findings)
- Cross-cutting: 8 mutable booleans parallel to FSM (see above)
- `forceTo()` corrosion (see above)
- 4 over-engineering targets: `TranscriptBarController`, `IdleTimer`, `SpeechRecognizerL1`, turbo-loop-should-be-Flow

### Uncorrelated (Gemini — production-impact findings)
- P0 #1 Firestore daily_count never resets — see TOP MUST-FIX
- P0 #2 No WakeLock during L4 — see TOP MUST-FIX
- P1 Inject text destroyed on focus drop — see TOP MUST-FIX
- P2 Anthropic ET extinction — see TOP MUST-FIX
- P3 (HYPOTHESIS) Firebase ID token in-flight expiration — `ReconstructClient.kt:125` + `main.py:46`. Cached token near 1h expiry + L4's 60-70s latency → server rejects with 401. Fix: `getIdToken(true)` (force refresh) before long L4 calls.

### Onboarding (Claude onboarding-design-reviewer)
- P2 Mr. DNA absent from both paths (`onboarding.html` and `activity_bouncer.xml` all 7 pages) — brand-critical
- P2 `OnboardingActivity` dead code still declared in `AndroidManifest.xml:44`
- P2 `.wim-bubble` `ease-in-out` tween at `onboarding.html:243` (spec says spring for persona motion)
- P2 NEW DRIFT: Plus Jakarta Sans 28× in `activity_bouncer.xml` + `res/font/plus_jakarta_sans*.ttf` — not in `brand.local.md`
- P3 Untracked display fonts in `res/font/` — `poiret_one.ttf`, `josefin_sans*.ttf`, `geist*.ttf` — unreferenced, APK bloat

### Comment Rot (Claude comment-rot agent — 9 lies)
1. `WimApp.kt:73-81` — "Moonshine BASE-en via sherpa-onnx is the L1 ASR" → ACTUALLY WhisperLocalEngine (`WimApp.kt:224-226`, `BubbleService.kt:1739-1763`)
2. `WimApp.kt:60-66` — "Default layer is 1 — fully-local Moonshine + Disfluency filter" → WhisperLocalEngine
3. `WimApp.kt:85-90` — references nonexistent "L1 SRC switch" in bubble menu
4. `WimApp.kt:144-150` — "Gemma local reconstruction for L2/L3" → Gemma retired Apr 24
5. `WimApp.kt:48-58` — "Anthropic API key for Falcon validator" → ACTUALLY for L4 Sonnet (`ReconstructClient.kt:586-589`)
6. `ReconstructClient.kt:655-666` — KDoc on `falconValidate` describes full routing → body is `return true`
7. `MultiPassDecoder.kt:21-25` — "L1-L3 paths do NOT call this" → called at L2+ (`BubbleService.kt:4046-4070`)
8. `Clarifier.kt:16-21` — "evaluate returns empty until Phase 2+4" → production calls landing at L2+
9. `BubbleService.kt:258-261` — "currently invoked by tests only" → production `processText` calls

### CLAUDE.md drift
- Line 37 says "Moonshine BASE-en is current L1 default" — fixed in later lines (37-38 show WhisperLocalEngine is active) but the doc is internally contradictory
- Line 54 hint "function may not yet consume [Secret Manager key]" — CONFIRMED, server has zero Anthropic codepath
- Line 61 "Backend Cloud Function doesn't yet consume `language_code`" — STALE, server DOES consume it
- Line 21 says BubbleService ~3000 lines — actually 4126

---

## Lav-WiM Parity (refresh)

Mostly clean. The three L1 commits (`258fbc2`, `8ae7bcc`, `dd8c72a`) introduced no new asymmetry — Lavrentiy already had matching auto-detect (`lavrentiy.py:7316-7351`), accent_mode polish/keep (`lavrentiy.py:2550, 7576-7584; l1_pack.py:153-201`), and pack contribution (`lavrentiy.py:1562-1563, 2827-2852`). L4 Sonnet ET matches (both call `claude-sonnet-4-6` with 8000 budget). Falcon stubs byte-equivalent. `profile_l1` consistent everywhere — no regression to bare `l1`.

Pre-existing drifts unchanged (intentional or low-priority):
- L1 local model size — Lav `large-v3-turbo` vs WiM `whisper-tiny.en` (intentional mobile-size tradeoff)
- `L1_CLOUD_ASR` default polarity — Lav ON, WiM OFF
- L2/L3 cloud ASR fallback model — Lav `whisper-1`, WiM `gpt-4o-transcribe`
- Multilingual L4 — Lav enabled, WiM soft-pinned English at 3 client-side call sites
- Internal Lav inconsistency: `lavrentiy.py:225` defaults `large-v3-turbo`; `fw_local.py:49` env default is `small.en`

One cosmetic fix: `ReconstructClient.kt:677,682` cite `lavrentiy.py:3369`, actual stub now at `lavrentiy.py:3420`. One-line edit.

---

## Recommended Fix Order

Ranked by (production impact × ease):

**Tier 1 — bleeding bugs (~2 days total):**
1. Firestore `daily_count` reset (`main.py:95-99`) — collapse to single update OR re-fetch after reset. ~10 min.
2. Acquire `PARTIAL_WAKE_LOCK` for the L4 reconstruction coroutine in `BubbleService.kt`. ~30 min.
3. `turboTaxJob` → Flow + structural cancellation. ~1 hour.
4. `clarifier_sensitivity = "off"` migration shim in `WimApp.onCreate`. ~10 min.
5. Clipboard fallback in `WimAccessibilityService.kt:215-219` no-refound branch. ~20 min.

**Tier 2 — patent-grade differentiator (~1 week):**
6. Reorder Clarifier to run pre-inject; multipass parallel with GPT call. Eliminates P0 offset misalignment by construction.
7. MultiPassDecoder `.toMap()` → `.groupBy(...).mapValues { it.value.min() }` (worst-wins). ~5 min.
8. Anthropic ET extinction: handle "no text block" by retrying with thinking disabled OR falling back to L3 GPT-4o reconstruction. ~1 hour.
9. ProfileManager synchronization — swap `MutableList`/`MutableMap` for `CopyOnWriteArrayList`/`ConcurrentHashMap`. ~30 min.
10. Force-refresh ID token before long L4 call — `authManager.getIdToken(forceRefresh = true)` if duration > 30s. ~10 min.

**Tier 3 — backend hardening (~1 week):**
11. `openai.OpenAI(timeout=20.0, max_retries=2)`. ~5 min.
12. Wrap `reconstruct_intent` in structured try/except with retriable / terminal codes. ~30 min.
13. Pin `gpt-4o-2024-11-20` in `reconstruct.py:30`. ~2 min.
14. Add JSON structured logging with `uid`, `layer`, `latency_ms`, `model`. ~30 min.
15. Token cap bump to 4000 for L4 in `reconstruct.py:881`. ~1 min.

**Tier 4 — architecture refactor sprint (~2 weeks):**
16. Extract `RecordingSession.kt` — the single highest-leverage change.
17. Extract `BubbleUiController.kt` — observe state Flow.
18. Extract `BubbleTouchController.kt` — gestures, twist, hold, facehugger.
19. Delete `TranscriptBarController` + `IdleTimer`; inline replacements.
20. Delete `forceTo()` — replace with `@VisibleForTesting` legal-chain walker.

**Tier 5 — onboarding + docs hygiene (~3 days):**
21. Re-add Mr. DNA persona to both onboarding paths (Lottie composition).
22. Decision on Plus Jakarta Sans: bless in `brand.local.md` or revert to Outfit.
23. Delete `OnboardingActivity` + manifest declaration.
24. Replace `.wim-bubble` `ease-in-out` with spring-physics-equivalent.
25. Fix all 9 lying comments — concentrate the edits in `WimApp.kt`, `MultiPassDecoder.kt`, `Clarifier.kt`, `BubbleService.kt`, `ReconstructClient.kt`.
26. Update CLAUDE.md (Anthropic-key path, `language_code` status, BubbleService line count).
27. Extend brand-guard hook to enforce font-family allowlist from `brand.local.md`.

**Tier 6 — remaining static code findings (open-ended):**
28. All the P1 cleanup items in "Code Quality" above (broadcast receiver leaks, AudioPlaybackCallback, hardcoded `"en"`, etc.).
29. Stale `lavrentiy.py:3369` citation in `ReconstructClient.kt:677,682` — change to `lavrentiy.py:3420`.

---

## Open Questions

1. **L4 path architecture**: leave Sonnet ET device-direct (current state — works) OR migrate to server-mediated (consume Secret Manager key, route `/reconstruct?layer=4` through Anthropic)? Functional choice, not blocking.
2. **L1 local model**: stay on `whisper-tiny.en`, or move to `whisper-small.en` (~91 MB) for accuracy on heavy disfluency?
3. **Plus Jakarta Sans**: bless in `brand.local.md` or revert? — **RESOLVED 2026-05-13: BLESSED.** `brand.local.md` now lists Plus Jakarta Sans as the body font with Outfit as fallback. Brand-guard treats it as canonical going forward.
4. **Mr. DNA**: re-add to onboarding now (BouncerActivity + onboarding.html), or schedule for Compose migration? — **DEFERRED 2026-05-13.** No character asset exists in the project (no Lottie under `res/raw/`, no drawable under `res/drawable*/`, no SVG anywhere). Revisit when source art is available; treat the brand-spec "always present" rule as advisory until an asset lands.
5. **OnboardingActivity**: delete or keep as legacy placeholder?
6. **Phase 2 e2e**: required for audit sign-off, or skip given the static audit's 25+ findings? My recommendation: e2e is high-value specifically for reproducing #3 (Clarifier offset) on real disfluent input — worth running once phone is back on USB.

---

## What Wasn't Run

- **Phase 2 e2e on Z Flip 7** — blocked on USB disconnect after pre-flight. `WimTestReceiver` broadcasts ready: `inject_raw`, `trigger_clarifier`, `set_pref`, etc. (16+ actions per `AndroidManifest.xml`).
- **Phase 4 visual regression on device** — likewise blocked. Plan: screenshot bubble through every state, diff vs `brand.local.md` tokens, validate Mr. DNA absence is the only persona drift.
- **Consensus pattern (second uncorrelated model)** — Gemini single opinion ran. GPT-5/o3 via Codex CLI or OpenAI API would add a third diverse perspective on the open findings.

---

*Raw inputs: `_raw/*_2026-05-10b.md` (post-remediation Claude reviewers), `_raw/onboarding_2026-05-10.md` (pre-remediation), `.jez/artifacts/brains-trust/2026-05-12-wim-audit-2nd-opinion/gemini-3.1-pro-preview.md` (Gemini findings). All file:line references validated in HEAD as of commit `5f2c177`.*
