package com.wim.app

/**
 * Pure-data clarifier — spec §6 Phase 2 step 11, claim 11.
 *
 * Takes the just-injected text + per-word confidence signals + the user's
 * vocabulary, and returns the bounded subset of words ([MAX_FLAGS] cap)
 * that the user should review. No Android imports — unit-testable as
 * a plain Kotlin object.
 *
 * Design lock from spec §6 Phase 2 acceptance:
 *   "NO flags fire on clean dictation" — uncertainty thresholds are tuned
 *   to under-flag rather than over-flag. The typical utterance produces
 *   zero flagged words.
 *
 * Phase 2 ships the data shape + evaluation pipeline + threshold tuning.
 * Real per-word confidence comes from Phase 2 step 12 (word timings via
 * SpeechRecognizerL1 verbose_json) and Phase 4 step 26 (multi-pass
 * decoder). Until those land, [evaluate] returns empty for any input
 * lacking [WordConfidence] data — the cycle UX is exercised via
 * [WimTestReceiver]'s `trigger_clarifier` synthetic broadcast.
 */
object Clarifier {

    /** No more than 3 words ever flagged in a single cycle. Claim 11(c). */
    const val MAX_FLAGS = 3

    /**
     * Combined uncertainty score required to surface a flag. Sub-threshold
     * words stay invisible. Tuned to produce ZERO flags on the typical
     * fluent utterance — false-positive cost is high (annoying), false-
     * negative cost is low (user just doesn't see the flag).
     */
    const val UNCERTAINTY_THRESHOLD = 0.55

    data class WordConfidence(
        val word: String,
        val rangeStart: Int,         // char offset in the injected text
        val rangeEnd: Int,           // exclusive
        val asrLogprob: Double?,     // closer-to-zero = more confident; null when unknown
        val multiPassDisagreements: Int = 0,  // count of decoders that disagreed; 0 = full agreement
        val nBestAlternates: List<String> = emptyList(),
        val phoneticNeighbors: List<String> = emptyList(),
    )

    /**
     * Compute [FlaggedWord]s for the given utterance.
     *
     * @param finalText the text injected into the host app
     * @param wordConfidences per-word ASR confidence (empty = no flagging)
     * @param vocabulary the user's per-app learned vocabulary
     * @param onsetWeights per-phoneme difficulty for THIS speaker
     * @param recentSwaps last few approve+reject decisions (close the loop)
     * @param candidateProvider optional sentence-boundary candidate source (Phase 5 §4)
     * @param contextPkg foreground app package, for [candidateProvider]
     */
    fun evaluate(
        finalText: String,
        wordConfidences: List<WordConfidence>,
        vocabulary: List<String> = emptyList(),
        onsetWeights: Map<String, Double> = emptyMap(),
        recentSwaps: List<Pair<String, String>> = emptyList(),
        candidateProvider: CandidateProvider? = null,
        contextPkg: String? = null,
        threshold: Double = UNCERTAINTY_THRESHOLD,
    ): List<FlaggedWord> {
        if (wordConfidences.isEmpty()) return emptyList()
        val rejected = recentSwaps.filter { it.second.isBlank() }.map { it.first.lowercase() }.toSet()
        val priorWordsByPosition = finalText.split(Regex("\\s+"))

        val candidates = wordConfidences.mapIndexedNotNull { idx, wc ->
            val score = uncertaintyScore(wc, vocabulary, onsetWeights)
            if (score < threshold) return@mapIndexedNotNull null
            val candidates = buildCandidates(
                wc = wc,
                rejected = rejected,
                idx = idx,
                priorWords = priorWordsByPosition,
                contextPkg = contextPkg,
                provider = candidateProvider,
            )
            if (candidates.isEmpty()) return@mapIndexedNotNull null
            FlaggedWord(
                originalWord = wc.word,
                candidates = candidates,
                rangeStart = wc.rangeStart,
                rangeEnd = wc.rangeEnd,
                uncertaintyScore = score,
            )
        }

        // Top-N by uncertainty. Cap at MAX_FLAGS (claim 11(c)).
        return candidates
            .sortedByDescending { it.uncertaintyScore }
            .take(MAX_FLAGS)
    }

