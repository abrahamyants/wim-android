package com.wim.app.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Minimal Wear OS activity: one button toggles PCM streaming to the paired phone.
 *
 * Entry points:
 *   - User opens the app directly → shows idle button "Record".
 *   - Complication tap → launches with EXTRA_START_NOW=true → starts recording
 *     immediately (still shows a Stop button so the user can end it).
 */
class MainActivity : Activity() {

    companion object {
        /** Set by WimComplicationService's PendingIntent. */
        const val EXTRA_START_NOW = "start_now"

        private const val MIC_REQUEST_CODE = 1001
    }

    private lateinit var streamer: PhoneStreamer
    private lateinit var toggleButton: Button

    @Volatile private var isRecording = false
    private var startOnPermissionGrant = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamer = PhoneStreamer(applicationContext)

        // Build UI in code — no layout XML needed for a single button.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        toggleButton = Button(this).apply {
            text = "Record"
            setOnClickListener { onToggleTapped() }
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        root.addView(toggleButton, lp)
        setContentView(root)

        // If launched from complication, start recording right away.
        if (intent?.getBooleanExtra(EXTRA_START_NOW, false) == true) {
            requestMicAndStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_NOW, false) && !isRecording) {
            requestMicAndStart()
        }
    }

    override fun onDestroy() {
        if (isRecording) streamer.stop()
        super.onDestroy()
    }

    private fun onToggleTapped() {
        if (isRecording) {
            stopRecording()
        } else {
            requestMicAndStart()
        }
    }

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startOnPermissionGrant = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_REQUEST_CODE
            )
            return
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && startOnPermissionGrant) {
            startOnPermissionGrant = false
            startRecording()
        }
    }

    private fun startRecording() {
        streamer.start()
        isRecording = true
        toggleButton.text = "Stop"
    }

    private fun stopRecording() {
        streamer.stop()
        isRecording = false
        toggleButton.text = "Record"
    }
}
