package com.wim.app.onboarding

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wim.app.WimAccessibilityService

/**
 * Canonical permission status checks + launchers for the five WiM onboarding cards.
 *
 * Cards (skill: permission-flow): overlay → accessibility → mic → battery → samsung-sleep.
 * Samsung card is shown ONLY when isSamsung() is true.
 *
 * Status checks are re-run on each screen's onResume so the badge flips to "Done"
 * once the user returns from Settings. Matches the BouncerActivity polling pattern.
 */
object PermissionStatus {
    fun overlay(c: Context): Boolean =
        Settings.canDrawOverlays(c)

    fun accessibility(c: Context): Boolean {
        val enabled = Settings.Secure.getString(
            c.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any {
            it.endsWith("/${WimAccessibilityService::class.java.canonicalName}")
        }
    }

    fun microphone(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun batteryOptimization(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    fun notifications(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun isSamsung(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}

object PermissionLauncher {

    fun openOverlaySettings(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    fun openAccessibilitySettings(activity: Activity) {
        val component = ComponentName(activity, WimAccessibilityService::class.java).flattenToString()
        val deepLink = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                putString(":settings:fragment_args_key", component)
            })
        }
        runCatching { activity.startActivity(deepLink) }.recoverCatching {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    fun requestMicrophone(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_MIC
        )
    }

    fun openBatteryOptimizationSettings(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    fun openSamsungDeepSleepSettings(activity: Activity) {
        runCatching {
            activity.startActivity(Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            })
            return
        }
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        }
    }

    fun requestNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    const val REQ_MIC = 100
    const val REQ_NOTIFICATIONS = 200
}
