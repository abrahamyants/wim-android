package com.wim.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the whisper.cpp ggml base.en model into app-private storage.
 * Single-file download — no archive extraction (unlike Vosk-style zips).
 *
 * Target file: <filesDir>/whisper-models/base.en/ggml-base.en.bin (~140 MB)
 *
 * Matches the LocalWhisperClient.init() path convention and provides the same
 * progress callback shape (bytesDownloaded, totalBytes) used elsewhere.
 */
object WhisperModelDownloader {

    private const val TAG = "WhisperModelDownloader"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
    private const val MODEL_SUBDIR = "whisper-models/base.en"
    private const val MODEL_FILENAME = "ggml-base.en.bin"

    /** Returns the target model file (whether or not it exists yet). */
    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, MODEL_SUBDIR)
        return File(dir, MODEL_FILENAME)
    }

    /** True if the model file exists and is non-trivially sized. */
    fun isInstalled(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 1_000_000L // >1 MB sanity gate
    }

    /**
     * Downloads the ggml model. No extraction step — it's a single .bin file.
     * Progress callback is invoked on the IO thread; callers should marshal to
     * main if touching UI.
     *
     * @param onProgress (bytesDownloaded, totalBytes). totalBytes may be -1 if
     *                   the server omits Content-Length.
     * @return the model File on success; throws on failure.
     */
    suspend fun downloadAndUnpack(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, MODEL_SUBDIR)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw java.io.IOException("Could not create model directory: ${targetDir.absolutePath}")
        }
        val targetFile = File(targetDir, MODEL_FILENAME)
        val tempFile = File(targetDir, "$MODEL_FILENAME.part")

        if (targetFile.exists() && targetFile.length() > 1_000_000L) {
            Log.i(TAG, "Model already installed at ${targetFile.absolutePath}")
            onProgress(targetFile.length(), targetFile.length())
            return@withContext targetFile
        }

        Log.i(TAG, "Downloading $MODEL_URL")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(MODEL_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "GET"
            }
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code downloading model")
            }

            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastReported = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        // Throttle callback to ~1% ticks or 512 KB min
                        if (downloaded - lastReported > 512 * 1024) {
                            onProgress(downloaded, total)
                            lastReported = downloaded
                        }
                    }
                    output.flush()
                }
            }

            if (downloaded < 1_000_000L) {
                tempFile.delete()
                throw java.io.IOException("Downloaded file too small: $downloaded bytes")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                throw java.io.IOException("Could not rename temp file to ${targetFile.absolutePath}")
            }

            onProgress(downloaded, if (total > 0) total else downloaded)
            Log.i(TAG, "Model downloaded successfully: ${targetFile.length()} bytes")
            return@withContext targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
            tempFile.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /** Deletes the installed model, if any. Useful for "reset" flows. */
    fun delete(context: Context): Boolean {
        val f = modelFile(context)
        return if (f.exists()) f.delete() else true
    }
}
