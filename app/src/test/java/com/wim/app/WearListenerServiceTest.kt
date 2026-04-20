package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pure-JVM tests for the PCM frame-handling logic in [WearListenerService.countFrames].
 *
 * We avoid instantiating WearableListenerService (which needs Android glue) by
 * calling countFrames through a bare instance — countFrames itself touches no
 * Android APIs beyond [android.util.Log], which Robolectric shadows for us.
 *
 * Robolectric is already on the testImplementation classpath and
 * testOptions { unitTests.isIncludeAndroidResources = true } is enabled in
 * app/build.gradle.kts, so android.util.Log.* calls won't blow up.
 */
class WearListenerServiceTest {

    private val svc = WearListenerService()

    @Test
    fun knownPcmPattern_bytesCountedCorrectly() {
        // 2000 bytes = 1000 PCM samples at 16-bit. Pattern is just a ramp.
        val bytes = ByteArray(2000) { i -> (i and 0xFF).toByte() }
        val stats = svc.countFrames(
            input = ByteArrayInputStream(bytes),
            logEveryBytes = 1024,
            maxBytes = 10L * 1024 * 1024
        )
        assertEquals(2000L, stats.totalBytes)
        assertEquals(1000L, stats.sampleCount)
        assertFalse("unexpectedly truncated", stats.truncated)
    }

    @Test
    fun oddByteLength_dropsTrailingByteFromSampleCount() {
        // 3 bytes = 1 full 16-bit sample + 1 trailing odd byte that can't form a sample.
        // We want: totalBytes=3, sampleCount=1, no crash.
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val stats = svc.countFrames(
            input = ByteArrayInputStream(bytes),
            logEveryBytes = 1024,
            maxBytes = 1024
        )
        assertEquals(3L, stats.totalBytes)
        assertEquals(1L, stats.sampleCount)
        assertFalse(stats.truncated)
    }

    @Test
    fun singleOddByte_doesNotCrash_andDropsToZeroSamples() {
        val bytes = byteArrayOf(0x42)
        val stats = svc.countFrames(
            input = ByteArrayInputStream(bytes),
            logEveryBytes = 1024,
            maxBytes = 1024
        )
        assertEquals(1L, stats.totalBytes)
        assertEquals(0L, stats.sampleCount)
        assertFalse(stats.truncated)
    }

    @Test
    fun emptyStream_returnsZeroes_noCrash() {
        val stats = svc.countFrames(
            input = ByteArrayInputStream(ByteArray(0)),
            logEveryBytes = 1024,
            maxBytes = 1024
        )
        assertEquals(0L, stats.totalBytes)
        assertEquals(0L, stats.sampleCount)
        assertFalse(stats.truncated)
    }

    @Test
    fun largeStream_over1MB_isBufferedSanely_andProgressFires() {
        // 1.5 MB of ramp — exercises multiple read() iterations.
        val size = (1.5 * 1024 * 1024).toInt()
        val bytes = ByteArray(size) { i -> (i and 0xFF).toByte() }

        val progressCalls = mutableListOf<Long>()
        val stats = svc.countFrames(
            input = ByteArrayInputStream(bytes),
            logEveryBytes = 64 * 1024,
            maxBytes = 10L * 1024 * 1024,
            onProgress = { progressCalls += it }
        )
        assertEquals(size.toLong(), stats.totalBytes)
        assertEquals(size.toLong() / 2, stats.sampleCount)
        assertFalse("1.5 MB should not trigger truncation at 10 MB cap", stats.truncated)
        assertTrue("progress should fire at least once on a 1.5 MB stream", progressCalls.isNotEmpty())
        // Progress values must be monotonically non-decreasing.
        var prev = 0L
        for (v in progressCalls) {
            assertTrue("progress not monotonic: $prev then $v", v >= prev)
            prev = v
        }
    }

    @Test
    fun streamOverMaxBytes_truncatesGracefully() {
        // Feed 3 MB with a 1 MB cap — should stop at ~1 MB and set truncated=true.
        val size = 3 * 1024 * 1024
        val bytes = ByteArray(size) { i -> (i and 0xFF).toByte() }

        val stats = svc.countFrames(
            input = ByteArrayInputStream(bytes),
            logEveryBytes = 64 * 1024,
            maxBytes = 1024L * 1024L
        )
        assertEquals(1024L * 1024L, stats.totalBytes)
        assertTrue("expected truncation flag", stats.truncated)
    }
}
