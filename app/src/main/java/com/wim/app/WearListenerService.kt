package com.wim.app

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import java.io.InputStream

/**
 * Receives PCM frames streamed from the paired Wear OS watch.
 *
 * Scaffold only: this service logs reception of raw 16 kHz / mono / 16-bit PCM
 * frames but does NOT feed them into the reconstruction pipeline yet.
 * Integration with [BubbleService.stopAndProcess] is phase 2.
 *
 * Channel path must match com.wim.app.wear.PhoneStreamer.CHANNEL_PATH
 * on the watch side.
 */
class WearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearListenerService"

        /** Must match PhoneStreamer.CHANNEL_PATH on the wear side. */
        const val CHANNEL_PATH = "/wim/pcm-stream"

        /** Log a progress line for every N bytes received. */
        const val LOG_EVERY_BYTES = 1024

        /** Defensive cap so a rogue/misbehaving peer can't eat all the heap. */
        const val MAX_BYTES = 64L * 1024L * 1024L // 64 MB
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != CHANNEL_PATH) {
            Log.d(TAG, "Ignoring channel with path=${channel.path}")
            return
        }
        Log.i(TAG, "Channel opened from node=${channel.nodeId}, path=${channel.path}")

        val channelClient = com.google.android.gms.wearable.Wearable.getChannelClient(this)
        channelClient.getInputStream(channel).addOnSuccessListener { input ->
            Thread({ drain(input) }, "wim-wear-drain").start()
        }.addOnFailureListener { t ->
            Log.e(TAG, "Failed to get channel input stream", t)
        }
    }

    override fun onChannelClosed(
        channel: ChannelClient.Channel,
        closeReason: Int,
        appSpecificErrorCode: Int
    ) {
        if (channel.path == CHANNEL_PATH) {
            Log.i(
                TAG,
                "Channel closed from node=${channel.nodeId} reason=$closeReason code=$appSpecificErrorCode"
            )
        }
    }

    /**
     * Drain the incoming PCM stream. Logs received-byte counts at ~1 KB granularity.
     *
     * Pure byte counting for now — Phase 2 will call into [BubbleService.stopAndPcmFromWatch]
     * or equivalent to merge these frames into the reconstruction job.
     */
    private fun drain(input: InputStream) {
        val stats = countFrames(input, LOG_EVERY_BYTES, MAX_BYTES) { running ->
            Log.i(TAG, "WearListenerService received $running bytes")
        }
        if ((stats.totalBytes and 1L) == 1L) {
            Log.d(
                TAG,
                "Dropped 1 trailing byte (short frame): total=${stats.totalBytes} samples=${stats.sampleCount}"
            )
        }
        Log.i(
            TAG,
            "Stream drained: total=${stats.totalBytes} samples=${stats.sampleCount} truncated=${stats.truncated}"
        )
        try { input.close() } catch (_: Throwable) {}
    }

    /**
     * Pure-JVM helper (no Android APIs) so unit tests can drive it directly
     * against a ByteArrayInputStream.
     *
     * Reads 16-bit little-endian PCM samples. Short trailing frames that don't
     * form a full 16-bit sample are dropped from [sampleCount] but still counted
     * in [totalBytes]. Hard-caps total read at [maxBytes] to protect the heap.
     *
     * @param logEveryBytes when running byte total crosses this threshold since
     *                      the last log, [onProgress] is fired with the running total.
     */
    data class Stats(
        val totalBytes: Long,
        val sampleCount: Long,
        val truncated: Boolean
    )

    fun countFrames(
        input: InputStream,
        logEveryBytes: Int,
        maxBytes: Long,
        onProgress: (Long) -> Unit = {}
    ): Stats {
        val buf = ByteArray(4096)
        var total = 0L
        var nextProgressAt = logEveryBytes.toLong()
        var truncated = false

        while (true) {
            val toRead = minOf(buf.size.toLong(), maxBytes - total).toInt()
            if (toRead <= 0) { truncated = true; break }
            val n = try {
                input.read(buf, 0, toRead)
            } catch (_: Throwable) {
                -1
            }
            if (n == -1) break
            if (n == 0) continue
            total += n
            while (total >= nextProgressAt) {
                onProgress(total)
                nextProgressAt += logEveryBytes
            }
        }

        // Samples = floor(total / 2). If total is odd, the trailing byte is a
        // short frame we cannot form into a 16-bit sample; drop from sample count.
        // Logging of the odd-byte event is handled by the caller so this method
        // stays pure-JVM (no android.util.Log on the hot path — tests rely on this).
        val samples = total / 2L

        return Stats(totalBytes = total, sampleCount = samples, truncated = truncated)
    }
}
