package com.wim.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio to a WAV file suitable for Whisper API (16kHz mono 16-bit PCM).
 *
 * Pre-roll buffer: maintains a 500ms rolling buffer of recent audio. When
 * recording starts, the buffer is prepended so the first words aren't lost
 * to startup latency.
 *
 * Quiet Mode: applies +18dB software gain and a high-pass filter so
 * whispered/soft speech becomes detectable.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Pre-roll buffer: 500ms @ 16kHz * 2 bytes/sample = 16,000 bytes
        private const val PREROLL_MS = 500
        private const val PREROLL_BYTES = SAMPLE_RATE * 2 * PREROLL_MS / 1000

        // Quiet mode parameters
        private const val QUIET_GAIN_DB = 18.0f
        private val QUIET_GAIN_LINEAR = Math.pow(10.0, (QUIET_GAIN_DB / 20.0)).toFloat()
        private const val HIGHPASS_ALPHA = 0.995f

        // Silent-block detector (foundation pitch — bridges users with
        // speech disfluency whose mid-utterance silent blocks produce no
        // audio for ASR to transcribe). Fires onSilentBlockMidSpeech ONCE
        // per recording when amplitude has stayed below SILENCE_RMS_THRESHOLD
        // for at least MIN_SILENCE_DURATION_MS continuous milliseconds AFTER
        // ≥1 chunk of speech-level amplitude was seen AND total recording time
        // ≥ MIN_RECORDING_BEFORE_DETECT_MS. Consumer (BubbleService) decides
        // whether to act on the signal — detector is opt-in via callback.
        // Tuned to NOT fire on normal breath/think pauses (1.5s threshold) or
        // short utterances (<3s ignored entirely).
        private const val SILENCE_RMS_THRESHOLD = 500.0
        private const val MIN_RECORDING_BEFORE_DETECT_MS = 3000L
        private const val MIN_SILENCE_DURATION_MS = 1500L
    }

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var outputFile: File? = null

    @Volatile private var isPrimed = false
    @Volatile private var isRecording = false
    @Volatile private var outputStream: FileOutputStream? = null
    @Volatile private var quietMode: Boolean = false

    // Silent-block detector state. See companion-object constants for the
    // tuning rationale.
    var onSilentBlockMidSpeech: (() -> Unit)? = null
    private var recordingStartMs = 0L
    @Volatile private var lastSpeechMs = 0L
    @Volatile private var hasSeenSpeechInRecording = false
    @Volatile private var silentBlockFiredThisRecording = false

    // Running loudness accumulator for Auto Quiet Mode (mean RMS over the take).
    private var takeRmsSum = 0.0
    private var takeRmsCount = 0

    // Circular pre-roll buffer
    private val circularBuffer = ByteArray(PREROLL_BYTES)
    private var bufferPos = 0
    private var bufferFilled = 0
    private val bufferLock = Object()

    private var hpLastInput = 0f
    private var hpLastOutput = 0f

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start continuous mic capture into the circular pre-roll buffer.
     * Call this as early as possible (e.g. when the bubble becomes visible).
     * Safe to call multiple times — no-op if already primed.
     *
     * @Synchronized closes a check-then-act race in the previous version:
     * `if (isPrimed) return` followed by the AudioRecord construction +
     * `isPrimed = true` was not atomic, so two threads racing the prime
     * could both pass the check, both build an AudioRecord, both start
     * recording, and leak the second native handle (pinning the mic for
     * the service's life). Audit finding 2026-06-06.
     */
    @Synchronized
    fun primeBuffer() {
        if (isPrimed) return
        if (!hasPermission()) return

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING,
            bufferSize * 2
        )
        recorder?.startRecording()
        isPrimed = true

        captureThread = Thread {
            captureLoop(bufferSize)
        }.apply { start() }
    }

    /**
     * Start writing captured audio to a WAV file, prepending the pre-roll buffer.
     */
    fun start(quietMode: Boolean = false): File {
        if (!hasPermission()) throw SecurityException("Microphone permission not granted")
        if (!isPrimed) primeBuffer()
        this.quietMode = quietMode
        hpLastInput = 0f
        hpLastOutput = 0f

        val file = File(context.cacheDir, "wim_recording.wav")
        outputFile = file
        val fos = FileOutputStream(file)
        try {
            fos.write(ByteArray(44))  // placeholder WAV header

            // Dump circular buffer contents (oldest → newest)
            synchronized(bufferLock) {
                val prerollBytes = ByteArray(bufferFilled)
                if (bufferFilled > 0) {
                    val start = if (bufferFilled < PREROLL_BYTES) 0 else bufferPos
                    for (i in 0 until bufferFilled) {
                        prerollBytes[i] = circularBuffer[(start + i) % PREROLL_BYTES]
                    }
                    if (quietMode) applyQuietModeProcessing(prerollBytes, prerollBytes.size)
                    fos.write(prerollBytes)
                }
            }

            // Reset silent-block detector state for the new recording.
            // Done here (not in primeBuffer) so the detector cycle aligns
            // with actual user-initiated recording, not the always-on prime.
            recordingStartMs = System.currentTimeMillis()
            lastSpeechMs = recordingStartMs
            hasSeenSpeechInRecording = false
            silentBlockFiredThisRecording = false
            takeRmsSum = 0.0
            takeRmsCount = 0

            outputStream = fos
            isRecording = true
        } catch (e: Exception) {
            // Anything between fos creation and outputStream assignment fails
            // (disk full, file deleted underneath, write IO error) — close the
            // fd here so it doesn't leak per call. stop() won't see it because
            // outputStream is still null.
            try { fos.close() } catch (_: Exception) {}
            throw e
        }
        return file
    }

    /**
     * Stop writing to file (but keep the mic primed for next recording).
     */
    fun stop(): File? {
        // Null outputStream BEFORE flipping isRecording so subsequent
        // captureLoop iterations short-circuit on `fos != null` and skip
        // the write. captureLoop may still hold a stale local fos reference
        // from a check that crossed our flip — its write catch logs + bails.
        val fos = outputStream
        outputStream = null
        isRecording = false
        try { fos?.flush(); fos?.close() } catch (_: Exception) {}
        outputFile?.let { fixWavHeader(it) }
        return outputFile
    }

    /**
     * Snapshot the audio captured so far into a self-contained WAV with a fixed
     * header, WITHOUT stopping the recording. Used by the silent-block bridging
     * consumer to transcribe the partial utterance at the moment a mid-utterance
     * block fires. Returns null if nothing meaningful has been recorded yet.
     *
     * NOTE: foundation only — not yet consumed by BubbleService.
     */
    fun snapshotPartialWav(): File? {
        val src = outputFile ?: return null
        try { outputStream?.flush() } catch (_: Exception) {}
        return try {
            val snap = File(context.cacheDir, "wim_partial_snapshot.wav")
            src.copyTo(snap, overwrite = true)
            if (snap.length() <= 44L) { snap.delete(); return null }
            fixWavHeader(snap)
            snap
        } catch (e: Exception) {
            Log.w("AudioRecorder", "snapshotPartialWav failed: ${e.message}")
            null
        }
    }

    /**
     * Fully stop and release the AudioRecord. Use when BubbleService is destroyed.
     */
    fun release() {
        isRecording = false
        isPrimed = false
        // L10: null the field BEFORE stopping so the capture loop stops touching
        // the recorder, then join the thread so it isn't mid-read() when we
        // stop/release (native crash). Defensive try/catch covers any residual
        // race if join(500) times out.
        val rec = recorder
        recorder = null
        captureThread?.join(500)
        captureThread = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        bufferFilled = 0
        bufferPos = 0
    }

    private fun captureLoop(bufferSize: Int) {
        val buf = ByteArray(bufferSize)
        while (isPrimed) {
            val read = recorder?.read(buf, 0, buf.size) ?: 0
            if (read <= 0) {
                // M4: don't busy-spin at 100% CPU. A negative code is a real
                // AudioRecord error (e.g. ERROR_DEAD_OBJECT) the loop can't
                // recover from — bail. read == 0 is an empty tick — yield first.
                if (read < 0) {
                    Log.e("AudioRecorder", "read error $read — ending capture loop")
                    break
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
                continue
            }

            // If recording, write to file (with quiet mode processing)
            val fos = outputStream
            if (fos != null && isRecording) {
                try {
                    if (quietMode) {
                        val processed = buf.copyOf(read)
                        applyQuietModeProcessing(processed, read)
                        fos.write(processed, 0, read)
                    } else {
                        fos.write(buf, 0, read)
                    }
                } catch (e: Exception) {
                    // Disk full, closed stream, or file deleted underneath us.
                    // Bail the loop so stop() sees a short/empty WAV and skips
                    // Whisper rather than transcribing silence/hallucination.
                    Log.w("AudioRecorder", "capture write failed: ${e.message}")
                    isRecording = false
                }

                // Auto Quiet Mode — accumulate mean RMS across the whole take.
                val chunkRms = computeRms(buf, read)
                takeRmsSum += chunkRms
                takeRmsCount++

                // Silent-block detector. Only runs while actually recording.
                // No-op (just state update) when no callback is registered —
                // consumer (BubbleService) wires the callback at L4 only.
                if (!silentBlockFiredThisRecording) {
                    val rms = chunkRms
                    val now = System.currentTimeMillis()
                    if (rms > SILENCE_RMS_THRESHOLD) {
                        hasSeenSpeechInRecording = true
                        lastSpeechMs = now
                    } else if (hasSeenSpeechInRecording) {
                        val silenceMs = now - lastSpeechMs
                        val recordingMs = now - recordingStartMs
                        if (silenceMs >= MIN_SILENCE_DURATION_MS &&
                            recordingMs >= MIN_RECORDING_BEFORE_DETECT_MS) {
                            silentBlockFiredThisRecording = true
                            onSilentBlockMidSpeech?.let { cb ->
                                Log.i("AudioRecorder", "silent-block detected: silence=${silenceMs}ms recording=${recordingMs}ms — firing callback")
                                try { cb() } catch (e: Exception) {
                                    Log.w("AudioRecorder", "silent-block callback threw: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            // Always write to circular buffer (unprocessed raw audio)
            synchronized(bufferLock) {
                for (i in 0 until read) {
                    circularBuffer[bufferPos] = buf[i]
                    bufferPos = (bufferPos + 1) % PREROLL_BYTES
                    if (bufferFilled < PREROLL_BYTES) bufferFilled++
                }
            }
        }
    }

    /**
     * RMS amplitude of a 16-bit PCM little-endian chunk. Used by the
     * silent-block detector in captureLoop. Returns 0.0 for empty chunks.
     */
    private fun computeRms(buf: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val samples = length / 2
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val s = bb.getShort(i * 2).toDouble()
            sumSquares += s * s
        }
        return Math.sqrt(sumSquares / samples)
    }

    private fun applyQuietModeProcessing(buffer: ByteArray, length: Int) {
        val bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val numSamples = length / 2
        for (i in 0 until numSamples) {
            val pos = i * 2
            val sample = bb.getShort(pos).toFloat()

            val filtered = HIGHPASS_ALPHA * (hpLastOutput + sample - hpLastInput)
            hpLastInput = sample
            hpLastOutput = filtered

            val boosted = filtered * QUIET_GAIN_LINEAR
            val clipped = when {
                boosted > 32767f -> 32767
                boosted < -32768f -> -32768
                else -> boosted.toInt()
            }
            bb.putShort(pos, clipped.toShort())
        }
    }

    /** Mean loudness of the last take in dBFS (16-bit full scale). Drives the
     *  Auto Quiet Mode decision in BubbleService. -120 if nothing recorded. */
    fun lastTakeDbfs(): Double {
        if (takeRmsCount <= 0) return -120.0
        val mean = takeRmsSum / takeRmsCount
        return 20.0 * Math.log10((mean / 32768.0).coerceAtLeast(1e-9))
    }

    /** Apply the Quiet Mode boost (high-pass + gain) to an already-written WAV.
     *  Auto Quiet Mode keeps capture raw and boosts the whole take here, before
     *  Whisper, only when the take was whisper-soft — mirrors the per-chunk
     *  capture-time path used for the manual toggle, so the two stay consistent. */
    fun boostWavQuiet(file: File) {
        try {
            val all = file.readBytes()
            if (all.size <= 44) return
            val data = all.copyOfRange(44, all.size)
            hpLastInput = 0f
            hpLastOutput = 0f
            applyQuietModeProcessing(data, data.size)
            RandomAccessFile(file, "rw").use { raf -> raf.seek(44); raf.write(data) }
        } catch (e: Exception) {
            Log.w("AudioRecorder", "boostWavQuiet failed: ${e.message}")
        }
    }

    private fun fixWavHeader(file: File) {
        val size = file.length().toInt()
        val dataSize = size - 44

        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            header.put("RIFF".toByteArray())
            header.putInt(size - 8)
            header.put("WAVE".toByteArray())

            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2)
            header.putShort(2)
            header.putShort(16)

            header.put("data".toByteArray())
            header.putInt(dataSize)

            raf.seek(0)
            raf.write(header.array())
        }
    }
}
