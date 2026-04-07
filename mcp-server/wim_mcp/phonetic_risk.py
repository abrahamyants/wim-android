"""
Phonetic risk prediction using Brown's 4-feature model.

Ported 1:1 from PhoneticRisk.kt (com.wim.app).

Brown (1945) identified 4 features that predict word difficulty for PWS:
  1. Initial consonant (plosives, affricates, clusters are hardest)
  2. Content words vs function words (content words harder)
  3. Word length (longer = harder)
  4. Sentence position (earlier in clause = harder)
"""

import re

# High-risk initial onsets (English)
HIGH_RISK_ONSETS: dict[str, float] = {
    # Unvoiced plosives (hardest)
    "p": 0.85, "t": 0.80, "k": 0.85, "c": 0.85,
    # Voiced plosives
    "b": 0.70, "d": 0.65, "g": 0.70,
    # Affricates
    "ch": 0.75, "j": 0.70,
    # Clusters at onset
    "bl": 0.80, "br": 0.80, "cl": 0.80, "cr": 0.80,
    "dr": 0.75, "fl": 0.70, "fr": 0.70, "gl": 0.75,
    "gr": 0.75, "pl": 0.85, "pr": 0.85, "sc": 0.75,
    "sk": 0.75, "sl": 0.70, "sm": 0.70, "sn": 0.70,
    "sp": 0.80, "st": 0.80, "str": 0.90, "sw": 0.70,
    "tr": 0.80, "tw": 0.75, "thr": 0.85, "shr": 0.80,
    "scr": 0.90, "spl": 0.90, "spr": 0.90,
}

FUNCTION_WORDS = {
    "the", "a", "an", "and", "or", "but", "so", "if", "when",
    "is", "are", "was", "were", "be", "been", "am", "do", "does",
    "did", "have", "has", "had", "can", "could", "will", "would",
    "to", "of", "in", "on", "at", "by", "for", "with", "from",
    "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
    "my", "your", "his", "its", "our", "their", "this", "that", "these", "those",
}

_WORD_RE = re.compile(r"\b\w+\b")


def extract_onset(word: str) -> str:
    """Extract the initial onset (1-3 chars of consonants) from a word."""
    lower = word.lower()
    if not lower:
        return ""
    for length in (3, 2, 1):
        if len(lower) >= length:
            candidate = lower[:length]
            if candidate in HIGH_RISK_ONSETS:
                return candidate
    return lower[0]


def predict_phonetic_risk(
    word: str,
    sentence_position: int | None = None,
    sentence_length: int | None = None,
    personal_onset_weights: dict[str, float] | None = None,
) -> float:
    """Score phonetic risk for a word (0.0 = easy, 1.0 = hard)."""
    personal_onset_weights = personal_onset_weights or {}
    clean = word.lower().strip()
    if len(clean) < 2:
        return 0.0

    # Feature 1: onset difficulty
    onset = extract_onset(clean)
    risk = personal_onset_weights.get(onset, HIGH_RISK_ONSETS.get(onset, 0.3))

    # Feature 2: function words are easier
    if clean in FUNCTION_WORDS:
        risk *= 0.4

    # Feature 3: word length
    length_boost = min(len(clean) / 10.0, 0.2)
    risk = min(risk + length_boost, 1.0)

    # Feature 4: sentence position (first 20% of clause = harder)
    if (
        sentence_position is not None
        and sentence_length is not None
        and sentence_length > 0
    ):
        position_frac = sentence_position / sentence_length
        if position_frac < 0.2:
            risk = min(risk + 0.15, 1.0)

    return risk


def predict_triggers_in_text(
    text: str,
    existing_triggers: list[str] | None = None,
    personal_onset_weights: dict[str, float] | None = None,
    threshold: float = 0.6,
) -> list[tuple[str, float]]:
    """Score all content words in text, return predicted triggers above threshold.

    Returns list of (word, risk) sorted by risk DESC.
    """
    existing_triggers = existing_triggers or []
    personal_onset_weights = personal_onset_weights or {}
    words = _WORD_RE.findall(text)
    known = {t.lower() for t in existing_triggers}
    predicted: list[tuple[str, float]] = []

    for i, word in enumerate(words):
        clean = word.lower()
        if len(clean) < 2:
            continue

        risk = predict_phonetic_risk(clean, i, len(words), personal_onset_weights)

        # Onset boost: shared first 2 letters with known trigger
        if len(clean) >= 2:
            onset2 = clean[:2]
            for trigger in known:
                if len(trigger) >= 2 and trigger[:2] == onset2 and clean != trigger:
                    risk = min(risk + 0.2, 1.0)
                    break

        if risk >= threshold:
            predicted.append((clean, int(risk * 100) / 100.0))

    return sorted(predicted, key=lambda x: x[1], reverse=True)
