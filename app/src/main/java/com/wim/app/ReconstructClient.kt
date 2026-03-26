package com.wim.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ReconstructClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TONE_RULES = mapOf(
        "formal" to "Use complete sentences. No contractions. No colloquialisms. Proper grammar and punctuation. Preserve the speaker's exact word choices where possible.",
        "professional" to "Clean, clear business language. Contractions acceptable but not preferred. Fix grammar and strip fillers. Minor rephrasing for clarity is OK.",
        "casual" to "Natural spoken rhythm. Contractions OK. Strip fillers and fix obvious errors, but keep the speaker's natural voice.",
        "friend" to "Conversational, relaxed. Contractions expected. Sentence fragments OK if natural. Strip fillers but preserve personality."
    )

    /**
     * Send raw transcription to GPT for reconstruction.
     * Returns clean text.
     */
    suspend fun reconstruct(rawText: String, tone: String = "casual", layer: Int = 2): ReconstructResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key configured")

            val t0 = System.currentTimeMillis()
            val toneRule = TONE_RULES[tone] ?: TONE_RULES["casual"]!!

            val systemPrompt = buildString {
                append("Rebuild this raw voice transcription into clean $tone text. ")
                append(toneRule)
                append(" Strip filler words (including non-English fillers like э, ну, ээ).")
                append(" Preserve FULL meaning. Do not summarize or add information.")
                append(" Output ONLY the reconstructed text.")
                if (layer >= 4) {
                    append("\n\nThe speaker stutters. Raw transcription is evidence, not truth. ")
                    append("Reconstruct intended meaning, not literal word sequence.")
                    append("\nOvert disfluencies — strip and reconstruct:")
                    append("\n- Part-word repetitions: 'b-b-b-buy' → 'buy'")
                    append("\n- Whole-word repetitions: 'I I I want' → 'I want'")
                    append("\n- Prolongations: 'mmmmaybe' → 'maybe'")
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
                put("temperature", 0.3)
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

            val reconMs = System.currentTimeMillis() - t0

            ReconstructResult(
                cleanText = cleanText,
                reconMs = reconMs
            )
        }

    data class ReconstructResult(
        val cleanText: String,
        val reconMs: Long
    )
}
