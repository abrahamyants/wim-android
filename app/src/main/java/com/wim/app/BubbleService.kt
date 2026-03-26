package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating bubble overlay that appears when a text field is focused.
 * Hold to record, release to transcribe + reconstruct + paste.
 */
class BubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var isRecording = false
    private var recorder: AudioRecorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "wim_bubble"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        recorder = AudioRecorder(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW" -> showBubble()
            "HIDE" -> hideBubble()
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 16
        }

        bubbleView?.setOnTouchListener(BubbleTouchListener(params))
        windowManager?.addView(bubbleView, params)
    }

    private fun hideBubble() {
        bubbleView?.let {
            windowManager?.removeView(it)
            bubbleView = null
        }
    }

    private fun startRecording() {
        if (isRecording || recorder == null) return
        if (!recorder!!.hasPermission()) return

        isRecording = true
        recorder!!.start()

        // Visual feedback — change bubble appearance
        bubbleView?.alpha = 1.0f
    }

    private fun stopRecordingAndProcess() {
        if (!isRecording) return
        isRecording = false

        val audioFile = recorder?.stop() ?: return
        bubbleView?.alpha = 0.5f

        scope.launch {
            try {
                // Step 1: Whisper transcription
                val transcribeResult = WhisperClient.transcribe(audioFile)
                val rawText = transcribeResult.text
                if (rawText.isBlank()) return@launch

                val layer = WimApp.layer()
                val tone = WimApp.tone()

                val outputText = if (layer >= 2) {
                    // Step 2: Reconstruction
                    val reconResult = ReconstructClient.reconstruct(rawText, tone, layer)
                    reconResult.cleanText
                } else {
                    rawText
                }

                // Step 3: Inject into text field
                WimAccessibilityService.instance?.injectText(outputText)

            } catch (e: Exception) {
                // TODO: show error feedback on bubble
                e.printStackTrace()
            }
        }
    }

    /**
     * Handle touch events on the bubble:
     * - Long press to start recording
     * - Release to stop + process
     * - Drag to move
     */
    private inner class BubbleTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isLongPress = false
        private val longPressHandler = android.os.Handler(mainLooper)
        private val longPressRunnable = Runnable {
            isLongPress = true
            startRecording()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isLongPress = false
                    longPressHandler.postDelayed(longPressRunnable, 200)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        if (!isLongPress) {
                            params.x = initialX - dx
                            params.y = initialY + dy
                            windowManager?.updateViewLayout(bubbleView, params)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (isLongPress) {
                        stopRecordingAndProcess()
                    } else {
                        // Short tap — toggle recording
                        if (isRecording) {
                            stopRecordingAndProcess()
                        } else {
                            startRecording()
                        }
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiM Voice Dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the voice dictation bubble active"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("What I Meant")
            .setContentText("Voice dictation active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

    override fun onDestroy() {
        hideBubble()
        scope.cancel()
        super.onDestroy()
    }
}
