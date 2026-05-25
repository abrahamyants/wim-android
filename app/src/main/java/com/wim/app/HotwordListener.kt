package com.wim.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous hotword listener for "Hey WiM."
 *
 * Wraps Android's SpeechRecognizer in a self-restarting loop, scans every
 * partial result for the wake phrase (and common phonetic mishears), and
 * fires [onHotword] exactly once per detection. Caller is responsible for
 * stopping this listener before any other mic-using path runs (only one
 * SpeechRecognizer can hold the mic at a time).
 *
 * Battery: continuous listening burns roughly 2–5% per hour on modern
 * Pixel/Samsung devices. The listener is gated on `hotword_enabled` pref
 * and only auto-resumes when the bubble is idle.
 *
 * Privacy: every audio frame is processed on-device by Google's recognizer
 * (we pin its component to bypass Samsung's chirp-emitting OEM service).
 * EXTRA_PREFER_OFFLINE forces no-network mode where supported.
 */
class HotwordListener(
    private val context: Context,
    private val onHotword: () -> Unit,
) {

    companion object {
        private const val TAG = "WiM.Hotword"

        // Phonetic mishear corpus for "hey wim" — matches common Whisper /
        // Google ASR substitutions. All matches are lowercase substring
        // checks; the user's actual phrase doesn't have to be the entire
        // partial, just appear inside it.
        private val PHRASES = listOf(
            "hey wim", "hey win", "hey when", "hey wem", "hey ween",
            "hey wimm", "haywim", "hey wim,", "hey, wim",
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private var listenIntent: Intent? = null
    private var running = false
    private var fired = false
    private val handler = Handler(Looper.getMainLooper())
    private val restart = Runnable {
        if (running) try {
            listenIntent?.let { recognizer?.startListening(it) }
        } catch (e: Exception) {
            Log.w(TAG, "restart failed: ${e.message}")
        }
    }

    fun start(): Boolean {
        if (running) return true
        running = true
        fired = false

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "isRecognitionAvailable=false — bailing out")
            running = false
            return false
        }
        recognizer = createGoogleOrDefaultRecognizer()
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
            }

            override fun onError(error: Int) {
                if (!running) return
                Log.w(TAG, "onError=$error (${errorName(error)})")
                // Almost everything is recoverable for hotword purposes —
                // we just want to keep a recognition session warm. Restart
                // with a brief delay; longer when the recognizer is busy
                // so we don't thrash.
                val delay = if (error == 8) 250L else 120L
                handler.postDelayed(restart, delay)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.i(TAG, "onResults: ${matches?.firstOrNull() ?: "(empty)"}")
                checkAndFire(matches)
                if (running && !fired) handler.postDelayed(restart, 60L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "onPartialResults: ${matches?.firstOrNull() ?: "(empty)"}")
                checkAndFire(matches)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Long minimum so the recognizer doesn't timeout-bounce constantly
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra("android.speech.extra.PROFANITY_FILTER_OFF", true)
        }

        try {
            recognizer?.startListening(listenIntent!!)
            Log.i(TAG, "Hotword listener started")
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed: ${e.message}")
            running = false
            recognizer?.destroy()
            recognizer = null
            return false
        }
        return true
    }

    fun stop() {
        running = false
        handler.removeCallbacks(restart)
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        listenIntent = null
        Log.i(TAG, "Hotword listener stopped")
    }

    private fun checkAndFire(matches: ArrayList<String>?) {
        if (fired || matches.isNullOrEmpty()) return
        for (raw in matches) {
            val text = raw.lowercase()
            if (PHRASES.any { phrase -> text.contains(phrase) }) {
                Log.i(TAG, "Hotword detected: '$raw'")
                fired = true
                stop()
                handler.post { onHotword() }
                return
            }
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        9 -> "INSUFFICIENT_PERMISSIONS_(9)"
        10 -> "TOO_MANY_REQUESTS"
        11 -> "SERVER_DISCONNECTED"
        12 -> "LANGUAGE_NOT_SUPPORTED"
        13 -> "LANGUAGE_UNAVAILABLE"
        14 -> "CANNOT_CHECK_SUPPORT"
        else -> "?"
    }

    private fun createGoogleOrDefaultRecognizer(): SpeechRecognizer {
        val googleComponent = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
        val probeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            component = googleComponent
        }
        return try {
            if (context.packageManager.resolveService(probeIntent, 0) != null) {
                SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: Exception) {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }
}
