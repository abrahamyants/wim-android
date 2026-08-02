# Session log — August 2, 2026 — pipeline audit L1–L4, then the machinery to stop it recurring

Scope: an audit of the reconstruction pipeline layer by layer, then the bubble
subsystem, then the capture path. 16 commits in `wim-android`, 9 in `lavrentiy`.
All of them green in CI — this session ran `gh run watch` after every push
rather than assuming.

**The through-line: every defect found was in deterministic code.** Regexes,
guard predicates, loop selection, prompt assembly, a constant, a pref key. Not
one of them needed a live model to reproduce. That is what made the second half
of the session possible: a harness that reaches the whole class offline, for
free, in seconds.

**One thing needs a device before it is trusted.** `63154a6` rewires
`BubbleService.startRecording` to an extracted decision object. CI compiles it and
proves `AsrRoute` correct, but no test instantiates an Android Service, so the
wiring is verified by tracing all 48 configurations rather than by execution.
Record once and paste once on the phone.

---

## 1. Three bugs that were changing money

| Commit | What it did to your text |
|---|---|
| `d9af683` | `PROLONGATION` was `(\w)\1{2,200}` and `\w` includes digits, so any run of three identical digits collapsed to one. **"wire 1000 dollars" became "wire 10 dollars."** "the balance is 2000" became 20. "account 1,000" became "1,0". It runs at L1, L2 and L3, and it runs *before* the numeric guard — so the guard compared already-mangled text and could never fire. At L1 there is no guard at all. |
| `c0b9b83` / `a5c1791` | The guard only checked a figure when the output wrote it in **digits**. Keep it spelled out and any change passed: "four thousand two hundred" → "five thousand three hundred", "two billion" → "two million", "twenty copies" → "thirty copies". L4 writes prose and spells figures out routinely, so the layer that rewrites hardest was the one the check could not see. |
| `0381fce` | `checkCriticalTokens` opened with a blanket bail-out: one "I mean", "no wait" or "scratch that" anywhere in the utterance switched the numeric check off for **every** number in it. Self-corrections are constant in disfluent speech. |

The same commit pair fixed the mirror of the second one: numeric anchors were
compared as literal substrings, so a *correct* reconstruction that spelled a
figure out read as a lost anchor and the guard threw it away in favour of raw.

## 2. Text the pipeline was deleting or inventing

- `f7f66e9` — the spaced stutter-fragment regex allowed **zero** repetitions, so
  any single `word- ` was deleted: "co- founder" → "founder", "pre- existing" →
  "existing". The sibling regex one line below guards the same hazard and its
  comment names those exact words. Fixed by the property that actually separates
  them: a fragment is the *onset* of the word being reached for.
- `24b473a` — a block transcribed as `[BLANK_AUDIO]` pasted the bracket.
- `04e3e13` — the LLM-leak filter substring-matched fourteen phrases of ordinary
  English against the whole output. **Saying "I'm sorry, I'll be late" discarded
  your clean sentence and pasted the raw stutter**, silently, logged as a leak.
  The raw transcript now decides: if you said it, it stays.
- `e7df24b` / `346cec8` — the repair loop returned its **last** attempt rather
  than its best, so a good attempt 1 was thrown away when 2 and 3 came back
  worse, and the caller then fell back to raw.
- `7d5d2e9` — terminal punctuation was chosen from the first word of the whole
  text, so "can you send the report. i will wait" closed as "I will wait?"

## 3. Settings that did nothing

- `ec8c33f` — the CLOUD transcription setting was honoured **only at layer 1**.
  It sits under a heading called TRANSCRIPTION and gates behind `cloudUnlocked()`,
  so someone could pay for cloud transcription and silently get the local
  English-only engine at L2, L3 and L4. Same commit: the documented L2/L3 cloud
  fallback had no branch at all, so a signed-in user whose model had not
  downloaded could not dictate while a recorder was already capturing their
  voice — **and the refusal never stopped that recorder, leaving the mic open**.
- `1dd0d08` / `657f24b` — onboarding's "MOSTLY FOR" wrote `work_setup`, which
  nothing read; the dashboard asked the identical question and wrote
  `profile_usage` with different spellings. Neither read the other.
