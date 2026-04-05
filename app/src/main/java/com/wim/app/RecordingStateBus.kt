package com.wim.app

/**
 * Simple pub/sub for recording state.
 * BubbleService emits when isRecording flips; UI elements (like LogoBarsView)
 * subscribe to react to the bubble starting/stopping recording.
 */
object RecordingStateBus {
    @Volatile private var _isRecording = false
    val isRecording: Boolean get() = _isRecording

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /**
     * Subscribe to recording state changes. Returns an unsubscribe function.
     * The listener fires immediately with the current state.
     */
    fun subscribe(listener: (Boolean) -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        listener(_isRecording)
        return { synchronized(listeners) { listeners.remove(listener) } }
    }

    fun emit(recording: Boolean) {
        _isRecording = recording
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it(recording) }
    }
}
