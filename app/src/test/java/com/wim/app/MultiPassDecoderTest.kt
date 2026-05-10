package com.wim.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for [MultiPassDecoder].
 *
 * No Android dependencies. Coroutines exercised via runBlocking.
 * [SpeechRecognizerL1] is implemented inline as a lambda-based fake.
 */
class MultiPassDecoderTest {

    // ── Fake recognizer helpers ─────────────────────────

    /** Returns the same fixed text for every temperature. */
    private fun fixedRecognizer(text: String): SpeechRecognizerL1 = object : SpeechRecognizerL1 {
        override suspend fun transcribe(
            audioFile: File,
            requestWordTimings: Boolean,
            temperature: Double,
        ): TranscribeResult = TranscribeResult(text = text, asrMs = 10)
        override val supportsWordTimings = false
        override val supportsLogprobs = false
        override val isLocal = true
    }

    /**
     * Returns different text based on temperature index.
     * [textByTemp] maps temperature → response text; missing temperatures
     * fall back to the first entry.
     */
    private fun multiRecognizer(textByTemp: Map<Double, String>): SpeechRecognizerL1 {
        val fallback = textByTemp.values.first()
        return object : SpeechRecognizerL1 {
            override suspend fun transcribe(
                audioFile: File,
                requestWordTimings: Boolean,
                temperature: Double,
            ): TranscribeResult = TranscribeResult(
                text = textByTemp[temperature] ?: fallback,
                asrMs = 10,
            )
            override val supportsWordTimings = false
            override val supportsLogprobs = false
            override val isLocal = true
        }
    }

    private val dummyFile = File("dummy.wav")

    // ── All-agreement fast path ─────────────────────────

