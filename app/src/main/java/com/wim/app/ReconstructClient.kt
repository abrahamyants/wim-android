package com.wim.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.util.concurrent.TimeUnit

object ReconstructClient {

    private const val BACKEND_URL = "https://us-central1-bakers-agent.cloudfunctions.net/wim-reconstruct"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val TONE_RULES = mapOf(
        "formal" to "Use complete sentences. No contractions. No colloquialisms. Proper grammar and punctuation. Preserve the speaker's exact word choices where possible.",
        "professional" to "Clean, clear business language. Contractions acceptable but not preferred. Fix grammar and strip fillers. Minor rephrasing for clarity is OK.",
        "casual" to "Natural spoken rhythm. Contractions OK. Strip fillers and fix obvious errors, but keep the speaker's natural voice.",
        "friend" to "Conversational, relaxed. Contractions expected. Sentence fragments OK if natural. Strip fillers but preserve personality."
    )

    /** Per-tone temperature: formal is tighter, friend is looser */
    val TONE_TEMPERATURE = mapOf(
        "formal" to 0.1,
        "professional" to 0.15,
        "casual" to 0.35,
        "friend" to 0.4
    )

    /** Situation severity mapping (mirrored from lavrentiy.py) */
    val SITUATION_SEVERITY = mapOf(
        "default" to 1.0,
        "high_stress" to 1.5,
        "reading" to 0.3
    )

    /**
     * Send raw transcription to GPT for reconstruction.
     * Returns clean text.
     *
     * @param rawText the pre-cleaned ASR output
     * @param tone voice tone setting (formal/professional/casual/friend)
     * @param layer reconstruction layer (2-4)
     * @param situation current situation context (default/high_stress/reading)
     * @param vocabulary user's custom vocabulary list (L3+)
     * @param corrections user's learned corrections map (L3+)
     */
    suspend fun reconstruct(
        rawText: String,
        tone: String = "casual",
        layer: Int = 2,
        situation: String = "default",
        vocabulary: List<String> = emptyList(),
        corrections: Map<String, String> = emptyMap(),
        fillerWords: List<String> = emptyList(),
        triggerWords: List<String> = emptyList(),
        onsetWeights: Map<String, Double> = emptyMap(),
        covertProfile: Map<String, String> = emptyMap()
    ): ReconstructResult {
        // If signed in, use backend proxy (server holds API key)
        val idToken = AuthManager.getIdToken()
        if (idToken != null) {
            return reconstructViaBackend(rawText, tone, layer, situation, vocabulary, corrections, fillerWords, triggerWords, onsetWeights, covertProfile, idToken)
        }
        // Fallback: direct OpenAI call (dev mode, requires local API key)
        return reconstructDirect(rawText, tone, layer, situation, vocabulary, corrections)
    }

    /**
     * Call the backend proxy (production path).
     * Server handles API key, tier enforcement, rate limiting.
     */
    private suspend fun reconstructViaBackend(
        rawText: String, tone: String, layer: Int, situation: String,
        vocabulary: List<String>, corrections: Map<String, String>,
        fillerWords: List<String>, triggerWords: List<String>,
        onsetWeights: Map<String, Double>, covertProfile: Map<String, String>,
        idToken: String
    ): ReconstructResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        val body = JSONObject().apply {
            put("raw", rawText)
            put("tone", tone)
            put("layer", layer)
            put("situation", situation)
            put("mode", "SAFE")
            val profile = JSONObject()
            if (vocabulary.isNotEmpty()) profile.put("vocabulary", JSONArray(vocabulary))
            if (corrections.isNotEmpty()) {
                val corr = JSONObject()
                corrections.forEach { (k, v) -> corr.put(k, v) }
                profile.put("corrections", corr)
            }
            if (fillerWords.isNotEmpty()) profile.put("filler_words", JSONArray(fillerWords))
            profile.put("trigger_words", JSONArray(triggerWords))
            profile.put("onset_weights", JSONObject(onsetWeights as Map<*, *>))
            profile.put("covert_profile", JSONObject(covertProfile as Map<*, *>))
            if (profile.length() > 0) put("profile", profile)
        }

        val request = Request.Builder()
            .url(BACKEND_URL)
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from backend")

        if (!response.isSuccessful) {
            throw RuntimeException("Backend error ${response.code}: $responseBody")
        }

        val json = JSONObject(responseBody)
        val reconMs = System.currentTimeMillis() - t0

        val cleanText = json.optString("clean", rawText)
        // Trust backend's safety decisions — it runs Falcon and risk flags server-side
        val falconOk = json.optBoolean("falcon_ok", true)
        val finalText = if (falconOk) cleanText else rawText

        if (!falconOk) {
            Log.w("WiM", "Backend Falcon rejected reconstruction, using raw text")
        }

