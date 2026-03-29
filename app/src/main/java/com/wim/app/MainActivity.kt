package com.wim.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val webClientId = "787543109204-4j4k9vtqq0282qa2sfpl7v6m7iaccojn.apps.googleusercontent.com"

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

        // ── Sessions (collapsible) ───────────────────────
        setupCollapsible(R.id.headerSessions, R.id.contentSessions, "sessions_section", defaultExpanded = false)
        loadSessions()

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
        loadSessions()
    }

    private fun loadSessions() {
        val container = findViewById<LinearLayout>(R.id.sessionsContainer)
        container.removeAllViews()

        val history = SessionHistory(this)
        val sessions = history.getRecentSessions(50)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dpScale = resources.displayMetrics.density

        if (sessions.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No sessions yet"
                setTextColor(getColor(R.color.text_dim))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding((14 * dpScale).toInt(), (10 * dpScale).toInt(), (14 * dpScale).toInt(), (10 * dpScale).toInt())
            }
            container.addView(empty)
            return
        }

        for (session in sessions) {
            val card = buildSessionCard(session, timeFormat, dpScale)
            container.addView(card)
        }
    }

    private fun buildSessionCard(session: SessionHistory.Session, timeFormat: SimpleDateFormat, dp: Float): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.session_card_bg)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
        }

        // Top row: timestamp + badges
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        }

        // Timestamp
        val timeText = TextView(this).apply {
            text = timeFormat.format(Date(session.timestamp))
            setTextColor(0xFF888890.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(timeText)

        // Badges: SAFE/FAST, L#, TONE, situation, timing
        val mode = if (session.falconApproved) "SAFE" else "RAW"
        val sitLabel = if (session.situation != "default") session.situation.uppercase().replace("_", " ") else "NONE"
        val badges = listOf(
            Pair(mode, false),
            Pair("L${session.layer}", false),
            Pair(session.tone.uppercase().take(3), true),
            Pair(sitLabel, false),
            Pair("${session.reconMs}MS", false)
        )

        for ((label, isRed) in badges) {
            val badge = TextView(this).apply {
                text = label
                textSize = 8f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(0xFFDDDDDD.toInt())
                setBackgroundResource(if (isRed) R.drawable.badge_bg_red else R.drawable.badge_bg)
                setPadding((5 * dp).toInt(), (2 * dp).toInt(), (5 * dp).toInt(), (2 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (3 * dp).toInt() }
            }
            topRow.addView(badge)
        }

        card.addView(topRow)

        // Output row: OUT label + text
        val outputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val outLabel = TextView(this).apply {
            text = "OUT"
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFEE5A24.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
        }
        outputRow.addView(outLabel)

        val outputText = TextView(this).apply {
            text = session.cleanText
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        outputRow.addView(outputText)

        card.addView(outputRow)

        // Copy on tap
        card.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("wim", session.cleanText))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        return card
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
