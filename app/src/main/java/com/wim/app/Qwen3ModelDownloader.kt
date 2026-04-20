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
 * Downloads and unpacks the Qwen3-ASR 0.6B int8 ONNX model (~1 GB total)
 * into filesDir/qwen3-models/0.6b-int8/.
 *
 * Unlike Vosk (which ships as a zip) the sherpa-onnx/Qwen3 model is published
 * as individual files on Hugging Face — we stream each file straight to disk
 * at its target path. No archive extraction needed, so no zip-slip guards,
 * but we still canonicalize every output path against the target dir to be
 * safe against any future archive mode.
 */
object Qwen3ModelDownloader {

    private const val TAG = "WiM.Qwen3DL"

    private const val HF_BASE =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"
    private const val MODEL_NAME = "0.6b-int8"

    /**
     * Files to download, as (remotePath, localRelativePath) pairs.
     * Remote path appended to HF_BASE; local path relative to modelDir().
     */
    private val FILES = listOf(
        "encoder.int8.onnx" to "encoder.int8.onnx",
        "decoder.int8.onnx" to "decoder.int8.onnx",
        "conv_frontend.onnx" to "conv_frontend.onnx",
        "tokenizer/vocab.json" to "tokenizer/vocab.json",
        "tokenizer/merges.txt" to "tokenizer/merges.txt",
        "tokenizer/tokenizer_config.json" to "tokenizer/tokenizer_config.json"
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    data class Progress(val bytesDone: Long, val bytesTotal: Long, val stage: String)

    sealed class Result {
        data class Success(val modelDir: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun modelDir(context: Context): File =
        File(context.filesDir, "qwen3-models/$MODEL_NAME")

    /**
     * Returns true if all required model files are present on disk.
     */
    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        for ((_, local) in FILES) {
            if (!File(dir, local).isFile) return false
        }
        return true
    }

    suspend fun downloadAndUnpack(
        context: Context,
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val targetDir = modelDir(context)
        if (isInstalled(context)) {
            Log.i(TAG, "Already installed at $targetDir")
            return@withContext Result.Success(targetDir)
        }
        targetDir.mkdirs()
        if (!targetDir.isDirectory) return@withContext Result.Failed("Could not create $targetDir")

        try {
            // First pass: resolve total content-length for accurate progress.
            onProgress(Progress(0, -1, "Resolving"))
            var totalBytes = 0L
            val contentLengths = LongArray(FILES.size)
            for ((i, pair) in FILES.withIndex()) {
                val (remote, _) = pair
                val req = Request.Builder()
                    .url("$HF_BASE/$remote")
                    .head()
                    .build()
                http.newCall(req).execute().use { r ->
                    val cl = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    contentLengths[i] = cl
                    if (cl > 0) totalBytes += cl
                }
            }
            Log.i(TAG, "Total download size: ${totalBytes / (1024 * 1024)} MB")

            // Second pass: download each file.
            var doneBytes = 0L
            for ((i, pair) in FILES.withIndex()) {
                val (remote, local) = pair
                val outFile = File(targetDir, local)
                // Canonical-path guard: make sure relative path cannot escape targetDir.
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    return@withContext Result.Failed("Path traversal blocked: $local")
                }
                outFile.parentFile?.mkdirs()

                if (outFile.isFile && contentLengths[i] > 0 && outFile.length() == contentLengths[i]) {
                    Log.i(TAG, "Skip (already present, correct size): $local")
                    doneBytes += contentLengths[i]
                    onProgress(Progress(doneBytes, totalBytes, "Downloading"))
                    continue
                }

                onProgress(Progress(doneBytes, totalBytes, "Downloading $local"))
                val req = Request.Builder().url("$HF_BASE/$remote").build()
                val response = http.newCall(req).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext Result.Failed("HTTP ${response.code} on $remote")
                }
                val body = response.body ?: return@withContext Result.Failed("Empty body on $remote")

                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            doneBytes += n
                            // Throttle callback — emit every ~1 MB.
                            if (doneBytes and 0xFFFFF == 0L) {
                                onProgress(Progress(doneBytes, totalBytes, "Downloading $local"))
                            }
                        }
                    }
                }
            }

            if (!isInstalled(context)) {
                return@withContext Result.Failed("Post-download check failed — missing files")
            }
            onProgress(Progress(totalBytes, totalBytes, "Ready"))
            Log.i(TAG, "Installed at $targetDir")
            Result.Success(targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            Result.Failed(e.message ?: "Unknown error")
        }
    }
}
