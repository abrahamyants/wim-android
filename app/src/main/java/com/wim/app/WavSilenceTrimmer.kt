package com.wim.app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strips leading and trailing silence from a 16kHz mono int16 PCM WAV file before
 * it reaches Whisper.  Equivalent to lavrentiy.py's SileroVAD LOG-ONLY pass —
 * same intent (don't waste Whisper tokens on silence) without bundling the ONNX model.
 *
 * RMS is measured in 20 ms chunks (320 samples / 640 bytes).  A 100 ms pad is kept
 * on both sides so word onsets / offsets are not clipped.
 */
object WavSilenceTrimmer {

    private const val TAG = "WavSilenceTrimmer"
    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_SAMPLE = 2
    private const val CHUNK_SAMPLES = 320               // 20 ms at 16 kHz
    private const val CHUNK_BYTES = CHUNK_SAMPLES * BYTES_PER_SAMPLE
    private const val PAD_CHUNKS = 5                    // 100 ms padding each side
    private const val RMS_THRESHOLD = 250.0             // below AudioRecorder.SILENCE_RMS_THRESHOLD
    private const val WAV_HEADER_BYTES = 44

    fun trim(wav: File): File {
        if (!wav.exists() || wav.length() <= WAV_HEADER_BYTES) return wav

        val bytes = wav.readBytes()
        val headerBytes = bytes.copyOf(WAV_HEADER_BYTES)
        val audio = bytes.copyOfRange(WAV_HEADER_BYTES, bytes.size)

        val totalChunks = audio.size / CHUNK_BYTES
        if (totalChunks < 2) return wav

        val buf = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
        val rms = DoubleArray(totalChunks) { i ->
            buf.position(i * CHUNK_BYTES)
            var sum = 0.0
            repeat(CHUNK_SAMPLES) {
                val s = buf.short.toDouble()
                sum += s * s
            }
            Math.sqrt(sum / CHUNK_SAMPLES)
        }

        var first = rms.indexOfFirst { it >= RMS_THRESHOLD }
        var last  = rms.indexOfLast  { it >= RMS_THRESHOLD }

        if (first < 0 || last < 0 || first >= last) {
            Log.i(TAG, "All silence — returning original (${wav.length()} bytes)")
            return wav
        }

        first = maxOf(0, first - PAD_CHUNKS)
        last  = minOf(totalChunks - 1, last + PAD_CHUNKS)

        val trimmedAudio = audio.copyOfRange(first * CHUNK_BYTES, (last + 1) * CHUNK_BYTES)
        val trimmedLength = trimmedAudio.size

        // Rewrite WAV header with updated sizes
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk size  @ offset 4  = fileSize - 8
        header.putInt(4, trimmedLength + WAV_HEADER_BYTES - 8)
        // data chunk size  @ offset 40 = audio bytes
        header.putInt(40, trimmedLength)

        val trimmedFile = File(wav.parent, "trimmed_${wav.name}")
        trimmedFile.outputStream().use { out ->
            out.write(headerBytes)
            out.write(trimmedAudio)
        }

        val savedMs = ((audio.size - trimmedLength).toDouble() / (SAMPLE_RATE * BYTES_PER_SAMPLE) * 1000).toInt()
        Log.i(TAG, "Trimmed ${wav.length()} → ${trimmedFile.length()} bytes (saved ~${savedMs} ms silence)")

        wav.delete()
        trimmedFile.renameTo(wav)
        return wav
    }
}
