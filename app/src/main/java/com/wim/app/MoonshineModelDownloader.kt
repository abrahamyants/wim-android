package com.wim.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads + extracts the Moonshine BASE English quantized model from the
 * sherpa-onnx asr-models GitHub release. Total decompressed payload is
 * ~141 MB: encoder_model.ort (31 MB) + decoder_model_merged.ort (109 MB) +
 * tokens.txt (549 KB) + LICENSE.
 *
 * Mirrors the design of [VoskModelDownloader] (download to staging file →
 * extract → verify) but unpacks tar.bz2 via Apache Commons Compress because
 * java.util.zip doesn't support bzip2. Files unpack to:
 *
 *   filesDir/moonshine-models/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/
 *
 * which is the path [MoonshineEngine] reads at start-up.
 *
 * On rerun: [isInstalled] short-circuits if the three required files exist
 * with non-zero size (catches LFS-pointer / partial-download corruption).
 */
object MoonshineModelDownloader {

    private const val TAG = "WiM.MoonshineDL"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2"
    const val MODEL_NAME = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27"

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
        File(context.filesDir, "moonshine-models/$MODEL_NAME")

    /**
     * True iff the three required model files exist with non-zero size.
     * Real encoder is ~31 MB, decoder ~109 MB — anything under 1 KB is a
     * truncated download or a Git LFS pointer file masquerading as real.
     */
    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.exists() || !dir.isDirectory) return false
        val encoder = File(dir, "encoder_model.ort")
        val decoder = File(dir, "decoder_model_merged.ort")
        val tokens = File(dir, "tokens.txt")
        return encoder.exists() && encoder.length() > 1024 &&
            decoder.exists() && decoder.length() > 1024 &&
            tokens.exists() && tokens.length() > 0
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
        val staging = File(context.cacheDir, "moonshine-base.tar.bz2")
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
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done and 0xFFFFF == 0L) onProgress(Progress(done, total, "Downloading"))
                    }
                    onProgress(Progress(done, total, "Downloaded"))
                }
            }

            onProgress(Progress(0, -1, "Unpacking"))
            val parentDir = targetDir.parentFile ?: return@withContext Result.Failed("Bad filesDir")
            parentDir.mkdirs()
            // tar.bz2 — bzip2 wraps tar wraps individual files. Apache Commons Compress
            // chains the streams: file → BufferedInputStream → BZip2 → Tar.
            BufferedInputStream(FileInputStream(staging)).use { bin ->
                BZip2CompressorInputStream(bin).use { bz ->
                    TarArchiveInputStream(bz).use { tarIn ->
                        while (true) {
                            val entry = tarIn.nextEntry ?: break
                            val outFile = File(parentDir, entry.name)
                            // Path traversal guard (mirrors VoskModelDownloader).
                            if (!outFile.canonicalPath.startsWith(parentDir.canonicalPath)) {
                                return@withContext Result.Failed("Tar traversal blocked: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = tarIn.read(buf)
                                        if (n < 0) break
                                        fos.write(buf, 0, n)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            staging.delete()

            if (!isInstalled(context)) {
                return@withContext Result.Failed("Post-unpack check failed (missing or truncated model files)")
            }
            onProgress(Progress(1, 1, "Ready"))
            Log.i(TAG, "Installed at $targetDir")
            Result.Success(targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            staging.delete()
            Result.Failed(e.message ?: "Unknown error")
        }
    }
}
