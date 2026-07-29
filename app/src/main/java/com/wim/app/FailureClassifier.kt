package com.wim.app

/**
 * Turns a pipeline exception into something a user can act on.
 *
 * The distinction matters: "check your connection" is useless advice when the
 * real problem is an expired subscription, and "server error" is useless when
 * the phone is simply offline. Finding H4.
 *
 * Extracted from BubbleService 2026-07-29 so these rules can actually be
 * tested. Every other adversarial test in this repo covers bad INPUT — garbled
 * ASR, hallucinated captions. Nothing covered the machinery around the app
 * failing, which is how a schema migration shipped with two data-loss holes in
 * it the same night.
 */
object FailureClassifier {

    data class Result(val reason: FailReason, val message: String)

    fun classify(e: Throwable): Result {
        val msg = e.message ?: ""
        return when {
            e is IllegalStateException &&
                (msg.contains("API key", ignoreCase = true) ||
                    msg.contains("Sign in", ignoreCase = true)) ->
                Result(FailReason.CONFIG, "Sign in and subscribe to WiM Cloud in Settings")

            e is SecurityException ->
                Result(FailReason.CONFIG, "Microphone permission missing — enable it in Settings")

            // Genuinely offline, or the request never completed. Checked before
            // the message-content rules below: a socket timeout carries no
            // useful message and must not fall through to "something went
            // wrong".
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException || e is java.io.InterruptedIOException ->
                Result(FailReason.NETWORK, "Network error — check your connection and try again")

            msg.contains("Backend error", ignoreCase = true) ||
                msg.contains("missing 'clean'", ignoreCase = true) ||
                msg.contains("API error", ignoreCase = true) ->
                Result(FailReason.SERVER, "Server error — please try again")

            // The server answered with something that isn't the contract —
            // truncated body, HTML error page, half-written JSON.
            e is org.json.JSONException ->
                Result(FailReason.SERVER, "Unexpected server response — please try again")

            // Any other IO: connection reset mid-body, TLS failure, DNS churn.
            e is java.io.IOException ->
                Result(FailReason.NETWORK, "Connection problem — try again")

            else ->
                Result(FailReason.UNKNOWN, "Something went wrong — tap to try again")
        }
    }
}
