# WiM Android — Session Log 2026-04-21

**Status at write time:** Main is 5 commits ahead of origin, NOT pushed. APK installed on Samsung R5CWB08K38H and device-verified on Gmail + WhatsApp + Google Messages. George active on phone.

---

## What shipped today — 5 commits on `main`

```
f9de686  PhoneticMatcher: Double-Metaphone correction against user vocabulary
9026c28  Improve editable refinding; add compat test script
c356101  Bubble UX + Vosk-ready L1 infrastructure
83f8f23  Merge remote-tracking branch 'origin/backup-vosk'
56583ff  (prior HEAD) README: inline SESSION LOG 2026-04-20
```

Branch `merge/vosk-into-main` retained as a reference. `origin/main` is still at `56583ff`.

## Changes in plain terms

### 1. Vosk merged into main (was on `backup-vosk`)
`backup-vosk` merged via throwaway `merge/vosk-into-main` branch, then fast-forwarded. Five-file conflict surface resolved per `MERGE_PLAN.md`:
- `app/build.gradle.kts` — kept both Vosk AAR + Firebase deps
- `WimApp.kt` — kept both `useVosk()` and `bubbleTheme()` getters
- `BubbleService.kt` — auto-merged cleanly (Vosk changes in `startRecording`, button fix in `stopAndProcess`, color selector in `showMenu` — no overlap)
- `bubble_menu.xml` — kept both Layer selector + Color selector rows
- `MainActivity.kt` — Vosk settings card added via auto-merge

Vosk available via `use_vosk = true` pref (dev enables via ADB, or user via the new Vosk ASR card in MainActivity settings). Default OFF — existing users see no change until they opt in. Model is NOT bundled; downloaded on first enable (~40 MB for `vosk-model-small-en-us-0.15`).

### 2. Lang-pack integration (L1–L3)
`app/src/main/assets/lang_packs/{ar,de,es,fr,hi,it,ja,ko,pt,zh}.json` — mirrored from `lavrentiy/lang_packs/`. `LangPack.kt` loads the user's primary-language pack on `WimApp.onCreate` via `loadFromPrefs()`. `DisfluencyFilter.filter()` gained an optional `naturalRepeats: Set<String>?` parameter that overrides the hardcoded English/Russian default when a language-specific pack is active. BubbleService passes `LangPack.fillers()` + `LangPack.naturalRepeats()` into the filter call at the L1–L3 path.

English users see no change (no pack). Spanish users get `sí sí`, `no no`, `ya ya`, `claro claro` protection. Japanese users get `そうそう`, `はいはい`. German gets `nein nein`. And so on.

### 3. Rate-gap detector (words-per-second vs baseline)
`RateGapDetector.kt` — pure utility. `analyze(wordCount, audioDurationSec, baseline)` returns `{wps, baselineWps, ratio, gapDetected}`. Threshold: ratio < 0.6 vs user's rolling median across 30 prior sessions fires the flag. `ProfileManager` persists `wps_history` as a JSONArray and exposes `recordWps()` + `baselineWps()` with `MIN_SAMPLES_FOR_BASELINE = 10`.

`BubbleService.processText()` computes per-utterance WPS before the disfluency filter runs, records to history, and — on L2+ calls — boosts `speechSeverityMod` to 0.3 when the gap fires so the reconstruction prompt knows the ASR may have eaten content.

### 4. Disfluency preservation guard test
`DisfluencyPreservationGuardTest.kt` — 15 fixture cases covering English + Russian + Spanish + Japanese natural repeats, stutter patterns, prolongations, fillers, and mixed combinations. First run caught a real gap: WiM's `NATURAL_REPEATS` was missing the emphatic doublings (`really really`, `many many`, `much much`, `right right`, `sure sure`, `okay okay`, `just just`) that Lavrentiy's eval-build added months ago. Fixed by extending the set in `DisfluencyFilter.kt`. Android and desktop lists are ACTUALLY back in sync.

