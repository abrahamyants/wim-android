# WiM — Disfluency Reconstruction Results, 2026-07-29

Second validation pass after `docs/WiM-Stutter-Test-Results.pdf` (April 1).
Fifteen disfluent inputs, run through the **deployed** reconstruction source
(`wim-reconstruct-00032-zit`, files taken from `gugosf114/lavrentiy`
`wim/api/`) at Layer 2 and Layer 4, professional tone, SAFE mode.

Text-in / text-out. No audio, no device. Blocks cannot be produced on demand —
they are involuntary — so the corpus encodes block *signatures* in the
transcript instead: the artifact Whisper emits into silence, the hole left
where words never surfaced, covert revision past a feared word.

**What this pass adds over April:** every April input was a transcript that
already contained its disfluency as text, so nothing tested the case the
product is built around — a block that puts *nothing* in the transcript.
Cases 2, 3, 8 and 11 cover it here.

## Result

| | Layer 2 | Layer 4 |
|---|---|---|
| Shipped a clean reconstruction | 14/15 | 14/15 |
| Meaning anchors lost (names, numbers, dates, negation) | 0 | 0 |
| Fabricated content shipped | 0 | 0 |
| Guard refused and fell back to raw | 1 (case 2) | 1 (case 2) |
| Median latency | 522 ms | 509 ms |

## Representative pairs

| In | Out (L4) |
|---|---|
| `c-c-can you s-s-send me the the report by by friday` | Can you send me the report by Friday? |
| `i'm going to the s-s-s the place tomorrow at four` | I'm going to the place tomorrow at four. |
| `the meeting is at three pm i mean four pm on tuesday` | The meeting is at 4 PM on Tuesday. |
| `i i i do not want to to cancel the appointment` | I do not want to cancel the appointment. |
| `tell d-d-doctor williams my son m-m-michael has an appointment on the the fifteenth` | Tell Doctor Williams my son Michael has an appointment on the fifteenth. |
| `wire f-f-four thousand two hundred to h-h-henderson before monday` | Wire four thousand two hundred to Henderson before Monday. |
| `i was going to um i was wondering if you could could send the address` | I was wondering if you could send the address. |

Self-correction, circumlocution and sentence abandonment all resolved to the
intended message rather than the detour. Numbers, proper nouns and negation
survived every reconstruction that shipped.

## Findings

**1. The always-reconstruct retry loop demonstrably fires (L2).** Fluent input
(`please send me the report tomorrow`) came back as identity on attempt 1, was
rejected as unchanged, and attempt 2 produced `I need you to send me the report
by tomorrow.` Case 2 exhausted all three attempts. Retries are real, not
theoretical.

**2. Open defect — a hallucinated block artifact reaches the user verbatim.**
Case 2, `i need to call thanks for watching about the invoice` (Whisper's
stock output for silence, i.e. a block on a name). Both layers refused to ship
a reconstruction, correctly: every candidate invented a replacement for the
missing name, and the guard caught it. But the SAFE fallback is
`strip_disfluencies(raw)`, which does not know about hallucination artifacts —
so the user gets `thanks for watching` pasted into their message. The guard
prevents fabrication and then hands over garbage.

Fix: the fallback should strip known artifacts before pasting. Android already
has `DisfluencyFilter.stripBlockHallucinations` for exactly this and applies it
pre-reconstruction; the refusal path does not re-apply it, and the backend has
no equivalent. Affects both paths.

**3. Minor — emphatic repetition is stripped at L2.** `no no that's not what i
meant at all` → `That's not what I meant at all.` (L2, both "no"s gone) vs
`No, that's not what I meant at all.` (L4). Natural-repeat protection is
carried by the language packs, which L4's prompt injects and L2's does not.

**4. Not a defect:** `march third` → `March 3rd` was flagged by the harness's
literal anchor check. Reformatting, not loss.

## What this supports, and what it does not

Supported: WiM removes the clinical disfluency types, resolves covert
avoidance to intent, and preserves meaning anchors, at ~0.5 s, on inputs
carrying real block signatures — with a guard that refuses rather than invent
when the transcript is too damaged to reconstruct honestly.

Not supported: any claim about outcomes for users. n=15, self-authored corpus,
no human participants, no audio path. The external number that frames the
problem remains Mujtaba et al. (NAACL 2024): Whisper WER 3.1% → 19.8% on
stuttered speech.

Corpus and raw output: `docs/disfluency_corpus_2026-07-29.json`.
