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
import android.widget.FrameLayout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
    private val layerIds = listOf(R.id.layer1, R.id.layer2, R.id.layer3, R.id.layer4)
    private val layerLabelIds = listOf(R.id.layer1Label, R.id.layer2Label, R.id.layer3Label, R.id.layer4Label)
    private val layerValues = listOf(1, 2, 3, 4)

    private val situationIds = listOf(R.id.sitDefault, R.id.sitHighStress, R.id.sitReading)
    private val situationLabelIds = listOf(R.id.sitDefaultLabel, R.id.sitHighStressLabel, R.id.sitReadingLabel)
    private val situationValues = listOf("default", "high_stress", "reading")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Onboarding disabled — rebuilding
        // WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()

        setContentView(R.layout.activity_main)

        // Request notification permission (Android 13+) — keeps foreground service alive
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }

        // Ambient center glow — subtle radiating pulse
        findViewById<View>(R.id.ambientGlow)?.let { glow ->
            val dur = 4000L
            val interp = android.view.animation.AccelerateDecelerateInterpolator()
            fun anim(prop: String, from: Float, to: Float) =
                android.animation.ObjectAnimator.ofFloat(glow, prop, from, to).apply {
                    duration = dur; repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE; interpolator = interp
                }
            android.animation.AnimatorSet().apply {
                playTogether(anim("scaleX", 0.75f, 1.25f), anim("scaleY", 0.75f, 1.25f), anim("alpha", 0.55f, 0.15f))
                start()
            }
        }

        // Reset section prefs once after layout changes (v2)
        val layoutVersion = WimApp.prefs.getInt("layout_version", 0)
        if (layoutVersion < 2) {
            val editor = WimApp.prefs.edit()
            listOf("sessions_section", "tone_section", "layer_section",
                "situation_section", "bubble_section", "daf_section", "permissions_section"
            ).forEach { editor.remove("section_$it") }
            editor.putInt("layout_version", 2).apply()
        }

        // ── Google Sign-In ───────────────────────────────
        val signedOutSection = findViewById<LinearLayout>(R.id.signedOutSection)
        val signedInSection = findViewById<LinearLayout>(R.id.signedInSection)
        val googleSignInBtn = findViewById<Button>(R.id.googleSignInBtn)
        val signOutBtn = findViewById<TextView>(R.id.signOutBtn)
        val userEmail = findViewById<TextView>(R.id.userEmail)
        val userTier = findViewById<TextView>(R.id.userTier)


        fun updateAuthUI() {
            val user = AuthManager.currentUser
            if (user != null) {
                signedOutSection.visibility = View.GONE
                signedInSection.visibility = View.VISIBLE
                userEmail.text = user.email ?: user.uid.take(12)
                userTier.text = "Free tier"
                // Hide API key section — consumer doesn't need it
            } else {
                signedOutSection.visibility = View.VISIBLE
                signedInSection.visibility = View.GONE
            }
        }

        updateAuthUI()

        // Web client ID from google-services.json (oauth_client type 3)
        val webClientId = getString(R.string.default_web_client_id)

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

        // ── Sessions (collapsible) ───────────────────────
        setupCollapsible(R.id.headerSessions, R.id.contentSessions, "sessions_section", defaultExpanded = false)
        // Apply maroon background + grille mesh overlay
        val sessionsPanel = findViewById<FrameLayout>(R.id.sessionsPanel)
        val panelBg = getDrawable(R.drawable.sessions_panel_bg)!!
        val grille = GrilleDrawable()
        sessionsPanel.background = android.graphics.drawable.LayerDrawable(arrayOf(panelBg, grille))
        loadSessions()

        // Stats cells → tap to expand and scroll to relevant section
        fun statClickExpand(statViewId: Int, headerId: Int, contentId: Int, sectionKey: String) {
            findViewById<View>(statViewId)?.setOnClickListener {
                val header = findViewById<TextView>(headerId) ?: return@setOnClickListener
                val content = findViewById<View>(contentId) ?: return@setOnClickListener
                if (content.visibility != View.VISIBLE) {
                    content.visibility = View.VISIBLE
                    updateCollapseArrow(header, true)
                    WimApp.prefs.edit().putBoolean("section_$sectionKey", true).apply()
                }
                header.post {
                    val sv = findScrollView(findViewById<ViewGroup>(android.R.id.content))
                    sv?.smoothScrollTo(0, header.top)
                }
            }
        }
        // Sessions stat → Sessions
        statClickExpand(R.id.statSessions, R.id.headerSessions, R.id.contentSessions, "sessions_section")
        // WPM stat → Sessions (detailed view)
        statClickExpand(R.id.statWpm, R.id.headerSessions, R.id.contentSessions, "sessions_section")
        // Cleanup/Words stat → Layer
        statClickExpand(R.id.statCleanup, R.id.headerLayer, R.id.contentLayer, "layer_section")
        // 4th stat → Settings
        statClickExpand(R.id.statFourth, R.id.headerSettings, R.id.contentSettings, "settings_section")

        // Export all sessions
        findViewById<TextView>(R.id.exportBtn)?.setOnClickListener {
            val history = SessionHistory(this)
            val all = history.getRecentSessions(10000)
            if (all.isEmpty()) {
                Toast.makeText(this, "No sessions to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sb = StringBuilder("WiM Session Export\n${"=".repeat(40)}\n\n")
            val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            for (s in all) {
                sb.append("[${tf.format(java.util.Date(s.timestamp))}] L${s.layer} ${s.tone}\n")
                sb.append("${s.cleanText}\n\n")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sb.toString())
                putExtra(Intent.EXTRA_SUBJECT, "WiM Sessions Export")
            }
            startActivity(Intent.createChooser(intent, "Export sessions"))
        }

        // Session search
        findViewById<EditText>(R.id.sessionSearch)?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                loadSessions(s?.toString()?.trim())
            }
        })

        // ── Tone selection (TextViews) ───────────────────────
        // Tone auto-detected — no manual selection needed

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
        styleToggle(switchDaf)

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

        // ── Gold SeekBars: gold thumb, gold filled track, gray unfilled track ──
        val copperGold = android.content.res.ColorStateList.valueOf(getColor(R.color.copper_gold))
        listOf(R.id.bubbleOpacitySeek, R.id.bubbleSizeSeek, R.id.seekDafDelay).forEach { id ->
            findViewById<SeekBar>(id)?.apply {
                thumbTintList = copperGold
                // Force-reload XML drawable to bypass Material theme tinting
                progressDrawable = resources.getDrawable(R.drawable.daf_seekbar_track, theme)
            }
        }

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
        // Tone section removed — auto-detected by app context
        setupCollapsible(R.id.headerLayer, R.id.contentLayer, "layer_section", defaultExpanded = true)
        setupCollapsible(R.id.headerSituation, R.id.contentSituation, "situation_section", defaultExpanded = false)
        // Bubble section moved to Settings
        setupCollapsible(R.id.headerDaf, R.id.contentDaf, "daf_section", defaultExpanded = false)
        setupCollapsible(R.id.headerPermissions, R.id.contentPermissions, "permissions_section", defaultExpanded = true)
        setupCollapsible(R.id.headerSettings, R.id.contentSettings, "settings_section", defaultExpanded = false)

        // ── Auto-collapse sections on scroll ──
        // Find the ScrollView in our split layout (fixed top + scrollable bottom)
        val scrollView = findScrollView(findViewById<ViewGroup>(android.R.id.content))
        val settingsHeader = findViewById<TextView>(R.id.headerSettings)
        scrollView?.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // One-directional sticky: Settings header can go down but not off-screen top
            settingsHeader?.let { header ->
                val headerTop = header.top
                if (headerTop < scrollY) {
                    // Header would be off-screen — pin it
                    header.translationY = (scrollY - headerTop).toFloat()
                    header.elevation = 8f
                } else {
                    // Header is naturally visible — no translation
                    header.translationY = 0f
                    header.elevation = 4f
                }
            }
        }

        // ── Settings: tips toggle ──
        val tipsToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.tipsToggle)
        tipsToggle.isChecked = WimApp.prefs.getBoolean("tips_enabled", true)
        styleToggle(tipsToggle)
        tipsToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("tips_enabled", checked).apply()
        }

        // ── FAST mode toggle ──
        val fastToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.fastModeToggle)
        fastToggle.isChecked = WimApp.prefs.getBoolean("fast_mode", false)
        styleToggle(fastToggle)
        fastToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("fast_mode", checked).apply()
        }

        // ── Languages ──
        val primaryLang = findViewById<EditText>(R.id.primaryLangInput)
        val secondaryLang = findViewById<EditText>(R.id.secondaryLangInput)
        primaryLang?.setText(WimApp.prefs.getString("primary_language", "en-US") ?: "en-US")
        secondaryLang?.setText(WimApp.prefs.getString("secondary_language", "") ?: "")
        primaryLang?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) WimApp.prefs.edit().putString("primary_language", primaryLang.text.toString().trim().ifBlank { "en-US" }).apply()
        }
        secondaryLang?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) WimApp.prefs.edit().putString("secondary_language", secondaryLang.text.toString().trim()).apply()
        }

        // ── Your name (for name vibration) ──
        val nameInput = findViewById<EditText>(R.id.userNameInput)
        nameInput?.setText(WimApp.prefs.getString("user_name", "") ?: "")
        nameInput?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) WimApp.prefs.edit().putString("user_name", nameInput.text.toString().trim()).apply()
        }

        // ── Voice shortcuts ──
        val shortcutsInput = findViewById<EditText>(R.id.shortcutsInput)
        shortcutsInput?.setText(WimApp.prefs.getString("voice_shortcuts", "") ?: "")
        shortcutsInput?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) WimApp.prefs.edit().putString("voice_shortcuts", shortcutsInput.text.toString()).apply()
        }

        // ── Type-to-speak ──
        val tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status != android.speech.tts.TextToSpeech.SUCCESS) Log.e("WiM", "TTS init failed")
        }
        findViewById<TextView>(R.id.ttsSpeak)?.setOnClickListener {
            val text = findViewById<EditText>(R.id.ttsInput)?.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isNotBlank()) tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "wim_tts")
        }

        // ── Double-shake toggle ──
        val shakeToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.shakeToggle)
        shakeToggle?.isChecked = WimApp.prefs.getBoolean("shake_enabled", true)
        styleToggle(shakeToggle!!)
        shakeToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("shake_enabled", checked).apply()
        }

        // ── Auto-tone toggle ──
        val autoToneToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.autoToneToggle)
        autoToneToggle?.isChecked = WimApp.prefs.getBoolean("auto_tone", true)
        styleToggle(autoToneToggle!!)
        autoToneToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("auto_tone", checked).apply()
        }

        // ── Cloud backup toggle ──
        val cloudToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.cloudBackupToggle)
        cloudToggle?.isChecked = WimApp.prefs.getBoolean("cloud_backup", true)
        styleToggle(cloudToggle!!)
        cloudToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("cloud_backup", checked).apply()
        }

        // ── DAF in Settings ──
        val settingsDafToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.settingsDafToggle)
        val settingsDafDelay = findViewById<SeekBar>(R.id.settingsDafDelay)
        val settingsDafValue = findViewById<TextView>(R.id.settingsDafValue)
        settingsDafToggle?.isChecked = WimApp.dafEnabled()
        styleToggle(settingsDafToggle!!)
        val savedDafDelay = WimApp.dafDelay().coerceIn(30, 300)
        settingsDafDelay?.progress = savedDafDelay - 30
        settingsDafValue?.text = "$savedDafDelay ms"
        settingsDafToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("daf_enabled", checked).apply()
        }
        settingsDafDelay?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = progress + 30
                settingsDafValue?.text = "$ms ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val ms = (seekBar?.progress ?: 70) + 30
                WimApp.prefs.edit().putInt("daf_delay", ms).apply()
            }
        })

        // ── Layer selector in Settings ──
        val sLayerBtns = listOf(
            findViewById<TextView>(R.id.settingsLayer1),
            findViewById<TextView>(R.id.settingsLayer2),
            findViewById<TextView>(R.id.settingsLayer3),
            findViewById<TextView>(R.id.settingsLayer4)
        )
        val curLayer = WimApp.layer()
        sLayerBtns.forEachIndexed { i, btn ->
            val layerVal = i + 1
            btn?.setTextColor(if (layerVal == curLayer) 0xFFEE5A24.toInt() else 0xFF888890.toInt())
            btn?.setOnClickListener {
                WimApp.prefs.edit().putInt("layer", layerVal).apply()
                sLayerBtns.forEach { b -> b?.setTextColor(0xFF888890.toInt()) }
                btn.setTextColor(0xFFEE5A24.toInt())
                loadSessions()
            }
        }


        // ── Profile overlay (iron curtain) ───────────────
        setupProfileOverlay()

        // ── Dev: long-press profile strip to test bubble functions ──
        findViewById<View>(R.id.profileStrip)?.setOnLongClickListener {
            BubbleService.instance?.showClarifier("WORK?", "Is this for work or casual?") { answer ->
                Log.i("WiM", "Clarifier answer: $answer")
            }
            true
        }

        // ── Guide button — opens operations manual overlay ──
        findViewById<View>(R.id.guideBtn)?.setOnClickListener { showGuide() }

        // ── Dark/light toggle — Edison bulb in brand plate ──
        // Edison bulb removed
        // Dark/light toggle removed — dark mode only

        // ── Permissions ────────────────────────────────────
        val overlayBtn = findViewById<View>(R.id.overlayPermBtn)
        val accessibilityBtn = findViewById<View>(R.id.accessibilityPermBtn)
        val micBtn = findViewById<View>(R.id.micPermBtn)

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

        // Request battery optimization exemption so Samsung/OEMs don't kill the bubble
        requestBatteryExemption()

        // Check notification channel is enabled — needed for bubble recall
        checkNotificationChannel()

        // ── Privacy Policy ────────────────────────────────
        findViewById<TextView>(R.id.privacyLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gugosf114/lavrentiy/blob/main/PRIVACY.md")))
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        loadSessions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ensureBubbleRunning()
            loadSessions()
        }
    }

    private fun loadSessions(query: String? = null) {
        val container = findViewById<LinearLayout>(R.id.sessionsContainer)
        container.removeAllViews()

        val history = SessionHistory(this)
        val sessions = history.getRecentSessions(50, query)

        // Compute stats
        val totalSessions = sessions.size
        val totalWords = sessions.sumOf { it.cleanText.split("\\s+".toRegex()).filter { w -> w.isNotBlank() }.size }
        val totalAsrMs = sessions.sumOf { it.asrMs }
        val wpm = if (totalAsrMs > 0) (totalWords * 60000.0 / totalAsrMs).toInt() else 0

        // Average cleanup %: how much disfluency/reconstruction changed across all sessions
        val avgCleanup = if (sessions.isNotEmpty()) {
            val total = sessions.sumOf { s ->
                val rw = s.rawText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                val cw = s.cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                if (rw > 0) ((rw - cw).toFloat() / rw * 100).toInt() else 0
            }
            total / sessions.size
        } else 0

        findViewById<TextView>(R.id.statSessions)?.text = totalSessions.toString()
        findViewById<TextView>(R.id.statWpm)?.text = wpm.toString()

        // Third stat: layer-dependent
        val currentLayer = WimApp.layer()
        if (currentLayer >= 2) {
            findViewById<TextView>(R.id.statCleanup)?.text = "${avgCleanup}%"
            findViewById<TextView>(R.id.statThirdLabel)?.text = "CLEANUP"
        } else {
            findViewById<TextView>(R.id.statCleanup)?.text = "%,d".format(totalWords)
            findViewById<TextView>(R.id.statThirdLabel)?.text = "WORDS"
        }
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

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = dateFormat.format(java.util.Date())
        val yesterday = dateFormat.format(java.util.Date(System.currentTimeMillis() - 86400000))
        var lastDateLabel = ""

        for (session in sessions) {
            val sessionDate = dateFormat.format(java.util.Date(session.timestamp))
            if (sessionDate != lastDateLabel) {
                lastDateLabel = sessionDate
                val label = when (sessionDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> SimpleDateFormat("MMMM d", Locale.getDefault()).format(java.util.Date(session.timestamp))
                }
                val header = TextView(this).apply {
                    text = label
                    setTextColor(getColor(R.color.copper_gold))
                    textSize = 10f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    letterSpacing = 0.1f
                    setPadding((4 * dpScale).toInt(), (8 * dpScale).toInt(), 0, (4 * dpScale).toInt())
                }
                container.addView(header)
            }
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
            Pair("${if (session.totalMs > 0) session.totalMs else session.reconMs}MS", false)
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
            // First sentence bold (summary), rest normal
            val fullText = session.cleanText
            val firstDot = fullText.indexOfAny(charArrayOf('.', '!', '?'))
            if (firstDot > 0 && firstDot < fullText.length - 1) {
                val styled = android.text.SpannableStringBuilder(fullText)
                styled.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, firstDot + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = styled
            } else {
                text = fullText
                setTypeface(null, Typeface.BOLD)
            }
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        outputRow.addView(outputText)

        card.addView(outputRow)

        // Metrics row: edit distance, disfluency ratio, timing breakdown
        val rawWords = session.rawText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanWords = session.cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val rawCount = rawWords.size
        val cleanCount = cleanWords.size
        val stripped = rawCount - cleanCount
        val editPct = if (rawCount > 0) ((stripped.toFloat() / rawCount) * 100).toInt() else 0
        val wer = SessionHistory(this).computeWer(session.rawText, session.cleanText)
        val werPct = (wer * 100).toInt()

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        // asr | recon | val timing
        val timingParts = mutableListOf("asr:${session.asrMs}ms")
        if (session.reconMs > 0) timingParts.add("recon:${session.reconMs}ms")
        if (session.totalMs > 0) timingParts.add("total:${session.totalMs}ms")

        val metricsText = TextView(this).apply {
            text = "${timingParts.joinToString("  ")}  WER:${werPct}%  (${rawCount}w\u2192${cleanCount}w)"
            setTextColor(0xFF666670.toInt())
            textSize = 9f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        metricsRow.addView(metricsText)

        // Risk flag badges if any
        if (session.riskFlags.isNotBlank()) {
            for (flag in session.riskFlags.split(",").filter { it.isNotBlank() }) {
                val flagBadge = TextView(this).apply {
                    text = flag.trim().uppercase().replace("_", " ")
                    textSize = 7f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(0xFFFFAAAA.toInt())
                    setBackgroundResource(R.drawable.badge_bg_red)
                    setPadding((4 * dp).toInt(), (1 * dp).toInt(), (4 * dp).toInt(), (1 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (3 * dp).toInt() }
                }
                metricsRow.addView(flagBadge)
            }
        }

        card.addView(metricsRow)

        // Action row: copy + share
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val copyBtn = TextView(this).apply {
            text = "COPY"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(getColor(R.color.copper_gold))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("wim", session.cleanText))
                Toast.makeText(this@MainActivity, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(copyBtn)

        val shareBtn = TextView(this).apply {
            text = "SHARE"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFEE5A24.toInt())
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, session.cleanText)
                }
                startActivity(Intent.createChooser(intent, "Share transcription"))
            }
        }
        actionRow.addView(shareBtn)

        card.addView(actionRow)

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
                // Situation auto-config
                if (prefKey == "situation" && values[i] == "high_stress") {
                    WimApp.prefs.edit().putInt("layer", 4).putBoolean("daf_enabled", true).apply()
                    loadSessions() // refresh stats for new layer
                }
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
                loadSessions() // refresh stats — third cell changes per layer
            }
        }
    }

    // ── Visual state: selected = dark red pressed, unselected = aluminum raised ──

    private fun setCardSelected(view: TextView, selected: Boolean) {
        if (selected) {
            view.setBackgroundResource(R.drawable.card_bg_selected)
        } else {
            view.setBackgroundResource(R.drawable.card_bg_pressable)
        }
        // Build text with clarifier from tag
        val rawText = view.text.toString().split("  ").first().trim()
        val clarifier = view.tag as? String
        val labelColor = if (selected) getColor(R.color.active_text) else getColor(R.color.text_dim)
        if (clarifier != null) {
            val full = "$rawText  $clarifier"
            val spannable = android.text.SpannableString(full)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(labelColor),
                0, rawText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.copper_gold)),
                rawText.length, full.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            view.text = spannable
        } else {
            view.setTextColor(labelColor)
        }
    }

    private fun setLayoutSelected(container: ViewGroup, label: TextView, selected: Boolean) {
        if (selected) {
            container.setBackgroundResource(R.drawable.card_bg_selected)
            label.setTextColor(getColor(R.color.active_text))
        } else {
            container.setBackgroundResource(R.drawable.card_bg_pressable)
            label.setTextColor(getColor(R.color.text_dim))
        }
    }

    private fun setupCollapsible(headerId: Int, contentId: Int, prefKey: String, defaultExpanded: Boolean = true) {
        val header = findViewById<TextView>(headerId)
        val content = findViewById<View>(contentId)

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

    private fun findScrollView(view: android.view.View?): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findScrollView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun updateCollapseArrow(header: TextView, expanded: Boolean) {
        val title = header.text.toString()
            .replace("  \u25BC", "").replace("  \u25B6", "")
            .replace("  \u25BE", "").replace("  \u25B8", "")
            .replace("  +", "").replace("  \u2212", "")
            .trim()
        header.text = title
        // Pin +/− toggle to right edge using drawableEnd
        val symbol = if (expanded) "\u2212" else "+"
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = header.textSize * 1.1f
            color = getColor(R.color.orange_label)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val w = paint.measureText(symbol).toInt() + 4
        val h = (paint.textSize + 4).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawText(symbol, 2f, paint.textSize, paint)
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        drawable.setBounds(0, 0, w, h)
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)
    }

    // ── Operations Manual (Лаврентий-style guide overlay) ──────────

    private fun showGuide() {
        val dp = resources.displayMetrics.density
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xFF1a1a2e.toInt() else 0xFFe8e8ec.toInt()
        val cardColor = if (isNight) 0xFF252530.toInt() else 0xFFd8d8dc.toInt()
        val textColor = if (isNight) 0xFFe0e0e4.toInt() else 0xFF2a2a30.toInt()
        val dimColor = if (isNight) 0xFF8a8a92.toInt() else 0xFF666670.toInt()
        val accentColor = 0xFFEE5A24.toInt()

        val sections = listOf(
            Triple("How It Works", "What happens when you tap the bubble", """
<b><font color="#EE5A24">1. You speak</font></b><br>Tap the bubble to start recording. Your voice is captured through the microphone.<br><br>
<b><font color="#EE5A24">2. On-device transcription</font></b><br>Android's built-in speech recognition converts your voice to text, right on your phone. No internet needed for this step, no cost.<br><br>
<b><font color="#EE5A24">3. Quick cleanup</font></b><br>Obvious disfluencies are stripped automatically (instant, free):<br>
&#8226; Repeated syllables: "p-p-pop" &#8594; "pop"<br>
&#8226; Repeated words: "I I I want" &#8594; "I want"<br>
&#8226; Fillers: "um", "uh", "like", "you know"<br>
&#8226; False starts and prolongations<br>
Natural phrases like "bye bye" are kept.<br><br>
<b><font color="#EE5A24">4. Depends on your Layer:</font></b><br><br>
<b>Layer I</b> &#8212; Done. Cleaned text is pasted. No AI, no cost.<br><br>
<b>Layer II</b> &#8212; GPT rewrites using your tone (casual, professional, etc). No personal data used.<br><br>
<b>Layer III</b> &#8212; Same as II, plus GPT knows your preferred words and learned corrections.<br><br>
<b>Layer IV</b> &#8212; Full stutter-aware engine. Stronger AI model, plus your personal phoneme map, avoidance patterns, and block detection.<br><br>
<b><font color="#EE5A24">5. Safety check</font></b><br>A second AI verifies the meaning wasn't changed. If it was, your original text is used instead. Numbers and dollar amounts are double-checked.<br><br>
<b><font color="#EE5A24">6. Paste</font></b><br>The final text is injected into whatever text field you're typing in.""".trimIndent()),

            Triple("The Bubble", "Your floating control center", """
<b>Tap</b> &#8212; Start recording. Tap again to stop and process.<br><br>
<b>Hold</b> &#8212; Record while held, release to process.<br><br>
<b>Double-tap</b> &#8212; Open the settings menu (opacity, size, live transcript).<br><br>
<b>Drag</b> &#8212; Move the bubble anywhere on screen.<br><br>
<b>Bezel tap (left/right)</b> &#8212; Quick toggle between work mode (professional) and personal mode (casual).<br><br>
The bubble shows your current layer (L1-L4) and tone (CAS/PRO/FRI/FOR). During recording: <b>REC</b>. During processing: <b>PROC</b>.<br><br>
<b>Double-tap menu</b> also lets you change layers without opening the app.""".trimIndent()),

            Triple("Layers", "What each layer does", """
<b>I. Transcribe</b> &#8212; On-device speech-to-text + disfluency filter. Free, instant, no internet after setup.<br><br>
<b>II. Reconstruct</b> &#8212; GPT cleans grammar, strips fillers, applies your tone. ~1-2s.<br><br>
<b>III. Profile</b> &#8212; Same as II + your personal vocabulary and corrections. The app learns which words you prefer over time.<br><br>
<b>IV. Stutter</b> &#8212; Full clinical mode. Stronger AI model, disfluency-aware prompts, phoneme difficulty map, avoidance detection.""".trimIndent()),

            Triple("Smart Tone", "Automatic context detection", """
WiM detects which app you're in and adjusts the tone automatically:<br><br>
<b>Work apps</b> (Gmail, Slack, Teams, LinkedIn) &#8594; Professional tone<br>
<b>Personal apps</b> (WhatsApp, Messenger, Telegram) &#8594; Casual tone<br><br>
No manual switching needed. The bubble shows your current tone underneath the layer number.<br><br>
Four tones available: <b>Casual</b>, <b>Professional</b>, <b>Friend</b>, <b>Formal</b>. Tone only affects Layers II+.""".trimIndent()),

            Triple("Situations", "Context-aware adjustments", """
<b>Default</b> &#8212; Everyday use. Balanced cleanup.<br>
<b>High Stress</b> &#8212; Phone calls, presentations, interviews. More aggressive cleanup, expects heavier disfluencies.<br>
<b>Reading</b> &#8212; Reading aloud. Light touch, minimal changes.""".trimIndent()),

            Triple("DAF", "Delayed Auditory Feedback", """
Plays your voice back to you with a slight delay (30-300ms). This is a well-known fluency technique &#8212; hearing yourself on a delay can reduce stuttering severity.<br><br>
Toggle it on, adjust the delay with the slider. Works alongside recording.""".trimIndent()),

            Triple("Tips", "Get the most out of WiM", """
&#8226; Keep the bubble <b>visible</b> &#8212; you don't need the app open to use it.<br>
&#8226; <b>Layer I</b> is free and instant &#8212; great for quick dictation.<br>
&#8226; <b>Double-tap</b> the bubble to adjust opacity if it's in the way.<br>
&#8226; The app <b>learns from you</b> at Layer III+ &#8212; corrections get smarter over time.<br>
&#8226; Tap any <b>session card</b> to copy the text.""".trimIndent()),

            Triple("Having Trouble?", "If the bubble disappears", """
<b>Bubble disappeared?</b><br>
Open this app &#8212; it will restart automatically if permissions are still granted.<br><br>
<b>Bubble keeps disappearing?</b><br>
Your phone may be killing WiM to save battery. Fix it once and forget:<br><br>
&#8226; Go to <b>Settings &#8594; Battery</b> (or Battery and device care)<br>
&#8226; Tap <b>Background usage limits</b><br>
&#8226; Tap <b>Never sleeping apps</b><br>
&#8226; Add <b>What I Meant</b> to the list<br><br>
This tells your phone to stop shutting WiM down. You only need to do this once.<br><br>
<b>Bubble gone after reboot?</b><br>
WiM restarts automatically after your phone turns back on, as long as the accessibility service is still enabled. If it doesn't, open the app once.<br><br>
<b>Text not pasting?</b><br>
Make sure a text field is active (cursor blinking) before you speak. WiM needs a place to put the text.""".trimIndent()),
        )

        // Build overlay
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener { (parent as? android.view.ViewGroup)?.removeView(this) }
        }

        val panel = ScrollView(this)
        panel.layoutParams = FrameLayout.LayoutParams(
            (340 * dp).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.END
            topMargin = (40 * dp).toInt()
            bottomMargin = (40 * dp).toInt()
        }
        panel.setBackgroundColor(bgColor)
        panel.isVerticalScrollBarEnabled = false
        panel.setOnClickListener { /* consume click, don't close */ }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (40 * dp).toInt())
        }

        // Close button
        content.addView(TextView(this).apply {
            text = "✕ Close"
            setTextColor(getColor(R.color.copper_gold))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.END
            setPadding(0, 0, 0, (8 * dp).toInt())
            setOnClickListener {
                val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
                root.removeView(overlay)
            }
        })

        // Header
        content.addView(TextView(this).apply {
            text = "Guide"
            setTextColor(textColor)
            textSize = 20f
            typeface = Typeface.create("serif", Typeface.BOLD)
            letterSpacing = 0.08f
        })
        content.addView(TextView(this).apply {
            text = "OPERATIONS MANUAL"
            setTextColor(accentColor)
            textSize = 8f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
            setPadding(0, (2 * dp).toInt(), 0, (16 * dp).toInt())
        })

        // Accordion sections
        for ((title, subtitle, body) in sections) {
            val sectionHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt())
                setBackgroundColor(cardColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() }
            }

            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleCol.addView(TextView(this).apply {
                text = title
                setTextColor(textColor)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            titleCol.addView(TextView(this).apply {
                text = subtitle
                setTextColor(dimColor)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.05f
            })
            sectionHeader.addView(titleCol)

            val chevron = TextView(this).apply {
                text = "\u25B6"
                setTextColor(dimColor)
                textSize = 10f
            }
            sectionHeader.addView(chevron)

            val bodyView = TextView(this).apply {
                text = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_COMPACT)
                setTextColor(dimColor)
                textSize = 11f
                setLineSpacing(0f, 1.5f)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
                visibility = View.GONE
            }

            sectionHeader.setOnClickListener {
                if (bodyView.visibility == View.GONE) {
                    bodyView.visibility = View.VISIBLE
                    chevron.text = "\u25BC"
                    chevron.setTextColor(accentColor)
                } else {
                    bodyView.visibility = View.GONE
                    chevron.text = "\u25B6"
                    chevron.setTextColor(dimColor)
                }
            }

            content.addView(sectionHeader)
            content.addView(bodyView)
        }

        panel.addView(content)
        overlay.addView(panel)

        val root = findViewById<View>(android.R.id.content) as FrameLayout
        root.addView(overlay)
    }

    private fun checkNotificationChannel() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channel = nm.getNotificationChannel("wim_bubble")
        if (channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            // User disabled the notification channel — bubble recall won't work
            Toast.makeText(this, "WiM notifications are disabled — you won't be able to recall the bubble. Please re-enable in Settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "wim_bubble")
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } catch (e: Exception) {
                Log.e("WiM", "Battery exemption request failed: ${e.message}")
            }
        }
    }

    private fun styleToggle(toggle: MaterialSwitch) {
        toggle.thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.copper_gold))
        toggle.trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFFB8B8C0.toInt(), 0xFF555560.toInt())
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("$packageName/${WimAccessibilityService::class.java.canonicalName}")
    }

    private fun ensureBubbleRunning() {
        if (!Settings.canDrawOverlays(this)) return
        if (!isAccessibilityEnabled()) return
        // Send SHOW regardless — if service is running but bubble is hidden, this brings it back
        try {
            startForegroundService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
            Log.i("WiM", "Bubble started/shown from MainActivity")
        } catch (e: Exception) {
            Log.e("WiM", "Failed to start bubble: ${e.message}")
        }
    }

    private fun updatePermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val micOk = AudioRecorder(this).hasPermission()

        findViewById<TextView>(R.id.overlayPermLabel)?.text =
            if (overlayOk) "Overlay ✓" else "Overlay"
        findViewById<TextView>(R.id.accessibilityPermLabel)?.text =
            if (accessibilityOk) "Accessibility ✓" else "Accessibility"
        findViewById<TextView>(R.id.micPermLabel)?.text =
            if (micOk) "Microphone ✓" else "Microphone"

        val statusText = findViewById<TextView>(R.id.statusText)
        // statusBulb removed
        // nightMode check removed
        if (overlayOk && accessibilityOk && micOk && (WimApp.apiKey().isNotBlank() || AuthManager.isSignedIn)) {
            statusText.text = "IDLE"
            // bulb removed
            // Start bubble now, and retry after 2s in case accessibility service is still connecting
            ensureBubbleRunning()
            window.decorView.postDelayed({ ensureBubbleRunning() }, 2000)
        } else {
            statusText.text = "SETUP"
            // bulb removed
        }
    }

    // ── Profile overlay (iron curtain) ───────────────────
    private var profileOverlay: View? = null

    private fun setupProfileOverlay() {
        findViewById<View>(R.id.profileStrip)?.setOnClickListener {
            showProfileOverlay()
        }
    }

    private fun showProfileOverlay() {
        if (profileOverlay != null) return

        val rootFrame = findViewById<FrameLayout>(android.R.id.content)
        val overlay = layoutInflater.inflate(R.layout.profile_overlay, rootFrame, false)
        profileOverlay = overlay

        // Slide in from right
        overlay.translationX = rootFrame.width.toFloat()
        rootFrame.addView(overlay)
        overlay.animate().translationX(0f).setDuration(250).start()

        // Close button
        overlay.findViewById<TextView>(R.id.profileClose)?.setOnClickListener {
            dismissProfileOverlay()
        }

        // Load saved profile values and set up tap handlers
        setupProfileSelections(overlay)
    }

    private fun dismissProfileOverlay() {
        val overlay = profileOverlay ?: return
        val rootFrame = findViewById<FrameLayout>(android.R.id.content)
        overlay.animate().translationX(rootFrame.width.toFloat()).setDuration(200).withEndAction {
            rootFrame.removeView(overlay)
            profileOverlay = null
        }.start()
    }

    private fun setupProfileSelections(overlay: View) {
        val prefs = WimApp.prefs

        // ── Usage mode ──
        val usageIds = listOf(R.id.profileWorkOnly, R.id.profileLifeOnly, R.id.profileBoth)
        val usageValues = listOf("work_only", "life_only", "both")
        val savedUsage = prefs.getString("profile_usage", "both") ?: "both"
        val sliderRow = overlay.findViewById<View>(R.id.balanceSliderRow)
        sliderRow?.visibility = if (savedUsage == "both") View.VISIBLE else View.GONE
        setupProfileGroup(overlay, usageIds, usageValues, "profile_usage", savedUsage) { _, value ->
            sliderRow?.visibility = if (value == "both") View.VISIBLE else View.GONE
        }

        // Balance slider
        val balanceSeek = overlay.findViewById<SeekBar>(R.id.balanceSeek)
        val balanceValue = overlay.findViewById<TextView>(R.id.balanceValue)
        val savedBalance = prefs.getInt("profile_balance", 50)
        balanceSeek?.progress = savedBalance
        balanceValue?.text = "${100 - savedBalance}/${savedBalance}"
        balanceSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                balanceValue?.text = "${100 - progress}/${progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val p = seekBar?.progress ?: 50
                prefs.edit().putInt("profile_balance", p).apply()
            }
        })

        // ── Industry ──
        val industryIds = listOf(R.id.indFinance, R.id.indLegal, R.id.indMedical, R.id.indTech, R.id.indSales, R.id.indTrades, R.id.indFood, R.id.indEducation, R.id.indOther)
        val industryValues = listOf("finance", "legal", "medical", "tech", "sales", "trades", "food", "education", "other")
        val savedIndustry = prefs.getString("profile_industry", "") ?: ""
        val otherInput = overlay.findViewById<EditText>(R.id.indOtherInput)
        otherInput?.visibility = if (savedIndustry == "other") View.VISIBLE else View.GONE
        otherInput?.setText(prefs.getString("profile_industry_other", "") ?: "")
        setupProfileGroup(overlay, industryIds, industryValues, "profile_industry", savedIndustry) { _, value ->
            if (value == "other") {
                otherInput?.visibility = View.VISIBLE
                otherInput?.requestFocus()
            } else {
                otherInput?.visibility = View.GONE
            }
        }
        otherInput?.setOnEditorActionListener { _, _, _ ->
            val text = otherInput.text.toString().trim()
            prefs.edit().putString("profile_industry_other", text).apply()
            true
        }

        // Speech section removed — layer selection covers this

        // ── Work hours ──
        val hoursIds = listOf(R.id.hoursMorning, R.id.hoursDay, R.id.hoursEvening, R.id.hoursFlexible)
        val hoursValues = listOf("morning", "nine_to_five", "evening", "flexible")
        val savedHours = prefs.getString("profile_hours", "nine_to_five") ?: "nine_to_five"
        setupProfileGroup(overlay, hoursIds, hoursValues, "profile_hours", savedHours)

        // ── Profile editor: vocabulary, corrections, triggers ──
        val profile = WimApp.profile()
        val vocabField = overlay.findViewById<EditText>(R.id.profileVocabulary)
        vocabField?.setText(profile.getVocabulary().joinToString("\n"))
        vocabField?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val words = vocabField.text.toString().split("\n").map { it.trim() }.filter { it.isNotBlank() }
                words.forEach { profile.addVocabulary(it) }
            }
        }

        val correctionsField = overlay.findViewById<EditText>(R.id.profileCorrections)
        val corrText = profile.getCorrections().entries.joinToString("\n") { "${it.key}=${it.value}" }
        correctionsField?.setText(corrText)
        correctionsField?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                for (line in correctionsField.text.toString().split("\n")) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        profile.addCorrection(parts[0].trim(), parts[1].trim())
                    }
                }
            }
        }

        val triggersField = overlay.findViewById<EditText>(R.id.profileTriggers)
        triggersField?.setText(profile.getTriggerWords().joinToString("\n"))
        triggersField?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val words = triggersField.text.toString().split("\n").map { it.trim() }.filter { it.isNotBlank() }
                // Save triggers to SharedPreferences profile JSON
                val json = prefs.getString("profile_json", null)
                val obj = if (json != null) org.json.JSONObject(json) else org.json.JSONObject()
                obj.put("trigger_words", org.json.JSONArray(words))
                prefs.edit().putString("profile_json", obj.toString()).apply()
            }
        }
    }

    private fun setupProfileGroup(overlay: View, ids: List<Int>, values: List<String>, prefKey: String, current: String, onSelect: ((Int, String) -> Unit)? = null) {
        for (i in ids.indices) {
            val tv = overlay.findViewById<TextView>(ids[i]) ?: continue
            if (values[i] == current) {
                tv.setBackgroundResource(R.drawable.card_bg_selected)
                tv.setTextColor(getColor(R.color.active_text))
            } else {
                tv.setBackgroundResource(R.drawable.card_bg)
                tv.setTextColor(getColor(R.color.text_dim))
            }
            tv.setOnClickListener {
                WimApp.prefs.edit().putString(prefKey, values[i]).apply()
                for (j in ids.indices) {
                    val other = overlay.findViewById<TextView>(ids[j]) ?: continue
                    if (j == i) {
                        other.setBackgroundResource(R.drawable.card_bg_selected)
                        other.setTextColor(getColor(R.color.active_text))
                    } else {
                        other.setBackgroundResource(R.drawable.card_bg)
                        other.setTextColor(getColor(R.color.text_dim))
                    }
                }
                onSelect?.invoke(i, values[i])
            }
        }
    }
}
