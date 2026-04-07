package com.wim.app.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Bridges AccessibilityService events into the agent socket event stream.
 *
 * Called from WimAccessibilityService.onAccessibilityEvent() on every event.
 * Filters based on client's subscribed event types, serializes to JSON,
 * and pushes to the connected agent via AgentSocketServer.pushEvent().
 *
 * Event types:
 *   "focus"      — TYPE_VIEW_FOCUSED
 *   "window"     — TYPE_WINDOW_STATE_CHANGED
 *   "text"       — TYPE_VIEW_TEXT_CHANGED
 *   "recording"  — RecordingStateBus state changes
 */
class AgentEventBridge(private val server: AgentSocketServer) {

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (server.subscribedEvents.isEmpty()) return

        val eventType = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focus"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text"
            else -> return
        }

        if (eventType !in server.subscribedEvents) return

        val data = JSONObject().apply {
            put("type", eventType)
            put("package", event.packageName?.toString() ?: "")
            put("class", event.className?.toString() ?: "")
            put("text", event.text?.joinToString(" ") ?: "")

            // Source node info (if available)
            val source = event.source
            if (source != null) {
                put("editable", source.isEditable)
                put("password", source.isPassword)
                put("resource_id", source.viewIdResourceName ?: "")
                val rect = Rect()
                source.getBoundsInScreen(rect)
                put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
            }

            // Text change specifics
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                put("before_text", event.beforeText?.toString() ?: "")
                put("added_count", event.addedCount)
                put("removed_count", event.removedCount)
                put("from_index", event.fromIndex)
            }
        }

        server.pushEvent(eventType, data)
    }

    fun onRecordingStateChanged(recording: Boolean) {
        if ("recording" !in server.subscribedEvents) return

        server.pushEvent("recording", JSONObject().apply {
            put("recording", recording)
        })
    }
}
