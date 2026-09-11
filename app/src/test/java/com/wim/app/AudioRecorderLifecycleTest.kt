package com.wim.app

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the microphone lifecycle.
 *
 * Field evidence (George's phone, 2026-09-11, 10h uptime): the process held
 * 29 live AudioRecord native clients and 29 capture threads, four of them
 * spinning in R state, where exactly one of each is correct. Cause: release()
 * ran unlocked against @Synchronized primeBuffer(), so a hotword mic handoff
 * could null `recorder` right after a fresh prime published it. That left
 * isPrimed=true with recorder=null, which the old capture loop turned into a
 * permanent 200-wakeups-per-second spin, and which the old
 * `if (isPrimed) return` guard made unrecoverable for the life of the process.
 * User-visible symptom: WiM keeps working for hours, then quietly stops
 * hearing anything until the service restarts.
 *
 * These tests assert the two invariants that failure violated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioRecorderLifecycleTest {

    private lateinit var recorder: AudioRecorder

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        recorder = AudioRecorder(app)
    }

    private fun field(name: String): Any? =
        AudioRecorder::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(recorder)

    private fun setField(name: String, value: Any?) {
        AudioRecorder::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(recorder, value)
    }

    private fun primed() = field("isPrimed") as Boolean
    private fun rec() = field("recorder")
    private fun captureThread() = field("captureThread") as Thread?

    /** After release the mic object and its thread are both gone. */
    @Test fun releaseLeavesNothingBehind() {
        recorder.primeBuffer()
        recorder.release()

        assertFalse("isPrimed must be false after release", primed())
        assertNull("AudioRecord must be released, not stranded", rec())
        assertNull("capture thread reference must be dropped", captureThread())
    }

    /**
     * A prime that lost its recorder must rebuild, not return early.
     * The old guard returned on isPrimed alone and stranded the mic forever.
     */
    @Test fun stalePrimeSelfHeals() {
        recorder.primeBuffer()
        val first = rec()
        assertNotNull("prime should have built an AudioRecord", first)

        // Reproduce the observed corrupt state: flagged as primed, no recorder.
        setField("recorder", null)
        assertTrue(primed())

        recorder.primeBuffer()

        assertNotNull("stale prime must rebuild the AudioRecord", rec())
        assertTrue("recorder must be usable again", primed())
        recorder.release()
    }

    /**
     * The core invariant: isPrimed implies a live recorder. Hammer prime and
     * release from two threads the way the hotword handoff does. On the
     * unlocked version this interleaving is what produced the leak.
     */
    @Test fun concurrentPrimeAndReleaseNeverStrandTheMic() {
        val rounds = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val broken = StringBuilder()

        val primer = Thread {
            start.await()
            repeat(rounds) {
                recorder.primeBuffer()
                if (primed() && rec() == null) {
                    synchronized(broken) { broken.append("primed with null recorder;") }
                }
            }
            done.countDown()
        }
        val releaser = Thread {
            start.await()
            repeat(rounds) { recorder.release() }
            done.countDown()
        }

        primer.start(); releaser.start(); start.countDown()
        assertTrue("threads should finish", done.await(30, TimeUnit.SECONDS))

        recorder.release()
        assertEquals("mic stranded during prime/release race: $broken", 0, broken.length)
        assertFalse(primed())
        assertNull(rec())
    }
}
