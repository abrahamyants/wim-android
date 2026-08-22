package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Bundle of notification + permission + alert UI surfaces — the parts of
 * BubbleService that talk to the system shade and to the user about
 * missing permissions, but never touch the bubble's recording / pipeline
 * paths.
 *
 * Extracted from BubbleService 2026-05-25 as the sixteenth slice. The
 * service still owns lifecycle (startForeground, the actual channel IDs),
 * but the channel construction, notification builder, and missing-
 * permission orchestration live here.
 *
 * Takes a Service rather than just a Context because some calls
 * (PendingIntent.getService, package-name resolution) need Service-flavor
 * APIs.
 */
class BubbleStatusUI(
    private val service: Service,
    private val getBubbleView: () -> View?,
) {
    private companion object {
        private const val TAG = "WiM"
    }

    fun createChannels(channelId: String, alertChannelId: String) {
        val nm = service.getSystemService(NotificationManager::class.java)
        // Routine status — required for the foreground service to exist,
        // but suppressed at the launcher (no badge, no sound, low importance).
        val statusChannel = NotificationChannel(
            channelId,
            "WiM Voice Dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the voice dictation bubble active"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        // Permission/error alerts — badge ON, higher importance so the user
        // notices when accessibility / mic / overlay permission is missing.
        val alertChannel = NotificationChannel(
            alertChannelId,
            "WiM Permission Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shown when WiM needs a permission to function"
            setShowBadge(true)
        }
        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(alertChannel)
    }

    fun buildForegroundNotification(channelId: String, text: String = "Voice dictation active"): Notification {
        val showIntent = Intent(service, BubbleService::class.java).apply { action = "SHOW" }
        val pendingIntent = PendingIntent.getService(
            service, 0, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Notification-shade quick action — opens the bubble menu without
        // having to interact with the bubble itself. Conflict-free with
        // every gesture on the bubble.
        val menuIntent = Intent(service, BubbleService::class.java).apply { action = "OPEN_MENU" }
        val menuPending = PendingIntent.getService(
            service, 1, menuIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // addAction(int, CharSequence, PendingIntent) was deprecated in API 23
        // in favor of the Action.Builder form. Same payload, modern signature.
        val menuAction = Notification.Action.Builder(
            Icon.createWithResource(service, android.R.drawable.ic_menu_preferences),
            "MENU",
            menuPending,
        ).build()
        val stopIntent = Intent(service, BubbleService::class.java).apply { action = "STOP" }
        val stopPending = PendingIntent.getService(
            service, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(service, android.R.drawable.ic_menu_close_clear_cancel),
            "STOP WiM",
            stopPending,
        ).build()
        return Notification.Builder(service, channelId)
            .setContentTitle("What I Meant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setBadgeIconType(Notification.BADGE_ICON_NONE)
            .setNumber(0)
            .addAction(menuAction)
            .addAction(stopAction)
            .build()
    }

    fun updateForegroundNotification(channelId: String, notificationId: Int, text: String) {
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.notify(notificationId, buildForegroundNotification(channelId, text))
    }

    /**
     * Permission-broken alert. Routes through the high-importance alert
     * channel so the launcher icon shows a real badge — the user sees a
     * signal that's distinct from the routine "service is running" state.
     * Cleared by [clearPermissionAlert] once permissions are restored.
     */
    fun showPermissionAlert(alertChannelId: String, alertNotificationId: Int, missingLabel: String) {
        val openSettings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            service, 100, openSettings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(service, alertChannelId)
            .setContentTitle("WiM needs a permission")
            .setContentText("Missing $missingLabel — tap to fix")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setNumber(1)
            .build()
        service.getSystemService(NotificationManager::class.java)
            .notify(alertNotificationId, notif)
    }

    fun clearPermissionAlert(alertNotificationId: Int) {
        service.getSystemService(NotificationManager::class.java).cancel(alertNotificationId)
    }

    /** True if WiM's accessibility service is enabled. Reinstalling the APK
     *  silently revokes accessibility on Samsung — without this check the
     *  bubble appears normally but inject and tap-to-send fail invisibly. */
    // Live truth, not the settings string — see WimApp.accessibilityLive.
    fun isAccessibilityEnabled(): Boolean = WimApp.accessibilityLive(service)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(service, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Surfaces missing-permission state so the user is never confused
     * about why the bubble "looks fine but does nothing." Toast + label
     * badge on the bubble + notification text update. Caller must supply
     * the alert channel + notification IDs since those are owned by the
     * service.
     */
    fun alertOnMissingPermissions(alertChannelId: String, alertNotificationId: Int) {
        val missing = mutableListOf<String>()
        if (!isAccessibilityEnabled()) missing += "Accessibility"
        if (!hasMicPermission()) missing += "Mic"
        if (missing.isEmpty()) {
            // All permissions granted — clear any stale alert so the launcher
            // badge goes away the moment the user fixes the permission.
            clearPermissionAlert(alertNotificationId)
            return
        }

        val label = missing.joinToString(" + ")
        val msg = "WiM needs $label permission — settings → accessibility → What I Meant"
        Log.w(TAG, "Permissions missing: $label")
        try {
            Toast.makeText(service, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
        val view = getBubbleView()
        view?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "FIX"
            setTextColor(0xFFE05050.toInt())
        }
        view?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            text = "PERM"
            setTextColor(0xFFE05050.toInt())
        }
        showPermissionAlert(alertChannelId, alertNotificationId, label)
    }
}
