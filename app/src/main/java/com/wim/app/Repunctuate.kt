package com.wim.app

/**
 * Lightweight deterministic capitalization + terminal punctuation.
 *
 * Targets local ASR engines that emit flat lowercase text with no punctuation
 * (whisper-tiny via sherpa-onnx, on-device SpeechRecognizer in some modes).
 * Cheap (~10us per typical utterance), no model load, no cloud round-trip.
 * Idempotent — skips text that already has BOTH a capital letter and terminal
 * punctuation.
 *
 * Adds:
 *   - Leading capital
 *   - Capitalized standalone "I" + "I'm/I'll/I've/I'd"
 *   - Capitalize after . ! ? followed by space
 *   - Terminal "." or "?" (? if the first word is a question starter)
 *
 * Verbatim port of `repunctuate()` in lavrentiy.py (commit c75d643).
 */
object Repunctuate {

    private val QUESTION_STARTERS = setOf(
        "who", "what", "when", "where", "why", "which", "how",
        "is", "are", "was", "were", "do", "does", "did",
        "can", "could", "should", "would", "will", "have", "has",
        "may", "might", "shall"
    )

    private val STANDALONE_I = Regex("\\bi\\b")
    private val I_CONTRACTION = Regex("\\bi(['\u2019])")
    private val AFTER_TERMINAL = Regex("([.!?]\\s+)([a-z])")
    private val FIRST_WORD = Regex("\\W+")

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        var s = text.trim()
        if (s.isEmpty()) return s

        val hasTerm = s.last() in ".!?"
        val hasCap = s.any { it.isUpperCase() }
        if (hasTerm && hasCap) return s

        s = STANDALONE_I.replace(s, "I")
        s = I_CONTRACTION.replace(s) { m -> "I${m.groupValues[1]}" }

        if (s.isNotEmpty() && s[0].isLetter()) {
            s = s[0].uppercaseChar() + s.substring(1)
        }

        s = AFTER_TERMINAL.replace(s) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }

        if (s.isNotEmpty() && s.last() !in ".!?") {
            val firstWord = s.split(FIRST_WORD, limit = 2).firstOrNull()?.lowercase().orEmpty()
            s += if (firstWord in QUESTION_STARTERS) "?" else "."
        }
        return s
    }
}
