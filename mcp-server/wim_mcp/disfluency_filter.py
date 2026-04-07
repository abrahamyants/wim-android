"""
Pre-reconstruction disfluency stripping.

Ported 1:1 from DisfluencyFilter.kt (com.wim.app).

Handles:
  1. Stutter fragments:   "p- p- pop"          -> "pop"
  2. Word repetitions:    "I I I want"          -> "I want"
  3. Phrase repetitions:  "I want I want to go" -> "I want to go"
  4. Filler words:        "um I want uh a dog"  -> "I want a dog"
  5. False starts:        "I was going to-- I went to the store" -> "I went to the store"
  6. Prolongations:       "mmmmaybe" -> "maybe"
"""

import re

# Grammatically valid repeated words — do NOT strip
NATURAL_REPEATS = {
    "had had", "that that", "is is", "was was", "do do",
    "can can", "no no", "bye bye", "so so", "very very",
    "go go", "now now", "come come", "well well",
    "out out", "boo boo", "ha ha", "ho ho",
    "knock knock", "tsk tsk", "aye aye",
    # Russian
    "да да", "нет нет", "ну ну",
}

# Step 1: Stutter fragments — "p- p- pop" -> "pop"
_STUTTER_FRAGMENT = re.compile(r"(\b\w+)-\s+(?:\1-\s+)*", re.IGNORECASE)

# Step 2: Consecutive word repetitions — "I I I want" -> "I want"
_WORD_REPEAT = re.compile(r"\b(\w+)(?:\s+\1)+\b", re.IGNORECASE)

# Step 3: Phrase repetitions (2-3 word phrases)
_PHRASE_REPEAT = re.compile(r"\b(\w+\s+\w+(?:\s+\w+)?)\s+\1\b", re.IGNORECASE)

# Step 5: False starts — clause + double-dash + restart
_FALSE_START = re.compile(
    r"[A-Za-z\u0400-\u04FF][^.!?]*?-{2,}\s*(?=[A-Z\u0410-\u042F])",
    re.DOTALL,
)

# Step 6: Prolongations — "mmmmaybe" -> "maybe"
_PROLONGATION = re.compile(r"\b(\w)\1{2,}(\w*)", re.IGNORECASE)

_MULTI_SPACE = re.compile(r"\s{2,}")


def strip_block_hallucinations(
    text: str,
    low_conf_segments: list[dict],
) -> str:
    """Remove short hallucinated filler phrases from Whisper block-suspect segments.

    Each segment dict should have keys: text, avg_logprob, no_speech_prob, block_suspect.
    """
    if not text or not low_conf_segments:
        return text
    result = text
    for seg in low_conf_segments:
        if not seg.get("block_suspect"):
            continue
        seg_text = seg.get("text", "").strip()
        word_count = len([w for w in seg_text.split() if w])
        if word_count >= 5 or not seg_text:
            continue
        pattern = re.compile(r"\b" + re.escape(seg_text) + r"\b", re.IGNORECASE)
        if pattern.search(result):
            result = pattern.sub("", result)
    return _MULTI_SPACE.sub(" ", result).strip()


def filter_disfluencies(text: str, filler_words: list[str] | None = None) -> str:
    """Strip disfluencies from raw ASR text.

    Args:
        text: raw speech-to-text output
        filler_words: list of filler words to strip (from user profile)

    Returns:
        cleaned text with disfluencies removed
    """
    if not text or not text.strip():
        return text

    filler_words = filler_words or []

    # Step 1: stutter fragments
    cleaned = _STUTTER_FRAGMENT.sub("", text)

    # Step 2: word repetitions (protect natural repeats)
    def _word_repeat_repl(m: re.Match) -> str:
        word = m.group(1).lower()
        pair = f"{word} {word}"
        return m.group(0) if pair in NATURAL_REPEATS else m.group(1)

    cleaned = _WORD_REPEAT.sub(_word_repeat_repl, cleaned)

    # Step 3: phrase repetitions
    cleaned = _PHRASE_REPEAT.sub(lambda m: m.group(1), cleaned)

    # Step 4: filler words
    filler_set = {w.lower() for w in filler_words}
    words = cleaned.split()
    filtered: list[str] = []
    for w in words:
        w_lower = w.lower().rstrip(".,!?;:")
        if w_lower in filler_set:
            if len(words) == 1:
                filtered.append(w)
            continue
        filtered.append(w)
    cleaned = " ".join(filtered).strip()

    # Step 5: false starts
    cleaned = _FALSE_START.sub("", cleaned)

    # Step 6: prolongations
    cleaned = _PROLONGATION.sub(lambda m: m.group(1) + m.group(2), cleaned)

    # Collapse spaces
    cleaned = _MULTI_SPACE.sub(" ", cleaned).strip()

    return cleaned if cleaned else text
