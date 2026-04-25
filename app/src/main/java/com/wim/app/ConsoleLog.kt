package com.wim.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * In-memory + on-disk pipeline log — mirrors Лаврентий's console.
 * BubbleService writes entries at each pipeline stage; MainActivity renders
 * them in the console view. Persisted to filesDir/console.json so the log
 * survives app process restarts (force-stop, reinstall, OS memory eviction).
 *
 * Format on disk: a JSON array of {timestamp, text, kind} objects, newest
 * first, capped at MAX_ENTRIES.
 */
object ConsoleLog {

    private const val TAG = "WiM.Console"
    private const val FILE_NAME = "console.json"
    private const val MAX_ENTRIES = 200

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val kind: String, // "rec", "info", "raw", "out", "warn", "error"
    )

    private val _entries = mutableListOf<Entry>()
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var loaded = false

    val entries: List<Entry>
        get() {
            ensureLoaded()
            return synchronized(_entries) { _entries.toList() }
        }

    fun log(text: String, kind: String = "info") {
        ensureLoaded()
        val snapshot = synchronized(_entries) {
            _entries.add(0, Entry(text = text, kind = kind))
            if (_entries.size > MAX_ENTRIES) _entries.removeAt(_entries.size - 1)
            _entries.toList()
        }
        // File write outside the lock — JSONArray is small (~200 entries × ~80
        // chars = ~16 KB) but no point holding the read-side lock while we
        // touch the disk.
        persist(snapshot)
        val listenersSnap = synchronized(listeners) { listeners.toList() }
        listenersSnap.forEach { it() }
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return { synchronized(listeners) { listeners.remove(listener) } }
    }

    fun clear() {
        synchronized(_entries) { _entries.clear() }
        persist(emptyList())
        val listenersSnap = synchronized(listeners) { listeners.toList() }
        listenersSnap.forEach { it() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(_entries) {
            if (loaded) return
            loaded = true // set first so re-entrant calls during load don't recurse
            try {
                val file = consoleFile() ?: return
                if (!file.exists()) return
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    _entries.add(
                        Entry(
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            text = obj.optString("text", ""),
                            kind = obj.optString("kind", "info"),
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load console.json: ${e.message}")
            }
        }
    }

    private fun persist(snapshot: List<Entry>) {
        try {
            val file = consoleFile() ?: return
            val arr = JSONArray()
            for (e in snapshot) {
                arr.put(
                    JSONObject().apply {
                        put("timestamp", e.timestamp)
                        put("text", e.text)
                        put("kind", e.kind)
                    },
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist console.json: ${e.message}")
        }
    }

    private fun consoleFile(): File? {
        val ctx = try {
            WimApp.instance
        } catch (_: UninitializedPropertyAccessException) {
            // Robolectric / unit tests may use ConsoleLog before WimApp.onCreate
            // sets `instance`. Skip persistence in that case — entries stay
            // in-memory only for the test run.
            return null
        }
        return File(ctx.filesDir, FILE_NAME)
    }
}
