"""
WiM MCP Server — exposes the voice-to-intent pipeline as MCP tools.

Tools:
  reconstruct        — raw text + tone + layer → clean text + risk flags + Falcon status
  filter_disfluencies — strip stutter fragments, repeats, fillers, false starts
  strip_block_hallucinations — remove Whisper-hallucinated filler during blocks
  predict_phonetic_risk — Brown's 4-feature risk score for a single word
  predict_triggers    — score all words in text, return high-risk triggers
  compute_risk_flags  — check reconstruction for length delta, lost tokens, etc.
  compute_wer         — Word Error Rate between raw and clean text
"""

from mcp.server.fastmcp import FastMCP
import httpx

from .disfluency_filter import filter_disfluencies, strip_block_hallucinations
from .phonetic_risk import (
    predict_phonetic_risk as _predict_risk,
    predict_triggers_in_text,
    extract_onset,
    HIGH_RISK_ONSETS,
)

mcp = FastMCP(
    "wim",
    instructions="WiM voice-to-intent pipeline — reconstruction, disfluency filtering, phonetic risk analysis",
)

BACKEND_URL = "https://us-central1-bakers-agent.cloudfunctions.net/wim-reconstruct"

# ── Tone / situation constants (mirrored from ReconstructClient.kt) ──────────

TONE_RULES = {
    "formal": "Use complete sentences. No contractions. No colloquialisms. Proper grammar and punctuation. Preserve the speaker's exact word choices where possible.",
    "professional": "Clean, clear business language. Contractions acceptable but not preferred. Fix grammar and strip fillers. Minor rephrasing for clarity is OK.",
    "casual": "Natural spoken rhythm. Contractions OK. Strip fillers and fix obvious errors, but keep the speaker's natural voice.",
    "friend": "Conversational, relaxed. Contractions expected. Sentence fragments OK if natural. Strip fillers but preserve personality.",
}

TONE_TEMPERATURE = {
    "formal": 0.1,
    "professional": 0.15,
    "casual": 0.35,
    "friend": 0.4,
}

SITUATION_SEVERITY = {
    "default": 1.0,
    "high_stress": 1.5,
    "reading": 0.3,
}


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 1: reconstruct
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
async def reconstruct(
    raw_text: str,
    tone: str = "casual",
    layer: int = 2,
    situation: str = "default",
    vocabulary: list[str] | None = None,
    corrections: dict[str, str] | None = None,
    filler_words: list[str] | None = None,
    trigger_words: list[str] | None = None,
    onset_weights: dict[str, float] | None = None,
    id_token: str | None = None,
    openai_api_key: str | None = None,
) -> dict:
    """Reconstruct raw speech-to-text output into clean, intended text.

    Uses the WiM backend (if id_token provided) or direct OpenAI (if openai_api_key provided).
    Layers: 2=generic cleanup, 3=+profile vocabulary/corrections, 4=+clinical stutter pipeline.
    Tones: formal, professional, casual, friend.
    Situations: default, high_stress, reading.

    Returns: {clean_text, recon_ms, risk_flags, falcon_approved}
    """
    vocabulary = vocabulary or []
    corrections = corrections or {}
    filler_words = filler_words or []
    trigger_words = trigger_words or []
    onset_weights = onset_weights or {}

    if id_token:
        return await _reconstruct_backend(
            raw_text, tone, layer, situation, vocabulary, corrections,
            filler_words, trigger_words, onset_weights, id_token,
        )
    elif openai_api_key:
        return await _reconstruct_direct(
            raw_text, tone, layer, situation, vocabulary, corrections,
            trigger_words, onset_weights, openai_api_key,
        )
    else:
        return {"error": "Provide either id_token (backend) or openai_api_key (direct)"}


async def _reconstruct_backend(
    raw_text: str, tone: str, layer: int, situation: str,
    vocabulary: list[str], corrections: dict[str, str],
    filler_words: list[str], trigger_words: list[str],
    onset_weights: dict[str, float], id_token: str,
) -> dict:
    body: dict = {
        "raw": raw_text,
        "tone": tone,
        "layer": layer,
        "situation": situation,
        "mode": "SAFE",
    }
    profile: dict = {}
    if vocabulary:
        profile["vocabulary"] = vocabulary
    if corrections:
        profile["corrections"] = corrections
    if filler_words:
        profile["filler_words"] = filler_words
    if trigger_words:
        profile["trigger_words"] = trigger_words
    if onset_weights:
        profile["onset_weights"] = onset_weights
    if profile:
        body["profile"] = profile

    async with httpx.AsyncClient(timeout=45.0) as client:
        resp = await client.post(
            BACKEND_URL,
            json=body,
            headers={
                "Authorization": f"Bearer {id_token}",
                "Content-Type": "application/json",
            },
        )
        resp.raise_for_status()
        data = resp.json()

    clean_text = data.get("clean", raw_text)
    falcon_ok = data.get("falcon_ok", True)
    risk_flags = compute_risk_flags(raw_text, clean_text)

    return {
        "clean_text": clean_text if falcon_ok else raw_text,
        "risk_flags": risk_flags,
        "falcon_approved": falcon_ok,
    }


