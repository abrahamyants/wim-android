package com.wim.app.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * Opens a ChannelClient channel to the paired phone and streams raw PCM frames
 * as they come off the on-watch AudioRecord.
 *
 * Format matches the phone's AudioRecorder.kt: 16 kHz / mono / 16-bit PCM.
 *
 * Scaffold-level: this just streams. Phase 2 will hand the bytes to the
 * reconstruction pipeline on the phone side.
 */
class PhoneStreamer(private val context: Context) {

    companion object {
        private const val TAG = "WimPhoneStreamer"

        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Must match WearListenerService.CHANNEL_PATH on the phone side. */
        const val CHANNEL_PATH = "/wim/pcm-stream"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    @Volatile private var isStreaming = false
    private var recorder: AudioRecord? = null
    private var channel: ChannelClient.Channel? = null
    private var outputStream: OutputStream? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start recording on the watch and stream PCM frames to the phone.
     * Returns immediately; streaming happens on a background coroutine.
     */
    fun start() {
        if (isStreaming) {
            Log.w(TAG, "start() called while already streaming; ignoring")
            return
        }
        if (!hasMicPermission()) {
            Log.w(TAG, "start() called without RECORD_AUDIO permission; aborting")
            return
        }

        isStreaming = true
        streamJob = scope.launch {
            try {
                streamLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "Stream loop failed", t)
            } finally {
                cleanup()
            }
        }
    }

    fun stop() {
        isStreaming = false
        streamJob?.cancel()
    }

    private fun streamLoop() {
        // 1. Find a connected node (phone) that advertises our app's capabilities.
        val node = findConnectedNode() ?: run {
            Log.w(TAG, "No connected node found; cannot stream")
            return
        }
        Log.i(TAG, "Streaming to node: ${node.displayName} (${node.id})")

        // 2. Open a channel to that node.
        val channelClient = Wearable.getChannelClient(context)
        val ch = Tasks.await(channelClient.openChannel(node.id, CHANNEL_PATH))
        channel = ch
        val os = Tasks.await(channelClient.getOutputStream(ch))
        outputStream = os

        // 3. Start AudioRecord.
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING,
            bufferSize * 2
        )
        recorder = rec
        rec.startRecording()

        // 4. Pump PCM frames into the channel until stop() is called.
        val buf = ByteArray(bufferSize)
        var totalBytes = 0L
        while (isStreaming) {
            val read = rec.read(buf, 0, buf.size)
            if (read <= 0) continue
            try {
                os.write(buf, 0, read)
                totalBytes += read
            } catch (t: Throwable) {
                Log.e(TAG, "Channel write failed at $totalBytes bytes", t)
                break
            }
        }
        Log.i(TAG, "Stream loop exiting; total bytes sent = $totalBytes")
    }

    private fun findConnectedNode(): Node? {
        val nodeClient = Wearable.getNodeClient(context)
        val nodes = Tasks.await(nodeClient.connectedNodes)
        // Prefer a nearby (directly-connected) node — that's the paired phone.
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    private fun cleanup() {
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null

        try { outputStream?.flush() } catch (_: Throwable) {}
        try { outputStream?.close() } catch (_: Throwable) {}
        outputStream = null

        val ch = channel
        channel = null
        if (ch != null) {
            try {
                Tasks.await(Wearable.getChannelClient(context).close(ch))
            } catch (_: Throwable) {}
        }
    }
}
