package com.wim.app

import java.io.File

/**
 * Engine-agnostic L1 ASR boundary per spec §8.
 *
 * Cloud whisper-1, on-device whisper-tiny, sherpa-onnx Moonshine,
 * whisper.cpp, Canary, Samsung native — every concrete recognizer
 * implements this interface. Both Clarifier (Spec B) and the pipeline
 * parade (Spec A) consume only this interface, so the bubble UX is
 * fully decoupled from whichever ASR is plugged in.
 *
 * Phase 0 ships [WhisperApiL1] as the cloud implementation. Engines
 * that don't return per-word timings or per-segment log-probs
 * advertise that via [supportsWordTimings] / [supportsLogprobs] and
 * the clarifier silently skips word-confidence flagging — graceful
 * degradation built in.
 */
interface SpeechRecognizerL1 {
    /**
     * Transcribe a WAV file. When [requestWordTimings] is true and the
     * engine supports them, [TranscribeResult.words] is populated.
     */
    suspend fun transcribe(
        audioFile: File,
        requestWordTimings: Boolean = false,
        temperature: Double = 0.0,
    ): TranscribeResult

    val supportsWordTimings: Boolean
    val supportsLogprobs: Boolean

    /**
     * True iff the engine runs entirely on-device. Pipeline parade uses
     * this to decide whether to draw the "TRANSCRIBING" stage at all
     * (cloud transcription has visible latency; local engines often
     * complete inside a single frame).
     */
    val isLocal: Boolean
}

/**
 * Per-word timing + (optional) per-word log-prob from the recognizer.
 * Engines that don't surface word boundaries return an empty list
 * rather than fabricating positions.
 */
data class WordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val logprob: Double? = null,
)

/**
 * Recognizer return shape. Mirrors the existing [WhisperClient.TranscribeResult]
 * fields for source-compat, with [words] added for the clarifier path.
 */
data class TranscribeResult(
    val text: String,
    val asrMs: Long,
    val lowConfidenceSegments: List<WhisperClient.LowConfidenceSegment> = emptyList(),
    val wasFullyHallucinated: Boolean = false,
    val words: List<WordTiming> = emptyList(),
)

/**
 * Cloud-Whisper-1 / gpt-4o-transcribe implementation. Wraps the existing
 * [WhisperClient] object so callers that already use the concrete client
 * keep working unchanged; new callers can program against the interface.
 *
 * Phase 0 adapter — no new ASR behavior, just the interface plumbing.
 */
class WhisperApiL1(
    private val model: String = "whisper-1",
    private val language: String = "en",
    private val patienceMode: Boolean = false,
) : SpeechRecognizerL1 {

    override val supportsWordTimings: Boolean
        get() = true // Whisper verbose_json includes per-segment timing; word-level via response_format=verbose_json

    override val supportsLogprobs: Boolean
        get() = true // avg_logprob per segment from verbose_json

    override val isLocal: Boolean
        get() = false

    override suspend fun transcribe(
        audioFile: File,
        requestWordTimings: Boolean,
        temperature: Double,
    ): TranscribeResult {
        val legacy = WhisperClient.transcribe(
            audioFile = audioFile,
            language = language,
            patienceMode = patienceMode,
            model = model,
            temperature = temperature,
        )
        return TranscribeResult(
            text = legacy.text,
            asrMs = legacy.asrMs,
            lowConfidenceSegments = legacy.lowConfidenceSegments,
            wasFullyHallucinated = legacy.wasFullyHallucinated,
            // Word-timings: Phase 2 will surface these via WhisperClient
            // verbose_json word_timestamps. Phase 0 adapter returns empty.
            words = emptyList(),
        )
    }
}
