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
            internal set

        private lateinit var profileManager: ProfileManager

        // Session sync listener — MainActivity registers to refresh UI after Firestore sync
        var onSessionsSynced: (() -> Unit)? = null

        // Lazy-cached bundled keys (read once from APK assets on first use).
        // Built-in defaults so a fresh download works without user setup; the
        // user can still override either via Settings (the prefs path wins).
        private var _bundledOpenAiKey: String? = null
        private var _bundledAnthropicKey: String? = null

        private fun bundledKey(filename: String): String {
            if (!BuildConfig.DEBUG) return ""
            return try {
                instance.assets.open(filename).bufferedReader().use { it.readText().trim() }
            } catch (e: Exception) {
                ""
            }
        }

        fun apiKey(): String {
            val user = prefs.getString("api_key", "") ?: ""
            if (user.isNotBlank()) return user
            if (_bundledOpenAiKey == null) _bundledOpenAiKey = bundledKey("api_key.txt")
            return _bundledOpenAiKey ?: ""
        }

        /**
         * Anthropic API key — used for the Falcon validator at L2/L3 only
         * (Claude Haiku 4.5). L4 Falcon stays on GPT-4o by current design.
         * When blank, all layers' Falcon falls back to GPT-4o.
         */
        fun anthropicApiKey(): String {
            val user = prefs.getString("anthropic_api_key", "") ?: ""
            if (user.isNotBlank()) return user
            if (_bundledAnthropicKey == null) _bundledAnthropicKey = bundledKey("anthropic_key.txt")
            return _bundledAnthropicKey ?: ""
        }
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
         * L1 ASR source toggle. FALSE = local whisper-tiny.en (default,
         * private, ~40 MB on-device). TRUE = cloud whisper-1 (paid OpenAI
         * call, no on-device model). Mirrors Lavrentiy desktop's L1 SOURCE
         * panel. Set via the bubble-menu L1 SRC switch.
         */
        fun l1CloudAsr(): Boolean = prefs.getBoolean("l1_cloud_asr", false)

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
            prefs.getString("clarifier_sensitivity", "medium") ?: "medium"
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
            val raw = prefs.getString("clarifier_blacklist_apps", "") ?: ""
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
        fun predictionsEnabled(): Boolean = prefs.getBoolean("predictions_enabled", true)

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
        fun autoSendEnabled(): Boolean = prefs.getBoolean("auto_send_enabled", true)

        fun facehuggerSendEnabled(): Boolean = prefs.getBoolean("facehugger_send", false)

        /**
         * How many times the disclaimer has been shown + confirmed. Three
         * confirmations and we stop showing it. `reset_auto_send_disclaimer`
         * in settings wipes this back to 0.
         */
        fun autoSendDisclaimerCount(): Int = prefs.getInt("auto_send_disclaimer_count", 0)

        /**
         * UI/spoken-language code: "en", "es", or "ru". Drives both the
         * Android resource locale (via AppCompatDelegate.setApplicationLocales)
         * and the OpenAI Whisper API `language` parameter. Non-English locales
         * force the cloud ASR path because whisper-tiny.en is English-only.
         */
        fun appLanguage(): String = prefs.getString("app_language", "en") ?: "en"

        fun setAppLanguage(code: String) {
            // BCP-47 mapping for the Android SpeechRecognizer fallback path.
            val bcp47 = when (code) {
                "es" -> "es-ES"
                "ru" -> "ru-RU"
                "pt" -> "pt-BR"
                "fr" -> "fr-FR"
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
        // Auto-download the whisper-tiny.en model on first launch (~40 MB
        // compressed). Until the model lands, BubbleService's L1 path falls
        // through to Android SpeechRecognizer (the chirpy fallback). Only
        // fires when l1_cloud_asr=false (local mode); cloud users skip the
        // download entirely.
        // Moonshine's prior auto-download was retired here; MoonshineEngine.kt
        // and MoonshineModelDownloader.kt remain on disk pending Whisper-tiny
        // verification on real voice (delete in a follow-up commit).
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

        // Restore user's app language preference (UI + spoken language).
        // Default "en" — first-launch users get English. Picker in Settings
        // calls setAppLanguage() to switch.
        AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(appLanguage())
        )

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
