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

        /** Developer-supplied direct key. Release builds are backend-only. */
        fun apiKey(): String {
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
            if (!BuildConfig.DEBUG) return ""
            return prefs.getString("anthropic_api_key", "") ?: ""
        }
        fun tone(): String = prefs.getString("tone", "casual") ?: "casual"
        /**
         * Default layer is 1 — users land on the fully-local WhisperLocalEngine
         * + Disfluency filter path on first launch. L2+ opts them into cloud
         * GPT-4o reconstruction; L4 adds Sonnet ET. Flip via the bubble menu
         * or settings.
         */
        fun layer(): Int = prefs.getInt("layer", 1)
        /** A cached UI hint only; the backend independently enforces access. */
        fun cloudUnlocked(): Boolean {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
            return prefs.getBoolean("cloud_unlocked", false) &&
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
        fun situation(): String = prefs.getString("situation", "default") ?: "default"
        fun dafEnabled(): Boolean = prefs.getBoolean("daf_enabled", false)
        fun dafDelay(): Int = prefs.getInt("daf_delay", 100)
        fun bubbleOpacity(): Int = prefs.getInt("bubble_opacity", 100)
        fun bubbleSize(): Int = prefs.getInt("bubble_size", 95)
        fun bubbleTheme(): String = prefs.getString("bubble_theme", "bronze") ?: "bronze"

        /**
         * L1 ASR source toggle. FALSE = local whisper-tiny.en (default,
         * private, ~40 MB on-device). TRUE = cloud whisper-1 (paid OpenAI
         * call, no on-device model). Mirrors Lavrentiy desktop's L1 SOURCE
         * panel. Currently set programmatically — non-English locale
         * auto-flips to cloud because whisper-tiny.en is English-only;
         * a user-facing toggle is not yet wired into the bubble menu.
         */
        fun l1CloudAsr(): Boolean = try {
            prefs.getBoolean("l1_cloud_asr", false)
        } catch (_: ClassCastException) {
            // Self-heal if a test broadcast stored the wrong type for this key
            // (set_pref defaults to type="string", so `--es value "false"`
            // without `--es type bool` writes a String here and the next read
            // crashes WimApp.onCreate). Clear the bad value, return the safe
            // default so the app starts instead of dying on launch.
            prefs.edit().remove("l1_cloud_asr").apply()
            false
        }

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
        fun autoSendEnabled(): Boolean = prefs.getBoolean("auto_send_enabled", true)

        /**
         * Facehugger: on send-ready, the bubble relocates onto the host app's
         * Send control so the thing you tap is where you are already looking.
         * ON by default as of 2026-07-25 — same reasoning. It moves the bubble;
         * it does not send. The tap still does.
         */
        fun facehuggerSendEnabled(): Boolean = prefs.getBoolean("facehugger_send", true)

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
        // One-time migration: old installs may have clarifier_sensitivity="off"
        // from when that was the default. Default is "medium" now; bring those
        // forward so users don't have a permanently-disabled clarifier.
        if (!prefs.getBoolean("clarifier_sensitivity_migrated", false)) {
            if (prefs.getString("clarifier_sensitivity", "medium") == "off") {
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
                }
            }
        }
        // Restore user's dark mode preference, default to system
        val nightMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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
