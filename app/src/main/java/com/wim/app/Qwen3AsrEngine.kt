package com.wim.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * Third ASR engine — NVIDIA-era Qwen3-ASR 0.6B (int8 ONNX) via sherpa-onnx runtime.
 *
 * Qwen3-ASR is an OFFLINE (non-streaming) recognizer, not streaming like Vosk.
 * We capture audio into a ring buffer on a background thread while the user
 * talks, then decode the entire utterance on stop. For UX parity with the
 * streaming engines, we emit a single onFinalResult at stop-time; partial
 * text is not supported by the model architecture.
 *
 * Interface mirrors OnDeviceRecognizer and VoskEngine so BubbleService can
 * switch between them via a SharedPreference flag.
 *
 * Model files must be present on disk at [modelDir]. The model is NOT bundled
 * in the APK — Qwen3ModelDownloader streams the ~1 GB of ONNX files from
 * Hugging Face on first enable.
 */
class Qwen3AsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "WiM.Qwen3"
        private const val MODEL_SUBDIR = "qwen3-models"
        private const val MODEL_NAME = "0.6b-int8"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80

        const val ERROR_NO_MODEL = 200
        const val ERROR_INIT = 201
        const val ERROR_INFERENCE = 202
        const val ERROR_AUDIO = 203
    }

    interface ResultListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(errorCode: Int)
        fun onSoundEvent(label: String) {}
    }

    private var recognizer: OfflineRecognizer? = null
    private var listener: ResultListener? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null
    private val pcmBuffer = ArrayList<Float>()

    /** Directory where the downloader unpacks the model. */
    fun modelDir(): File = File(context.filesDir, "$MODEL_SUBDIR/$MODEL_NAME")

    /** True iff all required model files are present on disk. */
    fun isReady(): Boolean {
        val dir = modelDir()
        if (!dir.isDirectory) return false
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val frontend = File(dir, "conv_frontend.onnx")
        val tokenizer = File(dir, "tokenizer")
        return encoder.isFile && decoder.isFile && frontend.isFile &&
                tokenizer.isDirectory && tokenizer.listFiles().orEmpty().isNotEmpty()
    }

    fun start(resultListener: ResultListener) {
        listener = resultListener
        if (!isReady()) {
            Log.w(TAG, "Model missing at ${modelDir().absolutePath}")
            resultListener.onError(ERROR_NO_MODEL)
            return
        }
        try {
            // Lazy-load sherpa-onnx recognizer on first start, keep warm across
            // sessions — loading the int8 encoder+decoder takes several seconds
            // even on a fast phone. Only close in destroy().
            if (recognizer == null) {
                val t0 = System.currentTimeMillis()
                recognizer = buildRecognizer()
                Log.i(TAG, "Qwen3 recognizer loaded in ${System.currentTimeMillis() - t0}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Qwen3 init failed: ${e.message}", e)
            resultListener.onError(ERROR_INIT)
            return
        }
        try {
            startAudioCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Qwen3 audio capture failed: ${e.message}", e)
            resultListener.onError(ERROR_AUDIO)
        }
    }

    fun stop() {
        if (!capturing) return
        capturing = false
        try { captureThread?.join(500) } catch (_: Exception) {}
        captureThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        decodeBufferedAudio()
    }

    /**
     * Abort capture without running ASR on whatever was buffered.
     * Used when the user drags the bubble mid-recording to cancel.
     */
    fun cancel() {
        if (!capturing) return
        capturing = false
        try { captureThread?.join(500) } catch (_: Exception) {}
        captureThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        synchronized(pcmBuffer) { pcmBuffer.clear() }
    }

    fun destroy() {
        try { stop() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        listener = null
        pcmBuffer.clear()
    }

    private fun buildRecognizer(): OfflineRecognizer {
        val dir = modelDir()
        // Find the tokens file inside tokenizer dir — sherpa-onnx expects a single
        // tokens file path for the tokens field of OfflineModelConfig, and the
        // tokenizer dir path for Qwen3's tokenizer slot. For Qwen3-ASR the top-level
        // `tokens` path is typically unused — Qwen3 reads its BPE vocab from its
        // own tokenizer dir. We still pass an empty string rather than null so the
        // native side doesn't NPE.
        val tokenizerDir = File(dir, "tokenizer").absolutePath

        val qwen3 = OfflineQwen3AsrModelConfig().apply {
            encoder = File(dir, "encoder.int8.onnx").absolutePath
            decoder = File(dir, "decoder.int8.onnx").absolutePath
            convFrontend = File(dir, "conv_frontend.onnx").absolutePath
            tokenizer = tokenizerDir
        }

        val modelConfig = OfflineModelConfig().apply {
            qwen3Asr = qwen3
            numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            debug = false
            provider = "cpu"
        }

        val feat = FeatureConfig().apply {
            sampleRate = SAMPLE_RATE
            featureDim = FEATURE_DIM
        }

        val cfg = OfflineRecognizerConfig().apply {
            featConfig = feat
            this.modelConfig = modelConfig
            hr = HomophoneReplacerConfig()
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }

        return OfflineRecognizer(context.assets, cfg)
    }

    private fun startAudioCapture() {
        pcmBuffer.clear()
        val channelCfg = AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelCfg, fmt)
        val bufSize = (minBuf * 2).coerceAtLeast(SAMPLE_RATE) // at least 1 second
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            channelCfg,
            fmt,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        audioRecord = rec
        rec.startRecording()
        capturing = true
        captureThread = thread(name = "Qwen3AudioCapture", start = true) {
            val chunk = ShortArray(1024)
            while (capturing) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(pcmBuffer) {
                        for (i in 0 until n) {
                            pcmBuffer.add(chunk[i] / 32768f)
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Qwen3 audio capture started at ${SAMPLE_RATE}Hz")
    }

    private fun decodeBufferedAudio() {
        val pcm: FloatArray = synchronized(pcmBuffer) {
            if (pcmBuffer.isEmpty()) return
            val arr = FloatArray(pcmBuffer.size)
            for (i in pcmBuffer.indices) arr[i] = pcmBuffer[i]
            pcmBuffer.clear()
            arr
        }
        val rec = recognizer ?: return
        try {
            val t0 = System.currentTimeMillis()
            val stream = rec.createStream()
            stream.acceptWaveform(pcm, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            stream.release()
            val elapsed = System.currentTimeMillis() - t0
            Log.i(TAG, "Qwen3 decoded ${pcm.size / SAMPLE_RATE}s in ${elapsed}ms: ${result.text}")
            val text = result.text.trim()
            if (text.isNotEmpty()) {
                listener?.onFinalResult(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Qwen3 decode failed: ${e.message}", e)
            listener?.onError(ERROR_INFERENCE)
        }
    }
}
