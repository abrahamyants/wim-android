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

object WhisperClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Send audio file to Whisper API, return transcribed text.
     */
    suspend fun transcribe(audioFile: File, language: String = "en"): TranscribeResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key configured")

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

            TranscribeResult(
                text = json.optString("text", "").trim(),
                asrMs = asrMs
            )
        }

    data class TranscribeResult(
        val text: String,
        val asrMs: Long
    )
}
