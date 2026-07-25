package com.wim.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/**
 * Home screen widget — 1x1 WM logo, tap to start/stop recording.
 *
 * The tap used to be a `PendingIntent.getForegroundService` aimed straight at
 * [BubbleService]. On Android 12+ that throws
 * `ForegroundServiceStartNotAllowedException` whenever the app isn't already
 * foreground — which, for a home-screen widget, is the normal case. The throw
 * surfaces in the app process with nothing able to catch it, so the widget was
 * dead on any modern device.
 *
 * The tap now comes back to this receiver, where the start CAN be wrapped, and
 * a refusal falls back to launching [MainActivity], which starts the bubble
 * itself from a legitimate foreground context.
 */
class WimWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "WiM.Widget"
        const val ACTION_WIDGET_TAP = "com.wim.app.WIDGET_TAP"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            // Explicit component, so the custom action needs no intent-filter
            // entry — manifest-declared receivers accept explicit intents.
            val intent = Intent(context, WimWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TAP
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget_layout).apply {
                setOnClickPendingIntent(R.id.widgetLogo, pendingIntent)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_TAP) {
            val toggle = Intent(context, BubbleService::class.java).apply {
                action = "TOGGLE_RECORDING"
            }
            try {
                context.startForegroundService(toggle)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Direct service start refused (${e.javaClass.simpleName}) — opening the app instead"
                )
                try {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback launch also failed: ${e2.javaClass.simpleName}")
                }
            }
            return
        }
        super.onReceive(context, intent)
    }
}