    @Test fun allAgreement_emptyDisagreementSummary() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        assertTrue(
            "All-agreement should produce empty disagreement summary",
            result.disagreementSummary.isEmpty()
        )
    }

    @Test fun allAgreement_wordConfidencesMatchPrimaryWords() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        assertEquals(2, result.wordConfidences.size)
        assertEquals("hello", result.wordConfidences[0].word)
        assertEquals("world", result.wordConfidences[1].word)
    }

    @Test fun allAgreement_zeroDisagreementsOnEachWord() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        for (wc in result.wordConfidences) {
            assertEquals(
                "All-agreement should produce 0 multiPassDisagreements on every word",
                0,
                wc.multiPassDisagreements
            )
        }
    }

    // ── wordConfidencesFromPrimary — character ranges ───

    @Test fun wordConfidences_rangeStart_correctForFirstWord() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        assertEquals(0, result.wordConfidences[0].rangeStart)
    }

    @Test fun wordConfidences_rangeEnd_correctForFirstWord() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        // "hello" ends at index 5
        assertEquals(5, result.wordConfidences[0].rangeEnd)
    }

    @Test fun wordConfidences_rangeStart_correctForSecondWord() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        // "world" starts at index 6
        assertEquals(6, result.wordConfidences[1].rangeStart)
    }

    @Test fun wordConfidences_rangeEnd_correctForSecondWord() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello world"))
        val result = decoder.run(dummyFile)
        // "world" ends at index 11
        assertEquals(11, result.wordConfidences[1].rangeEnd)
    }

    @Test fun wordConfidences_trailingPunctuation_trimmedFromRangeEnd() = runBlocking {
        // "hello." — rangeEnd should stop before the period
        val decoder = MultiPassDecoder(fixedRecognizer("hello. world"))
        val result = decoder.run(dummyFile)
        val first = result.wordConfidences[0]
        assertEquals("hello", first.word)
        // rangeEnd trims punctuation: "hello." runs 0..5 (letter range only)
        assertEquals(5, first.rangeEnd)
    }

    @Test fun wordConfidences_punctuationToken_rangeStartAtTokenStart() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello. world"))
        val result = decoder.run(dummyFile)
        // "hello." starts at 0
        assertEquals(0, result.wordConfidences[0].rangeStart)
        // "world" starts at 7
        assertEquals(7, result.wordConfidences[1].rangeStart)
    }

    // ── nBestAlternates — filtering ─────────────────────

    @Test fun nBestAlternates_filtersOutPrimaryWord() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello world",
                0.4 to "hello earth",
                0.8 to "hello globe",
            ))
        )
        val result = decoder.run(dummyFile)
        // Position 1 disagrees: primary says "world", alternates are "earth" and "globe"
        val secondWord = result.wordConfidences[1]
        assertFalse(
            "nBestAlternates must not include the primary word",
            secondWord.nBestAlternates.any { it.equals("world", ignoreCase = true) }
        )
    }

    @Test fun nBestAlternates_filtersOutEndSentinel() = runBlocking {
        // One pass has fewer words, producing <END> at that position
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello world foo",
                0.4 to "hello world",      // <END> at pos 2
                0.8 to "hello world bar",
            ))
        )
        val result = decoder.run(dummyFile)
        val thirdWord = result.wordConfidences[2]
        assertFalse(
            "nBestAlternates must not include <END>",
            thirdWord.nBestAlternates.contains("<END>")
        )
    }

    @Test fun nBestAlternates_containsActualAlternate() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello world",
                0.4 to "hello earth",
                0.8 to "hello earth",
            ))
        )
        val result = decoder.run(dummyFile)
        val secondWord = result.wordConfidences[1]
        assertTrue(
            "nBestAlternates should include 'earth'",
            secondWord.nBestAlternates.contains("earth")
        )
    }

    // ── Disagreement detection ──────────────────────────

    @Test fun disagreement_marksCorrectPosition() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello world",
                0.4 to "hello earth",
                0.8 to "hello earth",
            ))
        )
        val result = decoder.run(dummyFile)
        // Position 0 ("hello") agrees; position 1 disagrees
        assertEquals(0, result.wordConfidences[0].multiPassDisagreements)
        assertTrue(result.wordConfidences[1].multiPassDisagreements > 0)
    }

    @Test fun disagreementSummary_containsPositionLabel() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello world",
                0.4 to "hello earth",
                0.8 to "hello earth",
            ))
        )
        val result = decoder.run(dummyFile)
        assertEquals(1, result.disagreementSummary.size)
        assertTrue(result.disagreementSummary[0].startsWith("position 1:"))
    }

    // ── Off-by-one: punctuation token vs word position ─

    @Test fun punctuationToken_doesNotShiftDisagreementPosition() = runBlocking {
        // Primary has a comma after "hello" — the tokenizer strips punctuation,
        // so "hello," maps to word idx 0 and "world" maps to idx 1.
        // The disagreement walker also strips punctuation per spec, so positions align.
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello, world",
                0.4 to "hello, earth",
                0.8 to "hello, earth",
            ))
        )
        val result = decoder.run(dummyFile)
        // Primary word at idx 1 is "world"; it should be flagged as disagreement position 1
        val second = result.wordConfidences[1]
        assertEquals("world", second.word)
        assertTrue(
            "Punctuation token should not break position alignment",
            second.multiPassDisagreements > 0
        )
    }

    // ── Edge cases ──────────────────────────────────────

    @Test fun emptyPrimary_returnsEmptyWordConfidences() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer(""))
        val result = decoder.run(dummyFile)
        assertTrue(result.wordConfidences.isEmpty())
        assertTrue(result.disagreementSummary.isEmpty())
    }

    @Test fun singleWord_noDisagreement() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello"))
        val result = decoder.run(dummyFile)
        assertEquals(1, result.wordConfidences.size)
        assertEquals("hello", result.wordConfidences[0].word)
        assertTrue(result.disagreementSummary.isEmpty())
    }

    @Test fun singleWord_withDisagreement() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello",
                0.4 to "halo",
                0.8 to "halo",
            ))
        )
        val result = decoder.run(dummyFile)
        assertEquals(1, result.wordConfidences.size)
        assertTrue(result.wordConfidences[0].multiPassDisagreements > 0)
    }

    @Test fun unicodeInput_handledWithoutCrash() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("Дакешон привет"))
        val result = decoder.run(dummyFile)
        assertEquals(2, result.wordConfidences.size)
        assertEquals("Дакешон", result.wordConfidences[0].word)
    }

    @Test fun singleTemperature_returnsMinimalResult() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello"), temperatures = listOf(0.0))
        val result = decoder.run(dummyFile)
        // Single pass → not enough for comparison → emptyList per early-return branch
        assertTrue(result.wordConfidences.isEmpty())
        assertTrue(result.disagreementSummary.isEmpty())
    }
}
