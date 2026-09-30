package com.wim.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        // Sized for ~2-minute cloud recordings (George 2026-06-25). readTimeout is
        // the idle wait while OpenAI transcribes (the socket stays silent during
        // processing, so this must exceed total server time); writeTimeout covers
        // uploading the WAV (~4 MB for 2 min) on mobile. Bumped from 60s read /
        // 10s default write.
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Thresholds from Лаврентий
    private const val LOGPROB_THRESHOLD = -0.7
    // openai/whisper decode.py compression_ratio_threshold default.
    private const val LOOP_COMPRESSION_RATIO = 2.4
    private const val NO_SPEECH_THRESHOLD_DEFAULT = 0.15
    private const val NO_SPEECH_THRESHOLD_PATIENCE = 0.6

    /**
     * Known Whisper hallucinations — phrases Whisper injects when audio is silent/muffled/quiet.
     * These come from YouTube training data (auto-generated captions from silent video endings).
     * Strip them from any Whisper output regardless of confidence.
     */
    private val HALLUCINATION_PATTERNS = listOf(
        // otter.ai / transcription service credits
        "transcribed by https://otter.ai",
        "transcribed by otter.ai",
        "transcription by otter.ai",
        "transcribed by https://otter.ai[^.]*",
        "subtitles by the amara.org community",
        "captions by[^.]{0,30}",
        "transcript by[^.]{0,30}",
        // Brackets
        "\\[no output due to lack of context[^\\]]*\\]",
        "\\[no meaningful content[^\\]]*\\]",
        "\\[no output[^\\]]*\\]",
        "\\[no content[^\\]]*\\]",
        "\\[music\\]",
        "\\[applause\\]",
        "\\[silence\\]",
        "\\[no audio\\]",
        "\\[laughter\\]",
        // YouTube endings
        "thanks for watching",
        "thank you for watching",
        "thanks for listening",
        "don't forget to subscribe",
        "don't forget to like",
        "please subscribe",
        "like and subscribe",
        "like, comment, and subscribe",
        "see you in the next video",
        "see you next time",
        "see you next video",
        "see you next episode",
        "subscribe to my channel",
        "subscribe to the channel",
        "thanks to patreon",
        "hit the bell",
        "hit the notification",
        "leave a comment below",
        "check out our website",
        "check out my website",
        "follow us on",
        "follow me on",
        // Legal / disclaimer boilerplate
        "please see the complete disclaimer",
        "for more information,? ?visit",
        "all rights reserved",
        "terms and conditions apply",
        "terms of service",
        "visit us at",
        "powered by[^.]{0,30}",
        // Podcast / sponsor credits
        "sponsored by[^.]{0,40}",
        "brought to you by[^.]{0,40}",
        "this episode is",
        "this video is",
        "available on all platforms",
        // Music markers
        "♪♪♪",
        "♪ ♪ ♪"
    )

    // Pre-compiled regexes — recompiling 60 patterns on every transcribe
    // call was adding ~100-200ms per Whisper round-trip on every layer.
    // Class-level lazy ensures compile cost is paid once, not per call.
    private val COMPILED_HALLUCINATION_REGEXES: List<Regex> by lazy {
        HALLUCINATION_PATTERNS.map { Regex("(?i)$it[.!?]?", RegexOption.IGNORE_CASE) }
    }
    private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s{2,}")

    private fun stripHallucinations(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for (regex in COMPILED_HALLUCINATION_REGEXES) {
            result = regex.replace(result, "")
        }
        return WHITESPACE_COLLAPSE_REGEX.replace(result, " ").trim()
    }

    /**
     * Transcribe a WAV file via OpenAI's audio transcription API.
     *
     * @param model defaults to `gpt-4o-transcribe` (used at L2/L3 — the
     *   on-device whisper-tiny only runs at L1). Pass `whisper-1` at L4 —
     *   older model preserves more disfluencies and the verbose_json
     *   response shape exposes per-segment `avg_logprob` + `no_speech_prob`
     *   confidence signals that L4's clinical prompt depends on.
     */
    /**
     * The hint handed to the transcriber: what the speaker is about to read,
     * followed by the domain pack's canonical spellings.
     *
     * Order matters. Script prep is about this specific utterance and must not
     * be crowded out, so it goes first and the pack fills what is left.
     */
    private fun buildTranscriberHint(): String {
        val script = ScriptPrepStore.activeContext()
        val pack = try {
            DomainPackHelper.asrHint(WimApp.instance)
        } catch (e: Exception) {
            Log.w("WhisperClient", "domain pack hint unavailable: ${e.message}")
            ""
        }
        return when {
            script.isBlank() -> pack
            pack.isBlank() -> script
            else -> "$script\n$pack"
        }
    }

    suspend fun transcribe(
        audioFile: File,
        language: String = "en",
        patienceMode: Boolean = false,
        model: String = "gpt-4o-transcribe",
        temperature: Double = 0.0,
        // L1 cloud (`whisper-1` at layer < 2) doesn't need segment-level
        // confidence data — that's an L4 clinical signal. Plain `json`
        // returns just the text and skips per-segment server work plus
        // wire payload, which materially reduces round-trip on longer
        // recordings. L4 + reconstruction layers default to verbose_json.
        verboseSegments: Boolean = true,
        // Per-word start/end times for the L1-cloud clock-broom
        // (DisfluencyFilter.stripTimedFillers). whisper-1 only, and only
        // meaningful with verbose_json — silently ignored otherwise.
        wordTimestamps: Boolean = false,
        // Script prep (what the speaker is about to read) plus the active
        // domain pack's canonical terms. The pack half is spelling guidance:
        // it costs nothing and stops the transcriber from inventing "kuber
        // netties" that a later stage would have to guess its way out of.
        prompt: String = buildTranscriberHint(),
    ): TranscribeResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            val t0 = System.currentTimeMillis()
            // Upload, not inference, is the long pole: raw 16 kHz mono PCM is
            // 32 KB per second of speech, and the backend path base64-wraps it
            // and relays it, so it crosses the uplink twice. Transcode once
            // here and both routes carry a quarter of the bytes. Falls back to
            // the WAV on any encoder trouble — see AudioCompressor.
            val uploadFile = AudioCompressor.compressForUpload(audioFile) ?: audioFile
            val uploadMime = if (uploadFile.extension.equals("m4a", true)) "audio/mp4" else "audio/wav"
            // Keep a copy of exactly what Whisper heard, next to the 30-day
            // log (WimLogFile). The recorder overwrites one path per take, so
            // a garbled take was gone before anyone could listen to it
            // (9/28: "R. R. R. R." on a 35 s take — normal level, no audio left).
            WimLogFile.keepTakeAudio(WimApp.instance, uploadFile)
            // Route choice. Normally: a key in the build means go straight to
            // OpenAI. With force_backend on (see WimApp.forceBackend) the token
            // is fetched first and the backend wins, so the operator's build
            // takes the same path a Play install takes. No token means no
            // backend to reach, and the embedded key carries the take rather
            // than the dictation failing.
            val overrideToken =
                if (WimApp.forceBackend() || apiKey.isBlank()) AuthManager.getIdToken() else null
            val (responseBody, engine) = if (apiKey.isNotBlank() && overrideToken == null) {
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("language", language)
                    .addFormDataPart("temperature", temperature.toString())
                    .addFormDataPart("response_format", if (verboseSegments) "verbose_json" else "json")
                if (wordTimestamps && verboseSegments) {
                    // Word granularity alone drops the segments array, and L4's
                    // MultiPassDecoder still needs segments — request both.
                    bodyBuilder
                        .addFormDataPart("timestamp_granularities[]", "word")
                        .addFormDataPart("timestamp_granularities[]", "segment")
                }
                bodyBuilder
                    .addFormDataPart(
                        "file", uploadFile.name,
                        uploadFile.asRequestBody(uploadMime.toMediaType())
                    )
                if (prompt.isNotBlank()) bodyBuilder.addFormDataPart("prompt", prompt.take(4000))
                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(bodyBuilder.build())
                    .build()
                executeJson(request, "Whisper API") to "direct"
            } else {
                val idToken = overrideToken
                    ?: throw IllegalStateException("Sign in to use cloud transcription")
                // Raw envelope (2026-09-06): the recording goes up as a
                // multipart file part, the same shape the direct route sends
                // to OpenAI. The old JSON body carried it as base64 text — a
                // third more bytes over the uplink plus a decode on the server.
                // The function still accepts the old shape for older builds.
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("action", "transcribe_audio")
                    .addFormDataPart("language", language)
                    .addFormDataPart("model", model)
                    .addFormDataPart("temperature", temperature.toString())
                    .addFormDataPart("verbose_segments", verboseSegments.toString())
                if (wordTimestamps) bodyBuilder.addFormDataPart("word_timestamps", "true")
                if (prompt.isNotBlank()) bodyBuilder.addFormDataPart("prompt", prompt.take(4000))
                bodyBuilder.addFormDataPart(
                    "file", uploadFile.name,
                    uploadFile.asRequestBody(uploadMime.toMediaType())
                )
                val request = Request.Builder()
                    .url(BackendConfig.URL)
                    .addHeader("Authorization", "Bearer $idToken")
                    .post(bodyBuilder.build())
                    .build()
                try {
                    executeJson(request, "WiM transcription backend") to "backend"
                } catch (e: RuntimeException) {
                    // A function revision older than 00043 (2026-09-06) never
                    // looks at a multipart body: it sees an empty JSON body and
                    // answers 400 "Missing 'raw' field". That exact answer means
                    // "old server", so send the same recording once more in the
                    // wrapped shape it knows. Any revision then works with this
                    // build, and rolling the function back can never break a take.
                    if (e.message?.contains("Missing 'raw' field") != true) throw e
                    android.util.Log.w("WiM", "backend is an older revision — retrying with the wrapped envelope")
                    val wrapped = JSONObject().apply {
                        put("action", "transcribe_audio")
                        put("audio_base64", Base64.encodeToString(uploadFile.readBytes(), Base64.NO_WRAP))
                        put("audio_format", if (uploadFile === audioFile) "wav" else "m4a")
                        put("language", language)
                        put("model", model)
                        put("temperature", temperature)
                        put("verbose_segments", verboseSegments)
                        if (wordTimestamps) put("word_timestamps", true)
                        if (prompt.isNotBlank()) put("prompt", prompt.take(4000))
                    }
                    val wrappedRequest = Request.Builder()
                        .url(BackendConfig.URL)
                        .addHeader("Authorization", "Bearer $idToken")
                        .addHeader("Content-Type", "application/json")
                        .post(wrapped.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    executeJson(wrappedRequest, "WiM transcription backend") to "backend-wrapped"
                }
            }

            val json = JSONObject(responseBody)
            val asrMs = System.currentTimeMillis() - t0

            // Extract confidence data and timed segments
            val noSpeechThreshold = if (patienceMode) NO_SPEECH_THRESHOLD_PATIENCE else NO_SPEECH_THRESHOLD_DEFAULT
            val lowConfidenceSegments = mutableListOf<LowConfidenceSegment>()
            val timedSegments = mutableListOf<TimedSegment>()
            val segmentsArray = json.optJSONArray("segments")
            if (segmentsArray != null) {
                for (i in 0 until segmentsArray.length()) {
                    val seg = segmentsArray.getJSONObject(i)
                    val avgLogprob = seg.optDouble("avg_logprob", 0.0)
                    val noSpeechProb = seg.optDouble("no_speech_prob", 0.0)
                    // gzip ratio of the segment text. Whisper's reference
                    // decoder treats > 2.4 as a failed decode (the repetition
                    // loop: "1, 1, 1, ..." x112 pasted live 2026-09-30 07:20).
                    // The API still returns such segments; we drop them.
                    val compressionRatio = seg.optDouble("compression_ratio", 0.0)
                    val segText = seg.optString("text", "").trim()
                    val segStart = seg.optDouble("start", 0.0)
                    val segEnd = seg.optDouble("end", segStart + 0.5)
                    val blockSuspect = noSpeechProb > noSpeechThreshold
                    val lowConf = avgLogprob < LOGPROB_THRESHOLD
                    val loop = compressionRatio > LOOP_COMPRESSION_RATIO

                    timedSegments.add(TimedSegment(segStart, segEnd, segText))

                    if (lowConf || blockSuspect || loop) {
                        lowConfidenceSegments.add(
                            LowConfidenceSegment(
                                text = segText,
                                avgLogprob = avgLogprob,
                                noSpeechProb = noSpeechProb,
                                blockSuspect = blockSuspect,
                                compressionRatio = compressionRatio,
                                loop = loop
                            )
                        )
                    }
                }
            }

            // Per-word timings (timestamp_granularities[]=word). Only present
            // when requested; empty list otherwise, and every consumer treats
            // empty as "no clock available — behave as before".
            val timedWords = mutableListOf<TimedWord>()
            val wordsArray = json.optJSONArray("words")
            if (wordsArray != null) {
                for (i in 0 until wordsArray.length()) {
                    val w = wordsArray.getJSONObject(i)
                    val wStart = w.optDouble("start", 0.0)
                    timedWords.add(
                        TimedWord(wStart, w.optDouble("end", wStart), w.optString("word", "").trim())
                    )
                }
            }

            val rawText = json.optString("text", "").trim()
            // Four gates, in order. Each one is a failure Whisper produced on
            // George's phone between 2026-09-28 and 09-30 and the app pasted:
            //   phrase list  — YouTube credits ("thanks for watching")
            //   loop segment — "1, 1, 1, ..." x112 (compression_ratio 2.4+)
            //   junk tail    — "Go to Beadaholique.com for all of your beading
            //                   supply needs!" / "www.FEMA.gov" glued on the end
            //   lone letter  — "R." and "R. R. R. R. R. R. R." on silence
            val log: (String) -> Unit = { msg -> android.util.Log.i("WiM", msg) }
            val cleanText = DisfluencyFilter.stripLoneLetterNoise(
                DisfluencyFilter.stripJunkTail(
                    DisfluencyFilter.stripLoopSegments(
                        stripHallucinations(rawText), lowConfidenceSegments, log
                    ), log
                ), lowConfidenceSegments, log
            )
            val wasFullyHallucinated = rawText.isNotBlank() && cleanText.isBlank()
            if (rawText != cleanText) {
                if (wasFullyHallucinated) {
                    android.util.Log.w("WiM", "Whisper FULLY hallucinated (bailing): '${rawText.take(120)}'")
                } else {
                    android.util.Log.i("WiM", "Whisper hallucination stripped: '${rawText.take(80)}' → '${cleanText.take(80)}'")
                }
            }

            TranscribeResult(
                text = cleanText,
                asrMs = asrMs,
                lowConfidenceSegments = lowConfidenceSegments,
                timedSegments = timedSegments,
                timedWords = timedWords,
                wasFullyHallucinated = wasFullyHallucinated,
                engine = engine
            )
        }

    /**
     * The single point every cloud transcription goes through — and, until
     * 2026-07-26, a one-shot. No retry existed anywhere in this client, so a
     * dropped packet, a carrier handover, or one 503 from OpenAI lost the take
     * outright. That is the worst possible thing to lose: the audio is already
     * gone by the time this fails, and the user cannot reproduce a sentence
     * they had to fight through a block to say once.
     *
     * Retries only what can plausibly succeed on a second attempt — transport
     * failures, 429, and 5xx. A 4xx is the request being wrong and will be
     * wrong again. Backoff is deliberately short: the user is watching the
     * bubble, so the ceiling on added latency is about 1.6 seconds.
     */
    private fun executeJson(request: Request, service: String): String {
        var lastError: Exception? = null
        for (attempt in 0..MAX_TRANSPORT_RETRIES) {
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                        ?: throw RuntimeException("Empty response from $service")
                    if (response.isSuccessful) return body
                    val retryable = response.code == 429 || response.code >= 500
                    val error = RuntimeException("$service error ${response.code}: $body")
                    if (!retryable) throw error
                    lastError = error
                }
            } catch (e: java.io.IOException) {
                lastError = e
            }
            if (attempt < MAX_TRANSPORT_RETRIES) {
                android.util.Log.w(
                    "WiM.Whisper",
                    "$service attempt ${attempt + 1} failed (${lastError?.message?.take(80)}) — retrying",
                )
                try {
                    Thread.sleep(RETRY_BACKOFF_MS[attempt])
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw lastError ?: RuntimeException("$service failed")
    }

    private const val MAX_TRANSPORT_RETRIES = 2
    private val RETRY_BACKOFF_MS = longArrayOf(400L, 1_200L)

    internal fun stripHallucinationsForTest(text: String): String = stripHallucinations(text)

    data class LowConfidenceSegment(
        val text: String,
        val avgLogprob: Double,
        val noSpeechProb: Double,
        val blockSuspect: Boolean,
        /** gzip ratio of the segment text as reported by whisper-1 verbose_json. */
        val compressionRatio: Double = 0.0,
        /** compression ratio above 2.4 — the decoder's repetition loop. */
        val loop: Boolean = false
    )

    data class TimedSegment(val startS: Double, val endS: Double, val text: String)

    data class TimedWord(val startS: Double, val endS: Double, val text: String)

    data class TranscribeResult(
        val text: String,
        val asrMs: Long,
        val lowConfidenceSegments: List<LowConfidenceSegment> = emptyList(),
        val timedSegments: List<TimedSegment> = emptyList(),
        val timedWords: List<TimedWord> = emptyList(),
        val wasFullyHallucinated: Boolean = false,
        val engine: String = "unknown"
    )
}
