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

    // File writes are debounced and run on a dedicated background thread.
    // Without this, every log() call rewrote the entire ~16 KB JSON array
    // on the caller's thread (often Main on UI paths); hot pipeline runs
    // fire 5-8 log() calls in <100 ms, so the file was being rewritten N
    // times per recording for no gain. Audit finding 2026-06-06.
    private const val PERSIST_DEBOUNCE_MS = 250L
    private val ioHandler by lazy {
        val ht = android.os.HandlerThread("WiM.ConsoleLog.IO").apply { start() }
        android.os.Handler(ht.looper)
    }
    private val persistRunnable = Runnable {
        val snap = synchronized(_entries) { _entries.toList() }
        persist(snap)
    }
    private fun schedulePersist() {
        ioHandler.removeCallbacks(persistRunnable)
        ioHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

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
        synchronized(_entries) {
            _entries.add(0, Entry(text = text, kind = kind))
            if (_entries.size > MAX_ENTRIES) _entries.removeAt(_entries.size - 1)
        }
        // Schedule a debounced write — coalesces a burst of log() calls
        // into one file write on a dedicated IO thread.
        schedulePersist()
        val listenersSnap = synchronized(listeners) { listeners.toList() }
        listenersSnap.forEach { it() }
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return { synchronized(listeners) { listeners.remove(listener) } }
    }

    fun clear() {
        synchronized(_entries) { _entries.clear() }
        // Cancel any pending debounced write and write the empty array
        // right away — clear() is user-initiated so the file should match
        // what the user just saw on screen.
        ioHandler.removeCallbacks(persistRunnable)
        ioHandler.post { persist(emptyList()) }
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
