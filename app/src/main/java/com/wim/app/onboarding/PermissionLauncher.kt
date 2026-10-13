package com.wim.app.onboarding

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wim.app.WimAccessibilityService
import java.lang.ref.WeakReference
import java.util.WeakHashMap

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

    /** Samsung's sleep lists cannot be read. The onboarding remembers that the
     *  user opened Samsung's battery page from the Samsung card and lights that
     *  row from this flag (George 10/12). */
    fun samsungSleepVisited(): Boolean =
        com.wim.app.WimApp.prefs.getBoolean("samsung_sleep_visited", false)

    fun markSamsungSleepVisited() {
        com.wim.app.WimApp.prefs.edit().putBoolean("samsung_sleep_visited", true).apply()
    }
}

object PermissionLauncher {

    private const val TAG = "WiM.Onboarding"
    private const val RETURN_POLL_MS = 500L
    private const val RETURN_POLL_ATTEMPTS = 120
    private val mainHandler = Handler(Looper.getMainLooper())
    private val returnPolls = WeakHashMap<Activity, Runnable>()

    fun openOverlaySettings(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }.onSuccess {
            returnToOnboardingWhenGranted(activity) { PermissionStatus.overlay(it) }
        }.onFailure { Log.w(TAG, "Unable to open overlay settings", it) }
    }

    fun openAccessibilitySettings(activity: Activity) {
        val component = ComponentName(activity, WimAccessibilityService::class.java).flattenToString()
        // Straight to WiM's own toggle page. ACCESSIBILITY_DETAILS_SETTINGS is
        // how the system's own UI opens a single service's page; not in the
        // public Settings constants but honored on Android 12+ including One UI.
        val detailsPage = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        }
        val deepLink = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                putString(":settings:fragment_args_key", component)
            })
        }
        runCatching { activity.startActivity(detailsPage) }.recoverCatching {
            activity.startActivity(deepLink)
        }.recoverCatching {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onSuccess {
            // Samsung may ignore the best-effort deep-link extras and open the
            // accessibility root. Whichever Settings page it chooses, return
            // the user to WiM as soon as the service is actually enabled.
            returnToOnboardingWhenGranted(activity) { PermissionStatus.accessibility(it) }
        }.onFailure { Log.w(TAG, "Unable to open accessibility settings", it) }
    }

    fun requestMicrophone(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_MIC
        )
    }

    /**
     * Battery-optimization exemption, by the cheapest route the build is
     * allowed to take.
     *
     * The one-tap ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog needs the
     * matching permission, which Google Play reviews case by case and which is
     * a recurring rejection trigger for overlay apps. It is therefore declared
     * only in `src/giveaway` (sideload), never in `src/main`, so the Play
     * variant silently falls through to ACTION_IGNORE_BATTERY_OPTIMIZATION_
     * SETTINGS — same setting, no permission, but the user has to find WiM in
     * a list of every installed app.
     *
     * `PowerManager.isIgnoringBatteryOptimizations` reports the result either
     * way, so grant detection and the auto-return are identical on both paths.
     */
    fun openBatteryOptimizationSettings(activity: Activity) {
        // One-tap system dialog when the build is allowed to ask for it
        // (sideload/giveaway variants declare the permission). Otherwise the
        // permission-free list intent, which reaches the same setting but
        // makes the user find WiM among every installed app.
        val canPrompt = activity.packageManager.checkPermission(
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            activity.packageName,
        ) == PackageManager.PERMISSION_GRANTED
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching {
            if (canPrompt) activity.startActivity(direct) else error("no permission")
        }.recoverCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onSuccess {
            returnToOnboardingWhenGranted(activity) { PermissionStatus.batteryOptimization(it) }
        }.onFailure { Log.w(TAG, "Unable to open battery settings", it) }
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

    /** App-details page — fallback when a runtime-permission dialog is exhausted
     *  ("don't ask again") and the toggle only lives in Settings. */
    fun openAppDetails(activity: Activity) {
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

    /**
     * Settings-backed grants do not provide a reliable activity result. Poll
     * the real permission state, then reorder the existing onboarding Activity
     * to the front so users do not have to unwind Settings' nested back stack.
     *
     * The Activity is weakly referenced and the poll expires after one minute,
     * so leaving onboarding cannot retain it indefinitely.
     */
    private fun returnToOnboardingWhenGranted(
        activity: Activity,
        check: (Activity) -> Boolean
    ) {
        returnPolls.remove(activity)?.let(mainHandler::removeCallbacks)

        val activityRef = WeakReference(activity)
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                val host = activityRef.get() ?: return
                attempts++
                if (check(host)) {
                    returnPolls.remove(host)
                    val back = Intent(host, OnboardingActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                    runCatching { host.startActivity(back) }
                        .onFailure { Log.w(TAG, "Unable to return to onboarding", it) }
                    return
                }
                if (attempts < RETURN_POLL_ATTEMPTS) {
                    mainHandler.postDelayed(this, RETURN_POLL_MS)
                } else {
                    returnPolls.remove(host)
                }
            }
        }
        returnPolls[activity] = poll
        mainHandler.postDelayed(poll, RETURN_POLL_MS)
    }

    const val REQ_MIC = 100
    const val REQ_NOTIFICATIONS = 200
}
