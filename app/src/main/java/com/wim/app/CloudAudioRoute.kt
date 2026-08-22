package com.wim.app

/** Chooses the transport for cloud speech-to-text after token retrieval. */
object CloudAudioRoute {
    enum class Decision { BACKEND, DIRECT, UNAVAILABLE }

    fun decide(apiKeyPresent: Boolean, idTokenPresent: Boolean): Decision = when {
        // Signed-in audio always takes the WiM backend, where BSRNN-Flow runs.
        idTokenPresent -> Decision.BACKEND
        apiKeyPresent -> Decision.DIRECT
        else -> Decision.UNAVAILABLE
    }
}
