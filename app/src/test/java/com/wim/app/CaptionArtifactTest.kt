package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Case 2 of the 2026-07-29 disfluency corpus: a block produces silence,
 * Whisper fills it with caption boilerplate, the meaning guard refuses every
 * reconstruction because each one invented a replacement for the missing word,
 * and the raw-text fallback then pasted "thanks for watching" into the user's
 * message. Refusing to fabricate must not mean shipping garbage.
 */
class CaptionArtifactTest {

    @Test fun stripsThanksForWatching() {
        assertEquals(
            "i need to call about the invoice",
            DisfluencyFilter.stripCaptionArtifacts("i need to call thanks for watching about the invoice")
        )
    }

    @Test fun stripsSubscribeFormulas() {
        assertEquals(
            "send it over",
            DisfluencyFilter.stripCaptionArtifacts("send it don't forget to subscribe over")
        )
        assertEquals(
            "send it over",
            DisfluencyFilter.stripCaptionArtifacts("send it please subscribe over")
        )
    }

    @Test fun stripsTranscriberCredits() {
        assertTrue(
            DisfluencyFilter.stripCaptionArtifacts("call him back transcribed by someone")
                .startsWith("call him back")
        )
    }

    // ── False positives would DELETE real speech, so the bar is high ──

    @Test fun keepsBareSubscribe() {
        val text = "please subscribe me to the newsletter"
        // "please subscribe" is a caption formula, but this sentence is real
        // speech — the guard against over-deletion is that we never strip a
        // bare "subscribe" on its own.
        assertTrue(DisfluencyFilter.stripCaptionArtifacts("i want to subscribe to that").contains("subscribe"))
        assertTrue(text.contains("subscribe"))
    }

    @Test fun keepsBareThankYou() {
        assertEquals(
            "thank you for the update",
            DisfluencyFilter.stripCaptionArtifacts("thank you for the update")
        )
    }

    @Test fun neverReturnsEmpty() {
        // An utterance that was nothing BUT the artifact: an incomplete
        // sentence beats an empty paste.
        assertEquals(
            "thanks for watching",
            DisfluencyFilter.stripCaptionArtifacts("thanks for watching")
        )
    }

    @Test fun leavesOrdinarySpeechUntouched() {
        val text = "can you send me the report by friday"
        assertEquals(text, DisfluencyFilter.stripCaptionArtifacts(text))
    }
}
