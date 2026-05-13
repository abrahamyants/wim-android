package com.wim.app

/**
 * Re-map [Clarifier.WordConfidence] offsets from the source they were built
 * against (the Whisper-1 multi-pass primary transcript) to a different target
 * string (typically the GPT-reconstructed `outputText` actually injected).
 *
 * Background — the pr_code_review_2026-05-10b audit's P0-1: `MultiPassDecoder`
 * returns `WordConfidence.rangeStart/rangeEnd` indexed into `primary.text`
 * (Whisper raw). `BubbleService.processText` then hands those ranges to
 * `Clarifier.evaluate(finalText = outputText, ...)` and the resulting flags
 * carry the wrong offsets, so when the user approves a swap,
 * `swapWordInLastInjection` corrupts the injected text.
 *
 * This helper closes that gap with a simple left-to-right exact-token match
 * (case-insensitive). Words present in the Whisper transcript but absent in
 * `outputText` (filler, disfluency) are silently dropped — strictly better
 * than swapping wrong characters in `outputText`.
 *
 * Doesn't handle reorderings or substitutions. For those, the entry is
 * dropped and the clarifier under-flags rather than mis-flags. Safe default.
 */
fun alignWordConfidencesToText(
    wordConfidences: List<Clarifier.WordConfidence>,
    targetText: String,
): List<Clarifier.WordConfidence> {
    if (wordConfidences.isEmpty() || targetText.isBlank()) return emptyList()

    data class TargetToken(val word: String, val start: Int, val end: Int)
    val targetTokens = mutableListOf<TargetToken>()
    var pos = 0
    while (pos < targetText.length) {
        while (pos < targetText.length && targetText[pos].isWhitespace()) pos++
        if (pos >= targetText.length) break
        val start = pos
        while (pos < targetText.length && !targetText[pos].isWhitespace()) pos++
        // Trim trailing punctuation to match MultiPassDecoder.wordConfidencesFromPrimary
        var trimmedEnd = pos
        while (trimmedEnd > start && !targetText[trimmedEnd - 1].isLetterOrDigit()) trimmedEnd--
        val cleanEnd = trimmedEnd.coerceAtLeast(start + 1)
        val cleanWord = targetText.substring(start, cleanEnd)
        if (cleanWord.any(Char::isLetterOrDigit)) {
            targetTokens.add(TargetToken(cleanWord, start, cleanEnd))
        }
    }
    if (targetTokens.isEmpty()) return emptyList()

    // Left-to-right token consumption: each target token can match at most one
    // input wordConfidence. Prevents the same target word from being flagged
    // twice when the Whisper transcript repeated it ("I I I want").
    val consumed = BooleanArray(targetTokens.size)
    val out = mutableListOf<Clarifier.WordConfidence>()
    for (wc in wordConfidences) {
        val matchIdx = targetTokens.indices.firstOrNull { i ->
            !consumed[i] && targetTokens[i].word.equals(wc.word, ignoreCase = true)
        } ?: continue
        consumed[matchIdx] = true
        val matched = targetTokens[matchIdx]
        out.add(
            wc.copy(
                word = matched.word,
                rangeStart = matched.start,
                rangeEnd = matched.end,
            )
        )
    }
    return out
}
