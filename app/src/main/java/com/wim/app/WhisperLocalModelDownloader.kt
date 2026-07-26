package com.wim.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Downloads + extracts the Whisper-tiny.en quantized model from the
 * sherpa-onnx asr-models GitHub release. Decompressed payload is ~40 MB:
 * tiny.en-encoder.int8.onnx + tiny.en-decoder.int8.onnx + tiny.en-tokens.txt.
 *
 * Download → extract → verify pipeline; unpacks tar.bz2 via Apache Commons
 * Compress because java.util.zip lacks bzip2.
 * Files unpack to:
 *
 *   filesDir/whisper-models/sherpa-onnx-whisper-tiny.en/
 *
 * which is the path [WhisperLocalEngine] reads at start-up.
 */
object WhisperLocalModelDownloader {

    private const val TAG = "WiM.WhisperLocalDL"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-whisper-tiny.en.tar.bz2"
    const val MODEL_NAME = "sherpa-onnx-whisper-tiny.en"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // Serializes downloadAndUnpack so concurrent callers (auto-download
    // from WimApp.onCreate + a user-triggered redownload from settings)
    // don't interleave bytes into the same staging file — CRC errors
    // mid-stream surface to the user as opaque "Download failed".
    private val downloadMutex = Mutex()

    /**
     * True while a download attempt is running. Lets callers ask "is one
     * already in flight?" without blocking on [downloadMutex], so the bubble
     * can kick off a retry when the user taps and the model still isn't there,
     * without stacking a second attempt on top of a live one.
     */
    @Volatile
    var inProgress: Boolean = false
        private set

    /** Reason the last attempt failed, or null. Surfaced to the user so a
     *  failed download stops looking like a slow one. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Start a download if the model is missing and nothing is already trying.
     * Safe to call on every refused recording — that is the point. The
     * original code fired the download exactly once from `WimApp.onCreate` and
     * never again, so a single failed attempt left the user permanently stuck
     * behind a toast reading "try again in a moment" while nothing on any code
     * path would ever try again.
     *
     * @return true if an attempt was started by this call.
     */
    fun ensureDownloading(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        onDone: (Result) -> Unit = {},
    ): Boolean {
        if (isInstalled(context) || inProgress) return false
        scope.launch(Dispatchers.IO) {
            val r = downloadAndUnpack(context) { }
            onDone(r)
        }
        return true
    }

    data class Progress(val bytesDone: Long, val bytesTotal: Long, val stage: String)

    sealed class Result {
        data class Success(val modelDir: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun modelDir(context: Context): File =
        File(context.filesDir, "whisper-models/$MODEL_NAME")

    /**
     * True iff the three required model files exist with non-zero size.
     * Real encoder/decoder are tens of MB — anything under 1 KB is a
     * truncated download or LFS pointer.
     */
    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.exists() || !dir.isDirectory) return false
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoder = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        return encoder.exists() && encoder.length() > 1024 &&
            decoder.exists() && decoder.length() > 1024 &&
            tokens.exists() && tokens.length() > 0
    }

    /**
     * Download with retries. A single transient failure — a WiFi handover, a
     * slow GitHub, the process being backgrounded mid-stream — used to be
     * terminal: one attempt, result discarded, user stuck. Three attempts with
     * backoff covers the overwhelming majority of those without a user ever
     * seeing it.
     */
    suspend fun downloadAndUnpack(
        context: Context,
        onProgress: (Progress) -> Unit
    ): Result {
        if (isInstalled(context)) return Result.Success(modelDir(context))
        inProgress = true
        try {
            var last: Result = Result.Failed("not attempted")
            for (attempt in 1..MAX_ATTEMPTS) {
                last = attemptDownload(context, onProgress)
                if (last is Result.Success) {
                    lastFailure = null
                    return last
                }
                Log.w(TAG, "attempt $attempt/$MAX_ATTEMPTS failed: ${(last as Result.Failed).reason}")
                if (attempt < MAX_ATTEMPTS) delay(RETRY_BACKOFF_MS * attempt)
            }
            lastFailure = (last as? Result.Failed)?.reason
            return last
        } finally {
            inProgress = false
        }
    }

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 3_000L

    private suspend fun attemptDownload(
        context: Context,
        onProgress: (Progress) -> Unit
    ): Result = downloadMutex.withLock { withContext(Dispatchers.IO) {
        val targetDir = modelDir(context)
        if (isInstalled(context)) {
            Log.i(TAG, "Already installed at $targetDir")
            return@withContext Result.Success(targetDir)
        }
        val staging = File(context.cacheDir, "whisper-tiny-en.tar.bz2")
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
            BufferedInputStream(FileInputStream(staging)).use { bin ->
                BZip2CompressorInputStream(bin).use { bz ->
                    TarArchiveInputStream(bz).use { tarIn ->
                        while (true) {
                            val entry = tarIn.nextEntry ?: break
                            val outFile = File(parentDir, entry.name)
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
    } }
}
