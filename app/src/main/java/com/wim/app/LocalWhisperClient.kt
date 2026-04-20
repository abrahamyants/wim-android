package com.wim.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocalWhisperClient {

    private const val TAG = "LocalWhisperClient"

    init {
        try {
            System.loadLibrary("wim_whisper")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load wim_whisper native library", e)
        }
    }

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(audioData: FloatArray, length: Int, languageCode: String): String
    private external fun nativeRelease()

    @Volatile
    private var isInitialized = false
    private val lock = Any()

    /**
     * Initializes the Whisper model from the given file path.
     * Thread-safe, ensures only one model is loaded at a time.
     */
    suspend fun init(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (isInitialized) return@withContext true
            
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                return@withContext false
            }

            Log.i(TAG, "Initializing local Whisper model...")
            val success = nativeInit(modelFile.absolutePath)
            isInitialized = success
            return@withContext success
        }
    }

    /**
     * Transcribes 16kHz mono 16-bit PCM WAV file using the local model.
     */
    suspend fun transcribe(wavFile: File, languageCode: String = "en"): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!isInitialized) {
                Log.e(TAG, "Cannot transcribe, model is not initialized")
                throw IllegalStateException("Whisper model not initialized")
            }

            if (!wavFile.exists()) {
                throw IllegalArgumentException("Audio file not found")
            }

            Log.i(TAG, "Transcribing ${wavFile.length()} bytes...")
            
            // Read WAV data and convert 16-bit PCM to normalized 32-bit floats
            val bytes = wavFile.readBytes()
            
            // Skip WAV header (44 bytes)
            val headerSize = 44
            val dataSize = bytes.size - headerSize
            
            if (dataSize <= 0) {
                Log.w(TAG, "Audio file contains no data")
                return@withContext ""
            }
            
            val numSamples = dataSize / 2
            val floatData = FloatArray(numSamples)
            val bb = ByteBuffer.wrap(bytes, headerSize, dataSize).order(ByteOrder.LITTLE_ENDIAN)
            
            for (i in 0 until numSamples) {
                floatData[i] = bb.getShort().toFloat() / 32768.0f
            }

            val t0 = System.currentTimeMillis()
            val text = nativeTranscribe(floatData, numSamples, languageCode)
            val t1 = System.currentTimeMillis()
            
            Log.i(TAG, "Local transcription took ${t1 - t0}ms: $text")
            return@withContext text.trim()
        }
    }

    fun release() {
        synchronized(lock) {
            if (isInitialized) {
                nativeRelease()
                isInitialized = false
            }
        }
    }
}