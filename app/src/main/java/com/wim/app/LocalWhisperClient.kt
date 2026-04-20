package com.wim.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Set while a nativeTranscribe call is in-flight. Used to reject overlapping
    // transcriptions outright rather than queue them behind the mutex — native
    // JNI calls can't be interrupted mid-inference, so queueing caused visible
    // pileups (15-70s waits) when the user recorded twice in quick succession.
    @Volatile
    private var isTranscribing = false

    // Coroutine-aware mutex. Replaces the prior `synchronized(lock)` JVM monitor,
    // which blocked threads during 70+ second transcriptions and caused the UI to
    // freeze when the bubble tap handler tried to acquire the lock for init/release.
    // A Mutex suspends the coroutine instead of blocking the underlying thread.
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initializes the Whisper model from the given file path. Idempotent —
     * subsequent calls while already initialized return true immediately.
     */
    suspend fun init(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInitialized) return@withLock true

            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                return@withLock false
            }

            Log.i(TAG, "Initializing local Whisper model...")
            val success = nativeInit(modelFile.absolutePath)
            isInitialized = success
            success
        }
    }

    /**
     * Transcribes 16kHz mono 16-bit PCM WAV file using the local model.
     * Cancellable via standard coroutine cancellation — callers can cancel the
     * job before nativeTranscribe starts (no way to cancel mid-JNI call).
     */
    suspend fun transcribe(wavFile: File, languageCode: String = "en"): String = withContext(Dispatchers.IO) {
        // Skip-overlap policy: if a transcription is already running, return
        // empty immediately rather than queue behind it. Rationale: whisper.cpp
        // native calls cannot be interrupted, so two rapid-fire recordings
        // otherwise pile up and the second blocks for the first's duration.
        // Caller (BubbleService) falls back to Samsung / on-device text when
        // this returns empty.
        if (isTranscribing) {
            Log.w(TAG, "Skipping overlap — a transcription is already in flight")
            return@withContext ""
        }

        mutex.withLock {
            // Re-check inside the lock in case another coroutine grabbed the
            // slot between the outer check and lock acquisition.
            if (isTranscribing) {
                Log.w(TAG, "Skipping overlap (lock-check) — a transcription is already in flight")
                return@withLock ""
            }
            isTranscribing = true

            try {
                if (!isInitialized) {
                    Log.e(TAG, "Cannot transcribe, model is not initialized")
                    throw IllegalStateException("Whisper model not initialized")
                }

                if (!wavFile.exists()) {
                    throw IllegalArgumentException("Audio file not found")
                }

                Log.i(TAG, "Transcribing ${wavFile.length()} bytes...")

                val bytes = wavFile.readBytes()
                val headerSize = 44
                val dataSize = bytes.size - headerSize

                if (dataSize <= 0) {
                    Log.w(TAG, "Audio file contains no data")
                    return@withLock ""
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
                text.trim()
            } finally {
                isTranscribing = false
            }
        }
    }

    /**
     * Releases native resources. Non-blocking — schedules the native release
     * on the internal scope. Any in-flight transcription finishes first.
     */
    fun release() {
        if (!isInitialized) return
        scope.launch {
            mutex.withLock {
                if (isInitialized) {
                    nativeRelease()
                    isInitialized = false
                    Log.i(TAG, "Local Whisper released")
                }
            }
        }
    }
}
