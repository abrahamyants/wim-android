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
 * partials.
 *
 * "Output is lowercase / lightly punctuated" used to be written here, carried
 * over from MoonshineEngine. It is FALSE for this model. Measured on device
 * 2026-08-22 — whisper-tiny.en through sherpa-onnx returns properly cased,
 * comma-punctuated, terminated prose:
 *
 *   "Then I wish Claude would stand being such an idiot, I hate him, he
 *    cannot explain shit, so fuck him in the ass."
 *
 * A whole punctuation-and-truecasing feature was built against that stale
 * line — 7 MB model, JNI load, wiring through L1Pipeline — before anyone read
 * an actual decode. [Repunctuate] downstream is nearly always a no-op here,
 * because it skips text that already has a capital and a terminal mark; it
 * still earns its place as the fallback for the rare bare take.
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

        // Decode while the speaker is still talking.
        //
        // The whole take used to be decoded only after the stop tap, so the
        // wait grew with the take. Measured on the operator's phone 10/9–10/12
        // (377 takes): about 1 s of waiting per 15 s spoken — 4 s after a 43 s
        // take, 7.5 s after a 109 s one. Now, once this much un-decoded audio
        // has piled up, the capture thread hunts for a real pause and hands
        // everything before it to a background decode while the speaker
        // carries on. The stop tap then only waits for the tail.
        private const val EARLY_WINDOW_SAMPLES = SAMPLE_RATE * 12

        /** Hunt for that pause in the newest 2 s of un-decoded audio. */
        private const val EARLY_SEARCH_SAMPLES = SAMPLE_RATE * 2

        /** A real pause: a 20 ms frame at or below this fraction of the chunk's mean level. */
        private const val PAUSE_RATIO = 0.1f

        /** An early chunk this soft is room tone; decoding it would only invite a hallucination. */
        private const val EARLY_SILENT_DBFS = -50.0

        // Quiet Mode, matching AudioRecorder's values exactly. Those apply to
        // the WAV that feeds the CLOUD route; this engine keeps its own float
        // buffer and never touches that file, so neither the manual toggle nor
        // the automatic one reached the local route at all — the default,
        // offline path, where a soft take simply stayed soft and the speaker
        // had to lean into the microphone.
        private const val QUIET_GAIN_DB = 18.0f
        private val QUIET_GAIN_LINEAR = Math.pow(10.0, (QUIET_GAIN_DB / 20.0)).toFloat()
        private const val HIGHPASS_ALPHA = 0.995f

        /** Validated in lavrentiy.py:263 (QUIET_AUTO_DBFS). */
        private const val QUIET_AUTO_MAX_DBFS = -22.0
        private const val QUIET_AUTO_MIN_DBFS = -110.0

        /**
         * How far below a speaker's OWN normal level counts as whispering.
         *
         * QUIET_AUTO_MAX_DBFS above is absolute, and dBFS is not a property of
         * a voice — it is the voice plus the microphone plus whatever the OS
         * did before we saw the samples. The same -22 means "whispering" on a
         * hot mic and "shouting" on a quiet one, so the constant ends up
         * measuring the handset rather than the speaking, and handsets vary
         * more than laptops do.
         *
         * Measured on the operator's laptop 2026-08-29: normal speech into a
         * Logi C930s arrived at -46 dBFS, i.e. every take reads as a whisper by
         * the absolute number. Same constant, same bug, both apps.
         *
         * 12 dB is roughly conversational-to-whisper. With no baseline stored
         * the absolute constant is used exactly as before.
         */
        private const val QUIET_RELATIVE_DROP_DB = 12.0

        /** The dBFS below which a take counts as soft, for THIS speaker. */
        internal fun quietCutoffDbfs(): Double =
            WimApp.micBaselineDbfs()?.let { it - QUIET_RELATIVE_DROP_DB } ?: QUIET_AUTO_MAX_DBFS

        // Silence trimming, float mirror of WavSilenceTrimmer's constants:
        // 20 ms windows, 100 ms of padding kept on each side, and the same RMS
        // floor expressed for normalised floats (250 / 32768).
        //
        // WavSilenceTrimmer runs on the WAV, which only the CLOUD route reads.
        // This engine decodes its own float buffer and never opens that file,
        // so leading and trailing silence reached whisper-tiny untouched — the
        // same route boundary that hid Quiet Mode from the local path until it
        // was found. Silence is what Whisper hallucinates into: measured on
        // device 2026-08-22, three consecutive local takes returned "[",
        // "[BLANK_AUDIO]" and "[ Sound Effects ]".
        private const val TRIM_WINDOW_SAMPLES = 320
        private const val TRIM_PAD_WINDOWS = 5
        private const val TRIM_RMS_THRESHOLD = 250.0f / 32768.0f

        /**
         * Drop leading and trailing silence, keeping 100 ms of padding so word
         * onsets and offsets are not clipped. Returns [samples] unchanged when
         * the take is too short to window, or when no window clears the floor —
         * an all-quiet take is the caller's problem, not something to truncate
         * to nothing here.
         *
         * Deliberately measured BEFORE the quiet boost, unlike the WAV route
         * where AudioRecorder boosts first and WavSilenceTrimmer measures the
         * boosted file. An 18 dB lift multiplies room tone by about eight, so
         * measuring after it can push background noise over the floor and trim
         * nothing at all. Same numbers, better order; the WAV route probably
         * wants the same change, which is a separate job on a separate path.
         */
        internal fun trimSilence(samples: FloatArray): FloatArray {
            val windows = samples.size / TRIM_WINDOW_SAMPLES
            if (windows < 2 * TRIM_PAD_WINDOWS) return samples
            var first = -1
            var last = -1
            for (w in 0 until windows) {
                var sum = 0.0
                val base = w * TRIM_WINDOW_SAMPLES
                for (i in base until base + TRIM_WINDOW_SAMPLES) {
                    sum += samples[i].toDouble() * samples[i].toDouble()
                }
                if (Math.sqrt(sum / TRIM_WINDOW_SAMPLES) >= TRIM_RMS_THRESHOLD) {
                    if (first < 0) first = w
                    last = w
                }
            }
            if (first < 0) return samples
            val startW = maxOf(0, first - TRIM_PAD_WINDOWS)
            val endW = minOf(windows - 1, last + TRIM_PAD_WINDOWS)
            val start = startW * TRIM_WINDOW_SAMPLES
            val end = minOf(samples.size, (endW + 1) * TRIM_WINDOW_SAMPLES)
            if (start >= end) return samples
            if (start == 0 && end == samples.size) return samples
            return samples.copyOfRange(start, end)
        }

        /** Mean loudness of [samples] in dBFS. -120 for an empty take. */
        internal fun meanDbfs(samples: FloatArray): Double {
            if (samples.isEmpty()) return -120.0
            var sumSquares = 0.0
            for (s in samples) sumSquares += s.toDouble() * s.toDouble()
            val rms = Math.sqrt(sumSquares / samples.size)
            return 20.0 * Math.log10(rms.coerceAtLeast(1e-9))
        }

        /**
         * True when a take is quiet enough to boost but not pure silence.
         *
         * [maxDbfs] defaults to the absolute constant so the existing unit
         * tests keep exercising the pure comparison with no prefs behind them;
         * the live caller passes [quietCutoffDbfs], which is calibrated.
         */
        internal fun shouldAutoBoost(dbfs: Double, maxDbfs: Double = QUIET_AUTO_MAX_DBFS): Boolean =
            dbfs in QUIET_AUTO_MIN_DBFS..maxDbfs

        /**
         * High-pass then +18dB, in place. Same filter and same gain as
         * AudioRecorder.applyQuietModeProcessing, working in floats instead of
         * 16-bit shorts, so the two routes hear the same thing.
         */
        internal fun applyQuietBoost(samples: FloatArray) {
            var lastIn = 0f
            var lastOut = 0f
            for (i in samples.indices) {
                val x = samples[i]
                val filtered = HIGHPASS_ALPHA * (lastOut + x - lastIn)
                lastIn = x
                lastOut = filtered
                samples[i] = (filtered * QUIET_GAIN_LINEAR).coerceIn(-1f, 1f)
            }
        }

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

        /**
         * Where to cut an early chunk out of `samples[start, end)`, or -1 when
         * the newest audio holds no real pause.
         *
         * Unlike [chooseSplit], which must cut somewhere because the 30 s
         * ceiling is hard, this one may answer "not yet": the capture thread
         * asks again a moment later with more audio. A real pause is a 20 ms
         * frame at or below [PAUSE_RATIO] of the chunk's mean level — the gap
         * between phrases, not the dip between syllables. On a tie the latest
         * frame wins, so the cut sits as close to the live edge as the pause
         * allows. Nothing is cut before [EARLY_WINDOW_SAMPLES] have piled up.
         */
        internal fun choosePauseCut(samples: FloatArray, start: Int, end: Int): Int {
            if (end - start < EARLY_WINDOW_SAMPLES) return -1
            var total = 0f
            for (i in start until end) total += kotlin.math.abs(samples[i])
            val floor = total / (end - start) * SEAM_FRAME_SAMPLES * PAUSE_RATIO
            val searchFrom = maxOf(start + 1, end - EARLY_SEARCH_SAMPLES)
            var cutAt = -1
            var quietest = Float.MAX_VALUE
            var frameStart = searchFrom
            while (frameStart + SEAM_FRAME_SAMPLES <= end) {
                var energy = 0f
                for (i in frameStart until frameStart + SEAM_FRAME_SAMPLES) {
                    energy += kotlin.math.abs(samples[i])
                }
                if (energy <= floor && energy <= quietest) {
                    quietest = energy
                    cutAt = frameStart + SEAM_FRAME_SAMPLES / 2
                }
                frameStart += SEAM_FRAME_SAMPLES
            }
            return cutAt
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

    // Decode-while-speaking state, all guarded by sampleBuffer's lock.
    // consumedSamples = how much of sampleBuffer has already been handed to
    // an early decode; earlyResults = those decodes, in order. One decode
    // thread, so pieces never run concurrently on the recognizer and the
    // tail queues behind whatever early piece is still in flight.
    private var consumedSamples = 0
    private val earlyResults = ArrayList<java.util.concurrent.Future<String>>()
    private var earlyChunks = 0
    private var decodeExecutor: java.util.concurrent.ExecutorService? = null
    private var loopTicks = 0

    private fun decodeExecutor(): java.util.concurrent.ExecutorService =
        decodeExecutor ?: java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "WhisperLocalDecode").apply { isDaemon = true }
        }.also { decodeExecutor = it }

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
            synchronized(sampleBuffer) {
                sampleBuffer.clear()
                consumedSamples = 0
                earlyResults.clear()
                earlyChunks = 0
                loopTicks = 0
            }
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
            // Delayed Auditory Feedback plays the speaker's own voice back out
            // of the phone WHILE this recorder is running (see DafPlayer). The
            // microphone hears that playback, so the delayed copy of a word can
            // land in the transcript a second time. Android's echo canceller is
            // built for exactly this and costs two lines and no download.
            //
            // AEC only. NoiseSuppressor and AutomaticGainControl are deliberately
            // NOT enabled: this recorder already opens on VOICE_RECOGNITION,
            // which asks the platform for the processing chain ASR wants, and
            // both of those are tuned for a human ear on a phone call. They are
            // known to cost accuracy on top of a recognition source, and AGC
            // would fight the quiet boost above besides.
            try {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler
                        .create(record.audioSessionId)?.enabled = true
                    Log.i(TAG, "Echo canceller on")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Echo canceller unavailable: ${e.message}")
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
            // Every ~300 ms: is there a finished piece to decode already?
            if (++loopTicks % 3 == 0) maybeDecodeEarly()
        }
    }

    /**
     * Capture thread. Once [EARLY_WINDOW_SAMPLES] of un-decoded audio has
     * piled up, cut at a real pause — or at the 30 s ceiling when the speaker
     * never pauses — and decode that piece in the background while the mic
     * stays open. [stop] collects the pieces in order and only waits for the
     * tail.
     */
    private fun maybeDecodeEarly() {
        val rec = recognizer ?: return
        synchronized(sampleBuffer) {
            if (!isCapturing) return
            val pending = sampleBuffer.size - consumedSamples
            if (pending < EARLY_WINDOW_SAMPLES) return
            val view = FloatArray(pending) { sampleBuffer[consumedSamples + it] }
            val cut = if (pending >= WHISPER_WINDOW_SAMPLES) chooseSplit(view, 0)
                      else choosePauseCut(view, 0, pending)
            if (cut <= 0) return
            val chunk = view.copyOfRange(0, cut)
            consumedSamples += cut
            val n = ++earlyChunks
            earlyResults.add(decodeExecutor().submit(java.util.concurrent.Callable {
                decodePiece(rec, chunk, "early chunk #$n")
            }))
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
        val samples: FloatArray
        val tailFrom: Int
        val pieces: List<java.util.concurrent.Future<String>>
        val early: Int
        synchronized(sampleBuffer) {
            samples = FloatArray(sampleBuffer.size).also { arr ->
                for (i in arr.indices) arr[i] = sampleBuffer[i]
            }
            tailFrom = consumedSamples
            pieces = earlyResults.toList()
            early = earlyChunks
        }
        if (samples.isEmpty()) {
            dispatchFinal("")
            return
        }

        try {
            val t0 = System.currentTimeMillis()
            val tail = samples.copyOfRange(tailFrom, samples.size)
            // Same single thread as the early pieces, so the tail queues behind
            // any piece still decoding instead of racing it on the recognizer.
            val tailResult = decodeExecutor().submit(java.util.concurrent.Callable {
                decodePiece(rec, tail, if (early == 0) null else "tail")
            })
            val parts = ArrayList<String>(pieces.size + 1)
            for (f in pieces) parts.add(f.get())
            parts.add(tailResult.get())
            val text = parts.filter { it.isNotEmpty() }.joinToString(" ").trim()
            // "in Xms" is the time the speaker actually waited after the stop
            // tap, early pieces included — the number that matters.
            Log.i(
                TAG,
                "Decoded ${samples.size} samples (${samples.size / SAMPLE_RATE.toFloat()}s) " +
                    "in ${System.currentTimeMillis() - t0}ms: \"$text\"",
            )
            if (early > 0) {
                Log.i(
                    TAG,
                    "Decode-while-speaking: $early piece(s) decoded before the stop tap, " +
                        "only the ${tail.size / SAMPLE_RATE.toFloat()}s tail waited",
                )
            }
            dispatchFinal(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Decode failed: ${e.message}", e)
            dispatchError(ERROR_INFERENCE)
        }
    }

    /**
     * Trim, level-check, boost and decode one piece of a take — an early
     * chunk, the tail, or (when nothing was decoded early) the whole take,
     * in which case [label] is null and the log lines read exactly as before.
     */
    private fun decodePiece(rec: OfflineRecognizer, raw: FloatArray, label: String?): String {
        if (raw.isEmpty()) return ""
        val tag = if (label == null) "" else " [$label]"
        // Quiet Mode for the local route. BubbleService applies this to the WAV
        // that feeds the cloud route; this engine decodes its own float buffer
        // and never reads that file, so both the manual toggle and the
        // automatic boost stopped at the route boundary. Same threshold and
        // same gain as the cloud side, so a whispered take transcribes the same
        // whichever route it took.
        // Trim before measuring and boosting: the level check and the boost
        // should both describe the speech, not the room tone on either side of
        // it, and whisper-tiny hallucinates into whatever silence it is handed.
        var samples = trimSilence(raw)
        if (samples.size != raw.size) {
            val cutMs = (raw.size - samples.size) * 1000 / SAMPLE_RATE
            Log.i(TAG, "Trimmed ${cutMs}ms of silence before decode$tag")
        }

        val dbfs = meanDbfs(samples)
        if (label != null && dbfs < EARLY_SILENT_DBFS) {
            // A silent piece of a take that already has spoken pieces. Decoded
            // on its own, Whisper would invent words for it; folded into the
            // whole take it would at least have had speech around it. Skip it.
            Log.i(TAG, "Silent piece: ${"%.1f".format(dbfs)} dBFS — skipped$tag")
            return ""
        }
        val manualQuiet = WimApp.prefs.getBoolean("quiet_mode", false)
        // Log the CUTOFF and where it came from, not just the level. Without
        // it a boost decision cannot be read back: "-30.6 dBFS, boosted" is
        // equally consistent with a stored baseline making -30.6 genuinely
        // soft, and with no baseline at all falling back to the absolute -22.
        // Cost a session on 2026-08-29 that could not tell the two apart from
        // the device log.
        val baseline = WimApp.micBaselineDbfs()
        val cutoff = quietCutoffDbfs()
        val source = baseline?.let { "calibrated from ${"%.1f".format(it)}" } ?: "UNCALIBRATED"
        if (manualQuiet || shouldAutoBoost(dbfs, cutoff)) {
            Log.i(TAG, "Quiet boost on local take: ${"%.1f".format(dbfs)} dBFS " +
                "(manual=$manualQuiet, cutoff=${"%.1f".format(cutoff)}, $source)$tag")
            applyQuietBoost(samples)
        } else {
            Log.i(TAG, "Take level: ${"%.1f".format(dbfs)} dBFS — no boost " +
                "(cutoff=${"%.1f".format(cutoff)}, $source)$tag")
        }

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
        if (label != null) {
            Log.i(TAG, "Piece$tag: ${samples.size / SAMPLE_RATE.toFloat()}s decoded " +
                "in ${System.currentTimeMillis() - t0}ms: \"$text\"")
        }
        return text
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
        try { decodeExecutor?.shutdownNow() } catch (_: Throwable) {}
        decodeExecutor = null
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
