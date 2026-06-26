# Session log — June 26, 2026 — WiM audit verification + remediation (C2, H1–H8, mediums, lows)

Scope: George supplied a 6-audit roll-up (1 critical + 10 high + 17 medium + 20 low). This session **verified every finding against the live code** (not the audit's word) and **fixed the ones worth fixing**, committing in tiers. API-key findings (C1) were explicitly skipped per operator. **Nothing is build-verified** — no JDK/Gradle/Android SDK in this environment (this is the Termux/PRoot box on the phone; aapt2 is x86_64-only so a real `assembleDebug` can't run here). Every fix is code-review quality only.

> **HARD GATE before trusting any of this on-device:** on the Windows box run
> `gradlew.bat assembleDebug` then `gradlew.bat testDebugUnitTest`.
> Most likely break point if any: the **BubbleService / ReconstructClient seam**, where this session's edits meet the concurrent session's commits (see §5).

---

## 1. What was verified

All C/H/M/L findings were checked against the code with real line numbers. Highlights:

- **Audit was right** on every critical/high (C2, H1–H8) — line numbers accurate.
- **Audit was WRONG / overstated** on a few lows: **L13** (refuted — `WimTestReceiver.lastResult` writes are all `BuildConfig.DEBUG`-gated), **L20** (refuted — the "-38 dBFS untuned" comment was fabricated; a concurrent commit then changed it to −22), **L5** (18 sites not ~20), **L16** (~1,448 LOC not ~1,200), **M2** (3 recording-state reps, not "4 pub/sub"), **M11** (main `loadProfile` path DOES log+back up — only getters are silent), **M14** ("45 foundation markers" inflated), **L3** (AAR still actively used).

## 2. Fixes shipped (committed + pushed)

| Commit | Findings | What |
|---|---|---|
| `cc97e1c` | C2, H1, H2, H3 | catch no longer overwrites delivered text with raw; multipass async contained so a blip can't abort reconstruction; critical-token guard skips self-corrections + normalizes numeric tokens (+ RiskFlagsTest cases); `injectText` returns FIELD/CLIPBOARD/LOST, advance Injected only on FIELD |
| `6e8a1bf` | H5, H6, H7 | ASR engine errors toast; mic-start failure at L4/L1-cloud aborts instead of 30s hang; regen failure toasts |
| `c4d8f9c` | H4 | `classifyFailure()` → CONFIG/SERVER/NETWORK/UNKNOWN instead of blanket NETWORK |
| `2b6c7ba` | H8 | extracted `BubbleInjectionPort`; bubble reaches the field through a swappable port (testable, no `instance!!`) |
| `ffa338c` | M3 | Termux dev relay gated to `BuildConfig.DEBUG` |
| `650d215` | M4,M6,M7,M9,M10,M12,M15 | CPU busy-spin bail; overlay-window teardown + anim cancel; `WhisperLocalEngine.start()` re-entrancy guard; auth reroute logged; regen append-fallback logged; empty-transcription toast; corrected stale onboarding comment |
| `2528273` | L1,L2,L3,L10,L11,L15 | deleted 2 dead view classes (~560 LOC) + 6 unused PNGs; dropped lottie dep; fixed stale sherpa comment; `AudioRecorder.release()` race hardened; removed dead socket branch |

Total fixed: **all 9 high-tier + 8 medium + 6 low = 23 findings.**

## 3. NEXT-SESSION TODO (deliberately deferred — do these with a compiler)

**Refactors — real value for code health, but NOT safe blind; do on the Windows box, compile after each step:**
- **M1** — split BubbleService (3,139 LOC) / MainActivity (3,221 LOC). Lift the recording→reconstruct→inject pipeline into a UI-free class. H8's `BubbleInjectionPort` is the first brick.
- **M2** — collapse the 3 "is recording" representations (`BubbleService.isRecording`, `RecordingStateBus`, `BubbleStateBus.Listening`) onto one source of truth.
- **L14** — Service `CoroutineScope` defaults to `Dispatchers.Main`; every IO call must remember `withContext(IO)`. Footgun; change carefully.
- **L16** — ~1,448 LOC of debug tooling (`WimTestReceiver`, `agent/*`, `ConsoleLog`) lives under `src/main` and ships in release; move to `src/debug`.
- **L19** — 84 of 107 files flat in `com.wim.app`; subpackage them.

**Can't be fixed in this repo:**
- **M8** — signed-in (backend) path can silently downgrade L4→GPT-4o; the server doesn't return a downgrade flag. Fix lives in the `wim-reconstruct` Cloud Function (`lavrentiy/wim/api/`), not here.

**Skipped as not worth it (cosmetic / already-handled):** M5 (mixed HTTP), M11 (main path already handles corrupt profile), M14 (dead scaffolding), L4, L5, L7, L9 (already contained by H1), L12, L6 (real but `recycle()` is a no-op on target API).

**Operator-excluded:** C1 (API keys), M16/M17/L17/L18 (security bucket).

**Audit wrong / moot — no action:** L13, L20, L8.

## 4. Build status — OPEN

None of the 7 commits (nor the 3 concurrent-session commits) has been compiled. The new `RiskFlagsTest` self-correction/reformatting cases are the real check on the H2 logic — that test run is what proves H2 right. **Run the build before any device use.**

## 5. Concurrent-session hazard (important)

This repo is a **shared checkout edited by two Claude sessions at once.** During this session the other session landed `cc6aebd` (Lavrentiy parity: −22 dBFS, silence trim, multi-temp), `c5b2022` (max_tokens 16k, gpt-4o pinned, whisper-1 everywhere, multipass L4-only), and `df30057` (L5.5 prosodic F0/HNR). They merged line-by-line with this session's work with **no textual conflict** (verified), and one nice interaction: their "multipass L4-only" gate sits directly on top of the H1 crash-fix and reinforces it.

**Operational gotcha:** in a shared checkout, **"don't push" cannot be honored** — local commits ride up on the *other* session's next push (that's how M3 reached origin before this session pushed). If commits must be gated in future, run the two sessions on **separate branches**.

## 6. Lessons

- **The failure surface was the orchestrator, not the leaves.** Every silent-failure / data-loss bug (C2, H1, H3, H4, H5, H6, H7) lived in `BubbleService`'s post-reconstruction path; the leaf clients (WhisperClient, ReconstructClient, etc.) throw with specifics. This is why M1 (splitting BubbleService) is the highest-leverage structural fix.
- **C2 was the headline:** one `try` wrapped both the network call and all post-injection work; any throw after a good inject re-pasted raw text and toasted "failed" — and `advance(Failed)` from `Injected` was a forbidden FSM transition, proof it was never meant to fire there.
- **Verify the audit, don't trust it** — two low findings were flat wrong and several had stale line numbers.
