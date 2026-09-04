package com.wim.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Why did the bubble's process die last time? Android keeps the answer
 * (ApplicationExitInfo, API 30+). Read once per process start, log it, count
 * it, send one analytics event — so phones we will never hold tell us which
 * brand kills the bubble and how. 2026-09-04.
 *
 * Counters live in the "wim" prefs: exit_count_<reason>, exit_last_reason,
 * exit_last_desc, exit_last_ts. `adb shell logcat -s WiM | grep "process exit"`
 * shows the same line on a phone in hand.
 */
object BubbleDeathReporter {
    private const val TAG = "WiM"
    private const val PREF_LAST_TS = "exit_last_reported_ts"
    @Volatile private var reportedThisProcess = false

    fun reportIfNew(context: Context) {
        if (reportedThisProcess) return
        reportedThisProcess = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences("wim", Context.MODE_PRIVATE)
            val lastTs = prefs.getLong(PREF_LAST_TS, 0L)
            val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.timestamp > lastTs }
            if (fresh.isEmpty()) return
            val newestTs = fresh.maxOf { it.timestamp }
            var first = true
            for (e in fresh) {
                val reason = reasonName(e.reason)
                val desc = (e.description ?: "").take(120)
                Log.w(TAG, "process exit: reason=$reason sub=${e.subReason} importance=${e.importance} " +
                    "pss=${e.pss}KB desc=\"$desc\" at=${e.timestamp}")
                val key = "exit_count_$reason"
                prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
                if (first) {
                    first = false
                    prefs.edit()
                        .putString("exit_last_reason", reason)
                        .putString("exit_last_desc", desc)
                        .putLong("exit_last_ts", e.timestamp)
                        .apply()
                    event(context, "bubble_process_exit", Bundle().apply {
                        putString("reason", reason)
                        putInt("sub_reason", e.subReason)
                        putInt("importance", e.importance)
                        putString("description", desc.take(100))
                        putLong("pss_kb", e.pss)
                    })
                }
            }
            prefs.edit().putLong(PREF_LAST_TS, newestTs).apply()
        } catch (e: Exception) {
            Log.v(TAG, "exit reasons unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** The bubble was brought back, and how. */
    fun recovered(context: Context, how: String) {
        Log.i(TAG, "bubble recovered: $how")
        event(context, "bubble_recovered", Bundle().apply { putString("how", how.take(40)) })
    }

    /** The bubble was lost and could not come back on its own (needs the user). */
    fun lost(context: Context, why: String) {
        Log.w(TAG, "bubble lost: $why")
        event(context, "bubble_lost", Bundle().apply { putString("why", why.take(40)) })
    }

    private fun event(context: Context, name: String, params: Bundle) {
        try {
            params.putString("brand", Build.MANUFACTURER ?: "")
            params.putString("model", Build.MODEL ?: "")
            params.putInt("sdk", Build.VERSION.SDK_INT)
            FirebaseAnalytics.getInstance(context).logEvent(name, params)
        } catch (e: Exception) {
            Log.v(TAG, "analytics unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        else -> "reason_$reason"
    }
}
