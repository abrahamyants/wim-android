package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told when the machinery fails mid-sentence.
 *
 * A speaker who just fought through a block and got an error deserves to know
 * whether to check their wifi, sign in, or just tap again. Telling all three
 * of them "check your connection" wastes the one thing this app exists to
 * save them.
 */
class FailureClassifierTest {

    // ── Offline / flaky network ───────────────────────────────────

    @Test fun noDns() {
        val r = FailureClassifier.classify(java.net.UnknownHostException("api.openai.com"))
        assertEquals(FailReason.NETWORK, r.reason)
    }

    @Test fun timeoutMidRequest() {
        // Carries no message at all — must not fall through to "unknown".
        val r = FailureClassifier.classify(java.net.SocketTimeoutException())
        assertEquals(FailReason.NETWORK, r.reason)
    }

    @Test fun connectionRefused() {
        assertEquals(
            FailReason.NETWORK,
            FailureClassifier.classify(java.net.ConnectException("refused")).reason
        )
    }

    @Test fun connectionResetMidBody() {
        // Cell handoff mid-upload: generic IOException, still the network.
        assertEquals(
            FailReason.NETWORK,
            FailureClassifier.classify(java.io.IOException("unexpected end of stream")).reason
        )
    }

    // ── The server answered, but wrongly ──────────────────────────

    @Test fun backendNon2xx() {
        assertEquals(
            FailReason.SERVER,
            FailureClassifier.classify(RuntimeException("Backend error 503: upstream")).reason
        )
    }

    @Test fun brokenResponseContract() {
        assertEquals(
            FailReason.SERVER,
            FailureClassifier.classify(
                RuntimeException("Backend response missing 'clean' field")
            ).reason
        )
    }

    @Test fun providerApiError() {
        assertEquals(
            FailReason.SERVER,
            FailureClassifier.classify(RuntimeException("Reconstruction API error 429: rate limit")).reason
        )
    }

    @Test fun truncatedOrHtmlResponse() {
        // Captive portal / proxy returns an HTML page where JSON was promised.
        assertEquals(
            FailReason.SERVER,
            FailureClassifier.classify(org.json.JSONException("Value <html> of type java.lang.String")).reason
        )
    }

    // ── The user's own setup ──────────────────────────────────────

    @Test fun noApiKeyOrSubscription() {
        val r = FailureClassifier.classify(IllegalStateException("No API key configured"))
        assertEquals(FailReason.CONFIG, r.reason)
        assertTrue(r.message.contains("Sign in"))
    }

    @Test fun micPermissionRevokedWhileRunning() {
        // Android can revoke a permission out from under a running service.
        val r = FailureClassifier.classify(SecurityException("RECORD_AUDIO denied"))
        assertEquals(FailReason.CONFIG, r.reason)
        assertTrue(r.message.contains("Microphone"))
    }

    // ── Anything else ─────────────────────────────────────────────

    @Test fun unknownStaysActionable() {
        val r = FailureClassifier.classify(RuntimeException("something exotic"))
        assertEquals(FailReason.UNKNOWN, r.reason)
        assertTrue(r.message.contains("try again"))
    }

    @Test fun nullMessageNeverCrashesTheClassifier() {
        val r = FailureClassifier.classify(RuntimeException())
        assertEquals(FailReason.UNKNOWN, r.reason)
    }

    @Test fun everyResultCarriesUserFacingText() {
        val cases = listOf<Throwable>(
            java.net.UnknownHostException(), java.net.SocketTimeoutException(),
            java.io.IOException(), org.json.JSONException("x"),
            IllegalStateException("No API key configured"), SecurityException(),
            RuntimeException("Backend error 500"), RuntimeException(),
        )
        for (c in cases) {
            val r = FailureClassifier.classify(c)
            assertTrue("empty message for ${c.javaClass.simpleName}", r.message.isNotBlank())
        }
    }
}
