package com.wim.app

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.FirebaseAuth

class WimApp : Application() {

    companion object {
        lateinit var prefs: SharedPreferences
            private set

        private lateinit var profileManager: ProfileManager

        fun apiKey(): String = prefs.getString("api_key", "") ?: ""
        fun tone(): String = prefs.getString("tone", "casual") ?: "casual"
        fun layer(): Int = prefs.getInt("layer", 2)
        fun situation(): String = prefs.getString("situation", "default") ?: "default"
        fun dafEnabled(): Boolean = prefs.getBoolean("daf_enabled", false)
        fun dafDelay(): Int = prefs.getInt("daf_delay", 100)
        fun bubbleOpacity(): Int = prefs.getInt("bubble_opacity", 70)
        fun bubbleSize(): Int = prefs.getInt("bubble_size", 110)

        fun profile(): ProfileManager = profileManager
    }

    override fun onCreate() {
        super.onCreate()
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
            } else {
                profileManager.stopSync()
            }
        }
    }
}
