package com.wim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log

/**
 * Bluetooth SCO bring-up + state tracking. When a BT headset is connected,
 * routes mic capture through SCO so the bubble records from the headset
 * mic instead of the phone's built-in mic.
 *
 * Extracted from BubbleService 2026-05-25 as the sixth BubbleWindow slice
 * (auxiliary cluster, not BubbleWindow proper). Cohesive: one broadcast
 * receiver + one routing call + one state flag, all owning their own
 * lifecycle.
 *
 * Migration note (preserved verbatim): startBluetoothSco /
 * isBluetoothScoOn / isBluetoothScoAvailableOffCall were deprecated in
 * API 31 in favor of AudioManager.setCommunicationDevice(AudioDeviceInfo).
 * The new API changes routing immediately (vs the old "request, wait for
 * state callback" model). Migrating without phone-side validation of
 * every BT headset scenario (already-connected, hot-swap, fallback to
 * built-in mic) would risk silently breaking BT mic capture. Suppressing
 * for now; modernize in a session with a BT headset on hand to test the
 * routing transitions.
 */
class BubbleBluetoothSco(
    private val context: Context,
    private val audioManager: AudioManager
) {
    private companion object {
        private const val TAG = "WiM"
    }

    private var receiver: BroadcastReceiver? = null
    @Volatile var scoActive: Boolean = false
        private set

    fun start() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO connected — using BT mic")
                        scoActive = true
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO disconnected — using default mic")
                        scoActive = false
                    }
                }
            }
        }
        receiver?.let { context.registerReceiver(it, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) }

        // If a Bluetooth headset is already connected, start SCO so the
        // mic-capture path picks up the headset rather than the phone's
        // built-in mic. See class-level migration note for the deprecation
        // rationale.
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoAvailableOffCall) {
            try {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                Log.i(TAG, "Bluetooth SCO started")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start Bluetooth SCO: ${e.message}")
            }
        }
    }

    fun stop() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        // Critical: SCO is a global ref-counted audio route. start() pinned it
        // for the whole device — unpin it here, otherwise it stays bound to
        // WiM's now-dead process and breaks call / media / mic routing for
        // every other app until the user reboots.
        try {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop Bluetooth SCO: ${e.message}")
        }
        scoActive = false
    }
}
