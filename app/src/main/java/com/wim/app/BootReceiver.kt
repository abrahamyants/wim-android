package com.wim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Restarts the bubble service after device reboot.
 * Only starts if overlay + accessibility permissions are still granted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val overlayOk = Settings.canDrawOverlays(context)
        val accessibilityOk = isAccessibilityEnabled(context)
        // Mic permission isn't blocking — the bubble can show and prompt
        // on first record — but we log it so post-boot bug reports
        // surface whether the FGS came up in specialUse-only mode.
        val micOk = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (overlayOk && accessibilityOk) {
            try {
                val serviceIntent = Intent(context, BubbleService::class.java).apply {
                    action = "SHOW"
                }
                context.startForegroundService(serviceIntent)
                Log.i("WiM", "Bubble restarted after boot (mic=$micOk)")
            } catch (e: Exception) {
                Log.e("WiM", "Failed to restart bubble after boot: ${e.message}")
            }
        } else {
            Log.i("WiM", "Boot: skipping bubble start — permissions not granted (overlay=$overlayOk, accessibility=$accessibilityOk, mic=$micOk)")
        }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("${context.packageName}/${WimAccessibilityService::class.java.canonicalName}")
    }
}
