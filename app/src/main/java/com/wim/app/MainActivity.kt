package com.wim.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    private val situationIds = listOf(R.id.sitDefault, R.id.sitHighStress, R.id.sitReading)
    private val situationLabelIds = listOf(R.id.sitDefaultLabel, R.id.sitHighStressLabel, R.id.sitReadingLabel)
    private val situationValues = listOf("default", "high_stress", "reading")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── Google Sign-In ───────────────────────────────
        val signedOutSection = findViewById<LinearLayout>(R.id.signedOutSection)
        val signedInSection = findViewById<LinearLayout>(R.id.signedInSection)
        val googleSignInBtn = findViewById<Button>(R.id.googleSignInBtn)
        val signOutBtn = findViewById<Button>(R.id.signOutBtn)
        val userEmail = findViewById<TextView>(R.id.userEmail)
        val userTier = findViewById<TextView>(R.id.userTier)

        val apiKeyHeaderView = findViewById<TextView>(R.id.apiKeyHeader)
        val apiKeySectionView = findViewById<LinearLayout>(R.id.apiKeySection)

        fun updateAuthUI() {
            val user = AuthManager.currentUser
            if (user != null) {
                signedOutSection.visibility = View.GONE
                signedInSection.visibility = View.VISIBLE
                userEmail.text = user.email ?: user.uid.take(12)
                userTier.text = "Free tier"
                // Hide API key section — consumer doesn't need it
                apiKeyHeaderView.visibility = View.GONE
                apiKeySectionView.visibility = View.GONE
            } else {
                signedOutSection.visibility = View.VISIBLE
                signedInSection.visibility = View.GONE
                apiKeyHeaderView.visibility = View.VISIBLE
            }
        }

        updateAuthUI()

        // Web client ID from google-services.json oauth_client type 3
        val webClientId = "787543109204-4j4k9vtqq0282qa2sfpl7v6m7iaccojon.apps.googleusercontent.com"

        googleSignInBtn.setOnClickListener {
            val credentialManager = CredentialManager.create(this)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = credentialManager.getCredential(this@MainActivity, request)
                    val googleIdToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                    val user = AuthManager.signInWithGoogle(googleIdToken)
                    if (user != null) {
                        Toast.makeText(this@MainActivity, "Signed in as ${user.email}", Toast.LENGTH_SHORT).show()
                        updateAuthUI()
                    } else {
                        Toast.makeText(this@MainActivity, "Sign-in failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: GetCredentialCancellationException) {
                    // User cancelled — do nothing
                } catch (e: Exception) {
                    Log.e("WiM", "Sign-in error: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "Sign-in error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        signOutBtn.setOnClickListener {
            AuthManager.signOut()
            updateAuthUI()
            Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
        }

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



        // ── Situation selection (LinearLayouts with label child)
        setupLayoutGroup(situationIds, situationLabelIds, situationValues, "situation", WimApp.situation())

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

        // ── Bubble opacity slider ─────────────────────────
        val seekOpacity = findViewById<SeekBar>(R.id.bubbleOpacitySeek)
        val opacityValue = findViewById<TextView>(R.id.bubbleOpacityValue)
        val savedOpacity = WimApp.bubbleOpacity().coerceIn(20, 100)
        seekOpacity.progress = savedOpacity
        opacityValue.text = "$savedOpacity%"
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(20, 100)
                opacityValue.text = "$pct%"
                WimApp.prefs.edit().putInt("bubble_opacity", pct).apply()
                BubbleService.instance?.updateBubbleAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ── Bubble size slider ─────────────────────────────
        val seekSize = findViewById<SeekBar>(R.id.bubbleSizeSeek)
        val sizeValue = findViewById<TextView>(R.id.bubbleSizeValue)
        val savedSize = WimApp.bubbleSize().coerceIn(50, 150)
        seekSize.progress = savedSize
        sizeValue.text = "$savedSize%"
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(50, 150)
                sizeValue.text = "$pct%"
                WimApp.prefs.edit().putInt("bubble_size", pct).apply()
                BubbleService.instance?.updateBubbleAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ── Collapsible sections ─────────────────────────────
        setupCollapsible(R.id.headerTone, R.id.contentTone, "tone_section", defaultExpanded = true)
        setupCollapsible(R.id.headerLayer, R.id.contentLayer, "layer_section", defaultExpanded = true)

        setupCollapsible(R.id.headerSituation, R.id.contentSituation, "situation_section", defaultExpanded = true)

        setupCollapsible(R.id.headerBubble, R.id.contentBubble, "bubble_section", defaultExpanded = true)
        setupCollapsible(R.id.headerDaf, R.id.contentDaf, "daf_section", defaultExpanded = false)
        setupCollapsible(R.id.headerPermissions, R.id.contentPermissions, "permissions_section", defaultExpanded = false)

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

    private fun setupCollapsible(headerId: Int, contentId: Int, prefKey: String, defaultExpanded: Boolean = true) {
        val header = findViewById<TextView>(headerId)
        val content = findViewById<LinearLayout>(contentId)

        // Restore saved state
        val expanded = WimApp.prefs.getBoolean("section_$prefKey", defaultExpanded)
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        updateCollapseArrow(header, expanded)

        header.setOnClickListener {
            val isVisible = content.visibility == View.VISIBLE
            content.visibility = if (isVisible) View.GONE else View.VISIBLE
            updateCollapseArrow(header, !isVisible)
            WimApp.prefs.edit().putBoolean("section_$prefKey", !isVisible).apply()
        }
    }

    private fun updateCollapseArrow(header: TextView, expanded: Boolean) {
        val text = header.text.toString().replace("  \u25BE", "").replace("  \u25B8", "")
        header.text = if (expanded) "$text  \u25BE" else "$text  \u25B8"
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

        if (overlayOk && accessibilityOk && micOk && (WimApp.apiKey().isNotBlank() || AuthManager.isSignedIn)) {
            findViewById<TextView>(R.id.statusText).text = "Ready \u2014 open any app and start talking"
        }
    }
}
