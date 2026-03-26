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
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("wim", MODE_PRIVATE)
    }
}
