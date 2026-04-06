package com.wim.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

/**
 * Delayed Auditory Feedback (DAF) player.
 *
 * Captures mic audio via AudioRecord and plays it back through headphones/earbuds
 * with a configurable delay (30-300 ms). The delay is implemented with a circular
 * buffer: audio enters at the write position and exits from a read position that
 * trails by [delayMs] worth of samples.
 *
 * Uses VOICE_COMMUNICATION audio source so it can coexist with SpeechRecognizer
 * on most devices. Falls back to UNPROCESSED, then DEFAULT if the primary source
 * is unavailable.
 */
class DafPlayer {

    companion object {
        private const val TAG = "WiM-DAF"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true

        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runLoop()
        }, "DAF-Thread").also { it.start() }
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        t?.interrupt()
        try { t?.join(200) } catch (_: InterruptedException) {}
    }

    // ── internals ──────────────────────────────────────────

    private fun runLoop() {
        val delayMs = WimApp.dafDelay().coerceIn(30, 300)
        Log.i(TAG, "Starting DAF with ${delayMs}ms delay")

        val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val minPlayBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)

        if (minRecBuf <= 0 || minPlayBuf <= 0) {
            Log.e(TAG, "Cannot determine buffer sizes (rec=$minRecBuf, play=$minPlayBuf)")
            return
        }

        // Chunk size: ~10ms of audio (160 samples * 2 bytes = 320 bytes)
        val chunkSize = (SAMPLE_RATE / 100) * 2  // 320 bytes = 10ms at 16kHz 16-bit

        // Circular buffer sized to hold at least delayMs of audio + some headroom
        val delaySamples = (SAMPLE_RATE * delayMs) / 1000
        val delayBytes = delaySamples * 2  // 16-bit = 2 bytes per sample
        val ringSize = (delayBytes + chunkSize * 4).coerceAtLeast(minRecBuf * 2)
        val ring = ByteArray(ringSize)
        var writePos = delayBytes  // Start writing ahead so read position trails by delayBytes
        var readPos = 0

        val recorder = createAudioRecord(minRecBuf.coerceAtLeast(chunkSize * 4))
        if (recorder == null) {
            Log.e(TAG, "Failed to create AudioRecord with any source")
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(minPlayBuf.coerceAtLeast(chunkSize * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            recorder.startRecording()
            track.play()
            Log.i(TAG, "DAF active: ring=${ringSize}B, delay=${delayBytes}B, chunk=${chunkSize}B")

            val chunk = ByteArray(chunkSize)

            while (running) {
                val bytesRead = recorder.read(chunk, 0, chunkSize)
                if (bytesRead <= 0) continue

                // Write captured audio into the ring at writePos
                for (i in 0 until bytesRead) {
                    ring[writePos % ringSize] = chunk[i]
                    writePos++
                }

                // Read delayed audio from the ring at readPos
                val out = ByteArray(bytesRead)
                for (i in 0 until bytesRead) {
                    out[i] = ring[readPos % ringSize]
                    readPos++
                }

                track.write(out, 0, bytesRead)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DAF error: ${e.message}", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            Log.i(TAG, "DAF stopped")
        }
    }

    /**
     * Try to create an AudioRecord with VOICE_COMMUNICATION first (coexists with
     * SpeechRecognizer on most devices), then UNPROCESSED, then DEFAULT.
     */
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            try {
                val rec = AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord created with source=$source")
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=$source failed: ${e.message}")
            }
        }
        return null
    }
}
