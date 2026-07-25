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

    /** Recognizer that returns text + a fixed list of low-confidence segments. */
    private fun recognizerWithLowConf(
        text: String,
        lowConfSegments: List<WhisperClient.LowConfidenceSegment>,
    ): SpeechRecognizerL1 = object : SpeechRecognizerL1 {
        override suspend fun transcribe(
            audioFile: File,
            requestWordTimings: Boolean,
            temperature: Double,
        ): TranscribeResult = TranscribeResult(
            text = text,
            asrMs = 10,
            lowConfidenceSegments = lowConfSegments,
        )
        override val supportsWordTimings = false
        override val supportsLogprobs = true
        override val isLocal = false
    }

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
        // ATTACHED punctuation only ("hello,"). Both tokenizers see two tokens
        // here, so this case was always aligned — see the standalone-token tests
        // below for the case that actually drifted.
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

    // ── Regression: STANDALONE punctuation token drift ──
    //
    // Whisper emits free-standing punctuation tokens ("hello — world",
    // "wait ... no"). The cross-pass tokenizer drops them; the per-word walker
    // used to count them. From the first such token onward every disagreement
    // position was off by one, so the clarifier flagged the word AFTER the
    // uncertain one — and an approved swap trained ProfileManager on a
    // correction the user never made. L4 only, but silent and cumulative.

    @Test fun standalonePunctuation_disagreementLandsOnCorrectWord() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "hello — world",
                0.4 to "hello — earth",
                0.8 to "hello — earth",
            ))
        )
        val result = decoder.run(dummyFile)
        val world = result.wordConfidences.first { it.word == "world" }
        assertTrue(
            "Disagreement must land on 'world', not on the token after the dash",
            world.multiPassDisagreements > 0,
        )
        assertEquals(
            "'hello' agreed across every pass and must stay unflagged",
            0, result.wordConfidences.first { it.word == "hello" }.multiPassDisagreements,
        )
    }

    @Test fun standalonePunctuation_emitsNoPunctuationOnlyEntry() = runBlocking {
        val decoder = MultiPassDecoder(fixedRecognizer("hello — world ... ok"))
        val result = decoder.run(dummyFile)
        assertTrue(
            "WordConfidence is one-per-WORD; punctuation-only tokens must not appear",
            result.wordConfidences.none { wc -> wc.word.none(Char::isLetterOrDigit) },
        )
        assertEquals(3, result.wordConfidences.size)
    }

    @Test fun standalonePunctuation_rangesStillIndexPrimaryText() = runBlocking {
        // "hello — world": w starts at 8, token ends at 13. A wrong range here
        // is what corrupts the injected text on an approved swap.
        val decoder = MultiPassDecoder(fixedRecognizer("hello — world"))
        val result = decoder.run(dummyFile)
        val world = result.wordConfidences.first { it.word == "world" }
        assertEquals(8, world.rangeStart)
        assertEquals(13, world.rangeEnd)
    }

    @Test fun standalonePunctuation_alternatesAttachToCorrectWord() = runBlocking {
        val decoder = MultiPassDecoder(
            multiRecognizer(mapOf(
                0.0 to "wait ... world",
                0.4 to "wait ... earth",
                0.8 to "wait ... earth",
            ))
        )
        val result = decoder.run(dummyFile)
        assertTrue(
            "'earth' must be offered as an alternate for 'world'",
            result.wordConfidences.first { it.word == "world" }.nBestAlternates.contains("earth"),
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

    // ── Low-confidence-segment logprob mapping ──────────
    //
    // Regression for the 2026-05-16 "last-wins" bug: when the same word
    // (e.g. a disfluency-driven repeat) appeared in multiple low-confidence
    // segments, .toMap() silently kept only the LATER segment's avg_logprob,
    // masking the stronger pessimistic signal. The fix switched to a
    // groupBy+min so worst-confidence wins. Tested via WordConfidence.asrLogprob.

    @Test fun lowConf_singleSegmentLogprob_attachedToMatchingWord() = runBlocking {
        val seg = WhisperClient.LowConfidenceSegment(
            text = "computer", avgLogprob = -1.5, noSpeechProb = 0.1, blockSuspect = false,
        )
        val decoder = MultiPassDecoder(recognizerWithLowConf("the computer file", listOf(seg)))
        val result = decoder.run(dummyFile)
        val computer = result.wordConfidences.first { it.word == "computer" }
        assertEquals(-1.5, computer.asrLogprob!!, 0.001)
    }

    @Test fun lowConf_repeatedWord_worstLogprobWins() = runBlocking {
        // Same token "computer" appears in two low-conf segments. The fix
        // requires the MORE pessimistic (-2.0) logprob to win over -0.5.
        // Pre-fix, .toMap() last-wins meant whichever segment iterated last
        // (here -0.5) clobbered the worse signal.
        val seg1 = WhisperClient.LowConfidenceSegment(
            text = "the computer", avgLogprob = -2.0, noSpeechProb = 0.05, blockSuspect = false,
        )
        val seg2 = WhisperClient.LowConfidenceSegment(
            text = "computer file", avgLogprob = -0.5, noSpeechProb = 0.05, blockSuspect = false,
        )
        val decoder = MultiPassDecoder(recognizerWithLowConf("the computer file", listOf(seg1, seg2)))
        val result = decoder.run(dummyFile)
        val computer = result.wordConfidences.first { it.word == "computer" }
        assertEquals(
            "Repeated word's logprob must be the WORST (lowest) of its low-conf occurrences",
            -2.0, computer.asrLogprob!!, 0.001,
        )
    }

    @Test fun lowConf_orderInverted_stillWorstWins() = runBlocking {
        // Same as above but segments swapped — proves order doesn't matter
        // and the bug isn't latent in a different .toMap() iteration order.
        val segGood = WhisperClient.LowConfidenceSegment(
            text = "the computer", avgLogprob = -0.5, noSpeechProb = 0.05, blockSuspect = false,
        )
        val segBad = WhisperClient.LowConfidenceSegment(
            text = "computer file", avgLogprob = -2.0, noSpeechProb = 0.05, blockSuspect = false,
        )
        val decoder = MultiPassDecoder(recognizerWithLowConf("the computer file", listOf(segGood, segBad)))
        val result = decoder.run(dummyFile)
        val computer = result.wordConfidences.first { it.word == "computer" }
        assertEquals(-2.0, computer.asrLogprob!!, 0.001)
    }
}
