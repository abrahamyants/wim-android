package com.wim.app.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.wim.app.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Bidirectional JSON socket server for LLM agent control.
 *
 * Runs on 127.0.0.1:9222 (loopback only). Debug builds only.
 * Connect via: adb forward tcp:9222 tcp:9222
 *
 * Protocol: newline-delimited JSON.
 *   Request:  {"id":1, "cmd":"snapshot", "params":{...}}\n
 *   Response: {"id":1, "ok":true, "result":{...}}\n
 *   Event:    {"event":"focus_changed", "data":{...}, "ts":1712345678901}\n
 */
class AgentSocketServer(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WiM-Agent"
        private const val PORT = 9222

        @Volatile
        var instance: AgentSocketServer? = null
            private set

        fun start(service: AccessibilityService) {
            if (!BuildConfig.DEBUG) return
            if (instance != null) {
                Log.w(TAG, "Server already running")
                return
            }
            val server = AgentSocketServer(service)
            instance = server
            server.startListening()
        }

        fun stop() {
            instance?.shutdown()
            instance = null
        }
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    val handler = AgentCommandHandler(service)
    private val eventBridge = AgentEventBridge(this)

    @Volatile
    private var running = false

    // Event subscriptions (set by subscribe command)
    @Volatile
    var subscribedEvents: Set<String> = emptySet()

    fun startListening() {
        running = true
        serverThread = Thread({
            try {
                serverSocket = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "Agent socket server listening on 127.0.0.1:$PORT")

                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleClient(socket)
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
            }
        }, "wim-agent-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        // L11: removed dead "close previous client" — handleClient runs serially
        // from the accept loop and clientSocket is always null here (the finally
        // nulls it before the next accept).
        clientSocket = socket
        Log.i(TAG, "Agent connected from ${socket.remoteSocketAddress}")

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            clientWriter = PrintWriter(socket.getOutputStream(), true)

            while (running && socket.isConnected) {
                val raw = reader.readLine() ?: break
                if (raw.isBlank()) continue

                try {
                    val request = JSONObject(raw)
                    val id = request.opt("id")
                    val cmd = request.optString("cmd", "")
                    val params = request.optJSONObject("params") ?: JSONObject()

                    val result = when (cmd) {
                        "subscribe" -> {
                            val events = mutableSetOf<String>()
                            val arr = params.optJSONArray("events")
                            if (arr != null) {
                                for (i in 0 until arr.length()) events.add(arr.getString(i))
                            }
                            subscribedEvents = events
                            JSONObject().put("subscribed", events.joinToString(","))
                        }
                        "unsubscribe" -> {
                            subscribedEvents = emptySet()
                            JSONObject().put("unsubscribed", true)
                        }
                        else -> handler.dispatch(cmd, params)
                    }

                    val response = JSONObject().apply {
                        if (id != null) put("id", id)
                        put("ok", true)
                        put("result", result)
                    }
                    send(response)

                } catch (e: Exception) {
                    val errorResponse = JSONObject().apply {
                        put("ok", false)
                        put("error", e.message ?: "Unknown error")
                    }
                    send(errorResponse)
                    Log.e(TAG, "Command error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (running) Log.i(TAG, "Client disconnected: ${e.message}")
        } finally {
            subscribedEvents = emptySet()
            clientWriter = null
            clientSocket = null
            Log.i(TAG, "Agent disconnected")
        }
    }

    fun send(json: JSONObject) {
        try {
            clientWriter?.println(json.toString())
        } catch (_: Exception) { }
    }

    fun pushEvent(eventType: String, data: JSONObject) {
        if (eventType !in subscribedEvents) return
        val event = JSONObject().apply {
            put("event", eventType)
            put("data", data)
            put("ts", System.currentTimeMillis())
        }
        send(event)
    }

    fun getEventBridge(): AgentEventBridge = eventBridge

    fun shutdown() {
        running = false
        try { clientSocket?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        serverThread?.interrupt()
        Log.i(TAG, "Agent socket server stopped")
    }
}
