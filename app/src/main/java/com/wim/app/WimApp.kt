package com.wim.app

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

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
        fun layer(): Int = prefs.getInt("layer", 2)
        fun situation(): String = prefs.getString("situation", "default") ?: "default"
        fun dafEnabled(): Boolean = prefs.getBoolean("daf_enabled", false)
        fun dafDelay(): Int = prefs.getInt("daf_delay", 100)
        fun bubbleOpacity(): Int = prefs.getInt("bubble_opacity", 100)
        fun bubbleSize(): Int = prefs.getInt("bubble_size", 95)
        fun useLocalWhisper(): Boolean = prefs.getBoolean("use_local_whisper", false)

        fun profile(): ProfileManager = profileManager
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // In production FirebaseInitProvider runs first; in Robolectric unit tests
        // manifest providers are skipped, so initialize explicitly. Idempotent.
        if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        prefs = getSharedPreferences("wim", MODE_PRIVATE)
        profileManager = ProfileManager(prefs)
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
