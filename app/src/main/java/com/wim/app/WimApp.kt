package com.wim.app

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WimApp : Application() {

    companion object {
        lateinit var instance: WimApp
            private set
        lateinit var prefs: SharedPreferences
            private set

        private lateinit var profileManager: ProfileManager

        // Session sync listener — MainActivity registers to refresh UI after Firestore sync
        var onSessionsSynced: (() -> Unit)? = null

        fun apiKey(): String = prefs.getString("api_key", "") ?: ""

        /**
         * Anthropic API key — used for the Falcon validator at L2/L3 only
         * (Claude Haiku 4.5). L4 Falcon stays on GPT-4o by current design.
         * When blank, all layers' Falcon falls back to GPT-4o.
         */
        fun anthropicApiKey(): String = prefs.getString("anthropic_api_key", "") ?: ""
        fun tone(): String = prefs.getString("tone", "casual") ?: "casual"
        /**
         * Default layer is 1 — users land on the fully-local Moonshine +
         * Disfluency filter path on first launch. L2+ opts them into
         * reconstruction (and the Gemma model download). Flip to L2+ via the
         * bubble menu or settings when ready.
         */
        fun layer(): Int = prefs.getInt("layer", 1)
        fun situation(): String = prefs.getString("situation", "default") ?: "default"
        fun dafEnabled(): Boolean = prefs.getBoolean("daf_enabled", false)
        fun dafDelay(): Int = prefs.getInt("daf_delay", 100)
        fun bubbleOpacity(): Int = prefs.getInt("bubble_opacity", 100)
        fun bubbleSize(): Int = prefs.getInt("bubble_size", 95)
        fun bubbleTheme(): String = prefs.getString("bubble_theme", "bronze") ?: "bronze"
        /**
         * Moonshine BASE-en via sherpa-onnx is the L1 ASR. No chirp, beats the
         * Android SpeechRecognizer fallback on technical vocabulary, MIT
         * licensed, ~141 MB model downloaded on first launch. Default ON —
         * the only reason to flip it off is if a user is on a metered network
         * and wants to defer the model download, in which case the L1 path
         * falls back to Android SpeechRecognizer (Google's TTS recognizer
         * service on Samsung, Google's own on Pixel) until Moonshine is ready.
         */
        fun useMoonshine(): Boolean = prefs.getBoolean("use_moonshine", true)

        /**
         * Gemma local reconstruction for L2/L3. When the model is installed,
         * reconstruction runs on-device via MediaPipe LLM Inference instead
         * of cloud GPT-4o. L4 stays cloud regardless. Default ON — if the
         * model isn't installed yet, the pipeline falls back to cloud.
         */
        fun useGemmaLocal(): Boolean = prefs.getBoolean("use_gemma_local", true)

        /**
         * Auto-send lets the bubble click the app's Send button after a
         * successful inject. Off by default — the user opts in explicitly
         * in settings, and still has to tap the green bubble once to fire
         * each individual send. Per-app whitelist lives in
         * WimAccessibilityService.SUPPORTED_AUTOSEND_APPS.
         */
        fun autoSendEnabled(): Boolean = prefs.getBoolean("auto_send_enabled", false)

        /**
         * How many times the disclaimer has been shown + confirmed. Three
         * confirmations and we stop showing it. `reset_auto_send_disclaimer`
         * in settings wipes this back to 0.
         */
        fun autoSendDisclaimerCount(): Int = prefs.getInt("auto_send_disclaimer_count", 0)

        fun profile(): ProfileManager = profileManager
    }

    /** Application-lifetime scope for background work (Moonshine model download, etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // In production FirebaseInitProvider runs first; in Robolectric unit tests
        // manifest providers are skipped, so initialize explicitly. Idempotent.
        if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        prefs = getSharedPreferences("wim", MODE_PRIVATE)
        profileManager = ProfileManager(prefs)
        // English-only build. Lang packs in assets/ are not loaded — the
        // hardcoded English defaults in DisfluencyFilter and the English
        // baseline in PhoneticRisk are the only paths that run. Re-enable
        // by calling LangPack.loadFromPrefs(this) here.
        // Auto-download the Moonshine BASE-en model on first launch (~141 MB
        // compressed → ~141 MB on disk). Until the model lands, BubbleService's
        // wantMoonshine check returns false and the L1 path falls through to
        // Android SpeechRecognizer (the chirpy fallback).
        if (useMoonshine() && !MoonshineModelDownloader.isInstalled(this)) {
            appScope.launch {
                android.util.Log.i("WiM", "Auto-downloading Moonshine BASE model (~141 MB)...")
                MoonshineModelDownloader.downloadAndUnpack(this@WimApp) { progress ->
                    if (progress.stage == "Ready" || progress.stage == "Downloaded") {
                        android.util.Log.i("WiM", "Moonshine model ${progress.stage}")
                    }
                }
            }
        }
        // Gemma local reconstruction was retired April 24, 2026 — L2/L3 go
        // straight to cloud GPT-4o now. GemmaEngine + downloader files remain
        // dormant in case we ever revive a local-LLM path; nothing references
        // them at runtime.
        // Restore user's dark mode preference, default to system
        val nightMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // Sync profile from Firestore when signed in (pulls Lavrentiy desktop learning)
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                profileManager.startSync(user.uid)
                // Restore sessions from Firestore on sign-in
                SessionHistory(this).syncFromFirestore { imported ->
                    if (imported > 0) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSessionsSynced?.invoke()
                        }
                    }
                }
            } else {
                profileManager.stopSync()
            }
        }
    }
}
