package com.wim.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * One-tap fix for a revoked "Display over other apps" grant. Opened from the
 * alert notification (a notification tap may start an Activity on every
 * Android version; a Service may not). Sends the user to the system page for
 * WiM; when they come back with the grant restored, the bubble is started and
 * this invisible screen closes. 2026-09-04.
 */
class OverlayFixActivity : Activity() {
    private var sentToSettings = false
    private var wentAway = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            restartBubbleAndFinish("already granted")
            return
        }
        val page = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(page)
            sentToSettings = true
        } catch (e: Exception) {
            Log.w("WiM", "overlay page for package unavailable (${e.javaClass.simpleName}); trying the list")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                sentToSettings = true
            } catch (e2: Exception) {
                Log.e("WiM", "overlay settings unavailable: ${e2.javaClass.simpleName}")
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (sentToSettings) wentAway = true
    }

    override fun onResume() {
        super.onResume()
        if (!wentAway) return
        if (Settings.canDrawOverlays(this)) restartBubbleAndFinish("granted in settings")
        else finish() // came back without granting; the notification stays up
    }

    private fun restartBubbleAndFinish(why: String) {
        try {
            startForegroundService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
            BubbleDeathReporter.recovered(this, "overlay fix: $why")
        } catch (e: Exception) {
            Log.e("WiM", "overlay fix: bubble start failed: ${e.javaClass.simpleName}")
        }
        finish()
    }
}
