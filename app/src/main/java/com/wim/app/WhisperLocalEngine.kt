package com.wim.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.launch
import java.io.File

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
 * Originally built to mirror MoonshineEngine 1:1; Moonshine was retired
 * Apr 30 and this is now the sole local L1 engine. The cloud option
 * ([WhisperClient] hitting whisper-1 / gpt-4o-transcribe) sits behind the
 * `l1_cloud_asr` pref.
 *
 * Model files installed by [WhisperLocalModelDownloader].
 */
class WhisperLocalEngine(private val context: Context) {

    companion object {
        private const val TAG = "WiM.WhisperLocal"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600

        // Whisper's encoder is fixed at a 30-second window. sherpa-onnx silently
        // truncates audio longer than this to the first 30s, so anything over
        // ~30s must be split into <=30s chunks and stitched back together.
        private const val WHISPER_WINDOW_SAMPLES = SAMPLE_RATE * 30

        /** How far back from the 30s ceiling to hunt for a pause: 2 seconds. */
        private const val SEAM_SEARCH_SAMPLES = SAMPLE_RATE * 2

        /** Frame size for that hunt: 20ms, shorter than any spoken syllable. */
        private const val SEAM_FRAME_SAMPLES = SAMPLE_RATE / 50

        /**
         * Where to end the chunk that starts at [start].
         *
         * The window used to end at exactly `start + 30s`, which is a stopwatch
         * mark and has nothing to do with speech. A word straddling that mark was
         * cut in half and both halves decoded as nonsense — a two-minute recording
         * has three of these seams. Whisper is stateless across streams, so it
         * cannot recover the other half.
         *
         * So: keep the 30s ceiling, but back off to the quietest 20ms frame in the
         * last [SEAM_SEARCH_SAMPLES] before it. A pause between words is the
         * quietest thing in that stretch, and cutting there costs nothing.
         * Falls back to the hard ceiling when the tail is uniformly loud, and is a
         * no-op for the final chunk.
         */
        internal fun chooseSplit(samples: FloatArray, start: Int): Int {
            val ceiling = start + WHISPER_WINDOW_SAMPLES
            if (ceiling >= samples.size) return samples.size

            val searchFrom = maxOf(start + 1, ceiling - SEAM_SEARCH_SAMPLES)
            var quietestAt = ceiling
            var quietest = Float.MAX_VALUE
            var frameStart = searchFrom
            while (frameStart + SEAM_FRAME_SAMPLES <= ceiling) {
                var energy = 0f
                for (i in frameStart until frameStart + SEAM_FRAME_SAMPLES) {
                    energy += kotlin.math.abs(samples[i])
                }
                // <= not < : on a tie the LATEST frame wins. With a strict <, audio
                // of even loudness kept the earliest frame and threw away two
                // seconds of every window.
                if (energy <= quietest) {
                    quietest = energy
                    // Cut in the MIDDLE of the quiet frame, so neither side keeps
                    // the leading edge of the next word.
                    quietestAt = frameStart + SEAM_FRAME_SAMPLES / 2
                }
                frameStart += SEAM_FRAME_SAMPLES
            }
            return quietestAt.coerceIn(start + 1, ceiling)
        }

        const val ERROR_NO_MODEL = 300
        const val ERROR_INIT = 301
        const val ERROR_INFERENCE = 302
        const val ERROR_AUDIO = 303

        /** Guards the corruption-recovery path — fires at most once per process to avoid re-loop. */
        @Volatile var redownloadTriggered = false
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

    // Main-thread marshalling for the listener callbacks. stop() and the
    // capture-thread error path both fire from background threads (IO
    // dispatcher for stop, the capture worker for errors) and the
    // BubbleService listener implementation touches views inside the
    // callbacks — without this dispatch every successful decode is a
    // CalledFromWrongThreadException waiting on a timing window.
    // Audit finding 2026-06-06.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun dispatchFinal(text: String) {
        val cb = listener ?: return
        mainHandler.post { cb.onFinalResult(text) }
    }
    private fun dispatchError(code: Int) {
        val cb = listener ?: return
        mainHandler.post { cb.onError(code) }
    }

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
        // M7: re-entrancy guard. Without this, a second start() while already
        // capturing overwrites audioRecord + captureThread, leaking the mic and
        // the running thread.
        if (isCapturing) {
            Log.w(TAG, "start() ignored — already capturing")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start() blocked — microphone permission missing")
            resultListener.onError(ERROR_AUDIO)
            return
        }
        listener = resultListener
        if (!isReady()) {
            Log.w(TAG, "Model missing at ${WhisperLocalModelDownloader.modelDir(context)}")
            resultListener.onError(ERROR_NO_MODEL)
            return
        }
        if (ensureRecognizer() == null) {
            // isReady() was true (files exist + size OK) but init still failed —
            // model files are corrupted.  Delete and trigger a fresh download,
            // but only once per process to avoid an infinite re-loop.
            if (!redownloadTriggered) {
                redownloadTriggered = true
                Log.w(TAG, "isReady=true but recognizer init failed — deleting corrupted model dir and re-downloading")
                val dir = WhisperLocalModelDownloader.modelDir(context)
                dir.deleteRecursively()
                WimApp.instance.appScope.launch {
                    val dlResult = WhisperLocalModelDownloader.downloadAndUnpack(context) { _ -> }
                    if (dlResult is WhisperLocalModelDownloader.Result.Failed) {
                        Log.e(TAG, "re-download after corruption failed: ${dlResult.reason}")
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Speech model corrupted — re-downloading.", Toast.LENGTH_LONG).show()
                }
            }
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
            dispatchError(ERROR_INIT)
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
            dispatchError(ERROR_INFERENCE)
            return
        }
        val samples: FloatArray = synchronized(sampleBuffer) {
            FloatArray(sampleBuffer.size).also { arr ->
                for (i in arr.indices) arr[i] = sampleBuffer[i]
            }
        }
        if (samples.isEmpty()) {
            dispatchFinal("")
            return
        }
        try {
            val t0 = System.currentTimeMillis()
            // Whisper only hears a 30s window; sherpa-onnx drops everything past
            // it. Split long utterances into <=30s chunks and stitch the text so
            // a minute-long recording keeps its tail (was silently cut off after
            // ~30s — George 2026-06-25).
            val text = if (samples.size <= WHISPER_WINDOW_SAMPLES) {
                decodeChunk(rec, samples)
            } else {
                val sb = StringBuilder()
                var start = 0
                while (start < samples.size) {
                    val end = chooseSplit(samples, start)
                    val part = decodeChunk(rec, samples.copyOfRange(start, end))
                    if (part.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(part)
                    }
                    start = end
                }
                sb.toString().trim()
            }
            Log.i(
                TAG,
                "Decoded ${samples.size} samples (${samples.size / SAMPLE_RATE.toFloat()}s) " +
                    "in ${System.currentTimeMillis() - t0}ms: \"$text\"",
            )
            dispatchFinal(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Decode failed: ${e.message}", e)
            dispatchError(ERROR_INFERENCE)
        }
    }

    /** Decode a single <=30s chunk through a fresh stream. Whisper is stateless
     *  across streams, so chunks decode sequentially and concatenate cleanly. */
    private fun decodeChunk(rec: OfflineRecognizer, chunk: FloatArray): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(chunk, SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
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