    /**
     * Combine ASR signals into a 0..1 uncertainty score. Higher = more
     * uncertain = more likely to surface as a flag. Components:
     *   - ASR log-prob normalized into 0..1 (logprob -3 → ~0.95 uncertain)
     *   - Multi-pass disagreement count / 3 (full agreement = 0)
     *   - Phonetic-neighbor presence in user vocab adds a small uncertainty
     *     boost (the word the user said is plausibly a different word in
     *     their own vocabulary)
     *   - Word matches user's onset-weights for hard phonemes (small boost)
     */
    private fun uncertaintyScore(
        wc: WordConfidence,
        vocabulary: List<String>,
        onsetWeights: Map<String, Double>,
    ): Double {
        var score = 0.0
        wc.asrLogprob?.let { lp ->
            // Logprob is non-positive; -0 = perfectly confident, -3 = very unsure.
            // Map into 0..1 with a soft saturation around -1.5.
            val normalized = (-lp / 1.5).coerceIn(0.0, 1.0)
            score += 0.55 * normalized
        }
        if (wc.multiPassDisagreements > 0) {
            score += 0.30 * (wc.multiPassDisagreements.coerceAtMost(3) / 3.0)
        }
        if (wc.phoneticNeighbors.isNotEmpty()) {
            // Phonetic-neighbor in the user's vocab → likely-misheard bonus.
            score += 0.10
        }
        if (vocabulary.isNotEmpty() && wc.word.lowercase() !in vocabulary.map { it.lowercase() }) {
            // Word isn't in the user's known-good vocab → small uncertainty.
            score += 0.05
        }
        // Hard-onset boost: speaker's hardest phonemes have known unreliable
        // Whisper output (per Lavrentiy clinical notes).
        val firstChar = wc.word.firstOrNull()?.lowercase() ?: ""
        onsetWeights[firstChar]?.let { weight ->
            score += 0.10 * weight
        }
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Build the ordered candidates list per claim 14 priority:
     *   1. Sentence-boundary predictions (this user's history in this app)
     *   2. ASR n-best alternates
     *   3. Phonetic neighbors from per-user vocab
     * Filtered against [rejected] (close the feedback loop — don't suggest
     * something the user just rejected in a recent cycle).
     */
    private fun buildCandidates(
        wc: WordConfidence,
        rejected: Set<String>,
        idx: Int,
        priorWords: List<String>,
        contextPkg: String?,
        provider: CandidateProvider?,
    ): List<String> {
        val out = LinkedHashSet<String>()
        if (provider != null && contextPkg != null && atSentenceBoundary(wc, priorWords, idx)) {
            provider.candidatesForPosition(
                pkg = contextPkg,
                priorWords = priorWords.take(idx).takeLast(8),
                position = idx,
                maxCandidates = 3,
            ).forEach { out.add(it) }
        }
        wc.nBestAlternates.forEach { out.add(it) }
        wc.phoneticNeighbors.forEach { out.add(it) }
        // Strip the original word + previously rejected words.
        out.removeAll { it.equals(wc.word, ignoreCase = true) || it.lowercase() in rejected }
        return out.toList().take(3)
    }

    private fun atSentenceBoundary(
        wc: WordConfidence,
        priorWords: List<String>,
        idx: Int,
    ): Boolean {
        if (idx == 0) return true
        val prev = priorWords.getOrNull(idx - 1) ?: return false
        return prev.lastOrNull() in setOf('.', '?', '!')
    }
}

/**
 * Spec §4 — sentence-boundary candidate source. Empty default impl is
 * fine; Phase 5 wires `PredictionEngine` as the real provider.
 */
fun interface CandidateProvider {
    fun candidatesForPosition(
        pkg: String,
        priorWords: List<String>,
        position: Int,
        maxCandidates: Int,
    ): List<String>
}
