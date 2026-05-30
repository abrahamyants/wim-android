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
    }

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var outputFile: File? = null

    @Volatile private var isPrimed = false
    @Volatile private var isRecording = false
    @Volatile private var outputStream: FileOutputStream? = null
    @Volatile private var quietMode: Boolean = false

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
     */
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
     * Fully stop and release the AudioRecord. Use when BubbleService is destroyed.
     */
    fun release() {
        isRecording = false
        isPrimed = false
        captureThread?.join(500)
        recorder?.stop()
        recorder?.release()
        recorder = null
        captureThread = null
        bufferFilled = 0
        bufferPos = 0
    }

    private fun captureLoop(bufferSize: Int) {
        val buf = ByteArray(bufferSize)
        while (isPrimed) {
            val read = recorder?.read(buf, 0, buf.size) ?: 0
            if (read <= 0) continue

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
