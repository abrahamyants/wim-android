package com.wim.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import kotlinx.coroutines.Dispatchers
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
 * Punctuation + truecasing for the LOCAL L1 route.
 *
 * whisper-tiny.en through sherpa-onnx returns flat lowercase text with almost
 * no punctuation, and [Repunctuate] — a verbatim port of lavrentiy.py — only
 * capitalises the first letter, fixes standalone "i", and appends one terminal
 * mark. A three-sentence take came out as one lowercase run with a full stop
 * on the end. Every cloud route gets real punctuation from the provider; the
 * free tier, which is the one that must work offline, did not.
 *
 * The model is CNN-BiLSTM (arXiv 2407.13142): joint punctuation and word
 * casing, about a fortieth the size of the CT-Transformer alternative and
 * 2.5x faster, which is why it is the one small enough to ask a free-tier
 * user to download. **7.1 MB int8**, against whisper-tiny's ~40 MB.
 *
 * The Kotlin bindings were already inside the sherpa-onnx AAR this app ships
 * (1.12.40) — OnlinePunctuation, OnlinePunctuationConfig and
 * OnlinePunctuationModelConfig, unused. Only the model files were missing.
 *
 * Fails soft everywhere: no model, failed load, or a throwing inference all
 * return the input untouched, and [Repunctuate] still runs downstream as it
 * always did. Nothing about the existing path depends on this succeeding.
 */
object Punctuator {

    private const val TAG = "WiM.Punct"
    const val MODEL_NAME = "sherpa-onnx-online-punct-en-2024-08-06"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/" +
            "$MODEL_NAME.tar.bz2"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val downloadMutex = Mutex()
    @Volatile private var inProgress = false
    /** One load attempt per process. A failed init must not be retried on
     *  every utterance — that is a JNI model load on the paste path. */
    @Volatile private var loadFailed = false
    @Volatile private var punct: OnlinePunctuation? = null

    fun modelDir(context: Context): File = File(context.filesDir, "punct-models/$MODEL_NAME")

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        val model = File(dir, "model.int8.onnx")
        val vocab = File(dir, "bpe.vocab")
        // 1 KB floor catches a truncated download or an LFS pointer file,
        // same check WhisperLocalModelDownloader makes.
        return model.exists() && model.length() > 1024 && vocab.exists() && vocab.length() > 0
    }

    /**
     * Punctuate and truecase [text], or return it unchanged.
     *
     * Synchronous and on the caller's thread on purpose: this sits between the
     * decode and the paste, and the model is small enough that the alternative
     * — a coroutine hop for ~10 ms of work — costs more than it saves.
     */
    fun apply(text: String): String {
        if (text.isBlank()) return text
        val context = try { WimApp.instance } catch (_: Throwable) { return text }
        val engine = ensureLoaded(context) ?: run {
            ensureDownloading(context)
            return text
        }
        return try {
            val out = engine.addPunctuation(text).trim()
            // A model that returns nothing must never eat the take. Seen with
            // truncated model files that still passed the size floor.
            if (out.isBlank()) text else out
        } catch (e: Throwable) {
            Log.w(TAG, "addPunctuation failed, keeping raw: ${e.message}")
            text
        }
    }

    private fun ensureLoaded(context: Context): OnlinePunctuation? {
        punct?.let { return it }
        if (loadFailed || !isInstalled(context)) return null
        return try {
            val dir = modelDir(context)
            val config = OnlinePunctuationConfig(
                model = OnlinePunctuationModelConfig(
                    cnnBilstm = File(dir, "model.int8.onnx").absolutePath,
                    bpeVocab = File(dir, "bpe.vocab").absolutePath,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
            )
            // assetManager stays null — the model lives in filesDir, not assets,
            // exactly as WhisperLocalEngine loads its recognizer.
            val p = OnlinePunctuation(assetManager = null, config = config)
            punct = p
            Log.i(TAG, "Punctuation model loaded")
            p
        } catch (e: Throwable) {
            Log.e(TAG, "Punctuation model load failed: ${e.message}", e)
            loadFailed = true
            null
        }
    }

    /** Fire-and-forget fetch. The current take goes out unpunctuated; the next
     *  one gets the model. No UI, no toast — a 7 MB background download the
     *  user never asked about should not interrupt them. */
    fun ensureDownloading(context: Context) {
        if (inProgress || isInstalled(context)) return
        val scope = try { WimApp.instance.appScope } catch (_: Throwable) { return }
        scope.launch(Dispatchers.IO) { download(context) }
    }

    private suspend fun download(context: Context) = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isInstalled(context)) return@withContext
            inProgress = true
            val staging = File(context.cacheDir, "$MODEL_NAME.tar.bz2")
            try {
                val response = http.newCall(Request.Builder().url(MODEL_URL).build()).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "punct model HTTP ${response.code}")
                    return@withContext
                }
                val body = response.body ?: return@withContext
                body.byteStream().use { input ->
                    FileOutputStream(staging).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                val parentDir = modelDir(context).parentFile ?: return@withContext
                parentDir.mkdirs()
                BufferedInputStream(FileInputStream(staging)).use { bin ->
                    BZip2CompressorInputStream(bin).use { bz ->
                        TarArchiveInputStream(bz).use { tarIn ->
                            while (true) {
                                val entry = tarIn.nextEntry ?: break
                                val outFile = File(parentDir, entry.name)
                                // Same tar-traversal guard as the whisper
                                // downloader — an archive entry naming ../ can
                                // otherwise write anywhere the app can reach.
                                if (!outFile.canonicalPath.startsWith(parentDir.canonicalPath)) {
                                    Log.w(TAG, "tar traversal blocked: ${entry.name}")
                                    return@withContext
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
                Log.i(TAG, if (isInstalled(context)) "punct model installed" else "punct model unpack incomplete")
            } catch (e: Exception) {
                Log.w(TAG, "punct model download failed: ${e.message}")
                staging.delete()
            } finally {
                inProgress = false
            }
        }
    }
}
