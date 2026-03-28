package com.wim.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Main settings screen — instrument-panel aesthetic matching Лаврентий dashboard.
 * Tone, Layer, Mode, Situation, Enhancements, API key, Permissions.
 */
class MainActivity : AppCompatActivity() {

    // Card IDs grouped by section for selection logic
    private val toneIds = listOf(R.id.toneCasual, R.id.toneProfessional, R.id.toneFriend, R.id.toneFormal)
    private val toneValues = listOf("casual", "professional", "friend", "formal")

    private val layerIds = listOf(R.id.layer1, R.id.layer2, R.id.layer3, R.id.layer4)
    private val layerLabelIds = listOf(R.id.layer1Label, R.id.layer2Label, R.id.layer3Label, R.id.layer4Label)
    private val layerValues = listOf(1, 2, 3, 4)

    private val modeIds = listOf(R.id.modeRaw, R.id.modeFast, R.id.modeSafe)
    private val modeLabelIds = listOf(R.id.modeRawLabel, R.id.modeFastLabel, R.id.modeSafeLabel)
    private val modeValues = listOf("raw", "fast", "safe")

    private val situationIds = listOf(R.id.sitDefault, R.id.sitHighStress, R.id.sitReading)
    private val situationLabelIds = listOf(R.id.sitDefaultLabel, R.id.sitHighStressLabel, R.id.sitReadingLabel)
    private val situationValues = listOf("default", "high_stress", "reading")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── API Key section (collapsible) ──────────────────
        val apiKeyHeader = findViewById<TextView>(R.id.apiKeyHeader)
        val apiKeySection = findViewById<LinearLayout>(R.id.apiKeySection)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveKeyBtn = findViewById<Button>(R.id.saveKeyBtn)
        val statusText = findViewById<TextView>(R.id.statusText)