async def _reconstruct_direct(
    raw_text: str, tone: str, layer: int, situation: str,
    vocabulary: list[str], corrections: dict[str, str],
    trigger_words: list[str], onset_weights: dict[str, float],
    api_key: str,
) -> dict:
    """Direct OpenAI call — mirrors ReconstructClient.reconstructDirect()."""
    tone_rule = TONE_RULES.get(tone, TONE_RULES["casual"])
    temperature = TONE_TEMPERATURE.get(tone, 0.35)
    severity = SITUATION_SEVERITY.get(situation, 1.0)

    system_prompt = (
        "You are a voice transcription post-processor. You receive raw, messy speech-to-text "
        "output and return ONLY the cleaned-up version. "
        "You are NOT a chatbot. Do NOT respond to the content. Do NOT ask questions. "
        "Do NOT add commentary. "
        f"Tone: {tone}. {tone_rule} "
        "Strip filler words (including non-English fillers like э, ну, ээ). "
        "Preserve FULL meaning. Do not summarize or add information. "
        "Do NOT censor, sanitize, or soften the speaker's language. "
        "Output ONLY the reconstructed text, nothing else."
    )

    if severity >= 1.4:
        system_prompt += (
            "\n\nSpeaker is in a HIGH-STRESS context. "
            "Expect more disfluencies. Be MORE aggressive in reconstructing."
        )
    elif severity <= 0.6:
        system_prompt += (
            "\n\nSpeaker is in a low-stress context. "
            "Be conservative — minor cleanup only."
        )

    if layer >= 3 and (vocabulary or corrections):
        if vocabulary:
            system_prompt += f"\n\nSpeaker's known vocabulary: {', '.join(vocabulary)}"
        if corrections:
            pairs = ", ".join(f'"{k}" -> "{v}"' for k, v in corrections.items())
            system_prompt += f"\n\nKnown transcription errors: {pairs}"

    if layer >= 4:
        system_prompt += _build_l4_prompt(raw_text, trigger_words, onset_weights)

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": raw_text},
    ]

    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.post(
            "https://api.openai.com/v1/chat/completions",
            json={
                "model": "gpt-4o",
                "messages": messages,
                "temperature": temperature,
                "max_tokens": 1024,
            },
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )
        resp.raise_for_status()
        data = resp.json()

    clean_text = data["choices"][0]["message"]["content"].strip()
    risk_flags = compute_risk_flags(raw_text, clean_text)

    return {
        "clean_text": clean_text,
        "risk_flags": risk_flags,
        "falcon_approved": "critical_token_lost" not in risk_flags,
    }


