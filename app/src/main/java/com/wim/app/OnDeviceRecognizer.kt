package com.wim.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech recognition using Android's built-in SpeechRecognizer.
 * Zero latency, zero cost, no network needed for basic transcription.
 */
class OnDeviceRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "WiM"
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: ResultListener? = null
    private var partialText = ""
    private var keepListening = false
    private var listenIntent: Intent? = null

    interface ResultListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(errorCode: Int)
        fun onSoundEvent(label: String) {}
    }

    fun start(resultListener: ResultListener) {
        listener = resultListener
        partialText = ""
        keepListening = true

        recognizer = createGoogleOrDefaultRecognizer()
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer ready")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                Log.e(TAG, "SpeechRecognizer error: $error")
                // Detect silence/pause as a sound event
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    listener?.onSoundEvent("[Pause]")
                }
                if (keepListening && (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error == 8 /* ERROR_RECOGNIZER_BUSY */
                        || error == SpeechRecognizer.ERROR_SERVER)) {
                    val delay = if (error == 8) 150L else 0L
                    Log.d(TAG, "Recoverable error $error, restarting in ${delay}ms...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (keepListening) {
                            listenIntent?.let { recognizer?.startListening(it) }
                        }
                    }, delay)
                    return
                }
                listener?.onError(error)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Final result: $text")
                // Route through VoiceCommandRouter first — clarifier handler
                // claims "next/yes/no/skip/swap" while in BubbleState.Clarifying.
                // If a handler claims, the dictation pipeline is short-circuited.
                val claimed = VoiceCommandRouter.dispatch(text)
                if (claimed != null) {
                    Log.d(TAG, "Voice command claimed by: $claimed")
                    if (keepListening) listenIntent?.let { recognizer?.startListening(it) }
                    return
                }
                listener?.onFinalResult(text)
                // Auto-restart to keep listening until user taps stop
                if (keepListening) {
                    Log.d(TAG, "Restarting recognizer (continuous mode)")
                    listenIntent?.let { recognizer?.startListening(it) }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    partialText = text
                    listener?.onPartialResult(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val primaryLang = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString("primary_language", null)
            ?: com.wim.app.WimApp.prefs.getString("primary_language", "en-US")
            ?: "en-US"
        val secondaryLang = com.wim.app.WimApp.prefs.getString("secondary_language", null)

        // Patience Mode: extend silence timeout for L4/high_stress (blocks last 3-4s)
        val layer = com.wim.app.WimApp.layer()
        val situation = com.wim.app.WimApp.situation()
        val patienceMs = if (layer >= 4 || situation == "high_stress") 4500L else 2000L

        listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Force on-device recognition so this fallback path (only fires when
            // the whisper-tiny model isn't ready) doesn't quietly send audio to
            // Google's cloud. Hint, not guarantee — but Google's TTS recognizer
            // service honors it on Pixel and modern Samsung.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, patienceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, patienceMs)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            // Disable the recognizer's profanity filter — product rule is to
            // preserve the speaker's language verbatim. Undocumented but
            // respected by Google's recognizer.
            putExtra("android.speech.extra.PROFANITY_FILTER_OFF", true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60000L)
            // If secondary language set, allow both
            if (!secondaryLang.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLang)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE, arrayListOf(primaryLang, secondaryLang))
            }
        }

        recognizer?.startListening(listenIntent!!)
    }

    fun stop() {
        keepListening = false
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    // Pin Google's RecognitionService so Samsung's OEM recognizer never runs —
    // Samsung's chirp is a USAGE_ASSISTANCE_SONIFICATION beep that no AudioFocus
    // or stream-volume trick suppresses. Google's recognizer doesn't play it.
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
                Log.i(TAG, "Using Google recognizer (Samsung chirp bypass)")
                SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
            } else {
                Log.w(TAG, "Google recognizer unavailable, using default")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google recognizer bind failed: ${e.message}, using default")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }
}
