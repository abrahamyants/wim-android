# How to run a code review on this codebase

Written 2026-08-02, after an audit that found 25 defects across both repos.
Written *during* that audit rather than before it, which is why the first half of
the day was worse than the second — see §4.

---

## 1. The twelve steps

| # | Step | What it means |
|---|---|---|
| 1 | **Scope** | What code, what depth, what is out of bounds. |
| 2 | **Baseline** | `git fetch` both repos. Confirm you are reading what is actually deployed. |
| 3 | **Map** | Find the entry points and call sites so you can navigate. |
| 4 | **Axis** | Pick how you slice it — see §2. |
| 5 | **Flag** | Mark suspects. Fix nothing yet. |
| 6 | **Verify** | Prove each one is real. Kill the false positives. |
| 7 | **Prioritize** | By user impact, not by how interesting the bug is. |
| 8 | **Fix** | One defect per change. |
| 9 | **Prove** | Run the tests. When you cannot run them, simulate old behaviour against new. Then check CI. |
| 10 | **Twins** | Does this same defect exist in the other implementation? |
| 11 | **Record** | The commit says what was broken, not what you typed. |
| 12 | **Re-baseline** | Push, confirm sync. |

Steps 2, 10 and 12 are the ones this codebase punishes hardest for skipping.

## 2. The seven axes, in the order that pays

Run the first three before reading any code. They are mechanical and they
produce a ranked suspect list.

**1. Twins.** Find every place the same logic exists twice and diff the copies.
Highest yield of anything, by a distance. This repo and `lavrentiy` implement the
same pipeline in Kotlin and Python; when one copy gets fixed and the other does
not, the bug resurfaces months later with no obvious cause.

```
grep -rn <symbol> across both repos
git log -S<symbol>          # which copy got the fix, and which did not
```

`lavrentiy/test_pipeline_sweep.py` SWEEP E automates this by *extracting* the
Kotlin patterns at test time. It runs in CI. A hand-maintained second copy would
rot the same way the originals did.

**2. Claims.** Read every comment as an assertion and check whether it is still
true. Comments rot exactly where behaviour changed without anyone following
through, which marks the spot.

```
grep -rn "Port of\|Mirrors\|parity\|verbatim\|Retired\|is the only caller\|see \["
```

**3. Churn.** Hot files and repeat-fix clusters.

```
git log --format= --name-only --since=3.months | sort | uniq -c | sort -rn
git log --oneline --since=60.days | sed -n 's/^[a-f0-9]* fix(\([a-z0-9]*\)).*/\1/p' | sort | uniq -c | sort -rn
```

Then read what those repeat-fix commits were *about*. On 2026-08-02, sixteen
`fix(bubble)` commits in sixty days shared one shape — a setting that applies to
only part of what it names — and looking for a fifth instance found one.

**4. Call path.** Follow one user action end to end. Thorough, expensive, and
the only axis that finds bugs of *omission* like a missing branch.

**5. Risk.** Sort by blast radius rather than structure. Here that means anything
touching the text field, the microphone, or money.

**6. Contract.** Audit the boundaries: prefs keys, API payload keys, intent
extras, default parameter values. Finds settings written and never read.

**7. By file, alphabetically.** Do not. No signal, pure grind.

## 3. What this codebase specifically does to you

- **Every defect found on 2026-08-02 was in deterministic code.** Regexes, guard
  predicates, loop selection, prompt assembly, a constant, a pref key. None
  needed a live model to reproduce. Reach for `test_pipeline_sweep.py` before
  reaching for an API key.
- **A regex with `\w` will eventually eat a number.** `PROLONGATION` collapsed
  "1000" to "10" for months.
- **A guard that runs after a mangling stage cannot see the mangling.** Check the
  order, not just the logic.
- **`internal` + a pure function beats a comment.** Three fixes this session
  needed an extraction first, because the defect was unreachable from any test:
  `buildProfileTermsBlock`, `gpt4oRewriteLoop`, `AsrRoute`. Refactor to make a
  fix provable — never as its own errand.
- **Mutation-test any new safety net.** A test that cannot fail is worse than no
  test, because it reads as coverage. Break the thing on purpose, watch the
  assertion fail, put it back.

## 4. Honest scorecard from the audit that produced this file

First half — the L1 through L4 pipeline audits:

| Step | Result |
|---|---|
| 2 Baseline | **Skipped.** Read a `lavrentiy` clone 52 commits stale and diagnosed against dead code. Git caught it at push time, not me. |
| 7 Prioritize | **Skipped.** Fixed in discovery order. |
| 9 Prove | **Partial.** Logic simulated; CI never checked, so eleven commits sat "unverified" for hours while CI had in fact been green all along. |
| 10 Twins | **Skipped.** Four defects were created that day by fixing Kotlin and forgetting Python. |

Second half — bubble, capture path, guard:

Steps 2, 9, 10 and 12 followed throughout. `git fetch` first, `gh run watch`
after every push, every fix mirrored, both repos confirmed in sync.

Step 7 is still not followed. Fixes land in discovery order, which is why the
microphone-left-open bug was found in hour six rather than hour one.
