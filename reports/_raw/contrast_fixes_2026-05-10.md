# WCAG 2.2 Contrast Fixes — 2026-05-10

All ratios calculated using WCAG 2.1 relative luminance formula (IEC 61966-2-1 sRGB gamma).

---

## 1. text_dim — Light Mode
**File:** `app/src/main/res/values/colors.xml`
| | Before | After |
|---|---|---|
| Value | `#8A7D68` | `#5A5040` |
| Background | `#D4C8B0` (bg_base) | same |
| Ratio | 2.56:1 | 5.22:1 |
| WCAG level | FAIL | **AA** (normal text ≥ 4.5:1) |

Warm dark: deep taupe-brown. Keeps vintage character, no cool cast.

---

## 2. chip_value — Dark Mode
**File:** `app/src/main/res/values-night/colors.xml`
| | Before | After |
|---|---|---|
| Value | `#B55050` | `#D06060` |
| Background | `#1A1A1E` (bg_base dark) | same |
| Ratio | 3.52:1 | 4.60:1 |
| WCAG level | FAIL | **AA** (normal text ≥ 4.5:1) |

Brighter warm maroon. Still clearly in the red-maroon family; not a neutral.

---

## 3. text_secondary — Dark Mode
**File:** `app/src/main/res/values-night/colors.xml`
| | Before | After |
|---|---|---|
| Value | `#8A8078` | `#908880` |
| Background | `#1A1A1E` (bg_base dark) | same |
| Ratio | 4.49:1 | 4.96:1 |
| WCAG level | FAIL (borderline) | **AA** (normal text ≥ 4.5:1) |

Minimal step: +6 on each channel. Warm gray undertone preserved.

---

## 4. Progress Dots — onboarding.html
**File:** `app/src/main/assets/onboarding.html`

### Inactive dot
| | Before | After |
|---|---|---|
| CSS value | `var(--raised-hi)` = `#333338` | `var(--text-muted)` = `#908880` |
| Background | `#222226` (surface) | same |
| Ratio | 1.26:1 | 4.10:1 |
| WCAG level | FAIL | **AA** (UI component ≥ 3:1) |

Changed `.dot { background }` from `var(--raised-hi)` to `var(--text-muted)`.
No hardcoded hex introduced — uses existing CSS variable.

### Active dot
| | Before | After |
|---|---|---|
| CSS value | `var(--maroon)` = `#B55050` | `var(--maroon)` = `#D06060` |
| Background | `#222226` (surface) | same |
| Ratio | 3.20:1 | 4.05:1 |
| WCAG level | Pass (3:1 UI component) | **Pass** — ratio improved as side effect of chip_value fix |

Active dot already passed 3:1 before the fix. Ratio improved automatically because `--maroon`
was updated to `#D06060` to stay in sync with `chip_value` dark mode.

Also updated `--text-muted` in `:root` to `#908880` (from `#8A8078`) to match fix #3,
and updated `--maroon` glow box-shadow from `rgba(181,80,80,0.6)` to `rgba(208,96,96,0.6)`
to match the new `#D06060` value.

---

## 5. orange_label — Light Mode
**File:** `app/src/main/res/values/colors.xml`
| | Before | After |
|---|---|---|
| Value | `#C94A1F` | `#963611` |
| Background | `#D4C8B0` (bg_base light) | same |
| Ratio | 2.91:1 | 4.65:1 |
| WCAG level | FAIL | **AA** (normal text ≥ 4.5:1) |

Token is used as text color in `MainActivity.kt` (OUT label, SHARE label) and
`BubbleService.kt` (clarifier acknowledgment flash). In light mode the session card
background (`#853D1818` over `#D4C8B0`) composites to ~`#856C61`, making the old
`#C94A1F` nearly invisible (1.04:1). The new `#963611` (deep burnt orange) reads
clearly on both the composited card and the bare bg_base.

Dark mode `orange_label` (`#EE5A24`) was NOT changed — it already achieves 5.09:1
on `#1A1A1E` and passes AA.

---

## Guard and Brand Source Updates

**`onboarding-plugin/brand.local.md`** — brand token table updated with the four
WCAG-compliant replacements, including before/after notes inline.

**`~/.claude/hooks/wim-brand-guard.ps1`** — `$approved` list extended with the four
new hex values (`963611`, `5A5040`, `D06060`, `908880`). Old values retained in the
list so existing code referencing them still compiles without a guard block.

---

## Build Note

Build fails on `bouncer_send_ack_cd` missing string resource — pre-existing breakage
in `activity_bouncer.xml` from in-flight work in the repo (unrelated to this task).
Color XML changes are syntactically valid and were confirmed in-file.
