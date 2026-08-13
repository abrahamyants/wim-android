package com.wim.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Reads the reconstructed text back to the user during the SEND_READY window
 * (2026-08-13).
 *
 * The problem it solves: voice-to-intent can only be checked by the user, and
 * the moment they most need to check it is the moment they can least look —
 * driving, carrying a kid, walking. Reconstruction is not transcription, so
 * "did it understand me" is a real question with a real wrong answer, and the
 * five-second SEND_READY window was previously dead time with nothing in it.
 *
 * Deliberately uses Android's on-device [TextToSpeech] rather than a network
 * neural voice. It is the robot voice and it is the right one here: this reads
 * the user's OWN words back, so speed, offline capability and zero per-message
 * cost all beat warmth. Network voices are the paid tier, later.
 *
 * CONTRACT — [speak] always invokes onDone exactly once.
 * [BubbleService.setBubbleSendReady] arms the send countdown from that
 * callback, so a callback that never fires strands the bubble in SEND_READY
 * with no timer and no way back to IDLE. Engine-missing, init failure,
 * mid-utterance error and a silently stuck utterance all route to onDone.
 */
class ReadBackSpeaker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    /** Set once onInit reports SUCCESS. Until then requests queue in [pending]. */
    private var ready = false
    /** True once init has failed; every later speak() short-circuits to onDone. */
    private var dead = false
    /** A speak() that arrived before the engine finished initialising. */
    private var pending: Pair<String, () -> Unit>? = null

    /** Guards the once-only contract for the in-flight utterance. */
    private var activeDone: (() -> Unit)? = null
    private var watchdog: Runnable? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                // onInit lands on a binder thread; hop to main so the
                // ready/pending handoff cannot race with speak().
                main.post {
                    if (status == TextToSpeech.SUCCESS) {
                        configure()
                        ready = true
                        pending?.let { (text, done) -> pending = null; speak(text, done) }
                    } else {
                        Log.e(TAG, "TTS init failed (status=$status) — read-back disabled for this service life")
                        dead = true
                        pending?.let { (_, done) -> pending = null; done() }
                    }
                }
            }
        } catch (e: Exception) {
            // No TTS engine installed at all. Rare, but a bare AOSP build or a
            // user who uninstalled Google TTS gets here, and it must not take
            // the bubble down with it.
            Log.e(TAG, "TTS construction threw — read-back disabled: ${e.message}")
            dead = true
        }
    }

    private fun configure() {
        val engine = tts ?: return
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    // ASSISTANT (not MEDIA) so this ducks music instead of
                    // pausing it, and so it follows the headset route
                    // BubbleBluetoothSco already establishes.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {}

        try {
            val tag = WimApp.speechLocaleTag()
            val locale = Locale.forLanguageTag(tag)
            val res = engine.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // The user writes in a language this phone has no voice for.
                // Fall back to the engine default rather than reading their
                // Spanish out loud with an English mouth, which is noise.
                Log.w(TAG, "no on-device voice for $tag — falling back to engine default")
                engine.setLanguage(Locale.getDefault())
            }
        } catch (_: Exception) {}

        applyVoicePref()
        try { engine.setSpeechRate(WimApp.readBackSpeed()) } catch (_: Exception) {}
    }

    /** Re-reads the voice/speed prefs. Called on every speak so a settings
     *  change takes effect on the next message, with no service restart. */
    private fun applyVoicePref() {
        val engine = tts ?: return
        val wanted = WimApp.readBackVoiceId()
        if (wanted.isBlank()) return
        try {
            engine.voices?.firstOrNull { it.name == wanted }?.let { engine.voice = it }
        } catch (_: Exception) {}
    }

    /**
     * Speaks [text], then calls [onDone] on the main thread. Exactly once.
     *
     * @param text the exact string that was injected into the host field.
     */
    fun speak(text: String, onDone: () -> Unit) {
        val clean = text.trim()
        if (dead || clean.isEmpty()) { onDone(); return }

        val engine = tts
        if (engine == null || !ready) {
            // Engine still warming up. Hold the newest request only — an older
            // queued message is stale by definition, its countdown is gone.
            pending?.let { (_, staleDone) -> staleDone() }
            pending = clean to onDone
            return
        }

        // A new message arrived while the last one was still talking. Release
        // the old callback before taking the new one so nothing is stranded.
        finishActive()

        applyVoicePref()
        try { engine.setSpeechRate(WimApp.readBackSpeed()) } catch (_: Exception) {}

        val spoken = if (clean.length > MAX_CHARS) clean.take(MAX_CHARS) else clean
        if (spoken.length < clean.length) {
            Log.i(TAG, "read-back truncated ${clean.length} -> $MAX_CHARS chars")
        }

        activeDone = onDone
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { finishActive() } }
            @Deprecated("required by the abstract class")
            override fun onError(utteranceId: String?) { main.post { finishActive() } }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "read-back utterance error code=$errorCode")
                main.post { finishActive() }
            }
        })

        val params = Bundle()
        val result = try {
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.e(TAG, "speak() threw: ${e.message}")
            TextToSpeech.ERROR
        }

        if (result != TextToSpeech.SUCCESS) {
            finishActive()
            return
        }

        // Watchdog. Some engines drop onDone entirely when audio focus is
        // stolen mid-utterance — the bubble would then sit green forever with
        // no countdown. Budget the estimated read plus generous slack.
        val estimateMs = estimateSpeechMs(spoken.length, WimApp.readBackSpeed())
        val runnable = Runnable {
            Log.w(TAG, "read-back watchdog fired after ${estimateMs + WATCHDOG_SLACK_MS}ms — forcing countdown")
            finishActive()
        }
        watchdog = runnable
        main.postDelayed(runnable, estimateMs + WATCHDOG_SLACK_MS)
    }

    /** Invokes and clears the in-flight callback. Idempotent — this is what
     *  makes the once-only contract hold across done/error/watchdog racing. */
    private fun finishActive() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        val done = activeDone ?: return
        activeDone = null
        done()
    }

    /** Cuts the read short — the user tapped SEND, or the bubble is going away.
     *  Does NOT fire the pending callback: the caller already moved on. */
    fun stop() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        activeDone = null
        pending = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "WiM.ReadBack"
        private const val UTTERANCE_ID = "wim_read_back"

        /**
         * Hard cap on what gets read aloud. A 3,000-character message at 1x is
         * over three minutes of the bubble holding SEND_READY open, which is
         * not a review window, it is a hostage situation. Long messages get the
         * opening read and the user reads the rest.
         */
        const val MAX_CHARS = 600

        private const val WATCHDOG_SLACK_MS = 4_000L

        /** ~14 characters per second at 1.0x is a normal English speaking pace. */
        fun estimateSpeechMs(chars: Int, rate: Float): Long {
            val safeRate = if (rate <= 0.1f) 1.0f else rate
            return ((chars / 14.0) / safeRate * 1000).toLong().coerceIn(800L, 60_000L)
        }

        /**
         * Human-facing voice list for the settings screen, built from what the
         * phone actually has. Deliberately not a hard-coded four: a Pixel
         * carries several English voices, plenty of phones carry exactly one,
         * and offering a choice that does not exist is worse than offering one.
         */
        fun voiceOptions(engine: TextToSpeech?, localeTag: String): List<Pair<String, String>> {
            engine ?: return emptyList()
            val lang = Locale.forLanguageTag(localeTag).language
            val voices = try {
                engine.voices
                    ?.filter { it.locale.language == lang }
                    ?.filterNot { it.isNetworkConnectionRequired }
                    ?.sortedBy { it.name }
                    .orEmpty()
            } catch (_: Exception) { emptyList() }

            var femaleCount = 0
            var maleCount = 0
            var plainCount = 0
            return voices.map { v ->
                val n = v.name.lowercase()
                val label = when {
                    n.contains("female") -> { femaleCount++; "Female $femaleCount" }
                    n.contains("male") -> { maleCount++; "Male $maleCount" }
                    else -> { plainCount++; "Voice $plainCount" }
                }
                v.name to label
            }
        }
    }
}
