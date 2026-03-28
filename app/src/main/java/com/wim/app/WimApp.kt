package com.wim.app

import android.app.Application
import android.content.SharedPreferences

class WimApp : Application() {

    companion object {
        lateinit var prefs: SharedPreferences
            private set

        fun apiKey(): String = prefs.getString("api_key", "") ?: ""
        fun tone(): String = prefs.getString("tone", "casual") ?: "casual"
        fun layer(): Int = prefs.getInt("layer", 2)
        fun mode(): String = prefs.getString("mode", "safe") ?: "safe"
        fun situation(): String = prefs.getString("situation", "default") ?: "default"
        fun paralinguistic(): Boolean = prefs.getBoolean("paralinguistic", false)
        fun prosodic(): Boolean = prefs.getBoolean("prosodic", false)
        fun dafEnabled(): Boolean = prefs.getBoolean("daf_enabled", false)
        fun dafDelay(): Int = prefs.getInt("daf_delay", 100)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("wim", MODE_PRIVATE)
    }
}