- `08f1c68` — the L3 prompt emitted "Speaker's known vocabulary:" whenever
  *either* list had entries while printing only the vocabulary, so a profile with
  corrections and no vocabulary sent GPT-4o an empty header with the corrections
  list directly beneath it.
- `08b5d1e` — `setBubbleIdle` force-set Idle from 26 call sites, bypassing the
  transition matrix. The matrix forbids Idle from the four mid-pipeline states
  precisely so a failure passes through `Failed`, which is what `BubbleAffect`
  and `BubblePersona` watch. **Failures showed the user nothing.** That is the
  mechanism under the silent-reset complaints `6e8a1bf` patched site by site.
- `63154a6` — L4 with no key and not signed in started no recorder and no engine.
  The bubble sat recording and captured silence.

## 4. Divergence between the two implementations

Five fixes were one-sided, **four of them created this session** by fixing Kotlin
and forgetting Python. That is the single most common defect shape in this
codebase.

- `3861638` — the L1 fixes above, mirrored into `wim/api/reconstruct.py` and
  `lavrentiy.py`.
- `2187f8d` — the backend collapsed word repeats at 2+ with no allow-list, so
  "no no that's not what I meant" lost the emphasis that was the point of the
  sentence; and it had no caption-artifact stripping on any of its three
  raw-text paths.
- `9155d2c` — `SITUATION_SEVERITY["reading"]` was 0.5 in the Cloud Function and
  0.3 everywhere else, so the same person on the same setting got more aggressive
  reconstruction when signed in. `test_wim_api.py` asserted the drifted value
  under a heading reading "Canonical values for WiM parity".
- The sweep later found two rules present only in Kotlin: unspaced stutter
  fragments (`c-c-can`, `m-m-meeting` — the form Whisper produces most of the
  time) and prolongations. The product's core artifact, missing from the server.

## 5. What now stops it recurring

`test_pipeline_sweep.py` (lavrentiy), **961 assertions, no API key needed**, wired
into CI in `8869447` with a `wim-android` checkout so the parity stage can run.

- **Sweep A** — 96 prompt combinations across layer × tone × situation × language.
  Asserts tone reaches the prompt, L3+ carries profile terms, L4 carries the
  clinical block, and all four tones produce *distinct* prompts — the
  decorative-tile failure caught structurally.
- **Sweep B** — a 21-case corpus with per-case anchors that must survive and
  artifacts that must not.
- **Sweep C** — the rewrite loop across 80 layer/tone pairs under five scripted
  model failures, model stubbed so every branch executes deterministically.
- **Sweep D** — the meaning guard on engineered raw/clean pairs.
- **Sweep E** — cross-implementation parity **by extraction, not by copy**. Reads
  the regex bodies, word sets and constant maps straight out of
  `DisfluencyFilter.kt`, `MeaningGuard.kt`, `ReconstructClient.kt` and
  `ProfileTermRecovery.kt` at test time. A hand-maintained second copy would rot
  the way the originals did. It also pins the one *deliberate* divergence —
  Kotlin collapses word repeats at 2+, Python at 3+ — by asserting both sides
  still behave that way, so a silent "fix" to either fails.

Mutation-tested rather than assumed: re-introducing the severity drift fails 2
assertions, deleting the unspaced-fragment rule fails 4, removing "reverend" from
the Python title regex fails its parity check.

On the Kotlin side, `4af3e5e` runs the same corpus through the whole filter
inside Android CI, because matching *patterns* is not matching *output* — Kotlin
has stages Python lacks.

## 6. Known, deliberate, and recorded so nobody "fixes" it

Multi-word fillers never match, in all three implementations: the filler stage
splits on whitespace and compares one token at a time, so "you know" and "i mean"
pass straight through. The API accepts them anyway, including from the user's own
profile.

The obvious fix would break the numeric guard — `checkCriticalTokens` reads "i
mean" out of the raw text to know which figure was legitimately superseded.
`selfCorrectionMarkerSurvivesTheFilter` in `DisfluencyCorpusTest` pins it so the
next person to reach for multi-word filler support hits an explanation instead of
a regression.
