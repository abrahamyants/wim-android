package com.wim.app

/**
 * In-memory pipeline log — mirrors Лаврентий's console.
 * BubbleService writes entries at each pipeline stage;
 * MainActivity renders them in the console view.
 */
object ConsoleLog {
    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val kind: String // "rec", "info", "raw", "out", "warn", "error"
    )

    private val _entries = mutableListOf<Entry>()
    private val listeners = mutableListOf<() -> Unit>()
    private const val MAX_ENTRIES = 200

    val entries: List<Entry> get() = synchronized(_entries) { _entries.toList() }

    fun log(text: String, kind: String = "info") {
        synchronized(_entries) {
            _entries.add(0, Entry(text = text, kind = kind))
            if (_entries.size > MAX_ENTRIES) _entries.removeAt(_entries.size - 1)
        }
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return { synchronized(listeners) { listeners.remove(listener) } }
    }

    fun clear() {
        synchronized(_entries) { _entries.clear() }
    }
}
