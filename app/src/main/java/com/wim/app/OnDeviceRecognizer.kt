package com.wim.app

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
    }

    fun start(resultListener: ResultListener) {
        listener = resultListener
        partialText = ""
        keepListening = true

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
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
                // Error 7 = no speech detected — restart if still listening
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (keepListening) {
                        Log.d(TAG, "No speech detected, restarting...")
                        listenIntent?.let { recognizer?.startListening(it) }
                        return
                    }
                }
                listener?.onError(error)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Final result: $text")
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

        listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
}