def _build_l4_prompt(
    raw_text: str,
    trigger_words: list[str],
    onset_weights: dict[str, float],
) -> str:
    """Build the Layer 4 clinical stutter prompt appendix."""
    parts = [
        "\n\nThe speaker stutters. Raw transcription is evidence, not truth. "
        "Reconstruct intended meaning, not literal word sequence.",
        "\n\nOvert disfluencies — strip and reconstruct:",
        "\n- Part-word repetitions, whole-word repetitions, prolongations",
        "\n- Schwa substitution, blocks, false starts",
        "\n\nCovert stuttering — recognize as avoidance, not content:",
        "\n- Filler clusters = delay tactic, synonym substitution = avoiding feared word",
        "\n- Circumlocution, sentence abandonment, mazes/cluttering",
    ]
    if onset_weights:
        top = sorted(onset_weights.items(), key=lambda x: x[1], reverse=True)[:5]
        flagged = ", ".join(f"/{k}/ ({int(v * 100)}%)" for k, v in top)
        parts.append(f"\n\nTHIS SPEAKER'S HARDEST PHONEMES: {flagged}")
    if trigger_words:
        parts.append(f"\n\nKnown trigger words: {', '.join(trigger_words[:10])}")

    predicted = predict_triggers_in_text(raw_text, trigger_words, onset_weights)
    if predicted:
        flagged = ", ".join(f"{w}({r})" for w, r in predicted[:5])
        parts.append(f"\n\nPhonetically predicted high-risk words: {flagged}")

    return "".join(parts)


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 2: filter_disfluencies
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
def disfluency_filter(
    text: str,
    filler_words: list[str] | None = None,
) -> dict:
    """Strip disfluencies from raw ASR text (no LLM, pure regex).

    Removes: stutter fragments (p-p-pop), word/phrase repetitions,
    filler words, false starts, prolongations (mmmmaybe).
    Protects natural repeats (had had, bye bye, etc.).

    Returns: {cleaned_text, characters_removed}
    """
    filler_words = filler_words or []
    cleaned = filter_disfluencies(text, filler_words)
    return {
        "cleaned_text": cleaned,
        "characters_removed": len(text) - len(cleaned),
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 3: strip_block_hallucinations
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
def block_hallucination_filter(
    text: str,
    low_conf_segments: list[dict],
) -> dict:
    """Remove short phrases Whisper hallucinated during speech blocks.

    Each segment should have: text, avg_logprob, no_speech_prob, block_suspect (bool).
    Only strips segments < 5 words flagged as block suspects.

    Returns: {cleaned_text, segments_stripped}
    """
    cleaned = strip_block_hallucinations(text, low_conf_segments)
    stripped = sum(
        1 for seg in low_conf_segments
        if seg.get("block_suspect") and seg.get("text", "").strip() not in cleaned
    )
    return {"cleaned_text": cleaned, "segments_stripped": stripped}


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 4: predict_phonetic_risk
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
def phonetic_risk(
    word: str,
    sentence_position: int | None = None,
    sentence_length: int | None = None,
    personal_onset_weights: dict[str, float] | None = None,
) -> dict:
    """Score phonetic difficulty for a single word (0.0 = easy, 1.0 = hard).

    Uses Brown's 4-feature model: initial consonant, content vs function word,
    word length, sentence position. Personal onset weights override defaults.

    Returns: {word, onset, risk, is_function_word}
    """
    from .phonetic_risk import FUNCTION_WORDS

    risk = _predict_risk(word, sentence_position, sentence_length, personal_onset_weights)
    onset = extract_onset(word)
    return {
        "word": word,
        "onset": onset,
        "onset_base_risk": HIGH_RISK_ONSETS.get(onset, 0.3),
        "risk": round(risk, 3),
        "is_function_word": word.lower().strip() in FUNCTION_WORDS,
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 5: predict_triggers
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
def predict_triggers(
    text: str,
    existing_triggers: list[str] | None = None,
    personal_onset_weights: dict[str, float] | None = None,
    threshold: float = 0.6,
) -> dict:
    """Score all words in text and return predicted stutter triggers above threshold.

    Words that share onset patterns with known triggers get a +0.2 boost.
    Returns triggers sorted by risk (highest first).

    Returns: {triggers: [{word, risk}], total_words, flagged_count}
    """
    import re

    triggers = predict_triggers_in_text(
        text,
        existing_triggers or [],
        personal_onset_weights or {},
        threshold,
    )
    total_words = len(re.findall(r"\b\w+\b", text))
    return {
        "triggers": [{"word": w, "risk": r} for w, r in triggers],
        "total_words": total_words,
        "flagged_count": len(triggers),
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 6: compute_risk_flags
# ═══════════════════════════════════════════════════════════════════════════════


def _check_critical_tokens(raw: str, clean: str) -> bool:
    """Check that numbers, percentages, dollar amounts survived reconstruction."""
    import re

    token_pattern = re.compile(r"\$?\d[\d,.]*%?")
    raw_tokens = set(token_pattern.findall(raw))
    clean_tokens = set(token_pattern.findall(clean))
    return raw_tokens.issubset(clean_tokens)


@mcp.tool()
def compute_risk_flags(raw_text: str, clean_text: str) -> list[str]:
    """Check a reconstruction result for quality/safety flags.

    Flags: very_short_output, large_length_delta, contains_unfinished_fragment,
    critical_token_lost (numbers/$/% missing from reconstruction).

    Returns: list of flag strings (empty = clean)
    """
    flags: list[str] = []
    if len(clean_text) < 3:
        flags.append("very_short_output")
    if raw_text:
        ratio = len(clean_text) / len(raw_text)
        if ratio < 0.3 or ratio > 3.0:
            flags.append("large_length_delta")
    if clean_text.endswith("...") or clean_text.endswith("—"):
        flags.append("contains_unfinished_fragment")
    if not _check_critical_tokens(raw_text, clean_text):
        flags.append("critical_token_lost")
    return flags


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 7: compute_wer
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
def compute_wer(raw_text: str, clean_text: str) -> dict:
    """Compute Word Error Rate between raw and clean text.

    WER = (substitutions + insertions + deletions) / reference_words.
    0.0 = identical, 1.0 = completely different.

    Ported from SessionHistory.computeWer() — uses Levenshtein on word arrays.

    Returns: {wer, raw_word_count, clean_word_count, edit_distance}
    """
    raw_words = raw_text.lower().split()
    clean_words = clean_text.lower().split()

    if not raw_words:
        return {"wer": 0.0, "raw_word_count": 0, "clean_word_count": len(clean_words), "edit_distance": len(clean_words)}

    # Levenshtein on word arrays
    n, m = len(raw_words), len(clean_words)
    dp = list(range(m + 1))
    for i in range(1, n + 1):
        prev, dp[0] = dp[0], i
        for j in range(1, m + 1):
            cost = 0 if raw_words[i - 1] == clean_words[j - 1] else 1
            dp[j], prev = min(dp[j] + 1, dp[j - 1] + 1, prev + cost), dp[j]

    edit_dist = dp[m]
    wer = round(edit_dist / len(raw_words), 4)

    return {
        "wer": wer,
        "raw_word_count": n,
        "clean_word_count": m,
        "edit_distance": edit_dist,
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 8: therapist_correct
# ═══════════════════════════════════════════════════════════════════════════════


def _align_substitutions(
    app_words: list[str], intended_words: list[str]
) -> list[tuple[str, str]]:
    """Levenshtein alignment with backtrace to extract substitution pairs.

    Returns list of (app_word, intended_word) where app got it wrong.
    Only returns substitutions — not insertions or deletions.
    """
    n, m = len(app_words), len(intended_words)
    # DP table: dp[i][j] = edit distance for app_words[:i] vs intended_words[:j]
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    # Operation tracking: 'M'=match, 'S'=sub, 'D'=delete, 'I'=insert
    ops = [[""] * (m + 1) for _ in range(n + 1)]

    for i in range(n + 1):
        dp[i][0] = i
        if i > 0:
            ops[i][0] = "D"
    for j in range(m + 1):
        dp[0][j] = j
        if j > 0:
            ops[0][j] = "I"

    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if app_words[i - 1].lower() == intended_words[j - 1].lower():
                dp[i][j] = dp[i - 1][j - 1]
                ops[i][j] = "M"
            else:
                sub = dp[i - 1][j - 1] + 1
                delete = dp[i - 1][j] + 1
                insert = dp[i][j - 1] + 1
                best = min(sub, delete, insert)
                dp[i][j] = best
                if best == sub:
                    ops[i][j] = "S"
                elif best == delete:
                    ops[i][j] = "D"
                else:
                    ops[i][j] = "I"

    # Backtrace to extract substitution pairs
    subs: list[tuple[str, str]] = []
    i, j = n, m
    while i > 0 or j > 0:
        op = ops[i][j]
        if op == "M":
            i -= 1
            j -= 1
        elif op == "S":
            subs.append((app_words[i - 1], intended_words[j - 1]))
            i -= 1
            j -= 1
        elif op == "D":
            i -= 1
        elif op == "I":
            j -= 1
        else:
            break

    subs.reverse()
    return subs


@mcp.tool()
async def therapist_correct(
    app_said: str,
    intended: str,
    uid: str,
    id_token: str,
    bump: float = 0.15,
) -> dict:
    """Submit a therapist correction: "the app said X, the patient meant Y."

    Diffs the two strings, identifies which phoneme onsets failed,
    bumps the user's personal onset weights, adds new trigger words,
    and pushes the updated profile to Firestore. The Android device
    auto-pulls the new weights via its real-time listener.

    Weight formula: new = old + bump × (1.0 - old)
    Naturally bounded at 1.0, accumulates across corrections.

    Args:
        app_said: What the app reconstructed (the wrong output)
        intended: What the patient actually meant (ground truth)
        uid: Firebase user ID
        id_token: Firebase ID token for backend auth
        bump: Weight increase factor per correction (default 0.15)

    Returns: {substitutions, onset_adjustments, new_triggers, pushed}
    """
    app_words = app_said.split()
    intended_words = intended.split()

    # Step 1: Align and extract substitution pairs
    subs = _align_substitutions(app_words, intended_words)
    if not subs:
        return {
            "status": "no_substitutions",
            "app_said": app_said,
            "intended": intended,
            "note": "No word substitutions found — sentences may differ in insertions/deletions only",
        }

    # Step 2: Fetch current profile from backend
    current_weights: dict[str, float] = {}
    current_triggers: list[str] = []

    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            resp = await client.post(
                BACKEND_URL,
                json={"action": "export_data"},
                headers={"Authorization": f"Bearer {id_token}"},
            )
            if resp.status_code == 200:
                profile_data = resp.json()
                current_weights = {
                    str(k): float(v)
                    for k, v in profile_data.get("onset_weights", {}).items()
                }
                current_triggers = list(profile_data.get("trigger_words", []))
        except Exception:
            pass  # Continue with empty weights — bumps from base defaults

    # Step 3: Compute onset adjustments
    onset_adjustments: list[dict] = []
    new_triggers: list[str] = []

    for app_word, intended_word in subs:
        onset = extract_onset(intended_word)
        old_weight = current_weights.get(onset, HIGH_RISK_ONSETS.get(onset, 0.3))
        new_weight = round(old_weight + bump * (1.0 - old_weight), 4)
        current_weights[onset] = new_weight

        onset_adjustments.append({
            "app_word": app_word,
            "intended_word": intended_word,
            "onset": onset,
            "old_weight": round(old_weight, 4),
            "new_weight": new_weight,
            "delta": round(new_weight - old_weight, 4),
        })

        # Add intended word as trigger if not already known
        intended_lower = intended_word.lower()
        if intended_lower not in {t.lower() for t in current_triggers} and len(intended_lower) >= 3:
            current_triggers.append(intended_lower)
            new_triggers.append(intended_lower)

    # Step 4: Push updated profile to Firestore via backend
    pushed = False
    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            resp = await client.post(
                BACKEND_URL,
                json={
                    "action": "sync_profile",
                    "profile": {
                        "onset_weights": current_weights,
                        "trigger_words": current_triggers,
                    },
                },
                headers={
                    "Authorization": f"Bearer {id_token}",
                    "Content-Type": "application/json",
                },
            )
            pushed = resp.status_code == 200
        except Exception as e:
            return {
                "status": "push_failed",
                "error": str(e),
                "onset_adjustments": onset_adjustments,
                "note": "Weights computed but failed to push to Firestore",
            }

    return {
        "status": "ok",
        "substitutions": [{"app": a, "intended": i} for a, i in subs],
        "onset_adjustments": onset_adjustments,
        "new_triggers": new_triggers,
        "total_onsets_in_profile": len(current_weights),
        "total_triggers": len(current_triggers),
        "pushed_to_firestore": pushed,
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Tool 9: therapist_query_profile
# ═══════════════════════════════════════════════════════════════════════════════


@mcp.tool()
async def therapist_query_profile(uid: str, id_token: str) -> dict:
    """Query a user's speech profile — onset weights, trigger words, corrections.

    Returns the full profile as stored in Firestore, with onset weights
    sorted by difficulty (hardest first). Use before/after therapist_correct
    to see the effect of corrections.

    Args:
        uid: Firebase user ID
        id_token: Firebase ID token for backend auth

    Returns: {onset_weights, trigger_words, corrections, vocabulary, ...}
    """
    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            resp = await client.post(
                BACKEND_URL,
                json={"action": "export_data"},
                headers={"Authorization": f"Bearer {id_token}"},
            )
            if resp.status_code != 200:
                return {"status": "error", "http_status": resp.status_code, "body": resp.text[:200]}

            data = resp.json()
        except Exception as e:
            return {"status": "error", "error": str(e)}

    # Sort onset weights by difficulty (hardest first)
    raw_weights = data.get("onset_weights", {})
    sorted_weights = sorted(
        [(str(k), float(v)) for k, v in raw_weights.items()],
        key=lambda x: x[1],
        reverse=True,
    )

    trigger_words = data.get("trigger_words", [])
    corrections = data.get("corrections", {})
    vocabulary = data.get("vocabulary", [])
    covert_profile = data.get("covert_profile", {})

    return {
        "status": "ok",
        "uid": uid,
        "onset_weights": [{"onset": k, "weight": v} for k, v in sorted_weights],
        "onset_count": len(sorted_weights),
        "trigger_words": trigger_words,
        "trigger_count": len(trigger_words),
        "corrections": corrections,
        "correction_count": len(corrections),
        "vocabulary_count": len(vocabulary),
        "covert_profile": covert_profile,
        "top_5_hardest": [f"/{k}/ ({int(v*100)}%)" for k, v in sorted_weights[:5]],
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Entry point
# ═══════════════════════════════════════════════════════════════════════════════


def main():
    mcp.run()


if __name__ == "__main__":
    main()
