package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Restarts the bubble service after device reboot.
 * Only starts if overlay + accessibility permissions are still granted.
 *
 * Three-tier recovery (audit 2026-06-06): the immediate FGS start can
 * fail under Android 12+ background-FGS restrictions, OEM battery
 * savers, or aggressive Doze. Fallback ladder is:
 *
 *   1. Immediate `startForegroundService` — works on most setups.
 *   2. JobScheduler restart at +30 s — survives Android 12+ FGS gate
 *      and most OEM throttles.
 *   3. User-visible notification — survives even when JobScheduler is
 *      throttled / deferred indefinitely. Tap brings up MainActivity
 *      which starts the bubble normally. Without (3), a "silently
 *      dead after reboot" user has no clue why the bubble's gone.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val RECOVERY_NOTIF_ID = 2001
        private const val RECOVERY_CHANNEL_ID = "wim_bubble_alert"
    }

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
                // Tier 2: JobScheduler retry at +30 s.
                try {
                    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                    val jobInfo = JobInfo.Builder(BubbleRestartJobService.JOB_ID, ComponentName(context, BubbleRestartJobService::class.java))
                        .setMinimumLatency(30_000L)
                        .setRequiresCharging(false)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                        .build()
                    jobScheduler.schedule(jobInfo)
                    Log.i("WiM", "Boot recovery: JobScheduler restart queued for +30 s")
                } catch (je: Exception) {
                    Log.e("WiM", "Boot recovery: JobScheduler queue also failed: ${je.message}")
                }
                // Tier 3: user-visible escape hatch in case both tiers above
                // get throttled/rejected. Tap → MainActivity → bubble starts.
                postRecoveryNotification(context)
            }
        } else {
            Log.i("WiM", "Boot: skipping bubble start — permissions not granted (overlay=$overlayOk, accessibility=$accessibilityOk, mic=$micOk)")
        }
    }

    private fun postRecoveryNotification(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // Idempotent — Android no-ops if channel already exists.
            nm.createNotificationChannel(
                NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "WiM Bubble Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Surfaces when the bubble can't auto-restart after reboot"
                }
            )
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(
                context, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = Notification.Builder(context, RECOVERY_CHANNEL_ID)
                .setContentTitle("WiM is sleeping after reboot")
                .setContentText("Tap to wake the bubble back up.")
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm.notify(RECOVERY_NOTIF_ID, notif)
            Log.i("WiM", "Boot recovery: posted user-visible recovery notification")
        } catch (e: Exception) {
            Log.e("WiM", "Boot recovery: failed to post notification: ${e.message}")
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
