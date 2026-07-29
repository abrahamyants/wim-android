package com.wim.app.onboarding.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * Shared grant-detection mechanics for the permission cards:
 *
 *  - re-check on lifecycle ON_RESUME (covers the Settings round-trip)
 *  - 1s poll while ungranted (covers toggles the OS never calls back for —
 *    overlay and accessibility grants have no result callback)
 *  - auto-advance 900ms after the badge flips, so the ✓ beat is visible
 *
 * Mirrors OverlayPermissionScreen, which stays the reference implementation —
 * see its DO-NOT-REVERT note. Sideloaded users uninstall the moment they have
 * to back-button out of Android Settings by hand.
 */
@Composable
internal fun permissionGrantState(
    check: () -> Boolean,
    onGranted: () -> Unit
): Boolean {
    val currentCheck by rememberUpdatedState(check)
    val currentOnGranted by rememberUpdatedState(onGranted)
    var granted by remember { mutableStateOf(currentCheck()) }
    // Granted before the screen ever appeared (reinstall, partial prior run):
    // skip through instantly. The 900ms ✓ beat is only for a grant the user
    // just performed and needs to see land.
    val preGranted = remember { granted }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = currentCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Poll while not granted — catches the user-flips-toggle-in-Settings case.
    LaunchedEffect(granted) {
        var ticks = 0
        while (!granted && ticks < 60) {
            delay(1000)
            granted = currentCheck()
            ticks++
        }
    }

    // Auto-advance the moment the toggle flips, after the ✓ beat lands.
    LaunchedEffect(granted) {
        if (granted) {
            if (!preGranted) delay(900)
            currentOnGranted()
        }
    }

    return granted
}