        ReconstructResult(
            cleanText = finalText,
            reconMs = reconMs,
            riskFlags = emptyList(),
            falconApproved = falconOk
        )
    }

    /**
     * Call OpenAI directly (dev/fallback path).
     */
    private suspend fun reconstructDirect(
        rawText: String, tone: String, layer: Int, situation: String,
        vocabulary: List<String>, corrections: Map<String, String>
    ): ReconstructResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key configured")

            val t0 = System.currentTimeMillis()
            val toneRule = TONE_RULES[tone] ?: TONE_RULES["casual"]!!
            val temperature = TONE_TEMPERATURE[tone] ?: 0.35
            val severity = SITUATION_SEVERITY[situation] ?: 1.0

            val systemPrompt = buildString {
                append("You are a voice transcription post-processor. You receive raw, messy speech-to-text output and return ONLY the cleaned-up version. ")
                append("You are NOT a chatbot. Do NOT respond to the content. Do NOT ask questions. Do NOT add commentary. ")
                append("If the input is a fragment or incomplete sentence, return it cleaned up as-is — do NOT say 'your message got cut off'. ")
                append("Tone: $tone. ")
                append(toneRule)
                append(" Strip filler words (including non-English fillers like \u044d, \u043d\u0443, \u044d\u044d).")
                append(" Preserve FULL meaning. Do not summarize or add information.")
                append(" Output ONLY the reconstructed text, nothing else.")

                // Situation severity context (ported from lavrentiy.py aggression_note logic)
                if (severity >= 1.4) {
                    append("\n\nSpeaker is in a HIGH-STRESS context (phone/presentation/interview). ")
                    append("Expect more disfluencies, heavier avoidance, more filler stacking. ")
                    append("Be MORE aggressive in reconstructing — strip more, trust less of the literal words.")
                } else if (severity <= 0.6) {
                    append("\n\nSpeaker is in a low-stress context. Expect near-fluent speech. ")
                    append("Be conservative — minor cleanup only.")
                }

                // L3+ profile context: vocabulary and corrections
                if (layer >= 3) {
                    if (vocabulary.isNotEmpty() || corrections.isNotEmpty()) {
                        append("\n\nSpeaker's known vocabulary (prefer these spellings): ")
                        append(vocabulary.joinToString(", "))
                    }
                    if (corrections.isNotEmpty()) {
                        append("\n\nKnown transcription errors to correct: ")
                        corrections.forEach { (wrong, right) ->
                            append("\"$wrong\" -> \"$right\", ")
                        }
                    }
                }

                if (layer >= 4) {
                    append("\n\nThe speaker stutters. Raw transcription is evidence, not truth. ")
                    append("Reconstruct intended meaning, not literal word sequence.")
                    append("\nOvert disfluencies — strip and reconstruct:")
                    append("\n- Part-word repetitions: 'b-b-b-buy' -> 'buy'")
                    append("\n- Whole-word repetitions: 'I I I want' -> 'I want'")
                    append("\n- Prolongations: 'mmmmaybe' -> 'maybe'")
                    append("\n- Blocks: silence or frozen onset before a word")
                    append("\n- False starts and restarts")
                }
            }

            val model = if (layer >= 4) "gpt-4o" else "gpt-4o-mini"

            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", rawText))
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", temperature)
                put("max_tokens", 1024)
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from reconstruction API")

            if (!response.isSuccessful) {
                throw RuntimeException("Reconstruction API error ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)
            val cleanText = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val riskFlags = computeRiskFlags(rawText, cleanText)
            val falconOk = if (riskFlags.contains("critical_token_lost")) {
                false
            } else {
                falconValidate(rawText, cleanText, layer)
            }

            val reconMs = System.currentTimeMillis() - t0
            val finalText = if (falconOk) cleanText else rawText

            if (!falconOk) {
                Log.w("WiM", "Falcon rejected reconstruction, using raw text. Flags: $riskFlags")
            }

            ReconstructResult(
                cleanText = finalText,
                reconMs = reconMs,
                riskFlags = riskFlags,
                falconApproved = falconOk
            )
        }

    /** Check that numbers, percentages, dollar amounts survived reconstruction. */
    private fun checkCriticalTokens(raw: String, clean: String): Boolean {
        val tokenPattern = Regex("""\$?\d[\d,.]*%?""")
        val rawTokens = tokenPattern.findAll(raw).map { it.value }.toSet()
        val cleanTokens = tokenPattern.findAll(clean).map { it.value }.toSet()
        return rawTokens.all { it in cleanTokens }
    }

    /** Falcon validation: binary meaning-preservation check via GPT. */
    private suspend fun falconValidate(rawText: String, cleanText: String, layer: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = WimApp.apiKey()
                if (apiKey.isBlank()) return@withContext true // skip if no key

                val prompt = if (layer >= 4) {
                    "The speaker stutters. Disfluency removal and intent reconstruction are expected. " +
                    "Rambling filler should be stripped. Does the reconstruction preserve intended meaning? " +
                    "Answer ONLY 'yes' or 'no'."
                } else {
                    "Filler removal and grammar fixes are expected and acceptable. " +
                    "Does the reconstruction preserve the core content and intent? " +
                    "Answer ONLY 'yes' or 'no'."
                }

                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", prompt))
                    put(JSONObject().put("role", "user").put("content",
                        "Raw: $rawText\nReconstructed: $cleanText"))
                }

                val body = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("messages", messages)
                    put("temperature", 0.0)
                    put("max_tokens", 5)
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = http.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext true
                if (!response.isSuccessful) return@withContext true

                val answer = JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                    .lowercase()

                answer.startsWith("yes")
            } catch (e: Exception) {
                Log.e("WiM", "Falcon validation error: ${e.message}")
                true // on error, trust the reconstruction
            }
        }

    /** Compute risk flags for a reconstruction result. */
    fun computeRiskFlags(rawText: String, cleanText: String): List<String> {
        val flags = mutableListOf<String>()
        if (cleanText.length < 3) flags.add("very_short_output")
        val lenRatio = if (rawText.isNotEmpty()) cleanText.length.toFloat() / rawText.length else 1f
        if (lenRatio < 0.3 || lenRatio > 3.0) flags.add("large_length_delta")
        if (cleanText.endsWith("...") || cleanText.endsWith("—")) flags.add("contains_unfinished_fragment")
        if (!checkCriticalTokens(rawText, cleanText)) flags.add("critical_token_lost")
        return flags
    }

    data class ReconstructResult(
        val cleanText: String,
        val reconMs: Long,
        val riskFlags: List<String> = emptyList(),
        val falconApproved: Boolean = true
    )
}
