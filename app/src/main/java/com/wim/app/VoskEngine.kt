package com.wim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File

/**
 * Backup ASR engine using Vosk (Kaldi-based). Streams audio from the mic through
 * an internal AudioRecord — NEVER binds to android.speech.SpeechRecognizer — so
 * the Samsung / Google OEM chirp sonification never fires.
 *
 * Model must be present on disk at [modelDir]. The model is not bundled in the
 * APK; a downloader unpacks it there on first enable.
 *
 * Interface mirrors OnDeviceRecognizer so BubbleService can switch between the
 * two by a SharedPreference flag.
 */
class VoskEngine(private val context: Context) {

    companion object {
        private const val TAG = "WiM.Vosk"
        private const val MODEL_SUBDIR = "vosk-models"
        private const val DEFAULT_MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000f

        const val ERROR_NO_MODEL = 100
        const val ERROR_INIT = 101
        const val ERROR_INFERENCE = 102
    }

    interface ResultListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(errorCode: Int)
        fun onSoundEvent(label: String) {}
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var listener: ResultListener? = null

    /** Directory where the downloader should unpack the model. */
    fun modelDir(): File = File(context.filesDir, "$MODEL_SUBDIR/$DEFAULT_MODEL_NAME")

    /** True iff a usable model is present on disk. */
    fun isReady(): Boolean {
        val dir = modelDir()
        return dir.exists() && dir.isDirectory && dir.listFiles().orEmpty().isNotEmpty()
    }

    fun start(resultListener: ResultListener) {
        listener = resultListener
        if (!isReady()) {
            Log.w(TAG, "Model missing at ${modelDir().absolutePath}")
            resultListener.onError(ERROR_NO_MODEL)
            return
        }
        try {
            model = Model(modelDir().absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onResult(hypothesis: String?) {
                    parseText(hypothesis, "text")?.let { listener?.onFinalResult(it) }
                }
                override fun onPartialResult(hypothesis: String?) {
                    parseText(hypothesis, "partial")?.let { listener?.onPartialResult(it) }
                }
                override fun onFinalResult(hypothesis: String?) {
                    parseText(hypothesis, "text")?.let { listener?.onFinalResult(it) }
                }
                override fun onError(exception: Exception?) {
                    Log.e(TAG, "Vosk inference error: ${exception?.message}", exception)
                    listener?.onError(ERROR_INFERENCE)
                }
                override fun onTimeout() {
                    listener?.onSoundEvent("[Timeout]")
                }
            })
            Log.i(TAG, "Vosk listening from ${modelDir().name}")
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init failed: ${e.message}", e)
            listener?.onError(ERROR_INIT)
            cleanup()
        }
    }

    fun stop() {
        speechService?.stop()
        cleanup()
    }

    fun destroy() {
        cleanup()
        listener = null
    }

    private fun cleanup() {
        try { speechService?.shutdown() } catch (_: Exception) {}
        speechService = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    private fun parseText(json: String?, field: String): String? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json).optString(field).ifBlank { null }
        } catch (_: Exception) { null }
    }
}
