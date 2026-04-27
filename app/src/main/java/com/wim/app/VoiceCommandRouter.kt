package com.wim.app

/**
 * Priority dispatch chain for voice commands per spec §2 row 3.
 *
 * Multiple subsystems want to inspect every utterance from
 * [OnDeviceRecognizer.onResults]:
 *   1. Clarifier ("next / yes / no / skip / swap") — only when state is Clarifying
 *   2. Twist-trigger detector (existing)
 *   3. Primary dictation route (existing)
 *
 * Handlers are invoked in registration order. The first handler to return
 * `true` short-circuits the chain — primary dictation only fires if every
 * higher-priority handler declined.
 *
 * Spec risk #5 (router starvation): every handler MUST return false unless
 * the utterance is unambiguously theirs. The clarifier handler especially
 * must check `BubbleStateBus.current() is BubbleState.Clarifying` before
 * claiming any token.
 */
object VoiceCommandRouter {

    fun interface Handler {
        /**
         * Inspect [text]. Return true if handled (chain stops), false to
         * pass through to the next handler.
         */
        fun handle(text: String): Boolean
    }

    private data class Registered(val name: String, val handler: Handler)

    private val handlers = mutableListOf<Registered>()

    /**
     * Register a handler at the end of the chain. Order of registration
     * defines priority (first registered = highest priority).
     */
    fun register(name: String, handler: Handler) {
        if (handlers.any { it.name == name }) return
        handlers.add(Registered(name, handler))
    }

    fun unregister(name: String) {
        handlers.removeAll { it.name == name }
    }

    /**
     * Run [text] through the chain. Returns the name of the handler that
     * claimed it, or null if none did (caller falls through to primary
     * dictation).
     */
    fun dispatch(text: String): String? {
        if (text.isBlank()) return null
        for (h in handlers) {
            val claimed = try {
                h.handler.handle(text)
            } catch (e: Throwable) {
                android.util.Log.e("VoiceCommandRouter", "${h.name} threw: ${e.message}")
                false
            }
            if (claimed) return h.name
        }
        return null
    }

    /** Test-only / onDestroy. */
    fun reset() {
        handlers.clear()
    }
}
