package com.wim.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast

/**
 * "Hey WiM" hotword listener wiring — gates on the user pref, releases
 * the AudioRecorder pre-roll buffer (mic is exclusive between the
 * SpeechRecognizer and AudioRecord on the same source), and disables
 * the pref if the recognizer is unavailable on this device so the user
 * doesn't sit waiting for a hotword that will never fire.
 *
 * Extracted from BubbleService 2026-05-25 as the fifteenth slice.
 * Cohesive cluster: listener lifecycle, mic-handoff to the recognizer,
 * fire-callback (vibrate + start recording + drop listener).
 */
class BubbleHotword(
    private val context: Context,
    private val getVibrator: () -> Vibrator?,
    private val isRecording: () -> Boolean,
    private val releaseAudioRecorder: () -> Unit,
    private val onHotwordFired: () -> Unit,
) {
    private companion object {
        private const val TAG = "WiM"
    }

    private var listener: HotwordListener? = null

    fun startIfEnabled() {
        if (!WimApp.prefs.getBoolean("hotword_enabled", false)) return
        if (isRecording()) return
        if (listener != null) return
        // Free the mic for the recognizer — AudioRecord and SpeechRecognizer
        // can't share the same source.
        releaseAudioRecorder()
        val hl = HotwordListener(context) {
            // Hotword fired — vibrate, drop the listener (it stops itself
            // pre-callback), and kick off a normal recording session via
            // the supplied callback.
            try {
                getVibrator()?.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } catch (_: Exception) {}
            listener = null
            onHotwordFired()
        }
        if (hl.start()) {
            listener = hl
        } else {
            // Recognizer not available on this device — auto-disable the pref
            // so the user doesn't sit waiting for a hotword that will never
            // fire. Surface a toast so the silence has an explanation.
            WimApp.prefs.edit().putBoolean("hotword_enabled", false).apply()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Hey WiM unavailable on this device.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun stop() {
        listener?.stop()
        listener = null
    }
}
