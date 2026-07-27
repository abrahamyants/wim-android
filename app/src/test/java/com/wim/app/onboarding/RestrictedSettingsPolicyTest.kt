package com.wim.app.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsPolicyTest {

    @Test
    fun android16SystemSideload_needsOverlayUnlockGuide() {
        assertTrue(
            RestrictedSettingsPolicy.needsOverlayUnlock(
                sdkInt = 36,
                installerPackage = "com.google.android.packageinstaller",
                overlayGranted = false
            )
        )
    }

    @Test
    fun android16PlayInstall_doesNotShowSideloadGuide() {
        assertFalse(
            RestrictedSettingsPolicy.needsOverlayUnlock(
                sdkInt = 36,
                installerPackage = "com.android.vending",
                overlayGranted = false
            )
        )
    }

    @Test
    fun android15Sideload_doesNotUseAndroid16OverlayFlow() {
        assertFalse(
            RestrictedSettingsPolicy.needsOverlayUnlock(
                sdkInt = 35,
                installerPackage = "com.google.android.packageinstaller",
                overlayGranted = false
            )
        )
    }

    @Test
    fun grantedOverlay_neverShowsUnlockGuide() {
        assertFalse(
            RestrictedSettingsPolicy.needsOverlayUnlock(
                sdkInt = 36,
                installerPackage = "com.google.android.packageinstaller",
                overlayGranted = true
            )
        )
    }
}
