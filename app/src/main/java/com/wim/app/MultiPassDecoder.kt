package com.wim.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Multi-pass decoder — spec §6 Phase 4 step 26 (claim 11(a) ASR signal:
 * multi-pass decoding agreement counts).
 *
 * Calls a [SpeechRecognizerL1] N times in parallel with different
 * sampling temperatures. Where every pass agrees on a word = high
 * confidence. Where they diverge = uncertainty signal that feeds the
 * [Clarifier]'s `multiPassDisagreements` field.
 *
 * Engine-agnostic: the decoder doesn't know whether it's wrapping cloud
 * whisper-1, on-device whisper-tiny, or any other implementation. It
 * only consumes the [SpeechRecognizerL1] interface.
 *
 * Cost note (cloud whisper-1 specifically): 3× the whisper-1 cost on
 * L4 only (~$0.018/min instead of $0.006/min). L4 is clinical / heavy-
 * disfluency, justified. L1-L3 paths do NOT call this — too expensive
 * for the marginal accuracy lift on fluent speech.
 */
class MultiPassDecoder(
    private val recognizer: SpeechRecognizerL1,
    private val temperatures: List<Double> = DEFAULT_TEMPS,
) {

    companion object {
        val DEFAULT_TEMPS = listOf(0.0, 0.4, 0.8)
    }

    data class Pass(val temperature: Double, val result: TranscribeResult)

    data class MultiPassResult(
        /** The primary transcription (temperature 0 unless temperatures was reordered). */
        val primary: TranscribeResult,
        /** Every pass, in input-temperature order. */
        val passes: List<Pass>,
        /** One [Clarifier.WordConfidence] per primary-text word, with
         *  multiPassDisagreements populated from cross-pass alignment. */
        val wordConfidences: List<Clarifier.WordConfidence>,
        /** Plain string-list view: `"position N: variant1|variant2|..."` for
         *  each disagreement position. Convenient for sending to the
         *  reconstruction backend's `whisper_disagreements` field. */
        val disagreementSummary: List<String>,
    )

    suspend fun run(audioFile: File): MultiPassResult = coroutineScope {
        val deferred = temperatures.map { temp ->
            async { Pass(temp, recognizer.transcribe(audioFile, requestWordTimings = true, temperature = temp)) }
        }
        val passes = deferred.awaitAll()
        val primary = passes.firstOrNull()?.result
            ?: TranscribeResult(text = "", asrMs = 0)

        if (passes.size < 2) {
            return@coroutineScope MultiPassResult(
                primary = primary,
                passes = passes,
                wordConfidences = emptyList(),
                disagreementSummary = emptyList(),
            )
        }

        val texts = passes.map { it.result.text }
        // Quick bail when all passes returned identical text — full agreement.
        if (texts.toSet().size == 1) {
            return@coroutineScope MultiPassResult(
                primary = primary,
                passes = passes,
                wordConfidences = wordConfidencesFromPrimary(primary, disagreements = IntArray(0), variants = emptyList()),
                disagreementSummary = emptyList(),
            )
        }

        // Tokenize each pass to lowercase word-only lists (strip punctuation).
        val tokenLists = texts.map { it.lowercase().split(Regex("\\s+")).filter { tok -> tok.any(Char::isLetterOrDigit) } }
        val maxLen = tokenLists.maxOf { it.size }
        val disagreementPositions = mutableListOf<Int>()
        val variantsByPosition = mutableListOf<List<String>>()
        for (pos in 0 until maxLen) {
            val wordsAtPos = tokenLists.map { it.getOrNull(pos) ?: "<END>" }
            if (wordsAtPos.toSet().size > 1) {
                disagreementPositions += pos
                variantsByPosition += wordsAtPos
            }
        }

        val wordConfs = wordConfidencesFromPrimary(
            primary = primary,
            disagreements = disagreementPositions.toIntArray(),
            variants = variantsByPosition,
        )

        val summary = disagreementPositions.zip(variantsByPosition).map { (pos, variants) ->
            "position $pos: ${variants.joinToString("|")}"
        }
        MultiPassResult(
            primary = primary,
            passes = passes,
            wordConfidences = wordConfs,
            disagreementSummary = summary,
        )
    }

    /**
     * Build [Clarifier.WordConfidence] per primary-text word. Sets
     * `multiPassDisagreements` to the count of unique variants at that
     * position (1 = all agreed, 2 = two-way split, 3 = three-way split).
     * `nBestAlternates` is populated from the variants list (excluding the
     * primary's word itself).
     *
     * Range offsets are character positions in the primary text.
     */
    private fun wordConfidencesFromPrimary(
        primary: TranscribeResult,
        disagreements: IntArray,
        variants: List<List<String>>,
    ): List<Clarifier.WordConfidence> {
        val text = primary.text
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Clarifier.WordConfidence>()
        val disagreementSet = disagreements.toHashSet()
        val variantsByIdx = disagreements.indices.associate { idx -> disagreements[idx] to variants[idx] }
        // Walk the text, splitting on whitespace to find word ranges.
        var idx = 0
        var pos = 0
        while (pos < text.length) {
            // Skip whitespace.
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length) break
            val start = pos
            while (pos < text.length && !text[pos].isWhitespace()) pos++
            val word = text.substring(start, pos)
            // Trim trailing punctuation but keep its range so swap leaves it.
            val trimmedEnd = run {
                var e = pos
                while (e > start && !text[e - 1].isLetterOrDigit()) e--
                e.coerceAtLeast(start + 1)
            }
            val cleanWord = text.substring(start, trimmedEnd)
            val multi = variantsByIdx[idx]?.toSet()?.size ?: 1
            val nBest = variantsByIdx[idx]
                ?.filter { !it.equals(cleanWord, ignoreCase = true) && it != "<END>" }
                ?.distinct()
                .orEmpty()
            out.add(
                Clarifier.WordConfidence(
                    word = cleanWord,
                    rangeStart = start,
                    rangeEnd = trimmedEnd,
                    asrLogprob = null, // Phase 5/6 — surface from verbose_json segment logprobs
                    multiPassDisagreements = if (multi > 1) multi else 0,
                    nBestAlternates = nBest,
                    phoneticNeighbors = emptyList(),
                ),
            )
            idx++
        }
        return out
    }
}

