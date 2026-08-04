# The four layers

What each one does, why it exists, how they lean on each other, and what
they mean for the product. Written 2026-08-04 from the code, not from
memory or from earlier documents.

The user picks a layer in the app. The names they see are `transcribe`,
`reconstruct`, `profile`, `disfluency` (`MainActivity.kt:1552`). The bubble
shows a second set of words while it works — "generic cleanup", "+ your
vocabulary", "disfluency-aware" (`BubbleService.kt:3067`).

---

## The part that runs no matter which layer you pick

Every recording goes through the same four steps before the layers differ:

1. **Record.** `AudioRecorder`.
2. **Hear the words.** Local whisper-tiny by default, through sherpa-onnx.
   Cloud Whisper instead if `l1_cloud_asr` is on. Non-English locales flip
   that on by themselves, because the local model is English-only.
3. **Throw out hallucinations.** Whisper invents text on silence and noise;
   that gets stripped.
4. **Strip the disfluencies.** `DisfluencyFilter.filter()` — repeated words,
   stutter fragments, drawn-out sounds, filler words.

Step 4 is **skipped at layer 4 on purpose** (`BubbleService.kt:2927`). Layer
4 is the one that reasons about disfluencies, so it has to see them.

---

## Layer 1 — "transcribe"

**No model.** Nothing leaves the phone. `L1Pipeline.transform()`.

- Applies the corrections the user has taught it, as whole-word swaps.
- Every 30 sessions, prunes corrections that stopped being used.
- Adds capitals and a full stop or question mark (`Repunctuate`), which is
  a word-for-word port of the desktop `repunctuate()`.

Fast, free, offline, private.

**It has no safety net.** The code calls it "trusted by construction"
(`BubbleService.kt:2962`). No model, so no guard, no risk flags, no
fallback. Whatever the deterministic code produces goes straight into the
user's message. Both of the number-eating bugs found in this code lived
here, and at layer 1 nothing would ever have caught them.

`PhoneticMatcher` is deliberately not called at layer 1. There is no
validator here, so a wrong phonetic guess would land in the field with
nothing to catch it.

---

## Layer 2 — "reconstruct"

First layer that calls a model. GPT-4o, through the signed-in backend.

`L2CorrectionsStage.apply()` runs first: the same corrections as layer 1,
plus `PhoneticMatcher`. That last one swaps words that *sound like*
something in the speaker's vocabulary — "dunken" becomes "Dankeschön" —
using Double Metaphone, and only on the sounds that speaker personally
struggles with.

Then the reconstruction call. Tone shapes it: `formal`, `professional`,
`casual`, `friend`, each with its own rules and its own temperature
(0.1 up to 0.4).

Then the guards, which layer 1 never gets:

- **`ReconstructionGuard`** — catches chatbot text leaking through ("as an
  AI language model") and output more than 2.5× longer than the input,
  which nearly always means the model made things up. Both fall back to the
  layer-1 text.
- **`MeaningGuard`** — checks anchors in two directions. Things that
  **disappeared** (a name, a number, a negation, a title like "doctor") and
  things that **appeared** out of nowhere. The second is the dangerous one:
  a lost word can be recovered from the raw text, but an invented one reads
  as correct. Up to 3 rewrite attempts.
- **`computeRiskFlags`** — output too short, length ratio outside 0.3–3.0,
  unfinished endings, critical tokens lost.

---

## Layer 3 — "profile"

Same machinery as layer 2, with the speaker poured into the prompt:

- their vocabulary (capped at 60 terms)
- their learned corrections (capped at 40)
- their trigger words (capped at 10)
- style examples from reconstructions they accepted
- a rejection history of reconstructions they threw away

The caps exist because those lists grow forever and a bloated prompt
dilutes the instructions the model actually needs to follow. Truncation is
logged rather than silent.

Accept and reject are learned without the user doing anything. If they hit
regenerate, the previous output goes in the rejection pile. If they just
dictate again, it counts as accepted and becomes a style example.

---

## Layer 4 — "disfluency"

Sonnet with extended thinking, and the only layer that sees the speech as
it actually was.

- The disfluency filter is skipped, so the raw text arrives intact.
- **Prosody.** The wav file is read for rhythm and pauses, compared against
  that speaker's own baseline.
- **Multi-pass decoding.** The audio is transcribed several times at
  different temperatures. Words every pass agrees on are solid; words they
  argue about become an uncertainty signal. Costs 3× the transcription.
- **Whisper timings and low-confidence segments** are passed through.
- `PhoneticMatcher` is *not* called here. The model reasons through the
  sound patterns itself, from a richer prompt block.

All of that feeds the **Clarifier**, which picks at most 3 words for the
user to confirm, and only when the doubt score clears 0.55. It is tuned to
say nothing on a clean sentence.

Slowest layer, most expensive, and the one the product is really about.

---

## How they lean on each other

**The filter runs before the guards.** This is the trap in this codebase.
`DisfluencyFilter` mangles text at layers 1, 2 and 3, and `computeRiskFlags`
then compares the *already-mangled* text against the output. It cannot see
damage that happened upstream of itself. Two separate bugs have come from
exactly this — a drawn-out-sound collapse eating "1000" down to "10", and a
repeat collapse eating spoken phone numbers. Neither was catchable by any
guard in the app.

**Layer 1 has no guard at all,** so anything the deterministic code breaks
ships. Any bug in the shared filter is worst at layer 1 and invisible there.

**The desktop app is the second copy.** Every rule in here mirrors
`lavrentiy.py`. When one copy is fixed and the other is not, the bug comes
back months later with no obvious cause. `lavrentiy/test_pipeline_sweep.py`
SWEEP E reads the Kotlin patterns straight out of the source at test time to
stop that drift — but it reads the *patterns only*, so a guard written in
Kotlin code around a pattern is invisible to it.

**Everything shares the profile.** Corrections learned at any layer apply at
every layer. A single bad learned correction spreads everywhere.

**Layers degrade downward, never up.** No auth token, no Anthropic key, a
failed call — each drops the user to a simpler layer or to the layer-1 text.
The user is told, in the bubble.

---

## What this means for the product

**The ladder is the pitch.** "Voice-to-intent, not voice-to-text." Each
layer is a different promise:

| Layer | The promise | Costs |
|---|---|---|
| 1 | instant, offline, private, free | nothing |
| 2 | clean it up | one model call |
| 3 | clean it up *the way I talk* | one model call, bigger prompt |
| 4 | understands disfluent speech | transcription ×3, prosody, Sonnet extended thinking |

**Layer 4 is the whole differentiator.** Layers 1–3 are things other
dictation tools broadly do. Layer 4 — reading the disfluencies instead of
deleting them, using rhythm and pauses, asking about the three words it is
unsure of — is the part that is genuinely different, and it is the part the
patent claims are built around.

**Layer 1 is the free tier that has to be flawless.** It runs offline with no
model, no cost and no guard. It is the honest demo, the thing that works on a
plane, and the layer where a silent bug does the most damage, since nothing
downstream checks it.

**Layer 3 is the retention story.** It gets better the longer someone uses
it, without them doing anything. That is what makes leaving expensive.

**The costs are lopsided.** Layer 1 is free forever. Layer 4 is three
transcriptions plus a slow reasoning model, per utterance. Any pricing has
to price layer 4, not the average.