### 5. Bubble UX — button visual fix + color selector
`setBubbleProcessing()` now called at the TOP of `stopAndProcess()` so the bubble transitions to "PROC" amber the instant the stop tap is received, not after transcription finishes. Previously the bubble stayed in red-pulsing "REC" state for the entire 3–10s cloud Whisper wait, which looked like the stop button was broken.

Double-tap menu gained a "Color" row with three options: Bronze (default), Silver (aluminum), Gunmetal. Drawable variants already existed on disk since Session 6 — only the swap mechanic was missing. `applyBubbleTheme()` runs at `showBubble` and `setBubbleIdle` so the selected theme persists across recording cycles.

### 6. Gmail injection fix (applies to any modern app)
Gmail's compose view recycles its accessibility node hierarchy during the ~5–10 seconds a user is recording — the stored `activeTextField` reference goes stale, `node.refresh()` fails, and the paste dropped into a "tap a text field" toast.

Fix at `WimAccessibilityService.injectText()`: when the stored reference is stale, iterate all accessibility-visible windows (skipping WiM's own package so our bubble window isn't confused for the target), try `root.findFocus(FOCUS_INPUT)` on each, and fall back to a recursive descent searching for any `isEditable && isFocused` node. Also reassign `activeTextField` + re-detect password state when re-found.

Verified working on Gmail, WhatsApp, Google Messages.

### 7. PhoneticMatcher — Double-Metaphone post-hoc correction
`PhoneticMatcher.kt` — 130 lines. `DoubleMetaphone` (Apache Commons Codec 1.17.1, +300KB to APK) used to find phonetic neighbors of ASR output in the user's learned vocabulary. Targets only words whose onset is in the user's personal HIGH_RISK_ONSETS (from `PhoneticRisk.extractOnset` + `ProfileManager.getOnsetWeights`). Empty onset map = match all onsets (new user fallback). Preserves case pattern (`smyth` → `smith`, `SMYTH` → `SMITH`).

Wired into `BubbleService.processText()` at both:
- L1 path: after `decayStaleCorrections`, before inject
- L2+ path: after client-side correction substitution, before the GPT call

Catches "dunken" → "Dankeschön", "wisper" → "Wispr", "smyth" → "Smith" without requiring an explicit user-correction pair. Reduces GPT round-trip cost at L2+.

### 8. Compat test scripts (scripts/)
`scripts/compat_test.sh` — user-assisted compatibility runner. For each test: dumps UI before, fires `WimTestReceiver.inject_raw`, dumps after, checks if the test phrase landed, captures logcat, records PASS/FAIL/SKIP with reason in `compat_results/results.md`. 18 apps in the preset matrix.

`scripts/compat_auto.sh` — fully-automated variant. Launches each installed app via `monkey`, waits for UI to settle, looks for a visible editable field on the main screen, taps + tests + verifies. **Result of today's run: all 12 installed apps SKIPPED** — none of them open directly to an editable compose view. Messaging apps open to chat lists, feed apps open to feeds. Auto-running through the preset matrix is only useful for apps that expose compose via a launchable intent — rare. User-assisted path (compat_test.sh with per-app navigation) remains the practical route for coverage.

## Tests
**224 unit tests, 0 failures, 0 errors.** Growth from 213 at session start:
- PhoneticMatcherTest — 10 tests (Robolectric runner for Log.i stubbing)
- DisfluencyPreservationGuardTest — 1 test with 15 fixture cases

All via `./gradlew.bat testDebugUnitTest`.

## Device verification (Samsung R5CWB08K38H)
- Vosk engine loads, no chirp on start or stop, partials stream live in transcript strip, lowercase unpunctuated text pastes
- Button fix: bubble → PROC amber on stop tap instead of staying red
- Gmail compose field paste works after the window-walker fix
- WhatsApp + Google Messages paste works
- Bubble + accessibility services both running post-install
- 68 sessions of history preserved across reinstall

## Rules saved to memory this session
- `feedback_no_defensive_bug_framing.md` — when diagnosing a bug, do NOT add "pre-existing / not my fault / was there before" framing. Diagnose and fix. History lessons are CYA.

