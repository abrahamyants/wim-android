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
        fun tone(): String = prefs.getString("tone", "casual") ?: "casual"
        /**
         * Default layer is 1 — users land on the fully-local Vosk + Disfluency
         * filter path on first launch. L2+ opts them into reconstruction (and
         * the Gemma model download). Flip to L2+ via the bubble menu or
         * settings when ready.
         */
        fun layer(): Int = prefs.getInt("layer", 1)
        fun situation(): String = prefs.getString("situation", "default") ?: "default"
        fun dafEnabled(): Boolean = prefs.getBoolean("daf_enabled", false)
        fun dafDelay(): Int = prefs.getInt("daf_delay", 100)
        fun bubbleOpacity(): Int = prefs.getInt("bubble_opacity", 100)
        fun bubbleSize(): Int = prefs.getInt("bubble_size", 95)
        fun bubbleTheme(): String = prefs.getString("bubble_theme", "bronze") ?: "bronze"
        /**
         * Vosk is the default ASR path — no chirp, preserves speech
         * disfluencies, command-line nothing. Falls back to Samsung /
         * Google native ASR only when the Vosk model isn't installed yet
         * (VoskEngine.isReady() returns false). Toggle can be flipped off
         * by the user for emergency rollback; default is ON.
         */
        fun useVosk(): Boolean = prefs.getBoolean("use_vosk", true)

        /**
         * Gemma local reconstruction for L2/L3. When the model is installed,
         * reconstruction runs on-device via MediaPipe LLM Inference instead
         * of cloud GPT-4o. L4 stays cloud regardless. Default ON — if the
         * model isn't installed yet, the pipeline falls back to cloud.
         */
        fun useGemmaLocal(): Boolean = prefs.getBoolean("use_gemma_local", true)

        fun profile(): ProfileManager = profileManager
    }

    /** Application-lifetime scope for background work (Vosk model download, etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // In production FirebaseInitProvider runs first; in Robolectric unit tests
        // manifest providers are skipped, so initialize explicitly. Idempotent.
        if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        prefs = getSharedPreferences("wim", MODE_PRIVATE)
        profileManager = ProfileManager(prefs)
        // Load per-language disfluency pack (fillers + natural_repeats) for
        // the user's primary_language. English → empty pack, filter uses
        // hardcoded defaults. Reload after any language change.
        LangPack.loadFromPrefs(this)
        // If the user is on the default Vosk path and the model isn't
        // installed yet, kick the download off in the background so new
        // users aren't quietly stuck on the Samsung fallback.
        if (useVosk() && !VoskModelDownloader.isInstalled(this)) {
            appScope.launch {
                android.util.Log.i("WiM", "Auto-downloading Vosk model (default path)...")
                VoskModelDownloader.downloadAndUnpack(this@WimApp) { progress ->
                    if (progress.stage == "Ready" || progress.stage == "Downloaded") {
                        android.util.Log.i("WiM", "Vosk model ${progress.stage}")
                    }
                }
            }
        }
        // Same pattern for Gemma local reconstruction. ~550 MB model — larger
        // download, runs in background, pipeline falls back to cloud GPT-4o
        // until the model is ready. Gated on layer >= 2 so L1-only users
        // don't pull 550 MB they'll never use.
        if (useGemmaLocal() && layer() >= 2 && !GemmaModelDownloader.isInstalled(this)) {
            appScope.launch {
                android.util.Log.i("WiM", "Auto-downloading Gemma model (default L2/L3 path)...")
                GemmaModelDownloader.downloadAndUnpack(this@WimApp) { progress ->
                    if (progress.stage == "Ready" || progress.stage == "Downloaded") {
                        android.util.Log.i("WiM", "Gemma model ${progress.stage}")
                    }
                }
            }
        }
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
