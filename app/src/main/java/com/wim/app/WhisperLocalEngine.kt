package com.wim.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whisper-tiny.en ASR via sherpa-onnx — replaces Moonshine on the L1 path
 * when `l1_cloud_asr` pref is FALSE (local default). Same sherpa-onnx
 * runtime that hosts Moonshine; only the model files differ.
 *
 * Like Moonshine, Whisper is offline (non-streaming) — accumulate the full
 * utterance into a [FloatArray] and decode in one pass on stop. No live
 * partials. Output is lowercase / lightly punctuated; [Repunctuate] adds
 * deterministic capitalization + terminal punctuation downstream.
 *
 * Mirrors [MoonshineEngine] structure 1:1 — same listener interface, same
 * AudioRecord wiring, same lazy recognizer init. The two engines stay
 * swappable via the bubble-menu L1 SRC toggle.
 *
 * Model files installed by [WhisperLocalModelDownloader].
 */
class WhisperLocalEngine(private val context: Context) {

    companion object {
        private const val TAG = "WiM.WhisperLocal"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600

        const val ERROR_NO_MODEL = 300
        const val ERROR_INIT = 301
        const val ERROR_INFERENCE = 302
        const val ERROR_AUDIO = 303
    }

    interface ResultListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(errorCode: Int)
        fun onSoundEvent(label: String) {}
    }

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false
    private val sampleBuffer = ArrayList<Float>(SAMPLE_RATE * 30)
    private var listener: ResultListener? = null

    fun isReady(): Boolean = WhisperLocalModelDownloader.isInstalled(context)

    private fun ensureRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        val dir = WhisperLocalModelDownloader.modelDir(context)
        return try {
            val t0 = System.currentTimeMillis()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = java.io.File(dir, "tiny.en-encoder.int8.onnx").absolutePath,
                        decoder = java.io.File(dir, "tiny.en-decoder.int8.onnx").absolutePath,
                    ),
                    tokens = java.io.File(dir, "tiny.en-tokens.txt").absolutePath,
                    modelType = "whisper",
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                ),
            )
            val rec = OfflineRecognizer(assetManager = null, config = config)
            recognizer = rec
            Log.i(TAG, "Recognizer ready in ${System.currentTimeMillis() - t0}ms")
            rec
        } catch (e: Throwable) {
            Log.e(TAG, "Recognizer init failed: ${e.message}", e)
            null
        }
    }

    fun start(resultListener: ResultListener) {
        listener = resultListener
        if (!isReady()) {
            Log.w(TAG, "Model missing at ${WhisperLocalModelDownloader.modelDir(context)}")
            resultListener.onError(ERROR_NO_MODEL)
            return
        }
        if (ensureRecognizer() == null) {
            resultListener.onError(ERROR_INIT)
            return
        }
        try {
            sampleBuffer.clear()
            val minBufBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufBytes = maxOf(minBufBytes, CHUNK_SAMPLES * 2 * 4)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed (state=${record.state})")
                record.release()
                resultListener.onError(ERROR_AUDIO)
                return
            }
            audioRecord = record
            record.startRecording()
            isCapturing = true
            captureThread = Thread({ captureLoop(record) }, "WhisperLocalCapture").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Listening (whisper-tiny.en)")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed: ${e.message}", e)
            cleanupAudio()
            listener?.onError(ERROR_INIT)
        }
    }

    private fun captureLoop(record: AudioRecord) {
        val shorts = ShortArray(CHUNK_SAMPLES)
        while (isCapturing) {
            val n = try {
                record.read(shorts, 0, shorts.size)
            } catch (e: Throwable) {
                Log.e(TAG, "AudioRecord read failed: ${e.message}")
                break
            }
            if (n <= 0) continue
            synchronized(sampleBuffer) {
                for (i in 0 until n) {
                    sampleBuffer.add(shorts[i] / 32768f)
                }
            }
        }
    }

    fun stop() {
        if (!isCapturing && audioRecord == null) return
        isCapturing = false
        try { captureThread?.join(500) } catch (_: InterruptedException) {}
        captureThread = null
        cleanupAudio()

        val rec = recognizer
        if (rec == null) {
            listener?.onError(ERROR_INFERENCE)
            return
        }
        val samples: FloatArray = synchronized(sampleBuffer) {
            FloatArray(sampleBuffer.size).also { arr ->
                for (i in arr.indices) arr[i] = sampleBuffer[i]
            }
        }
        if (samples.isEmpty()) {
            listener?.onFinalResult("")
            return
        }
        try {
            val t0 = System.currentTimeMillis()
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                Log.i(
                    TAG,
                    "Decoded ${samples.size} samples (${samples.size / SAMPLE_RATE.toFloat()}s) " +
                        "in ${System.currentTimeMillis() - t0}ms: \"$text\"",
                )
                listener?.onFinalResult(text)
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Decode failed: ${e.message}", e)
            listener?.onError(ERROR_INFERENCE)
        }
    }

    fun destroy() {
        cleanupAudio()
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        listener = null
    }

    private fun cleanupAudio() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }
}