## Wrong calls / corrections this session
1. **Claimed Vosk supports runtime hot-word biasing.** Wrong — Vosk's `Recognizer(model, rate, grammarJson)` is grammar-constraint mode (command-and-control), NOT soft bias. Corrected mid-sprint: the post-hoc PhoneticMatcher achieves the same user-facing effect without needing decoder-level biasing that Vosk doesn't expose.
2. **Defensive "pre-existing bug" framing on the Gmail injection fix.** George flagged; memory rule saved.
3. **Asked George for source of his "steal" ideas** — but he gave them to me, so the question was nonsense. Memory pattern: don't ask clarifying questions that re-expand scope when the task is clear.
4. **compat_auto.sh overpromise.** Claimed generic auto-testing across the preset matrix would give coverage; in practice 12/12 apps SKIPPED because they don't open to compose views. User-assisted path is the real coverage mechanism.
5. **Time estimates still inflated** — George teased: "your 90 minutes is three minutes" and "one day of work is usually around eight minutes." Rule: `feedback_dont_overestimate_time.md` exists; apply it harder.

## Landmines still active (from prior session logs, not new)
1. Play Store copy at `wim-android/play_store/*.md` still uses "stutter/stuttering" — MUST rewrite to "speech disfluency" before Play Store submission.
2. `64f418f` whisper.cpp skip-overlap patch unverified under rapid-fire conditions.
3. `add-qwen3-asr` APK never installed on device.
4. `feat/l4-prompt-rewrite` — shell #3 status unclear at end of prior session.
5. Phase-4 ears benchmark at `lavrentiy/bench/_phase4_ears_benchmark.py` — never run against the three ears branches.

## Open compatibility matrix — apps waiting for user-assisted verification
Already validated: Gmail, WhatsApp, Google Messages. Still to test (run `./scripts/compat_test.sh <label>` after opening the app to its compose view):
- telegram, signal, discord, fb-messenger, instagram-dm, linkedin-dm, reddit, outlook, samsung-messages

## Next-Claude punch list

1. **Confidence gate + lang-pack match** — 2-factor filter: strip repetitions only when ASR confidence is HIGH AND text doesn't match the lang-pack emphatic list. Uses Whisper `avg_logprob` and Vosk per-word confidence. Next item after PhoneticMatcher lands.

2. **sherpa-onnx AAR + punctuation restoration** — biggest visible UX win over Vosk's current lowercase-unpunctuated output. Framework-in-place also unlocks Moonshine later.

3. **Moonshine Tiny as optional English engine** — MIT-licensed 34 MB model, RTF ~0.05 claimed on mobile, rides same sherpa-onnx AAR as (2).

4. **Phonetic LM for N-best rescoring** — trained on user's session history, phonetic-neighbor-aware (not semantic). Biggest payoff, biggest engineering.

5. **Phase-1 benchmark runner for desktop** — English-only Vosk vs cloud Whisper against `~/.lavrentiy/audio_archive/`. Multilingual blocked on audio corpus.

## File pointers
- Script directory: `wim-android/scripts/` — `compat_test.sh`, `compat_auto.sh`
- Results: `wim-android/compat_results/results.md` (single test + SKIP rows)
- Main design doc: search session history or see recent Claude output (design was generated in-session, not committed to docs/)
- Merge roadmap: `wim-android/MERGE_PLAN.md` (now partially executed — Vosk half merged; whisper.cpp half still pending per user preference)

## Quotes for next Claude's calibration
- "Was this sentence so important to be included?" — about "this is a pre-existing bug" framing
- "I'm not getting it. It's just so fucking confusing, like on purpose" — about my "ping me the pointer" ask when he'd given me the ideas
- "your 90 minutes is three minutes... one day of work is usually around eight minutes" — ongoing tease about inflated estimates
- "begin the countdown" — release phrase, means execute the sprint without further discussion

## Session end state
- Main at `f9de686`, 5 commits ahead of origin (not pushed)
- Uncommitted tree: compat_auto.sh, compat_results/results.md, minor test file formatting (D.PreservationGuardTest.kt has a whitespace diff)
- Bubble + a11y running on Samsung R5CWB08K38H
- Vosk toggle ON
- APK installed is current
