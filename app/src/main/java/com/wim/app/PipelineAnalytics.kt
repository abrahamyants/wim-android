package com.wim.app

/**
 * Pure-function analytics helpers used by the BubbleService pipeline at
 * post-inject time. None of these touch service state — all inputs are
 * parameters, all outputs are values. Side effects (PredictionEngine
 * mutations) are explicit and limited to one entry point.
 *
 * Extracted from BubbleService 2026-05-25 as the seventeenth slice.
 */
object PipelineAnalytics {

    /** Jaccard similarity (word-level, case-insensitive) between two
     *  strings. Returns 1.0 for two empty strings; 0.0 when both sides
     *  are non-empty but share no words. Used by the redo-detection
     *  heuristic to spot the user re-recording a near-identical
     *  utterance. */
    fun jaccard(a: String, b: String): Float {
        val setA = a.lowercase().split("\\s+".toRegex()).toSet()
        val setB = b.lowercase().split("\\s+".toRegex()).toSet()
        if (setA.isEmpty() && setB.isEmpty()) return 1f
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    /**
     * Walk a reconstructed output, locate sentence boundaries, and record
     * each (last-3-words-before-period, next-word) trigram into
     * [PredictionEngine] for context-aware prediction. Quiet on edge
     * cases (single-sentence output, ending punctuation, blank tokens) —
     * the goal is clean trigrams when they exist, not perfect coverage.
     *
     * Side effect: calls `PredictionEngine.recordCompletion(...)` per
     * trigram found. Caller controls when this runs (typically once per
     * successful inject).
     */
    fun recordPredictionsFromOutput(pkg: String, text: String) {
        if (text.isBlank()) return
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in 0 until tokens.size - 1) {
            val tok = tokens[i]
            if (tok.lastOrNull() in setOf('.', '?', '!')) {
                val priorStart = (i - 2).coerceAtLeast(0)
                val priorWords = tokens.subList(priorStart, i + 1)
                val rawNext = tokens[i + 1]
                // Strip any leading/trailing punctuation off the next word
                // so "Maybe." doesn't end up as a different trigram seed
                // than "maybe".
                val nextClean = rawNext.trim('"', '\'', '(', ')', ',', '.', '!', '?', ';', ':')
                if (nextClean.isNotBlank()) {
                    PredictionEngine.recordCompletion(pkg, priorWords, nextClean)
                }
            }
        }
    }
}
