package com.wim.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends recorded audio to OpenAI Whisper API for transcription.
 * Used for L4 stutter pipeline — Whisper handles disfluent speech
 * better than on-device ASR which is trained for fluent speakers.
 *
 * Extracts confidence signals from verbose_json response:
 * - avg_logprob: decoder confidence per segment (closer to 0 = confident)
 * - no_speech_prob: probability segment is silence (high = possible block hallucination)
 *
 * Ported from Лаврентий _extract_low_confidence_segments()
 */
object WhisperClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Thresholds from Лаврентий
    private const val LOGPROB_THRESHOLD = -0.7
    private const val NO_SPEECH_THRESHOLD_DEFAULT = 0.15
    private const val NO_SPEECH_THRESHOLD_PATIENCE = 0.6

    /**
     * Known Whisper hallucinations — phrases Whisper injects when audio is silent/muffled/quiet.
     * These come from YouTube training data (auto-generated captions from silent video endings).
     * Strip them from any Whisper output regardless of confidence.
     */
    private val HALLUCINATION_PATTERNS = listOf(
        "transcribed by https://otter.ai",
        "transcribed by otter.ai",
        "transcription by otter.ai",
        "thanks for watching",
        "thank you for watching",
        "don't forget to subscribe",
        "please subscribe",
        "like and subscribe",
        "like, comment, and subscribe",
        "see you in the next video",
        "see you next time",
        "see you next video",
        "subscribe to my channel",
        "subscribe to the channel",
        "thanks to patreon",
        "thanks for listening",
        "subtitles by the amara.org community",
        "captions by",
        "♪♪♪",
        "♪ ♪ ♪",
        "[music]",
        "[applause]"
    ).map { it.lowercase() }

    private fun stripHallucinations(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for (pattern in HALLUCINATION_PATTERNS) {
            // Case-insensitive replacement with optional trailing punctuation
            val regex = Regex("(?i)\\b${Regex.escape(pattern)}[.!?]?", RegexOption.IGNORE_CASE)
            result = regex.replace(result, "")
        }
        // Collapse multiple spaces
        return result.replace(Regex("\\s{2,}"), " ").trim()
    }

    suspend fun transcribe(audioFile: File, language: String = "en", patienceMode: Boolean = false): TranscribeResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key for Whisper")

            val t0 = System.currentTimeMillis()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", language)
                .addFormDataPart("temperature", "0")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart(
                    "file", audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = http.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from Whisper API")

            if (!response.isSuccessful) {
                throw RuntimeException("Whisper API error ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)
            val asrMs = System.currentTimeMillis() - t0

            // Extract confidence data from segments
            val noSpeechThreshold = if (patienceMode) NO_SPEECH_THRESHOLD_PATIENCE else NO_SPEECH_THRESHOLD_DEFAULT
            val lowConfidenceSegments = mutableListOf<LowConfidenceSegment>()
            val segmentsArray = json.optJSONArray("segments")
            if (segmentsArray != null) {
                for (i in 0 until segmentsArray.length()) {
                    val seg = segmentsArray.getJSONObject(i)
                    val avgLogprob = seg.optDouble("avg_logprob", 0.0)
                    val noSpeechProb = seg.optDouble("no_speech_prob", 0.0)
                    val segText = seg.optString("text", "").trim()
                    val blockSuspect = noSpeechProb > noSpeechThreshold
                    val lowConf = avgLogprob < LOGPROB_THRESHOLD

                    if (lowConf || blockSuspect) {
                        lowConfidenceSegments.add(
                            LowConfidenceSegment(
                                text = segText,
                                avgLogprob = avgLogprob,
                                noSpeechProb = noSpeechProb,
                                blockSuspect = blockSuspect
                            )
                        )
                    }
                }
            }

            val rawText = json.optString("text", "").trim()
            val cleanText = stripHallucinations(rawText)
            if (rawText != cleanText) {
                android.util.Log.i("WiM", "Whisper hallucination stripped: '${rawText.take(80)}' → '${cleanText.take(80)}'")
            }

            TranscribeResult(
                text = cleanText,
                asrMs = asrMs,
                lowConfidenceSegments = lowConfidenceSegments
            )
        }

    data class LowConfidenceSegment(
        val text: String,
        val avgLogprob: Double,
        val noSpeechProb: Double,
        val blockSuspect: Boolean
    )

    data class TranscribeResult(
        val text: String,
        val asrMs: Long,
        val lowConfidenceSegments: List<LowConfidenceSegment> = emptyList()
    )
}
