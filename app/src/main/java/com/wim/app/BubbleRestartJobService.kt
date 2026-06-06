package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.util.Log

class BubbleRestartJobService : JobService() {
    companion object {
        const val JOB_ID = 1001
        private const val RECOVERY_NOTIF_ID = 2001
        private const val RECOVERY_CHANNEL_ID = "wim_bubble_alert"
    }

    override fun onStartJob(params: JobParameters): Boolean {
        try {
            startForegroundService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
            Log.i("WiM", "BubbleRestartJobService: startForegroundService dispatched")
        } catch (e: Exception) {
            Log.e("WiM", "BubbleRestartJobService: startForegroundService failed: ${e.javaClass.simpleName}")
            // Last-line escape hatch — if even the deferred JobScheduler FGS
            // start is rejected, post the same user-visible recovery
            // notification BootReceiver uses. Audit finding 2026-06-06.
            postRecoveryNotification()
        }
        return false
    }
    override fun onStopJob(params: JobParameters) = false

    private fun postRecoveryNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "WiM Bubble Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Surfaces when the bubble can't auto-restart after reboot"
                }
            )
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = Notification.Builder(this, RECOVERY_CHANNEL_ID)
                .setContentTitle("WiM is sleeping after reboot")
                .setContentText("Tap to wake the bubble back up.")
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(RECOVERY_NOTIF_ID, notif)
            Log.i("WiM", "JobService recovery: posted user-visible recovery notification")
        } catch (e: Exception) {
            Log.e("WiM", "JobService recovery: failed to post notification: ${e.message}")
        }
    }
}
