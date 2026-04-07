package com.wim.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wim.app.BubbleService
import com.wim.app.RecordingStateBus
import com.wim.app.WimApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handles all commands from the agent socket.
 *
 * Transient node refs: each snapshot() assigns "n0", "n1", ... to nodes
 * and stores the live AccessibilityNodeInfo in nodeMap. Commands reference
 * nodes by these refs. Refs expire on next snapshot.
 */
class AgentCommandHandler(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WiM-Agent"
        private const val MAIN_THREAD_TIMEOUT_MS = 5000L
        private const val MAX_DEPTH_DEFAULT = 15
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nodeMap = HashMap<String, AccessibilityNodeInfo>()
    private var nodeCounter = 0

    // ── Command dispatch ────────────────────────────────────────────────────

    fun dispatch(cmd: String, params: JSONObject): JSONObject {
        return when (cmd) {
            "ping"            -> handlePing()
            "snapshot"        -> handleSnapshot(params)
            "find_by_id"      -> handleFindById(params)
            "find_by_text"    -> handleFindByText(params)
            "get_focused"     -> handleGetFocused()
            "get_windows"     -> handleGetWindows()
            "click"           -> handleClick(params)
            "long_click"      -> handleLongClick(params)
            "set_text"        -> handleSetText(params)
            "scroll"          -> handleScroll(params)
            "focus"           -> handleFocus(params)
            "global_action"   -> handleGlobalAction(params)
            "gesture"         -> handleGesture(params)
            "inject_pipeline" -> handleInjectPipeline(params)
            else              -> JSONObject().put("error", "Unknown command: $cmd")
        }
    }

    // ── READ commands ───────────────────────────────────────────────────────

    private fun handlePing(): JSONObject {
        return JSONObject().apply {
            put("bubble_running", BubbleService.instance != null)
            put("recording", RecordingStateBus.isRecording)
            put("tone", WimApp.tone())
            put("layer", WimApp.layer())
        }
    }

    private fun handleSnapshot(params: JSONObject): JSONObject {
        val maxDepth = params.optInt("max_depth", MAX_DEPTH_DEFAULT)
        val result = JSONObject()

        runOnMainThread {
            // Clear previous refs
            nodeMap.clear()
            nodeCounter = 0

            val windows = service.windows
            val windowsArr = JSONArray()

            for (window in windows) {
                val root = window.root ?: continue
                val windowObj = JSONObject().apply {
                    put("title", window.title?.toString() ?: "")
                    put("type", windowTypeToString(window.type))
                    put("layer", window.layer)
                    val rect = Rect()
                    window.getBoundsInScreen(rect)
                    put("bounds", boundsToString(rect))
                    put("tree", dumpNode(root, 0, maxDepth))
                }
                windowsArr.put(windowObj)
            }

            result.put("windows", windowsArr)
            result.put("node_count", nodeCounter)
        }

        return result
    }

    private fun handleFindById(params: JSONObject): JSONObject {
        val id = params.getString("id")
        val result = JSONObject()

        runOnMainThread {
            val matches = JSONArray()
            for (window in service.windows) {
                val root = window.root ?: continue
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    val ref = assignRef(node)
                    matches.put(nodeToJson(node, ref))
                }
            }
            result.put("matches", matches)
            result.put("count", matches.length())
        }

        return result
    }

    private fun handleFindByText(params: JSONObject): JSONObject {
        val text = params.getString("text")
        val result = JSONObject()

        runOnMainThread {
            val matches = JSONArray()
            for (window in service.windows) {
                val root = window.root ?: continue
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    val ref = assignRef(node)
                    matches.put(nodeToJson(node, ref))
                }
            }
            result.put("matches", matches)
            result.put("count", matches.length())
        }

        return result
    }

    private fun handleGetFocused(): JSONObject {
        val result = JSONObject()

        runOnMainThread {
            var focused: AccessibilityNodeInfo? = null
            for (window in service.windows) {
                val root = window.root ?: continue
                focused = findFocused(root)
                if (focused != null) break
            }
            if (focused != null) {
                val ref = assignRef(focused)
                result.put("node", nodeToJson(focused, ref))
            } else {
                result.put("node", JSONObject.NULL)
            }
        }

        return result
    }

    private fun handleGetWindows(): JSONObject {
        val result = JSONObject()

        runOnMainThread {
            val arr = JSONArray()
            for (window in service.windows) {
                arr.put(JSONObject().apply {
                    put("title", window.title?.toString() ?: "")
                    put("type", windowTypeToString(window.type))
                    put("layer", window.layer)
                    put("focused", window.isFocused)
                    put("active", window.isActive)
                    val rect = Rect()
                    window.getBoundsInScreen(rect)
                    put("bounds", boundsToString(rect))
                    val root = window.root
                    put("has_tree", root != null)
                    put("child_count", root?.childCount ?: 0)
                })
            }
            result.put("windows", arr)
        }

        return result
    }

    // ── WRITE commands ──────────────────────────────────────────────────────

    private fun handleClick(params: JSONObject): JSONObject {
        val ref = params.getString("ref")
        val node = resolveRef(ref)
        val result = JSONObject()

        runOnMainThread {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            result.put("clicked", success)
            result.put("ref", ref)
        }

        return result
    }

    private fun handleLongClick(params: JSONObject): JSONObject {
        val ref = params.getString("ref")
        val node = resolveRef(ref)
        val result = JSONObject()

        runOnMainThread {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            result.put("long_clicked", success)
            result.put("ref", ref)
        }

        return result
    }

    private fun handleSetText(params: JSONObject): JSONObject {
        val ref = params.getString("ref")
        val text = params.getString("text")
        val node = resolveRef(ref)
        val result = JSONObject()

        runOnMainThread {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            result.put("set_text", success)
            result.put("ref", ref)
            result.put("text", text)
        }

        return result
    }

    private fun handleScroll(params: JSONObject): JSONObject {
        val ref = params.getString("ref")
        val direction = params.optString("direction", "forward")
        val node = resolveRef(ref)
        val result = JSONObject()

        runOnMainThread {
            val action = if (direction == "backward")
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val success = node.performAction(action)
            result.put("scrolled", success)
            result.put("ref", ref)
            result.put("direction", direction)
        }

        return result
    }

    private fun handleFocus(params: JSONObject): JSONObject {
        val ref = params.getString("ref")
        val node = resolveRef(ref)
        val result = JSONObject()

        runOnMainThread {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            result.put("focused", success)
            result.put("ref", ref)
        }

        return result
    }

    private fun handleGlobalAction(params: JSONObject): JSONObject {
        val action = params.getString("action")
        val result = JSONObject()

        val actionId = when (action) {
            "back"          -> AccessibilityService.GLOBAL_ACTION_BACK
            "home"          -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents"       -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings"-> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power"         -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock"          -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot"    -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> throw IllegalArgumentException("Unknown global action: $action")
        }

        runOnMainThread {
            val success = service.performGlobalAction(actionId)
            result.put("performed", success)
            result.put("action", action)
        }

        return result
    }

    private fun handleGesture(params: JSONObject): JSONObject {
        val pathArr = params.getJSONArray("path")
        if (pathArr.length() < 2) throw IllegalArgumentException("Gesture path needs at least 2 points")

        val result = JSONObject()

        runOnMainThread {
            val gestPath = Path()
            val first = pathArr.getJSONArray(0)
            gestPath.moveTo(first.getDouble(0).toFloat(), first.getDouble(1).toFloat())

            var duration = 0L
            for (i in 1 until pathArr.length()) {
                val point = pathArr.getJSONArray(i)
                gestPath.lineTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
                if (point.length() > 2) {
                    duration = point.getLong(2)
                }
            }
            if (duration <= 0) duration = 300 // default 300ms

            val stroke = GestureDescription.StrokeDescription(gestPath, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var success = false

            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, mainHandler)

            latch.await(5, TimeUnit.SECONDS)
            result.put("gesture_completed", success)
            result.put("points", pathArr.length())
            result.put("duration_ms", duration)
        }

        return result
    }

    private fun handleInjectPipeline(params: JSONObject): JSONObject {
        val rawText = params.getString("raw_text")
        val bubble = BubbleService.instance
            ?: throw IllegalStateException("BubbleService not running")

        bubble.processTextForTest(rawText)

        return JSONObject().apply {
            put("injected", true)
            put("raw_text", rawText)
        }
    }

    // ── Node serialization ──────────────────────────────────────────────────

    private fun assignRef(node: AccessibilityNodeInfo): String {
        val ref = "n${nodeCounter++}"
        nodeMap[ref] = node
        return ref
    }

    private fun resolveRef(ref: String): AccessibilityNodeInfo {
        return nodeMap[ref]
            ?: throw IllegalArgumentException("Unknown ref: $ref (take a new snapshot)")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): JSONObject {
        val ref = assignRef(node)
        val obj = nodeToJson(node, ref)

        if (node.childCount > 0 && depth < maxDepth) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                children.put(dumpNode(child, depth + 1, maxDepth))
            }
            obj.put("children", children)
        }

        return obj
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, ref: String): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        return JSONObject().apply {
            put("ref", ref)
            put("cls", node.className?.toString() ?: "")
            put("text", node.text?.toString() ?: "")
            put("desc", node.contentDescription?.toString() ?: "")
            put("id", node.viewIdResourceName ?: "")
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
            put("selected", node.isSelected)
            put("checked", node.isChecked)
            put("password", node.isPassword)
            put("bounds", boundsToString(rect))
        }
    }

    private fun findFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocused(child)
            if (found != null) return found
        }
        return null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun boundsToString(rect: Rect): String =
        "${rect.left},${rect.top},${rect.right},${rect.bottom}"

    private fun windowTypeToString(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        else -> "unknown($type)"
    }

    /**
     * Execute a block on the main thread and wait for completion.
     * All AccessibilityNodeInfo operations must run on the main thread.
     */
    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        var error: Exception? = null

        mainHandler.post {
            try {
                block()
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Main thread timeout (${MAIN_THREAD_TIMEOUT_MS}ms)")
        }

        error?.let { throw it }
    }
}
