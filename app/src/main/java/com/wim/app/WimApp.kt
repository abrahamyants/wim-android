package com.wim.app

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WimApp : Application() {

    companion object {
        lateinit var instance: WimApp
            private set
        lateinit var prefs: SharedPreferences
            internal set

        private lateinit var profileManager: ProfileManager

        // Single SessionHistory instance shared across the process. Re-allocating
        // per auth-state change leaked SQLiteOpenHelper file descriptors (one
        // per sign-in / sign-out / token refresh) until EMFILE.
        lateinit var sessionHistory: SessionHistory
            private set

        // Session sync listener — MainActivity registers to refresh UI after Firestore sync
        var onSessionsSynced: (() -> Unit)? = null

        /**
         * OpenAI key for the device-direct path.
         *
         * Order: a key compiled into this build wins, then a developer key in
         * prefs on debug builds, then nothing.
         *
         * `EMBEDDED_OPENAI_KEY` is blank in every variant except `giveaway`,
         * the self-contained build that ships the operator's own key so a
         * stranger can install the APK and use every cloud layer with no
         * sign-in and no subscription, billed to him. `release` is unchanged
         * and still returns blank, so the normal build remains backend-only.
         */
        fun apiKey(): String {
            BuildConfig.EMBEDDED_OPENAI_KEY.takeIf { it.isNotBlank() }?.let { return it }
            if (!BuildConfig.DEBUG) return ""
            return prefs.getString("api_key", "") ?: ""
        }

        /**
         * Anthropic API key — used for L4 Sonnet 4.6 extended-thinking
         * reconstruction (the clinical / heavy-disfluency layer). Falcon
         * was retired 2026-05-10, so this key powers L4 only. When blank,
         * L4 falls back to GPT-4o.
         */
        fun anthropicApiKey(): String {
            BuildConfig.EMBEDDED_ANTHROPIC_KEY.takeIf { it.isNotBlank() }?.let { return it }
            if (!BuildConfig.DEBUG) return ""
            return prefs.getString("anthropic_api_key", "") ?: ""
        }

        /**
         * Type-safe preference reads.
         *
         * SharedPreferences throws ClassCastException when a key holds a
         * different type than the getter asks for, and these are read from
         * WimApp.onCreate — so ONE wrongly-typed value makes the app
         * unlaunchable, permanently, with clearing app data (and the user's
         * whole speech profile) as the only way out.
         *
         * That is not hypothetical: it already happened to `l1_cloud_asr`,
         * which is why that one accessor grew a hand-written try/catch. A test
         * broadcast wrote a String where a Boolean was expected and the next
         * launch died. Twenty-two other reads had the same exposure and no
         * protection. These helpers self-heal instead: drop the bad value,
         * return the default, start.
         */
        private fun prefBool(key: String, def: Boolean): Boolean = try {
            prefs.getBoolean(key, def)
        } catch (_: ClassCastException) {
            android.util.Log.w("WiM", "pref '$key' held a non-boolean — clearing")
            prefs.edit().remove(key).apply(); def
        }

        private fun prefInt(key: String, def: Int): Int = try {
            prefs.getInt(key, def)
        } catch (_: ClassCastException) {
            android.util.Log.w("WiM", "pref '$key' held a non-int — clearing")
            prefs.edit().remove(key).apply(); def
        }

        private fun prefStr(key: String, def: String): String = try {
            prefs.getString(key, def) ?: def
        } catch (_: ClassCastException) {
            android.util.Log.w("WiM", "pref '$key' held a non-string — clearing")
            prefs.edit().remove(key).apply(); def
        }

        /** True when this build carries its own provider keys and therefore
         *  never needs the authenticated backend for a cloud layer. */
        fun hasEmbeddedKeys(): Boolean =
            BuildConfig.EMBEDDED_OPENAI_KEY.isNotBlank()

        /**
         * True when the pipeline should expose its test hooks — the
         * WimTestReceiver control surface and the JSON result it writes back.
         *
         * Debug builds, plus giveaway (sideload) builds, which are the ones
         * the operator actually runs. Speaking produces text, and every
         * failure mode after that point is text-in/text-out, so being able to
         * inject raw text and read the result back is what makes the real
         * device testable without anyone talking into a microphone. `release`
         * stays closed: the receiver is exported.
         */
        fun testHooksEnabled(): Boolean = BuildConfig.DEBUG || hasEmbeddedKeys()

        fun tone(): String = prefStr("tone", "casual")
        /**
         * Default layer is 1 — users land on the fully-local WhisperLocalEngine
         * + Disfluency filter path on first launch. L2+ opts them into cloud
         * GPT-4o reconstruction; L4 adds Sonnet ET. Flip via the bubble menu
         * or settings.
         */
        fun layer(): Int = prefInt("layer", 1)
        /** A cached UI hint only; the backend independently enforces access. */
        fun cloudUnlocked(): Boolean {
            // The giveaway build carries its own provider keys and never uses
            // the backend — every cloud layer is open by definition, with no
            // sign-in and no subscription. Without this, the layer pickers
            // gate L2+ behind "sign in, then subscribe", which is exactly
            // what that variant exists to bypass.
            if (hasEmbeddedKeys()) return true
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
            return prefBool("cloud_unlocked", false) &&
                prefs.getString("cloud_unlock_uid", null) == uid
        }

        fun setCloudUnlocked(uid: String, unlocked: Boolean) {
            prefs.edit()
                .putBoolean("cloud_unlocked", unlocked)
                .putString("cloud_unlock_uid", uid)
                .apply()
        }

        fun clearCloudUnlock() {
            prefs.edit().remove("cloud_unlocked").remove("cloud_unlock_uid").apply()
        }
        fun situation(): String = prefStr("situation", "default")
        fun dafEnabled(): Boolean = prefBool("daf_enabled", false)
        fun dafDelay(): Int = prefInt("daf_delay", 100)
        fun bubbleOpacity(): Int = prefInt("bubble_opacity", 100)
        fun bubbleSize(): Int = prefInt("bubble_size", 95)
        fun bubbleTheme(): String = prefStr("bubble_theme", "bronze")

        /**
         * L1 ASR source toggle. FALSE = local whisper-tiny.en (default,
         * private, ~40 MB on-device). TRUE = cloud whisper-1 (paid OpenAI
         * call, no on-device model). Mirrors Lavrentiy desktop's L1 SOURCE
         * panel. Currently set programmatically — non-English locale
         * auto-flips to cloud because whisper-tiny.en is English-only;
         * a user-facing toggle is not yet wired into the bubble menu.
         */
        fun l1CloudAsr(): Boolean = prefBool("l1_cloud_asr", false)

        /**
         * Clarifier sensitivity per spec §6 Phase 4 step 28. Maps
         * "low" / "medium" / "high" → [Clarifier.UNCERTAINTY_THRESHOLD]
         * adjustments. Default = medium (0.55, the canonical threshold).
         *
         *   low    → 0.70 (only the most uncertain word ever flags)
         *   medium → 0.55 (default — under-flag bias)
         *   high   → 0.40 (catches more, occasional false positive)
         */
        fun clarifierSensitivityThreshold(): Double = when (
            prefStr("clarifier_sensitivity", "medium")
        ) {
            // Word-confidence scores from MultiPassDecoder are in 0..1, so
            // a threshold ≥ 1.0 means nothing ever crosses → Clarifier.evaluate
            // returns empty flags every call → no clarifier cycle ever fires.
            // Reverse by changing default back to "medium" or letting the
            // user pick a non-"off" sensitivity in settings.
            "off" -> 999.0
            "low" -> 0.85    // bumped from 0.70 — fires only on truly weird inputs
            "medium" -> 0.55
            "high" -> 0.40
            else -> 999.0    // unknown pref → safe default = disabled
        }

        /**
         * Per-app no-flag list per spec §6 Phase 4 step 29. Newline-
         * separated package names; the clarifier is suppressed when the
         * foreground app's package matches any entry. Use case: code
         * editors, terminals, password managers — apps where the user
         * does NOT want a "did you mean" prompt.
         */
        fun clarifierBlacklistedApps(): Set<String> {
            val raw = prefStr("clarifier_blacklist_apps", "")
            return raw.split('\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }

        fun isClarifierAllowed(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return true
            return pkg !in clarifierBlacklistedApps()
        }

        /**
         * Anticipatory prediction toggle per spec §6 Phase 5 step 35.
         * When false, [PredictionEngine] is not consulted (no chip,
         * no sentence-boundary candidates feeding the clarifier). Default
         * true — surfaces value early; user can disable in MainActivity.
         */
        fun predictionsEnabled(): Boolean = prefBool("predictions_enabled", true)

        /**
         * Tap-to-send assist lets the bubble offer to click the app's Send
         * button after a successful inject. ON by default as of 2026-07-25 —
         * it was the app's headline behaviour sitting behind a switch nobody
         * found, so the default install did not do the thing the app is for.
         *
         * Enabling it by default does NOT make anything send by itself. All it
         * does is let the bubble go green and offer. Every individual send
         * still needs a separate deliberate tap on the green bubble, and the
         * five-second window expiring sends nothing. That distinction is what
         * keeps the Play accessibility declaration accurate — the declaration
         * text was updated alongside this, since it previously described the
         * assist as something the user switches on.
         */
        fun autoSendEnabled(): Boolean = prefBool("auto_send_enabled", true)

        /**
         * Facehugger: on send-ready, the bubble relocates onto the host app's
         * Send control so the thing you tap is where you are already looking.
         * ON by default as of 2026-07-25 — same reasoning. It moves the bubble;
         * it does not send. The tap still does.
         */
        fun facehuggerSendEnabled(): Boolean = prefBool("facehugger_send", true)

        /**
         * How many times the disclaimer has been shown + confirmed. Three
         * confirmations and we stop showing it. `reset_auto_send_disclaimer`
         * in settings wipes this back to 0.
         */
        fun autoSendDisclaimerCount(): Int = prefInt("auto_send_disclaimer_count", 0)

        /**
         * Read-back: on SEND_READY, speak the reconstructed text before the
         * countdown starts (2026-08-13). The countdown is held for the whole
         * read, so the five seconds is a real window that begins after the
         * user has heard what they are about to send.
         *
         * ON by default. Reconstruction is not transcription — the user cannot
         * know it understood them without checking, and the window existed
         * with nothing in it to check with.
         */
        fun readBackEnabled(): Boolean = prefBool("read_back_enabled", true)

        /**
         * Engine voice name from [android.speech.tts.Voice.name], or blank for
         * the engine default. Stored as the raw name because the set of voices
         * is whatever that particular phone has installed — see
         * [ReadBackSpeaker.voiceOptions].
         *
         * Kept per register (2026-08-13). [ToneRouting] already decides
         * professional vs casual from the focused app and the hour, so a
         * different voice per register costs one lookup and turns the read-back
         * into a mode display: in the car, hearing WHICH voice starts talking
         * tells you which register went out before you hear a single word.
         * Wrong-register is a worse failure than a typo and this catches it in
         * half a second, without looking at the screen.
         */
        fun readBackVoiceId(tone: String = tone()): String =
            if (tone == ToneRouting.PROFESSIONAL) prefStr("read_back_voice_professional", "")
            else prefStr("read_back_voice_casual", "")

        fun readBackVoiceKey(tone: String): String =
            if (tone == ToneRouting.PROFESSIONAL) "read_back_voice_professional"
            else "read_back_voice_casual"

        /** Playback rate: 1.0, 1.25 or 1.5. Above 1.5 the voice reads as
         *  rushed and users stop trusting what they heard. */
        fun readBackSpeed(): Float = when (prefStr("read_back_speed", "1.0")) {
            "1.25" -> 1.25f
            "1.5" -> 1.5f
            else -> 1.0f
        }

        /**
         * BCP-47 tag for the language the user is actually writing in, as
         * stored by [setAppLanguage]. Read-back follows the OUTGOING message's
         * language, not the dashboard's — the interface can sit in English
         * while the user writes Spanish, and an English voice reading Spanish
         * is noise.
         */
        fun speechLocaleTag(): String = prefStr("primary_language", "en-US")

        /**
         * UI/spoken-language code: "en", "es", or "ru". Drives both the
         * Android resource locale (via AppCompatDelegate.setApplicationLocales)
         * and the OpenAI Whisper API `language` parameter. Non-English locales
         * force the cloud ASR path because whisper-tiny.en is English-only.
         */
        fun appLanguage(): String = prefStr("app_language", "en")

        fun setAppLanguage(code: String) {
            // BCP-47 mapping for the Android SpeechRecognizer fallback path.
            val bcp47 = when (code) {
                "es" -> "es-ES"
                "ru" -> "ru-RU"
                "pt" -> "pt-BR"
                "fr" -> "fr-FR"
                "ar" -> "ar-SA"
                "de" -> "de-DE"
                "hi" -> "hi-IN"
                "it" -> "it-IT"
                "ja" -> "ja-JP"
                "ko" -> "ko-KR"
                "zh" -> "zh-CN"
                else -> "en-US"
            }
            prefs.edit()
                .putString("app_language", code)
                .putString("primary_language", bcp47)
                .apply()
            val locales = androidx.core.os.LocaleListCompat.forLanguageTags(code)
            AppCompatDelegate.setApplicationLocales(locales)
        }

        /**
         * True when the active locale requires cloud Whisper (multilingual)
         * because the on-device whisper-tiny.en model can't decode it.
         */
        fun localeRequiresCloudAsr(): Boolean = appLanguage() != "en"

        fun profile(): ProfileManager = profileManager
    }

    /**
     * Uncaught-coroutine handler — mirrors the threading.excepthook added to
     * Lavrentiy desktop on 05-30. Any exception that bubbles out of an
     * [appScope] coroutine without being caught lands here. Without this,
     * SupervisorJob silently swallows the failure (parent stays alive, child
     * dies) and the user sees nothing — the same "learning silently stops"
     * failure mode Lav's audit flagged for daemon threads.
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { ctx, throwable ->
        val name = ctx[kotlinx.coroutines.CoroutineName]?.name ?: "appScope-coroutine"
        Log.e("WiM", "Uncaught in coroutine '$name': ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
    }

    /** Application-lifetime scope for background work (Moonshine model download, etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

    /**
     * One-time removal of a stored auto_tone=true.
     *
     * Auto-tone decided the register from the words of the message and
     * silently discarded whatever the user had picked. It cost two rounds of
     * debugging aimed at Layer 4 — professional and casual returned the
     * identical sentence because the classifier reclassified both — and it
     * fired the work/casual question at moments the user had not asked for.
     * The default is now off, but a device that already has `true` written to
     * prefs would never see the new default. Clear it once, keyed on a flag,
     * so a user who deliberately turns it back on afterwards keeps it.
     */
    private fun clearRetiredAutoTone() {
        try {
            val p = prefs
            if (p.getBoolean("auto_tone_default_cleared", false)) return
            p.edit()
                .remove("auto_tone")
                .putBoolean("auto_tone_default_cleared", true)
                .apply()
        } catch (t: Throwable) {
            Log.w("WiM", "auto_tone migration skipped: ${t.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Catch uncaught exceptions on plain Threads (e.g. SDK helpers that
        // spawn a non-coroutine worker — Firebase listeners, Whisper model
        // downloader's IO calls, anything that doesn't run through appScope).
        // Without this the JVM's default handler just kills the thread + logs
        // to a place pywebview-equivalent (Logcat under noise) and the rest of
        // the app degrades silently. Mirrors threading.excepthook added to
        // Lavrentiy desktop on 05-30.
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("WiM", "Uncaught in thread '${thread.name}': ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // handler itself must never raise
            }
            // Preserve the existing chain (Firebase Crashlytics, system crash UI).
            existing?.uncaughtException(thread, throwable)
        }
        // In production FirebaseInitProvider runs first; in Robolectric unit tests
        // manifest providers are skipped, so initialize explicitly. Idempotent.
        if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        prefs = getSharedPreferences("wim", MODE_PRIVATE)
        clearRetiredAutoTone()
        // One-time migration: old installs may have clarifier_sensitivity="off"
        // from when that was the default. Default is "medium" now; bring those
        // forward so users don't have a permanently-disabled clarifier.
        if (!prefBool("clarifier_sensitivity_migrated", false)) {
            if (prefStr("clarifier_sensitivity", "medium") == "off") {
                prefs.edit().putString("clarifier_sensitivity", "medium").apply()
            }
            prefs.edit().putBoolean("clarifier_sensitivity_migrated", true).apply()
        }
        profileManager = ProfileManager(prefs)
        sessionHistory = SessionHistory(this)
        // English-only build. Lang packs in assets/ are not loaded — the
        // hardcoded English defaults in DisfluencyFilter and the English
        // baseline in PhoneticRisk are the only paths that run. Re-enable
        // by calling LangPack.loadFromPrefs(this) here.
        // Auto-download the whisper-tiny.en model on first launch (~40 MB
        // compressed). Until the model lands, BubbleService's L1 path falls
        // through to Android SpeechRecognizer (the chirpy fallback). Only
        // fires when l1_cloud_asr=false (local mode); cloud users skip the
        // download entirely.
        // Moonshine was retired Apr 30 (WhisperLocalEngine is the active L1
        // local engine); MoonshineEngine.kt + MoonshineModelDownloader.kt
        // deleted 2026-05-16. This auto-download covers whisper-tiny.en only.
        if (!l1CloudAsr() && !WhisperLocalModelDownloader.isInstalled(this)) {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                android.widget.Toast.makeText(
                    this,
                    "Speech model downloading… (~30 s on Wi-Fi)",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            appScope.launch {
                android.util.Log.i("WiM", "Auto-downloading whisper-tiny.en model (~40 MB)...")
                WhisperLocalModelDownloader.downloadAndUnpack(this@WimApp) { progress ->
                    if (progress.stage == "Ready") {
                        android.util.Log.i("WiM", "whisper-tiny.en model Ready")
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                this@WimApp,
                                "Speech model ready — no more chirp",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else if (progress.stage == "Downloaded") {
                        android.util.Log.i("WiM", "whisper-tiny.en model Downloaded (unpacking…)")
                    }
                }.let { result ->
                    // The Result used to be discarded entirely, so a failed
                    // download was completely silent — the user found out by
                    // tapping the bubble and being told to try again, forever.
                    if (result is WhisperLocalModelDownloader.Result.Failed) {
                        android.util.Log.e("WiM", "whisper-tiny.en download failed: ${result.reason}")
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                this@WimApp,
                                "Speech model didn't download (${result.reason.take(50)}). " +
                                    "Layer I Cloud works meanwhile; tapping the bubble retries.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
        // Restore user's dark mode preference, default to system
        val nightMode = prefInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // Restore user's app language preference (UI + spoken language).
        // Default "en" — first-launch users get English. Picker in Settings
        // calls setAppLanguage() to switch.
        AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(appLanguage())
        )

        // Sync profile from Firestore when signed in (pulls Lavrentiy desktop learning).
        // Firebase auth-state listener fires on sign-in, sign-out, AND every
        // hourly token refresh — the previous version re-ran the full profile
        // pull + full sessions import on every fire, hammering Firestore with
        // identical reads every hour. Guard with lastSyncedUid so a token
        // refresh on the same account is a no-op. Audit finding 2026-06-06.
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                if (user.uid == lastSyncedUid) {
                    android.util.Log.d(
                        "WiM",
                        "Auth state changed for ${user.uid.take(6)}… (token refresh) — skip re-sync"
                    )
                    return@addAuthStateListener
                }
                lastSyncedUid = user.uid
                profileManager.startSync(user.uid)
                // Restore sessions from Firestore on sign-in. Reuse the
                // process-scoped SessionHistory so we don't leak SQLite fds
                // on every auth state change.
                sessionHistory.syncFromFirestore { imported ->
                    if (imported > 0) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSessionsSynced?.invoke()
                        }
                    }
                }
            } else {
                lastSyncedUid = null
                profileManager.stopSync()
            }
        }
    }

    @Volatile
    private var lastSyncedUid: String? = null
}
