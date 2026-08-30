package com.wim.app.onboarding.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wim.app.AudioRecorder
import com.wim.app.WimApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding card: measure this speaker's normal level.
 *
 * Sits immediately after the microphone permission, which is the only step it
 * needs and the reason that permission is no longer skippable.
 *
 * Why it exists at all: WhisperLocalEngine decided "this take is soft, boost
 * it" by comparing against QUIET_AUTO_MAX_DBFS, a fixed -22 dBFS. But dBFS is
 * not a property of a voice — it is the voice plus the microphone plus
 * whatever the OS did to the samples before we saw them. The same number means
 * "whispering" on one handset and "shouting" on another, so the constant was
 * measuring hardware rather than speaking. Four seconds here turns it into a
 * comparison against the person.
 *
 * The boost is not free, which is why getting this wrong costs something: it
 * high-passes and adds 18 dB, so applied to ordinary speech it thins the voice
 * and flattens the consonant peaks the recogniser leans on.
 */
private const val CALIBRATION_SECONDS = 4

@Composable
fun MicLevelScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var listening by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Double?>(WimApp.micBaselineDbfs()) }
    var failed by remember { mutableStateOf<String?>(null) }

    val measured = result != null

    fun measure() {
        if (listening) return
        listening = true
        failed = null
        scope.launch {
            val dbfs = withContext(Dispatchers.IO) {
                val recorder = AudioRecorder(context)
                try {
                    recorder.primeBuffer()
                    recorder.start(quietMode = false)
                    delay(CALIBRATION_SECONDS * 1000L)
                    recorder.stop()
                    recorder.calibrationDbfs()
                } catch (t: Throwable) {
                    android.util.Log.w("WiM.Onboarding", "calibration failed: ${t.message}")
                    null
                } finally {
                    try { recorder.release() } catch (_: Throwable) {}
                }
            }
            listening = false
            if (dbfs == null || dbfs <= -119.0) {
                failed = "Nothing came through. Check the microphone is not muted, then try again."
            } else {
                WimApp.setMicBaselineDbfs(dbfs)
                result = dbfs
                android.util.Log.i("WiM.Onboarding", "mic baseline set to ${"%.0f".format(dbfs)} dBFS")
            }
        }
    }

    val body = when {
        listening -> "Listening — read it now, in your normal voice."
        failed != null -> failed!!
        measured -> {
            val d = result!!
            val verdict = when {
                d < -40 -> "That is very quiet. It will still work, but move a little closer to the phone for the best results."
                d > -8 -> "That is loud — try holding the phone a bit further away."
                else -> "Good level."
            }
            "Measured ${"%.0f".format(d)} dBFS. $verdict"
        }
        else ->
            "Read this out loud, in your normal speaking voice:\n\n" +
                "\"The quick brown fox jumps over the lazy dog.\"\n\n" +
                "WiM measures how loud you actually arrive, so it can tell a whisper " +
                "from ordinary speech on your phone rather than on someone else's."
    }

    PermissionCard(
        onBack = onBack,
        heading = "YOUR LEVEL",
        body = body,
        // granted stays FALSE even once measured. PermissionCard sets
        // `enabled = !granted` on BOTH buttons, which is right for the
        // permission cards - they auto-advance the instant the grant lands, so
        // freezing the card is the correct end state. This screen has no
        // auto-advance and still needs a decision, so passing granted=true
        // showed "GRANTED ✓" and left the operator with two dead buttons and
        // no way off the screen. Reported on device 2026-08-29.
        granted = false,
        showBadge = false,
        primaryCta = when {
            listening -> "LISTENING…"
            measured -> "CONTINUE"
            else -> "START — THEN READ IT"
        },
        secondaryCta = if (measured) "MEASURE AGAIN" else "NOT NOW",
        onPrimary = {
            when {
                listening -> Unit
                measured -> onContinue()
                else -> measure()
            }
        },
        onSecondary = {
            when {
                listening -> Unit
                measured -> measure()
                else -> {
                    android.util.Log.w(
                        "WiM.Onboarding",
                        "mic level skipped — auto-quiet falls back to the absolute threshold"
                    )
                    onSkip()
                }
            }
        }
    )
}
