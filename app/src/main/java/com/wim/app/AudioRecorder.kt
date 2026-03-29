package com.wim.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio to a WAV file suitable for Whisper API (16kHz mono 16-bit PCM).
 * Used for L4 stutter pipeline where Whisper outperforms on-device ASR.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var recorder: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var outputFile: File? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): File {
        if (!hasPermission()) throw SecurityException("Microphone permission not granted")

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING,
            bufferSize * 2
        )

        val file = File(context.cacheDir, "wim_recording.wav")
        outputFile = file

        recorder?.startRecording()
        isRecording = true

        recordingThread = Thread {
            writeWavFile(file, bufferSize)
        }.apply { start() }

        return file
    }

    fun stop(): File? {
        isRecording = false
        recordingThread?.join(2000)
        recorder?.stop()
        recorder?.release()
        recorder = null
        recordingThread = null

        outputFile?.let { fixWavHeader(it) }
        return outputFile
    }

    private fun writeWavFile(file: File, bufferSize: Int) {
        val fos = FileOutputStream(file)
        val buffer = ByteArray(bufferSize)

        // Placeholder WAV header (44 bytes), fixed after recording
        fos.write(ByteArray(44))

        while (isRecording) {
            val read = recorder?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                fos.write(buffer, 0, read)
            }
        }

        fos.flush()
        fos.close()
    }

    private fun fixWavHeader(file: File) {
        val size = file.length().toInt()
        val dataSize = size - 44

        val raf = RandomAccessFile(file, "rw")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray())
        header.putInt(size - 8)
        header.put("WAVE".toByteArray())

        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)          // PCM
        header.putShort(1)          // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)          // block align
        header.putShort(16)         // bits per sample

        header.put("data".toByteArray())
        header.putInt(dataSize)

        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}
