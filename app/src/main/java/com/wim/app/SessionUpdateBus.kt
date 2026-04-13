package com.wim.app

/**
 * Notifies MainActivity when a new session is recorded.
 * BubbleService emits after sessionHistory.record(); MainActivity subscribes to reload the list.
 */
object SessionUpdateBus {
    private val listeners = mutableListOf<() -> Unit>()

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return { synchronized(listeners) { listeners.remove(listener) } }
    }

    fun emit() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }
}
