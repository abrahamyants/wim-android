package com.wim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isRecording = false
    private var vibrator: Vibrator? = null
    private var onDeviceRecognizer: OnDeviceRecognizer? = null
    private var dafPlayer: DafPlayer? = null
    private var lastRawText = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingStartTime = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val min = elapsed / 60
                val sec = elapsed % 60
                bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.text = "$min:${"%02d".format(sec)}"
                timerHandler.postDelayed(this, 500)
            }
        }
    }

    companion object {
        private const val TAG = "WiM"
        private const val CHANNEL_ID = "wim_bubble"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BubbleService created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        onDeviceRecognizer = OnDeviceRecognizer(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BubbleService onStartCommand: ${intent?.action}")
        when (intent?.action) {
            "SHOW" -> showBubble()
            "HIDE" -> hideBubble()
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubbleView != null) return

        Log.i(TAG, "Showing bubble")
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble, null)

        val params = bubbleParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 900
            y = 1000
        }
        bubbleParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L
        var startedOnThisPress = false

        bubbleView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()

                    if (!isRecording) {
                        try { vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        startListening()
                        startedOnThisPress = true
                    } else {
                        startedOnThisPress = false
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) return@setOnTouchListener true
                    val holdDuration = System.currentTimeMillis() - downTime

                    if (startedOnThisPress && holdDuration > 500) {
                        // Hold mode: started on this press, held 500ms+, release = done
                        try { vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        stopAndProcess()
                    } else if (!startedOnThisPress && isRecording) {
                        // Second tap: recording was already running, stop it
                        try { vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
                        stopAndProcess()
                    }
                    // else: quick tap that started recording — do nothing, keep recording
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, params)
    }

    private fun hideBubble() {
        bubbleView?.let {
            windowManager?.removeView(it)
            bubbleView = null
        }
    }

    private fun startListening() {
        if (isRecording) return
        isRecording = true
        lastRawText = ""

        Log.i(TAG, "Starting on-device recognition")
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()

        // Visual: recording state
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleRoot)
            ?.getChildAt(0)?.setBackgroundResource(R.drawable.bubble_bg_recording)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "REC"
            setTextColor(0xFFEF4444.toInt())
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            setTextColor(0xFFFF6B35.toInt())
        }
        recordingStartTime = System.currentTimeMillis()
        timerHandler.post(timerRunnable)
        val pulse = AlphaAnimation(1.0f, 0.6f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.startAnimation(pulse)

        // Start DAF playback if enabled
        if (WimApp.dafEnabled()) {
            dafPlayer = DafPlayer()
            dafPlayer?.start()
        }

        onDeviceRecognizer?.start(object : OnDeviceRecognizer.ResultListener {
            override fun onPartialResult(text: String) {
                Log.i(TAG, "Partial: $text")
                lastRawText = text
            }

            override fun onFinalResult(text: String) {
                Log.i(TAG, "Final ASR: $text")
                lastRawText = text
                // If still recording (user hasn't tapped stop yet), auto-process
                if (isRecording) {
                    isRecording = false
                    dafPlayer?.stop()
                    dafPlayer = null
                    processText(text)
                }
            }

            override fun onError(errorCode: Int) {
                Log.e(TAG, "ASR error: $errorCode")
                isRecording = false
                dafPlayer?.stop()
                dafPlayer = null
                setBubbleIdle()
                bubbleView?.scaleX = 1.0f
                bubbleView?.scaleY = 1.0f
            }
        })
    }

    private fun stopAndProcess() {
        Log.i(TAG, "Stop requested, lastRawText=$lastRawText")
        isRecording = false
        dafPlayer?.stop()
        dafPlayer = null
        onDeviceRecognizer?.stop()

        if (lastRawText.isNotBlank()) {
            processText(lastRawText)
        } else {
            setBubbleIdle()
        }
    }

    private fun setBubbleIdle() {
        timerHandler.removeCallbacks(timerRunnable)
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)?.clearAnimation()
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleRoot)
            ?.getChildAt(0)?.setBackgroundResource(R.drawable.bubble_bg_idle)
        bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "IDLE"
            setTextColor(0xFF55555E.toInt())
        }
        bubbleView?.findViewById<TextView>(R.id.bubbleTime)?.apply {
            text = "0:00"
            setTextColor(0xFFEE5A24.toInt())
        }
    }

    private fun setBubbleProcessing() {
        setBubbleIdle()
    }

    private fun processText(rawText: String) {
        Log.i(TAG, "Processing: $rawText")
        setBubbleProcessing()

        val layer = WimApp.layer()
        val tone = WimApp.tone()

        if (layer < 2) {
            // L1: transcribe only — inject raw text immediately
            Log.i(TAG, "L1 mode, injecting raw text")
            WimAccessibilityService.instance?.injectText(rawText)
            bubbleView?.alpha = 0.7f
            return
        }

        // L2+: reconstruct via GPT
        Toast.makeText(this, "Reconstructing...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                Log.i(TAG, "Calling reconstruction (L$layer, $tone)...")
                val reconResult = ReconstructClient.reconstruct(rawText, tone, layer)
                Log.i(TAG, "Reconstruction (${reconResult.reconMs}ms): ${reconResult.cleanText}")

                WimAccessibilityService.instance?.injectText(reconResult.cleanText)
                    ?: Log.e(TAG, "No accessibility service instance!")

                setBubbleIdle()
            } catch (e: Exception) {
                Log.e(TAG, "Reconstruction error: ${e.message}", e)
                // Fallback: inject raw text
                WimAccessibilityService.instance?.injectText(rawText)
                setBubbleIdle()
            }
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
        dafPlayer?.stop()
        dafPlayer = null
        hideBubble()
        onDeviceRecognizer?.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
