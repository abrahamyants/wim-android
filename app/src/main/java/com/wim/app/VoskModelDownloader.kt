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
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the Vosk English small model (~40 MB).
 * Streams bytes to disk so we don't OOM on the zip, unpacks into
 * filesDir/vosk-models/vosk-model-small-en-us-0.15/.
 */
object VoskModelDownloader {

    private const val TAG = "WiM.VoskDL"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    data class Progress(val bytesDone: Long, val bytesTotal: Long, val stage: String)

    sealed class Result {
        data class Success(val modelDir: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Returns true if the model is already unpacked.
     */
    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.exists() && dir.isDirectory && dir.listFiles().orEmpty().isNotEmpty()
    }

    fun modelDir(context: Context): File =
        File(context.filesDir, "vosk-models/$MODEL_NAME")

    suspend fun downloadAndUnpack(
        context: Context,
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val targetDir = modelDir(context)
        if (isInstalled(context)) {
            Log.i(TAG, "Already installed at $targetDir")
            return@withContext Result.Success(targetDir)
        }
        val stagingZip = File(context.cacheDir, "vosk-model-en.zip")
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
                FileOutputStream(stagingZip).use { output ->
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
            ZipInputStream(stagingZip.inputStream()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val outFile = File(parentDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(parentDir.canonicalPath)) {
                        return@withContext Result.Failed("Zip traversal blocked: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zin.copyTo(it) }
                    }
                }
            }
            stagingZip.delete()

            if (!isInstalled(context)) {
                return@withContext Result.Failed("Post-unpack check failed")
            }
            onProgress(Progress(1, 1, "Ready"))
            Log.i(TAG, "Installed at $targetDir")
            Result.Success(targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            stagingZip.delete()
            Result.Failed(e.message ?: "Unknown error")
        }
    }
}
