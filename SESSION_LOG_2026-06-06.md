# SESSION LOG — 2026-06-06 (WiM-Android)

WiM-side half of a long cross-project session. The LAV-desktop + shared Cloud
Function half is in `lavrentiy/README.md` under the `## 2026-06-06` entry — read
that too. Three features landed here, all built to mirror what shipped on LAV.

## WiM commits this session
```
b65b542  feat(profile): expand WiM first-language / accent picker from 3 to all 10 packs
a3858e5  feat(quiet): auto-engage Quiet Mode for whisper-soft takes + bubble amber + Settings toggle
d4253da  feat(bridge): wire silent-block completion into the bubble clarifier (L4)
c1df178  feat(bridge): silent-block completion foundation — backend client + partial snapshot
```
(Parallel sessions interleaved their own commits — `e833d2e`, `c1f63eb` — on `main`
between these; not ours.) All four are pushed. APKs built (`assembleDebug`) and
`adb install`-ed onto the Fold (192.168.1.234, wireless-debug port rotates).

## 1. Silent-block bridging (L4) — `c1df178` + `d4253da`
When the speaker freezes mid-sentence at L4, offer ONE completion to finish it,
through the EXISTING clarifier (operator's design call: reuse the clarifier,
single pick — not a new 3-stack overlay).
- `AudioRecorder.completePartial`... no — `ReconstructClient.completePartial()`:
  partial text → up to 3 completions via the `wim-reconstruct` `complete_partial`
  action (signed-in) or direct GPT-4o (local key). Fast model, not Sonnet ET.
- `AudioRecorder.snapshotPartialWav()`: copy the in-flight take + fix header so the
  partial audio can be transcribed without stopping. (NOTE: the live handler ended
  up using the *stopped* WAV instead — snapshotPartialWav is currently unused, safe
  to remove later.)
- `BubbleService.onSilentBlockBridge()` (consumes `AudioRecorder.onSilentBlockMidSpeech`,
  registered L4-only in `startListening`): stops the take WITHOUT the normal
  transcribe→inject pipeline (mirrors the too-short abort branch in `stopAndProcess`;
  `processed=true` neutralizes a later `stopAndProcess`), transcribes the take
  (whisper-1), asks `completePartial` for ONE finish, then offers it via
  `clarifierUI.show("USE?", "Finish it for you? <sentence>")` → ✓ injects via
  `WimAccessibilityService.injectText`, ✕ types nothing.
- **Why stop-first:** the clarifier's ✓/✕ bezel taps only route while `!isRecording`
  (see the bezel tap handler ~line 1082) — so the take must be stopped before the
  offer can be tapped. This also avoids double-inject.
- **NOT verified:** the live block→clarifier→inject on the device (needs a real
  ~2s mid-utterance freeze).

## 2. Auto Quiet Mode — `a3858e5`
Mirror of LAV's auto-quiet. When the user hasn't forced Quiet Mode on, boost the
whole take before Whisper ONLY if it was whisper-soft.
- `AudioRecorder`: mean-RMS accumulator over the take + `lastTakeDbfs()` +
  `boostWavQuiet()` (post-capture high-pass + 18 dB boost on the WAV, reusing the
  manual-path DSP `applyQuietModeProcessing`).
- `BubbleService.stopAndProcess`: if `!quiet_mode` pref and `lastTakeDbfs()` is in
  −110..−38 dBFS → `boostWavQuiet(wav)` before transcribe + set `pendingQuietAutoFlash`.
- `setBubbleIdle`: flashes amber "QUIET" on the bubble label for 1.5s after a boosted
  take (operator chose bubble, not Settings — Settings is closed during dictation).
  Amber = `R.color.orange_label` (brand-guard blocked a raw hex; used the token).
- Settings: the manual Quiet toggle was hidden ("auto-detect planned") — re-added it
  (`MaterialSwitch` mirroring DAF; writes the same `quiet_mode` pref).
- **⚠ NOT TUNED — parity gap with LAV.** Cutoff is hardcoded **−38 dBFS**. On LAV,
  −34 proved too strict (George's whisper ≈ −24) and we loosened to a VALIDATED −22.
  WiM's −38 is almost certainly too strict too, and WiM's phone mic reads differently.
  WiM also only logs WHEN it engages (not every take), so to tune: add an always-log
  line, whisper-test on the Fold, read `adb logcat`, set the right value. **Next-session
  TODO.** (LAV failure #117 is the same lesson.)
- **NOT verified:** live whisper-boost + amber timing on the device.

## 3. Accent / first-language picker expanded 3 → 10 — `b65b542`
The profile overlay's "Native English? → first language" list only offered Russian/
Spanish/Mandarin + Other; all 10 L1 packs already ship in the app. Added Hindi,
Arabic, Farsi, French, German, Korean, Japanese.
- `profile_overlay.xml`: the 3-chip row → a 5-row grid of all 10 + Other.
- `strings.xml`: 7 new `profile_l1_*` labels; dropped "(more packs coming)" from Other.
- `MainActivity`: `l1LangIds`/`l1LangValues` now cover all 10 + other.
- (The onboarding *bouncer* already listed all 10 in its spinner — only the profile
  overlay was behind.) Picking a first language sets `profile_l1` = the L1-transfer
  pack AND the accent it watches for. **Distinct from dictation language** (WiM's
  dictation language rides on `app_language`, EN/RU/ES/PT/FR — unchanged this session).

## Shared Cloud Function (lives in `lavrentiy/wim/api/`, serves WiM signed-in users)
Redeployed twice today (`wim-reconstruct`): added the `complete_partial` action (for
silent-block), then `domain_packs/` (were missing on the server → finance/legal/
medical/hipaa/sf_pro were no-ops for signed-in users), `ru.json`, and None-output
crash guards. So signed-in WiM users now get those. Detail in the LAV log.

## Brand guard fired twice (working as intended)
The `wim-brand-guard` PreToolUse hook blocked: (1) "stutter" in a completion-prompt
string → changed to "disfluent repetitions"; (2) a raw amber hex `#FFC8A04E` → used
`R.color.orange_label`. Both fixed, not bypassed.

## State at session end
- WiM `main` clean + pushed (mine: `c1df178`, `d4253da`, `a3858e5`, `b65b542`).
- All three features built + installed on the Fold. None of the live ON-DEVICE
  behaviors verified by an actual whisper/block (I can't speak into the phone).
- `WimAccessibilityService.kt` had uncommitted parallel-session work earlier in the
  session; I never touched it (only call `injectText`), and it was backed out by that
  session before my later commits.

## Next-session WiM TODO
1. **Tune the auto-quiet cutoff** (−38 → real value): add always-log, whisper-test,
   read logcat, set it (LAV is validated at −22).
2. **Voice-test silent-block bridging** at L4 (freeze mid-sentence → "USE?" → ✓ types).
3. **Voice-test auto-quiet + amber** on the bubble.
4. Optional cleanup: remove the now-unused `AudioRecorder.snapshotPartialWav()`.
5. Parity check vs LAV `## 2026-06-06`: WiM dictation already covers EN/RU/ES/PT/FR via
   `app_language`; LAV just caught up with a dedicated picker + `ru.json`.