        // Toggle expand/collapse
        apiKeyHeader.setOnClickListener {
            if (apiKeySection.visibility == View.GONE) {
                apiKeySection.visibility = View.VISIBLE
                apiKeyHeader.text = "API KEY  \u25BE"
            } else {
                apiKeySection.visibility = View.GONE
                apiKeyHeader.text = "API KEY  \u25B8"
            }
        }

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
                statusText.text = "Invalid key \u2014 must start with sk-"
            }
        }

        // ── Tone selection (TextViews) ───────────────────────
        setupToneGroup(toneIds, toneValues, "tone", WimApp.tone())

        // ── Layer selection (LinearLayouts with label child) ─
        val currentLayer = WimApp.layer()
        val layerIdx = layerValues.indexOf(currentLayer).coerceAtLeast(0)
        setupLayoutGroupInt(layerIds, layerLabelIds, layerValues, "layer", layerIdx)

        // ── Mode selection (LinearLayouts with label child) ──
        setupLayoutGroup(modeIds, modeLabelIds, modeValues, "mode", WimApp.mode())

        // ── Situation selection (LinearLayouts with label child)
        setupLayoutGroup(situationIds, situationLabelIds, situationValues, "situation", WimApp.situation())

        // ── Enhancement toggles ────────────────────────────
        val switchPara = findViewById<MaterialSwitch>(R.id.switchParalinguistic)
        val switchPros = findViewById<MaterialSwitch>(R.id.switchProsodic)

        switchPara.isChecked = WimApp.paralinguistic()
        switchPros.isChecked = WimApp.prosodic()

        switchPara.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("paralinguistic", checked).apply()
        }
        switchPros.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("prosodic", checked).apply()
        }

        // ── DAF toggle + delay SeekBar ───────────────────
        val switchDaf = findViewById<MaterialSwitch>(R.id.switchDaf)
        val seekDafDelay = findViewById<SeekBar>(R.id.seekDafDelay)
        val dafDelayValue = findViewById<TextView>(R.id.dafDelayValue)

        switchDaf.isChecked = WimApp.dafEnabled()

        // SeekBar range: 0..270 maps to 30..300 ms
        val savedDelay = WimApp.dafDelay().coerceIn(30, 300)
        seekDafDelay.progress = savedDelay - 30
        dafDelayValue.text = "$savedDelay ms"

        switchDaf.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("daf_enabled", checked).apply()
        }

        seekDafDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = progress + 30
                dafDelayValue.text = "$ms ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val ms = (seekBar?.progress ?: 70) + 30
                WimApp.prefs.edit().putInt("daf_delay", ms).apply()
            }
        })

        // ── Permissions ────────────────────────────────────
        val overlayBtn = findViewById<Button>(R.id.overlayPermBtn)
        val accessibilityBtn = findViewById<Button>(R.id.accessibilityPermBtn)
        val micBtn = findViewById<Button>(R.id.micPermBtn)

        overlayBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Overlay already granted", Toast.LENGTH_SHORT).show()
            }
        }

        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

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

    // ── Tone-group selection (simple TextViews) ─────────────

    private fun setupToneGroup(ids: List<Int>, values: List<String>, prefKey: String, current: String) {
        val idx = values.indexOf(current).coerceAtLeast(0)
        ids.forEachIndexed { i, id ->
            val v = findViewById<TextView>(id)
            setCardSelected(v, i == idx)
        }
        ids.forEachIndexed { i, id ->
            findViewById<TextView>(id).setOnClickListener {
                ids.forEach { otherId -> setCardSelected(findViewById(otherId), false) }
                setCardSelected(it as TextView, true)
                WimApp.prefs.edit().putString(prefKey, values[i]).apply()
            }
        }
    }

    // ── Layout-group selection (LinearLayout with label TextView) ──

    private fun setupLayoutGroup(ids: List<Int>, labelIds: List<Int>, values: List<String>, prefKey: String, current: String) {
        val idx = values.indexOf(current).coerceAtLeast(0)
        ids.forEachIndexed { i, id ->
            val container = findViewById<LinearLayout>(id)
            val label = findViewById<TextView>(labelIds[i])
            setLayoutSelected(container, label, i == idx)
        }
        ids.forEachIndexed { i, id ->
            findViewById<LinearLayout>(id).setOnClickListener {
                ids.forEachIndexed { j, otherId ->
                    setLayoutSelected(findViewById(otherId), findViewById(labelIds[j]), false)
                }
                setLayoutSelected(it as LinearLayout, findViewById(labelIds[i]), true)
                WimApp.prefs.edit().putString(prefKey, values[i]).apply()
            }
        }
    }

    private fun setupLayoutGroupInt(ids: List<Int>, labelIds: List<Int>, values: List<Int>, prefKey: String, currentIdx: Int) {
        ids.forEachIndexed { i, id ->
            val container = findViewById<LinearLayout>(id)
            val label = findViewById<TextView>(labelIds[i])
            setLayoutSelected(container, label, i == currentIdx)
        }
        ids.forEachIndexed { i, id ->
            findViewById<LinearLayout>(id).setOnClickListener {
                ids.forEachIndexed { j, otherId ->
                    setLayoutSelected(findViewById(otherId), findViewById(labelIds[j]), false)
                }
                setLayoutSelected(it as LinearLayout, findViewById(labelIds[i]), true)
                WimApp.prefs.edit().putInt(prefKey, values[i]).apply()
            }
        }
    }

    // ── Visual state: selected = dark red pressed, unselected = aluminum raised ──

    private fun setCardSelected(view: TextView, selected: Boolean) {
        if (selected) {
            view.setBackgroundResource(R.drawable.card_bg_selected)
            view.setTextColor(getColor(R.color.active_text))
        } else {
            view.setBackgroundResource(R.drawable.card_bg)
            view.setTextColor(getColor(R.color.text_dim))
        }
    }

    private fun setLayoutSelected(container: ViewGroup, label: TextView, selected: Boolean) {
        if (selected) {
            container.setBackgroundResource(R.drawable.card_bg_selected)
            label.setTextColor(getColor(R.color.active_text))
        } else {
            container.setBackgroundResource(R.drawable.card_bg)
            label.setTextColor(getColor(R.color.text_dim))
        }
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

        if (overlayOk && accessibilityOk && micOk && WimApp.apiKey().isNotBlank()) {
            findViewById<TextView>(R.id.statusText).text = "Ready \u2014 open any app and start talking"
        }
    }
}
