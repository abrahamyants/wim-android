package com.wim.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads the Gemma 3 1B IT .task model (~550 MB, INT4-quantized) from
 * Hugging Face. Public download — no auth required for this specific
 * community-hosted variant, unlike the gated `google/gemma-*` repos.
 *
 * Mirrors the VoskModelDownloader pattern: streaming write to disk,
 * progress callback, idempotent on re-run.
 */
object GemmaModelDownloader {

    private const val TAG = "WiM.GemmaDL"
    // Community-hosted LiteRT .task build of Gemma 3 1B IT, INT4 quantized.
    // ~550MB. No HF auth token needed on this repo.
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    data class Progress(val bytesDone: Long, val bytesTotal: Long, val stage: String)

    sealed class Result {
        data class Success(val modelFile: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isInstalled(context: Context): Boolean = GemmaEngine.isReady(context)

    suspend fun downloadAndUnpack(
        context: Context,
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val target = GemmaEngine.modelFile(context)
        if (isInstalled(context)) {
            Log.i(TAG, "Already installed at $target")
            return@withContext Result.Success(target)
        }
        target.parentFile?.mkdirs()
        val staging = File(context.cacheDir, "gemma-model-staging.task")
        try {
            onProgress(Progress(0, -1, "Connecting"))
            val request = Request.Builder().url(MODEL_URL).build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.Failed("HTTP ${response.code}")
            }
            val body = response.body ?: return@withContext Result.Failed("Empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(staging).use { output ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        // Progress tick every ~8 MB
                        if (done and 0x7FFFFF == 0L) {
                            onProgress(Progress(done, total, "Downloading"))
                        }
                    }
                    onProgress(Progress(done, total, "Downloaded"))
                }
            }
            // Atomic move into place
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            if (!isInstalled(context)) {
                return@withContext Result.Failed("Post-install check failed")
            }
            onProgress(Progress(1, 1, "Ready"))
            Log.i(TAG, "Installed at $target (${target.length()} bytes)")
            Result.Success(target)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            staging.delete()
            Result.Failed(e.message ?: "Unknown error")
        }
    }
}
