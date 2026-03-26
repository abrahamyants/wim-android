package com.wim.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Main settings screen — API key setup, tone/layer selection,
 * permission grants (overlay, accessibility, mic).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveKeyBtn = findViewById<Button>(R.id.saveKeyBtn)
        val statusText = findViewById<TextView>(R.id.statusText)
        val toneGroup = findViewById<RadioGroup>(R.id.toneGroup)
        val overlayBtn = findViewById<Button>(R.id.overlayPermBtn)
        val accessibilityBtn = findViewById<Button>(R.id.accessibilityPermBtn)
        val micBtn = findViewById<Button>(R.id.micPermBtn)

        // Load saved API key
        val savedKey = WimApp.apiKey()
        if (savedKey.isNotBlank()) {
            apiKeyInput.setText(savedKey)
            statusText.text = "API key configured"
        }

        // Save API key
        saveKeyBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.startsWith("sk-")) {
                WimApp.prefs.edit().putString("api_key", key).apply()
                statusText.text = "API key saved"
                Toast.makeText(this, "Key saved", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "Invalid key — must start with sk-"
            }
        }

        // Tone selection
        val currentTone = WimApp.tone()
        when (currentTone) {
            "casual" -> toneGroup.check(R.id.toneCasual)
            "professional" -> toneGroup.check(R.id.toneProfessional)
            "formal" -> toneGroup.check(R.id.toneFormal)
            "friend" -> toneGroup.check(R.id.toneFriend)
        }
        toneGroup.setOnCheckedChangeListener { _, checkedId ->
            val tone = when (checkedId) {
                R.id.toneCasual -> "casual"
                R.id.toneProfessional -> "professional"
                R.id.toneFormal -> "formal"
                R.id.toneFriend -> "friend"
                else -> "casual"
            }
            WimApp.prefs.edit().putString("tone", tone).apply()
        }

        // Overlay permission
        overlayBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay already granted", Toast.LENGTH_SHORT).show()
            }
        }

        // Accessibility service
        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Mic permission
        micBtn.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                100
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = WimAccessibilityService.instance != null
        val micOk = AudioRecorder(this).hasPermission()

        findViewById<Button>(R.id.overlayPermBtn).text =
            if (overlayOk) "Overlay: Granted" else "Grant Overlay Permission"
        findViewById<Button>(R.id.accessibilityPermBtn).text =
            if (accessibilityOk) "Accessibility: Enabled" else "Enable Accessibility Service"
        findViewById<Button>(R.id.micPermBtn).text =
            if (micOk) "Microphone: Granted" else "Grant Microphone Permission"

        // If all permissions granted, show ready status
        if (overlayOk && accessibilityOk && micOk && WimApp.apiKey().isNotBlank()) {
            findViewById<TextView>(R.id.statusText).text = "Ready — open any app and start talking"
        }
    }
}
