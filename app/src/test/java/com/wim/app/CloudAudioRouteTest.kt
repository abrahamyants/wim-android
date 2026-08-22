package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAudioRouteTest {
    @Test fun signedInGiveawayUsesNoiseBackend() {
        assertEquals(
            CloudAudioRoute.Decision.BACKEND,
            CloudAudioRoute.decide(apiKeyPresent = true, idTokenPresent = true),
        )
    }

    @Test fun signedOutGiveawayKeepsDirectFallback() {
        assertEquals(
            CloudAudioRoute.Decision.DIRECT,
            CloudAudioRoute.decide(apiKeyPresent = true, idTokenPresent = false),
        )
    }

    @Test fun keylessSignedInReleaseUsesNoiseBackend() {
        assertEquals(
            CloudAudioRoute.Decision.BACKEND,
            CloudAudioRoute.decide(apiKeyPresent = false, idTokenPresent = true),
        )
    }

    @Test fun noCredentialRouteIsUnavailable() {
        assertEquals(
            CloudAudioRoute.Decision.UNAVAILABLE,
            CloudAudioRoute.decide(apiKeyPresent = false, idTokenPresent = false),
        )
    }
}
