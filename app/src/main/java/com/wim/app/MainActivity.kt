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
import androidx.lifecycle.lifecycleScope
import androidx.core.app.ActivityCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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

    private var sessionUpdateUnsub: (() -> Unit)? = null
    private var consoleUnsub: (() -> Unit)? = null
    private var showingConsole = false
    private var tts: android.speech.tts.TextToSpeech? = null
    private var billingManager: PlayBillingManager? = null

    // Session timer (relocated from bubble — shown next to the "Today" header
    // in the Sessions list, with a reset button beside it).
    private var todayTimerView: TextView? = null
    private val sessionTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sessionTimerTick = object : Runnable {
        override fun run() {
            updateTodayTimer()
            sessionTimerHandler.postDelayed(this, 1000)
        }
    }
    private fun updateTodayTimer() {
        val tv = todayTimerView ?: return
        val start = WimApp.prefs.getLong("session_start_time", 0L)
        if (start <= 0L) { tv.text = "0:00"; return }
        val elapsed = (System.currentTimeMillis() - start) / 1000
        val capped = elapsed.coerceAtMost(99 * 60 + 59)
        val mins = capped / 60
        val secs = capped % 60
        tv.text = "%d:%02d".format(mins, secs)
    }

    // Card IDs grouped by section for selection logic
    private val layerIds = listOf(R.id.layer1, R.id.layer2, R.id.layer3, R.id.layer4)
    private var layerSubRowPrefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var l1SourcePrefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val layerLabelIds = listOf(R.id.layer1Label, R.id.layer2Label, R.id.layer3Label, R.id.layer4Label)
    private val layerValues = listOf(1, 2, 3, 4)

    // Situation selection (default / high_stress / reading) was retired Apr 24, 2026.
    // The pref still defaults to "default" via WimApp.situation() and is passed
    // through the reconstruction pipeline; only the UI is gone.

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved dark/light mode before setContentView
        val darkMode = WimApp.prefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)

        // Route to onboarding if first run. The Compose OnboardingActivity is now
        // the canonical first-run flow (was BouncerActivity) — George 2026-06-25.
        // handleComplete() sets onboarding_complete, starts the bubble, and returns
        // here. BouncerActivity is kept for now as a fallback, not deleted yet.
        if (!WimApp.prefs.getBoolean("onboarding_complete", false)) {
            startActivity(Intent(this, com.wim.app.onboarding.OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Request notification permission (Android 13+) — keeps foreground service alive
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }

        // Ambient parallax orbs handle their own rendering + sensor — no setup here.
        // See app/src/main/java/com/wim/app/AmbientOrbsView.kt.

        // Reset section prefs once after layout changes (v2)
        val layoutVersion = WimApp.prefs.getInt("layout_version", 0)
        if (layoutVersion < 2) {
            val editor = WimApp.prefs.edit()
            listOf("sessions_section", "tone_section", "layer_section",
                "bubble_section", "daf_section", "permissions_section"
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
                userTier.text = if (WimApp.cloudUnlocked()) "Cloud unlocked" else "Local / Free"
                findViewById<TextView>(R.id.settingsUserEmail)?.text = user.email ?: user.uid.take(12)
                findViewById<TextView>(R.id.settingsUserTier)?.text = userTier.text
                // Hide API key section — consumer doesn't need it
            } else {
                signedOutSection.visibility = View.VISIBLE
                signedInSection.visibility = View.GONE
                findViewById<TextView>(R.id.settingsUserEmail)?.text = "Not signed in"
                findViewById<TextView>(R.id.settingsUserTier)?.text = "Local / Free"
            }
        }

        updateAuthUI()

        // Web client ID from google-services.json (oauth_client type 3)
        val webClientId = getString(R.string.default_web_client_id)

        googleSignInBtn.setOnClickListener {
            val credentialManager = CredentialManager.create(this)
            // Bottom-sheet path for returning users
            val idOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .build()
            val idRequest = GetCredentialRequest.Builder().addCredentialOption(idOption).build()

            CoroutineScope(Dispatchers.Main).launch {
                val idToken = try {
                    val result = credentialManager.getCredential(this@MainActivity, idRequest)
                    GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                } catch (e: GetCredentialCancellationException) {
                    return@launch
                } catch (e: NoCredentialException) {
                    // Fresh-device path — no returning credential matches, open the full Sign-in-with-Google flow
                    Log.i("WiM", "No bottom-sheet credentials, falling back to full Sign-in-with-Google")
                    try {
                        val siwgOption = GetSignInWithGoogleOption.Builder(webClientId).build()
                        val siwgRequest = GetCredentialRequest.Builder().addCredentialOption(siwgOption).build()
                        val result = credentialManager.getCredential(this@MainActivity, siwgRequest)
                        GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                    } catch (e2: GetCredentialCancellationException) {
                        return@launch
                    } catch (e2: Exception) {
                        Log.e("WiM", "Sign-in fallback failed: ${e2.message}", e2)
                        Toast.makeText(this@MainActivity, "Sign-in error: ${e2.message}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("WiM", "Sign-in error: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "Sign-in error: ${e.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }

                when (val result = AuthManager.signInWithGoogle(idToken)) {
                    is SignInResult.Success -> {
                        Toast.makeText(this@MainActivity, "Signed in as ${result.user.email}", Toast.LENGTH_SHORT).show()
                        updateAuthUI()
                        billingManager?.refresh()
                    }
                    is SignInResult.Failed -> {
                        Toast.makeText(this@MainActivity, result.reason, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        signOutBtn.setOnClickListener {
            AuthManager.signOut()
            WimApp.clearCloudUnlock()
            updateAuthUI()
            billingManager?.refresh()
            Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
        }

        setupCloudBilling()

        // ── Sessions (collapsible) ───────────────────────
        // Sessions panel must be expanded on every app start — operator
        // requirement. User can still collapse during a session via the
        // header tap; next launch pops back open. The pref key needs the
        // "section_" prefix to match what setupCollapsible actually reads.
        WimApp.prefs.edit().putBoolean("section_sessions_section", true).apply()
        setupCollapsible(R.id.headerSessions, R.id.contentSessions, "sessions_section", defaultExpanded = true)
        setupCollapsible(R.id.headerScriptPrep, R.id.contentScriptPrep, "script_prep_section", defaultExpanded = false)
        setupScriptPrep()
        // Apply maroon background + grille mesh overlay
        val sessionsPanel = findViewById<FrameLayout>(R.id.sessionsPanel)
        val panelBg = getDrawable(R.drawable.sessions_panel_bg)!!
        val grille = GrilleDrawable()
        sessionsPanel.background = android.graphics.drawable.LayerDrawable(arrayOf(panelBg, grille))
        loadSessions()

        // Refresh sessions UI after Firestore sync completes (e.g. after sign-in on fresh install)
        WimApp.onSessionsSynced = { loadSessions() }

        // Refresh sessions UI in real time when BubbleService records a new session
        sessionUpdateUnsub = SessionUpdateBus.subscribe { runOnUiThread { loadSessions() } }

        // Console / Sessions toggle
        val tabConsole = findViewById<TextView>(R.id.tabConsole)
        val tabSessions = findViewById<TextView>(R.id.tabSessions)
        val consoleScroll = findViewById<View>(R.id.consoleScroll)
        val sessionsPanelView = findViewById<View>(R.id.sessionsPanel)

        fun switchToConsole() {
            showingConsole = true
            consoleScroll.visibility = View.VISIBLE
            sessionsPanelView.visibility = View.GONE
            tabConsole.setTextColor(getColor(R.color.section_header))
            tabSessions.setTextColor(getColor(R.color.text_secondary))
            loadConsole()
        }
        fun switchToSessions() {
            showingConsole = false
            consoleScroll.visibility = View.GONE
            sessionsPanelView.visibility = View.VISIBLE
            tabSessions.setTextColor(getColor(R.color.section_header))
            tabConsole.setTextColor(getColor(R.color.text_secondary))
        }
        tabConsole.setOnClickListener { switchToConsole() }
        tabSessions.setOnClickListener { switchToSessions() }

        // Live console updates
        consoleUnsub = ConsoleLog.subscribe { runOnUiThread { if (showingConsole) loadConsole() } }

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
        // Double-tap any gauge to reset it
        setupVuDoubleTapReset(R.id.vuSessions, "stat_sessions_reset")
        setupVuDoubleTapReset(R.id.vuWpm, "stat_wpm_reset")
        setupVuDoubleTapReset(R.id.vuCleanup, "stat_cleanup_reset")
        setupVuDoubleTapReset(R.id.vuScore, "filler_count")

        // Summon chip removed — tap the WiM logo in the brand plate to spawn
        // the bubble. Header badges still need a refresh pass.
        refreshHeaderBadges()

        // LogoBarsView was removed pending frontend-design redesign of the
        // equalizer flanking the WiM logo.

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

        // ── Tone selection (4 cards: Casual/Professional/Friend/Formal) ──
        val toneIds = listOf(R.id.toneCasual, R.id.toneProfessional)
        val toneLabelIds = listOf(R.id.toneCasualLabel, R.id.toneProfessionalLabel)
        val toneValues = listOf("casual", "professional")
        setupLayoutGroup(toneIds, toneLabelIds, toneValues, "tone", WimApp.tone())

        // ── Layer selection (LinearLayouts with label child) ─
        val currentLayer = WimApp.layer()
        val layerIdx = layerValues.indexOf(currentLayer).coerceAtLeast(0)
        setupLayoutGroupInt(layerIds, layerLabelIds, layerValues, "layer", layerIdx)

        // Layer I sub-options — engraved nameplate pills (LOCAL / API),
        // hidden by default and revealed only while Transcribe is the
        // active layer. Mirrors the inline LOCAL/CLOUD picker in the
        // bubble menu and keeps both surfaces in sync via the shared
        // `l1_cloud_asr` pref. The reveal/hide uses a soft slide-down
        // (220ms decelerate) / slide-up (160ms accelerate) so the
        // sub-row feels nested under Transcribe rather than abrupt.
        val l1SubRow = findViewById<View>(R.id.layer1SubRow)
        val l1LocalBtn = findViewById<TextView>(R.id.layer1Local)
        val l1CloudBtn = findViewById<TextView>(R.id.layer1Cloud)
        val brandCream = 0xFFC8B48A.toInt()
        val brandCreamBright = 0xFFEDDFC2.toInt()
        fun paintL1Source() {
            val cloud = WimApp.l1CloudAsr()
            // The nameplate_bg selector responds to isSelected with the
            // maroon-fill + cream-highlight border treatment.
            l1LocalBtn?.isSelected = !cloud
            l1CloudBtn?.isSelected = cloud
            l1LocalBtn?.setTextColor(if (!cloud) brandCreamBright else brandCream)
            l1CloudBtn?.setTextColor(if (cloud) brandCreamBright else brandCream)
        }
        paintL1Source()
        l1LocalBtn?.setOnClickListener {
            WimApp.prefs.edit().putBoolean("l1_cloud_asr", false).apply()
            paintL1Source()
        }
        l1CloudBtn?.setOnClickListener {
            if (!WimApp.cloudUnlocked()) {
                requestCloudUnlock()
                return@setOnClickListener
            }
            WimApp.prefs.edit().putBoolean("l1_cloud_asr", true).apply()
            paintL1Source()
        }
        // Cross-surface sync: when the bubble-menu's L1 source picker
        // (or any other write site) changes the pref, repaint here.
        // Without this, the dashboard sub-row goes stale until activity
        // is recreated. Stored in a class field so the listener isn't GC'd.
        val l1Listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "l1_cloud_asr") {
                runOnUiThread { paintL1Source() }
            }
        }
        WimApp.prefs.registerOnSharedPreferenceChangeListener(l1Listener)
        l1SourcePrefListener = l1Listener

        fun setLayer1SubRowVisible(visible: Boolean) {
            val row = l1SubRow ?: return
            val dp = resources.displayMetrics.density
            if (visible) {
                if (row.visibility == View.VISIBLE) return
                row.alpha = 0f
                row.translationY = -8f * dp
                row.visibility = View.VISIBLE
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .start()
            } else {
                if (row.visibility != View.VISIBLE) return
                row.animate()
                    .alpha(0f)
                    .translationY(-8f * dp)
                    .setDuration(160)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                    .withEndAction { row.visibility = View.GONE }
                    .start()
            }
        }
        // Initial state matches the saved layer.
        setLayer1SubRowVisible(WimApp.layer() == 1)
        // Watch the `layer` pref and toggle sub-row visibility whenever
        // it changes. Avoids clobbering setupLayoutGroupInt's existing
        // click handlers (those commit the pref; we just react to it).
        val layerPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "layer") setLayer1SubRowVisible(WimApp.layer() == 1)
        }
        WimApp.prefs.registerOnSharedPreferenceChangeListener(layerPrefListener)
        // Keep a strong reference so it isn't GC'd.
        layerSubRowPrefListener = layerPrefListener

        // ── ACCENT — single MaterialSwitch matching Auto-send pattern. ──
        // ON  = accent_mode=keep (preserve speaker's voice verbatim).
        // OFF = accent_mode=polish (default normalize toward Standard English).
        // Same L1 packs are used both ways at L2/L3; the LLM directive flips.
        val switchAccent = findViewById<MaterialSwitch>(R.id.switchAccent)
        switchAccent?.isChecked = (WimApp.prefs.getString("accent_mode", "polish") == "keep")
        switchAccent?.let { styleToggle(it) }
        switchAccent?.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putString("accent_mode", if (checked) "keep" else "polish").apply()
        }

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

        // Quiet Mode manual force-on. Auto-detect (BubbleService) handles
        // whisper-soft takes on its own; this lets the user force the boost
        // always-on. Reads/writes the same "quiet_mode" pref BubbleService checks.
        val switchQuiet = findViewById<MaterialSwitch>(R.id.switchQuiet)
        switchQuiet.isChecked = WimApp.prefs.getBoolean("quiet_mode", false)
        styleToggle(switchQuiet)
        switchQuiet.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("quiet_mode", checked).apply()
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

        // ── Gold SeekBars ──
        val copperGold = android.content.res.ColorStateList.valueOf(getColor(R.color.copper_gold))
        listOf(R.id.bubbleOpacitySeek, R.id.bubbleSizeSeek, R.id.seekDafDelay).forEach { id ->
            findViewById<SeekBar>(id)?.apply {
                thumbTintList = copperGold
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
        // Layer/Tone/Permissions/Settings are now tile-controlled — they
        // reparent into bottom sheets (Layer/Tone/Permissions) or the
        // openSectionOverlay (Settings) on tile tap. Their inline content
        // must stay GONE; old setupCollapsible calls were toggling them
        // visible from stale SharedPreferences on fresh installs.
        setupCollapsible(R.id.headerDaf, R.id.contentDaf, "daf_section", defaultExpanded = false)
        setupCollapsible(R.id.headerGuide, R.id.contentGuide, "guide_section", defaultExpanded = false)
        buildGuideContent()

        // ── Shimmer FX (per design spec: "flashlight passing over" subtle sweep) ──
        // Two distinct treatments:
        //   • Category header borders — ONE-SHOT on tap. Touch any part of a
        //     category (the header's +/− / label, or any subcategory tile
        //     inside it), the parent header's stroke fires a single light
        //     sweep. No continuous animation (too busy per design).
        //   • Subcategory tile label text — continuous subtle sweep. Stays
        //     always-on; these are the "flashlight across text" effect.
        listOf(
            R.id.headerLayer,
            R.id.headerTone,
            R.id.headerSessions,
            R.id.headerDaf,
            R.id.headerPermissions,
            R.id.headerSettings,
            R.id.headerGuide,
            R.id.headerScriptPrep
        ).forEach { applyHeaderShimmer(it) }

        // Layer tile labels
        listOf(R.id.layer1Label, R.id.layer2Label, R.id.layer3Label, R.id.layer4Label).forEach {
            findViewById<TextView>(it)?.let(ShimmerFx::attachTextShimmer)
        }
        // Tone tile labels
        listOf(R.id.toneCasualLabel, R.id.toneProfessionalLabel).forEach {
            findViewById<TextView>(it)?.let(ShimmerFx::attachTextShimmer)
        }
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

        // ── App language picker (UI + Whisper ASR language) ──
        // Five-way: English, Español, Русский, Português, Français. Picking
        // non-English forces the cloud ASR path (whisper-tiny.en is English-only).
        val appLanguageRow = findViewById<View>(R.id.appLanguageRow)
        val appLanguageValue = findViewById<TextView>(R.id.appLanguageValue)
        fun appLanguageDisplay(code: String) = when (code) {
            "es" -> getString(R.string.lang_spanish)
            "ru" -> getString(R.string.lang_russian)
            "pt" -> getString(R.string.lang_portuguese)
            "fr" -> getString(R.string.lang_french)
            else -> getString(R.string.lang_english)
        }
        appLanguageValue?.text = appLanguageDisplay(WimApp.appLanguage())
        appLanguageRow?.setOnClickListener {
            val codes = arrayOf("en", "es", "ru", "pt", "fr")
            val labels = arrayOf(
                getString(R.string.lang_english),
                getString(R.string.lang_spanish),
                getString(R.string.lang_russian),
                getString(R.string.lang_portuguese),
                getString(R.string.lang_french),
            )
            val current = codes.indexOf(WimApp.appLanguage()).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.lang_picker_dialog_title)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    val picked = codes[which]
                    if (picked != "en" && !WimApp.cloudUnlocked()) {
                        dialog.dismiss()
                        requestCloudUnlock()
                        return@setSingleChoiceItems
                    }
                    if (picked != WimApp.appLanguage()) {
                        WimApp.setAppLanguage(picked)
                        // Force cloud ASR when locale leaves English
                        WimApp.prefs.edit()
                            .putBoolean("l1_cloud_asr", picked != "en")
                            .apply()
                    }
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        // ── Languages ──
        // Removed: standalone primary/secondary language EditText row.
        // The "App language" picker (appLanguageRow) now owns language
        // selection — it writes the same prefs (primary_language /
        // secondary_language) via the picker dialog. Operator flagged
        // the inline EditText row as duplicate 2026-04-30.

        // ── Voice shortcuts ──
        val shortcutsInput = findViewById<EditText>(R.id.shortcutsInput)
        shortcutsInput?.setText(WimApp.prefs.getString("voice_shortcuts", "") ?: "")
        shortcutsInput?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) WimApp.prefs.edit().putString("voice_shortcuts", shortcutsInput.text.toString()).apply()
        }

        // ── Type-to-speak ──
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status != android.speech.tts.TextToSpeech.SUCCESS) Log.e("WiM", "TTS init failed")
        }
        findViewById<TextView>(R.id.ttsSpeak)?.setOnClickListener {
            val text = findViewById<EditText>(R.id.ttsInput)?.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isNotBlank()) tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "wim_tts")
        }

        // ── Bubble color picker ──
        // Row shows 3 inline 28dp circle swatches (actual bubble bezels).
        // Selected swatch is highlighted via a maroon ring + slight scale.
        // Tap a swatch → commits that theme directly. Tap the row label
        // (or any non-swatch area) → opens the picker dialog with full
        // bubble previews including bezel + knob + tick + IDLE label,
        // mirroring the floating bubble exactly.
        val swatchBronze = findViewById<View>(R.id.colorBronze)
        val swatchAluminum = findViewById<View>(R.id.colorAluminum)
        val swatchGunmetal = findViewById<View>(R.id.colorGunmetal)
        fun paintBubbleColorRow() {
            val theme = WimApp.prefs.getString("bubble_theme", "bronze") ?: "bronze"
            // Selected swatch: scale up + opacity 1.0; others: scale down + dim.
            // Cheap, drawable-free way to show selection without needing a
            // selector XML for every bezel drawable.
            val map = mapOf(
                "bronze" to swatchBronze,
                "aluminum" to swatchAluminum,
                "gunmetal" to swatchGunmetal,
            )
            for ((k, v) in map) {
                v ?: continue
                val selected = k == theme
                v.scaleX = if (selected) 1.15f else 0.92f
                v.scaleY = if (selected) 1.15f else 0.92f
                v.alpha = if (selected) 1.0f else 0.6f
            }
        }
        val applyTheme: (String) -> Unit = { theme ->
            WimApp.prefs.edit().putString("bubble_theme", theme).apply()
            BubbleService.instance?.refreshBubbleTheme()
            paintBubbleColorRow()
        }
        paintBubbleColorRow()
        swatchBronze?.setOnClickListener { applyTheme("bronze") }
        swatchAluminum?.setOnClickListener { applyTheme("aluminum") }
        swatchGunmetal?.setOnClickListener { applyTheme("gunmetal") }
        findViewById<View>(R.id.bubbleColorRow)?.setOnClickListener {
            val pickerView = layoutInflater.inflate(R.layout.bubble_color_picker, null)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(pickerView)
                .create()
            dialog.window?.setBackgroundDrawableResource(R.drawable.card_bg)
            pickerView.findViewById<View>(R.id.pickerBronze)?.setOnClickListener {
                applyTheme("bronze"); dialog.dismiss()
            }
            pickerView.findViewById<View>(R.id.pickerAluminum)?.setOnClickListener {
                applyTheme("aluminum"); dialog.dismiss()
            }
            pickerView.findViewById<View>(R.id.pickerGunmetal)?.setOnClickListener {
                applyTheme("gunmetal"); dialog.dismiss()
            }
            dialog.show()
        }

        // ── Cloud backup toggle ──
        val cloudToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.cloudBackupToggle)
        cloudToggle?.isChecked = WimApp.prefs.getBoolean("cloud_backup", false)
        styleToggle(cloudToggle!!)
        cloudToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("cloud_backup", checked).apply()
        }

        // ── Bubble dock — three parked bubble variants (bronze/silver/gunmetal) ──
        setupBubbleDock()
        // ── WiM logo in brand plate = summon — replaces the old SUMMON chip bar ──
        setupLogoSummon()
        // ── Auto-send toggle (green SEND bubble after inject) ──
        setupAutoSendToggle()
        // ── Facehugger Send (bubble jumps to host Send button on SEND_READY) ──
        setupFacehuggerToggle()

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

        // ── Profile overlay (iron curtain) ───────────────
        setupProfileOverlay()

        // ── Dev: long-press the footer Profile button to run the bubble
        // feature parade. (Old profileStrip was hidden when Profile moved
        // to the frozen footer; we attach to footerProfile instead.) The
        // tap opens the profile overlay; long-press fires the same
        // narrated demo script onboarding uses, cycling through every
        // major bubble capability. Tap the bubble at any point to skip.
        findViewById<View>(R.id.footerProfile)?.setOnLongClickListener {
            BubbleService.instance?.runFeatureParade() ?: run {
                Toast.makeText(this, "Bubble service isn't running yet", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // ── Shake to summon/hide toggle ──
        val shakeToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.shakeToggle)
        shakeToggle?.isChecked = WimApp.prefs.getBoolean("shake_enabled", true)
        shakeToggle?.let { styleToggle(it) }
        shakeToggle?.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("shake_enabled", checked).apply()
        }

        // ── "Hey WiM" hotword toggle ──
        val hotwordToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.hotwordToggle)
        hotwordToggle?.isChecked = WimApp.prefs.getBoolean("hotword_enabled", false)
        hotwordToggle?.let { styleToggle(it) }
        hotwordToggle?.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("hotword_enabled", checked).apply()
            // Reflect in the live bubble service immediately — start the
            // listener if the bubble is currently idle, stop it if running.
            BubbleService.instance?.let { svc ->
                if (checked) svc.updateIdleDisplay() else svc.stopHotwordListenerPublic()
            }
        }

        // ── Dark/light mode toggle (in Settings) ──
        val darkToggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.darkModeToggle)
        val isDark = AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO
        darkToggle.isChecked = isDark
        styleToggle(darkToggle)
        darkToggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("dark_mode", checked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

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
            // Enforce order: overlay must be granted before accessibility
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        micBtn.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                100
            )
        }

        findViewById<View>(R.id.batteryPermBtn)?.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Open battery settings manually", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.notifPermBtn)?.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    200
                )
            } else {
                Toast.makeText(this, "Notifications already granted", Toast.LENGTH_SHORT).show()
            }
        }

        // Request battery optimization exemption so Samsung/OEMs don't kill the bubble
        requestBatteryExemption()

        // Check notification channel is enabled — needed for bubble recall
        checkNotificationChannel()

        // ── Privacy Policy ────────────────────────────────
        findViewById<TextView>(R.id.privacyLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mybakingcreations.com/wim-privacy-policy")))
        }

        if (intent.getBooleanExtra("open_cloud_unlock", false)) {
            window.decorView.post { requestCloudUnlock() }
        }
    }

    private fun setupCloudBilling() {
        val statusView = findViewById<TextView>(R.id.cloudUnlockStatus)
        val priceView = findViewById<TextView>(R.id.cloudUnlockPrice)
        val actionView = findViewById<TextView>(R.id.cloudUnlockAction)
        billingManager = PlayBillingManager(this, lifecycleScope) { state ->
            statusView?.text = state.message
            priceView?.text = if (state.unlocked) "PERMANENT ACCESS"
                else state.price ?: "Price unavailable"
            actionView?.text = if (state.unlocked) "UNLOCKED" else "UNLOCK"
            actionView?.isEnabled = !state.unlocked
            actionView?.alpha = if (state.unlocked) 0.55f else 1f
            findViewById<TextView>(R.id.userTier)?.text =
                if (state.unlocked) "Cloud unlocked" else "Local / Free"
            findViewById<TextView>(R.id.settingsUserTier)?.text =
                if (state.unlocked) "Cloud unlocked" else "Local / Free"
        }
        actionView?.setOnClickListener { billingManager?.launchPurchase() }
        billingManager?.start()
    }

    private fun requestCloudUnlock() {
        if (!AuthManager.isSignedIn) {
            Toast.makeText(this, "Sign in first, then unlock cloud speech", Toast.LENGTH_LONG).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.cloud_unlock_title)
            .setMessage(R.string.cloud_unlock_dialog)
            .setPositiveButton(R.string.cloud_unlock_action) { _, _ ->
                billingManager?.launchPurchase()
            }
            .setNeutralButton(R.string.cloud_unlock_restore) { _, _ ->
                billingManager?.refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Capture pre-resume state of Permissions overlay so we can detect
        // a "user just came back from system Settings with all green" event.
        val permissionsSurfaceWasOpen = (sectionOverlay != null &&
            sectionContentView?.id == R.id.contentPermissions) ||
            (currentBottomSheet != null && currentBottomSheetContentId == R.id.contentPermissions)
        updatePermissionStatus()
        // Auto-dismiss Permissions surface if all 5 are now green — saves
        // the user a manual X-tap after returning from Android Settings.
        // Per operator: "third back from accessibility should land in the
        // app, not on the permissions screen — it's all green anyway."
        if (permissionsSurfaceWasOpen) {
            val overlayOk = Settings.canDrawOverlays(this)
            val accessibilityOk = isAccessibilityEnabled()
            val micOk = AudioRecorder(this).hasPermission()
            val pm = getSystemService(android.os.PowerManager::class.java)
            val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
            val notifOk = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (overlayOk && accessibilityOk && micOk && batteryOk && notifOk) {
                if (sectionOverlay != null) dismissSectionOverlay()
                currentBottomSheet?.dismiss()
            }
        }
        loadSessions()
        // Re-publish dock slot screen rects so BubbleService's drag-snap is
        // aware while MainActivity is visible. Done after the next layout
        // pass; rects at onResume are often (0,0,0,0) before layout settles.
        val dockBronze = findViewById<android.view.View>(R.id.dockBronze)
        val dockAluminum = findViewById<android.view.View>(R.id.dockAluminum)
        val dockGunmetal = findViewById<android.view.View>(R.id.dockGunmetal)
        if (dockBronze != null && dockAluminum != null && dockGunmetal != null) {
            val slots = mapOf(
                "bronze" to dockBronze,
                "aluminum" to dockAluminum,
                "gunmetal" to dockGunmetal
            )
            dockBronze.post { publishDockRects(slots) }
        }
        // Coat-rack wire-up: BubbleService calls this when show/hide fires.
        // We also refresh now in case the bubble state changed while paused.
        BubbleService.onBubbleVisibilityChanged = { runOnUiThread { refreshDockGhost() } }
        refreshDockGhost()
        // Start the session timer ticker (updates the "Today" header timer
        // every second). Stops in onPause.
        sessionTimerHandler.removeCallbacks(sessionTimerTick)
        sessionTimerHandler.post(sessionTimerTick)
        billingManager?.refresh()
    }

    override fun onPause() {
        super.onPause()
        // Clear the rects so the floating bubble doesn't try to snap into
        // invisible targets when MainActivity is backgrounded.
        BubbleService.dockSlotRects = null
        // Drop the coat-rack callback so the service doesn't hold this
        // activity after it's gone.
        BubbleService.onBubbleVisibilityChanged = null
        sessionTimerHandler.removeCallbacks(sessionTimerTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ensureBubbleRunning()
            loadSessions()
        }
    }

    override fun onDestroy() {
        billingManager?.close()
        billingManager = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        sessionUpdateUnsub?.invoke()
        consoleUnsub?.invoke()
        // Unregister pref listeners — Android keeps SharedPreferences
        // alive across activity death; without this the leaked listener
        // would re-fire against a destroyed activity on the next pref
        // write from another surface.
        l1SourcePrefListener?.let { WimApp.prefs.unregisterOnSharedPreferenceChangeListener(it) }
        l1SourcePrefListener = null
        layerSubRowPrefListener?.let { WimApp.prefs.unregisterOnSharedPreferenceChangeListener(it) }
        layerSubRowPrefListener = null
        // The process-scoped WimApp.onSessionsSynced callback captures
        // `this` via the lambda registered in onResume / onCreate. Without
        // nulling it, a destroyed activity is retained until process death
        // because FirebaseAuth holds a strong reference to the listener
        // that fires the callback.
        WimApp.onSessionsSynced = null
        super.onDestroy()
    }

    private fun loadConsole() {
        val container = findViewById<LinearLayout>(R.id.consoleContainer) ?: return
        container.removeAllViews()
        val entries = ConsoleLog.entries.take(100)
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
        for (entry in entries) {
            val tv = TextView(this).apply {
                val timeStr = sdf.format(Date(entry.timestamp))
                text = "$timeStr  ${entry.text}"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 2, 8, 2)
                setTextColor(when (entry.kind) {
                    "rec" -> 0xFFFF6B6B.toInt()   // red — recording
                    "raw" -> 0xFFFFFFFF.toInt()    // white — raw transcription
                    "out" -> 0xFF16A34A.toInt()    // green — final output
                    "warn" -> 0xFFFFB74D.toInt()   // orange — warnings
                    "error" -> 0xFFFF5252.toInt()  // bright red — errors
                    else -> 0xFFB0B0B0.toInt()     // gray — info
                })
            }
            container.addView(tv)
        }
        if (entries.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No log entries yet. Record something."
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(0xFF666666.toInt())
                setPadding(8, 16, 8, 16)
            }
            container.addView(tv)
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

        // VU meter gauges
        findViewById<VuMeterView>(R.id.vuSessions)?.apply {
            maxValue = 500f
            value = totalSessions.toFloat()
            displayText = totalSessions.toString()
            label = "SESSIONS"
        }
        findViewById<VuMeterView>(R.id.vuWpm)?.apply {
            maxValue = 200f
            value = wpm.toFloat()
            displayText = wpm.toString()
            label = "WPM"
        }
        val currentLayer = WimApp.layer()
        findViewById<VuMeterView>(R.id.vuCleanup)?.apply {
            if (currentLayer >= 2) {
                maxValue = 100f
                value = avgCleanup.toFloat()
                displayText = "${avgCleanup}%"
                label = "CLEANUP"
            } else {
                maxValue = 10000f
                value = totalWords.toFloat()
                displayText = "%,d".format(totalWords)
                label = "WORDS"
            }
        }
        val fillerCount = WimApp.prefs.getInt("filler_count", 0)
        findViewById<VuMeterView>(R.id.vuScore)?.apply {
            maxValue = 150f
            value = fillerCount.toFloat()
            displayText = fillerCount.toString()
            label = "FILLERS"
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

        // Reset our reference each render — old views get GC'd.
        todayTimerView = null

        for (session in sessions) {
            val sessionDate = dateFormat.format(java.util.Date(session.timestamp))
            if (sessionDate != lastDateLabel) {
                lastDateLabel = sessionDate
                val label = when (sessionDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> SimpleDateFormat("MMMM d", Locale.getDefault()).format(java.util.Date(session.timestamp))
                }
                if (label == "Today") {
                    // Horizontal row: "Today" + timer + reset (↺) button
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((4 * dpScale).toInt(), (8 * dpScale).toInt(), (4 * dpScale).toInt(), (4 * dpScale).toInt())
                    }
                    val labelTv = TextView(this).apply {
                        text = "Today"
                        setTextColor(getColor(R.color.copper_gold))
                        textSize = 15f
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        letterSpacing = 0.1f
                    }
                    val timerTv = TextView(this).apply {
                        text = "0:00"
                        setTextColor(getColor(R.color.copper_gold))
                        textSize = 15f
                        typeface = Typeface.MONOSPACE
                        setPadding((10 * dpScale).toInt(), 0, 0, 0)
                    }
                    val resetBtn = TextView(this).apply {
                        text = "↺"
                        setTextColor(getColor(R.color.copper_gold))
                        textSize = 21f
                        setPadding((10 * dpScale).toInt(), 0, (4 * dpScale).toInt(), 0)
                        setOnClickListener {
                            WimApp.prefs.edit().putLong("session_start_time", System.currentTimeMillis()).apply()
                            updateTodayTimer()
                        }
                    }
                    row.addView(labelTv)
                    row.addView(timerTv)
                    row.addView(resetBtn)
                    container.addView(row)
                    todayTimerView = timerTv
                    updateTodayTimer()
                } else {
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
            setTextColor(getColor(R.color.orange_label))
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
            setTextColor(getColor(R.color.orange_label))
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
                triggerShimmerForPrefKey(prefKey)
                ids.forEachIndexed { j, otherId ->
                    setLayoutSelected(findViewById(otherId), findViewById(labelIds[j]), false)
                }
                setLayoutSelected(it as LinearLayout, findViewById(labelIds[i]), true)
                WimApp.prefs.edit().putString(prefKey, values[i]).apply()
                refreshHeaderBadges()
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
                if (prefKey == "layer" && values[i] > 1 && !WimApp.cloudUnlocked()) {
                    requestCloudUnlock()
                    return@setOnClickListener
                }
                triggerShimmerForPrefKey(prefKey)
                ids.forEachIndexed { j, otherId ->
                    setLayoutSelected(findViewById(otherId), findViewById(labelIds[j]), false)
                }
                setLayoutSelected(it as LinearLayout, findViewById(labelIds[i]), true)
                WimApp.prefs.edit().putInt(prefKey, values[i]).apply()
                loadSessions() // refresh stats — third cell changes per layer
                refreshHeaderBadges()
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

    /**
     * Set a category header's text with a maroon state badge appended.
     * Format: "LAYER   [tab]   badge" — tab stop aligns badges across categories.
     */
    private fun setHeaderWithBadge(headerId: Int, baseName: String, badgeValue: String) {
        val header = findViewById<TextView>(headerId) ?: return
        val separator = "\t"
        val full = baseName + separator + badgeValue
        val sb = android.text.SpannableString(full)
        val badgeStart = baseName.length + separator.length
        val badgeEnd = full.length
        val maroonColor = 0xFFB55050.toInt()

        // Tab stop at 110dp — past longest base name ("SITUATION") in any font
        val tabStopPx = (110 * resources.displayMetrics.density).toInt()
        sb.setSpan(
            android.text.style.TabStopSpan.Standard(tabStopPx),
            0, full.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.ForegroundColorSpan(maroonColor),
            badgeStart, badgeEnd,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.RelativeSizeSpan(0.75f),
            badgeStart, badgeEnd,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // Monospace so within-category padded values stay uniform
        sb.setSpan(
            android.text.style.TypefaceSpan("monospace"),
            badgeStart, badgeEnd,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        header.text = sb
    }

    /**
     * Refresh all category header badges with their current state values.
     * Combining overline (U+0305) + combining low line (U+0332) on I/V
     * renders Roman numerals with top/bottom bars in any font.
     * Each category's values are space-padded to the longest one for visual alignment.
     */
    private fun refreshHeaderBadges() {
        // Layer: padded to 11 visual chars ("reconstruct" is longest)
        val layerNames = mapOf(
            1 to "transcribe ",
            2 to "reconstruct",
            3 to "profile    ",
            4 to "disfluency "
        )
        // Tone: padded to 12 visual chars ("professional" is longest)
        val toneNames = mapOf(
            "casual"       to "casual      ",
            "professional" to "professional",
            "friend"       to "friend      ",
            "formal"       to "formal      "
        )
        setHeaderWithBadge(R.id.headerLayer, "LAYER", layerNames[WimApp.layer()] ?: layerNames[1]!!)
        setHeaderWithBadge(R.id.headerTone, "TONE", toneNames[WimApp.tone()] ?: toneNames["casual"]!!)
        // Update the compact dual-tile values too
        val layerLabel = (layerNames[WimApp.layer()] ?: "transcribe").trim()
            .replaceFirstChar { it.uppercase() }
        val toneLabel = (toneNames[WimApp.tone()] ?: "casual").trim()
            .replaceFirstChar { it.uppercase() }
        findViewById<TextView>(R.id.tileLayerValue)?.text = layerLabel
        findViewById<TextView>(R.id.tileToneValue)?.text = toneLabel
    }

    private fun setupVuDoubleTapReset(vuId: Int, prefKey: String) {
        val vu = findViewById<VuMeterView>(vuId) ?: return
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                WimApp.prefs.edit().putInt(prefKey, 0).apply()
                vu.value = 0f
                vu.displayText = "0"
                android.widget.Toast.makeText(this@MainActivity, "${vu.label} reset", android.widget.Toast.LENGTH_SHORT).show()
                return true
            }
        })
        vu.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    private fun setLayoutSelected(container: ViewGroup, label: TextView, selected: Boolean) {
        if (selected) {
            container.setBackgroundResource(R.drawable.card_bg_selected)
            label.setTextColor(getColor(R.color.active_text))
        } else {
            container.setBackgroundResource(R.drawable.card_bg_pressable)
            label.setTextColor(getColor(R.color.text_bright))
        }
        // Toggle the new ◉ selected-state pill based on container ID. Map
        // is local to keep the function self-contained — adding new layers
        // / tones only requires extending this when its row gets a pill.
        val pillId = when (container.id) {
            R.id.layer1 -> R.id.layer1SelectedPill
            R.id.layer2 -> R.id.layer2SelectedPill
            R.id.layer3 -> R.id.layer3SelectedPill
            R.id.layer4 -> R.id.layer4SelectedPill
            R.id.toneCasual -> R.id.toneCasualSelectedPill
            R.id.toneProfessional -> R.id.toneProfessionalSelectedPill
            else -> 0
        }
        if (pillId != 0) {
            findViewById<TextView>(pillId)?.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    // Live map of category header → its BorderShimmerDrawable so we can fire
    // trigger() from any click path (the header itself, or a tile inside it).
    private val headerShimmers = mutableMapOf<Int, ShimmerFx.BorderShimmerDrawable>()

    // Map of prefKey (as passed to setupCollapsible / setupLayoutGroup) to the
    // parent header ID so tile-click listeners can fire their parent's shimmer.
    private val prefKeyToHeaderId = mapOf(
        "layer" to R.id.headerLayer,
        "tone" to R.id.headerTone,
    )

    /**
     * Layer a BorderShimmerDrawable foreground on top of a section header.
     * The drawable is one-shot — trigger() must be called on each interaction
     * for the shimmer to sweep. Underlying background (glass_panel_bg_pressable
     * etc.) is untouched; the shimmer only renders while its sweep is in flight.
     */
    private fun applyHeaderShimmer(headerId: Int) {
        val header = findViewById<View>(headerId) ?: return
        val shimmer = ShimmerFx.BorderShimmerDrawable(
            displayMetrics = resources.displayMetrics,
            cornerRadiusDp = 10f,
            strokeWidthDp = 1.5f,
            cycleMs = 3500L
        )
        header.foreground = shimmer
        headerShimmers[headerId] = shimmer
    }

    /** Fire the border shimmer sweep on a specific header. No-op if none attached. */
    private fun triggerHeaderShimmer(headerId: Int) {
        headerShimmers[headerId]?.trigger()
    }

    /** Fire the shimmer for the header a given tile-group prefKey belongs to. */
    private fun triggerShimmerForPrefKey(prefKey: String) {
        prefKeyToHeaderId[prefKey]?.let { triggerHeaderShimmer(it) }
    }

    private fun setupCollapsible(headerId: Int, contentId: Int, prefKey: String, defaultExpanded: Boolean = true) {
        val header = findViewById<TextView>(headerId)
        val content = findViewById<View>(contentId)

        // Restore saved state
        val expanded = WimApp.prefs.getBoolean("section_$prefKey", defaultExpanded)
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        updateCollapseArrow(header, expanded)

        header.setOnClickListener {
            triggerHeaderShimmer(headerId)
            val isVisible = content.visibility == View.VISIBLE
            content.visibility = if (isVisible) View.GONE else View.VISIBLE
            updateCollapseArrow(header, !isVisible)
            WimApp.prefs.edit().putBoolean("section_$prefKey", !isVisible).apply()
        }
    }

    private fun setupScriptPrep() {
        val input = findViewById<EditText>(R.id.scriptPrepText)
        val enabled = findViewById<MaterialSwitch>(R.id.scriptPrepEnabled)
        val status = findViewById<TextView>(R.id.scriptPrepStatus)
        input.setText(ScriptPrepStore.text())
        enabled.isChecked = ScriptPrepStore.isEnabled()

        fun refresh() {
            val chars = ScriptPrepStore.text().length
            val active = ScriptPrepStore.isEnabled()
            status.text = if (active) "ACTIVE — $chars characters sent as private context" else "OFF — no prepared context is being used"
            status.setTextColor(getColor(if (active) R.color.green_active else R.color.text_dim))
            enabled.isChecked = active
        }

        findViewById<Button>(R.id.scriptPrepSave).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "Paste or type context first", Toast.LENGTH_SHORT).show()
            } else {
                ScriptPrepStore.save(text, enabled = true)
                refresh()
                Toast.makeText(this, "Script Prep is active", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.scriptPrepClear).setOnClickListener {
            ScriptPrepStore.clear()
            input.setText("")
            refresh()
        }
        enabled.setOnCheckedChangeListener { _, checked ->
            if (checked && input.text.toString().trim() != ScriptPrepStore.text()) {
                ScriptPrepStore.save(input.text.toString(), enabled = true)
            } else {
                ScriptPrepStore.setEnabled(checked)
            }
            refresh()
        }
        refresh()
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
        // Pin +/− toggle to right edge using drawableEnd
        val symbol = if (expanded) "\u2212" else "+"
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = header.textSize * 1.1f
            color = getColor(R.color.section_header)
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

    // ── Guide — inline collapsible section ──────────

    /**
     * Render a static, non-interactive copy of the floating bubble
     * (`bubble.xml`) for use in Guide cards. Scales the 120dp layout to
     * the requested size, applies the chosen theme's bezel + knob drawables,
     * sets the label text and (optionally) color. Touch handling is
     * stripped — these are purely visual.
     *
     * @param theme  "bronze" | "aluminum" | "gunmetal"
     * @param label  "IDLE" / "REC" / "PROC" / "SEND" / etc.
     * @param labelColor optional ARGB int; defaults to a state-appropriate color when null
     * @param scale  fraction of the original 120dp (0.45f → ~54dp wide)
     */
    private fun makeBubblePreview(
        theme: String,
        label: String,
        labelColor: Int? = null,
        scale: Float = 0.6f,
    ): android.view.View {
        val dp = resources.displayMetrics.density
        val sizePx = (120 * dp * scale).toInt()
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
        try {
            val bubble = layoutInflater.inflate(R.layout.bubble, container, false)
            bubble.layoutParams = FrameLayout.LayoutParams(
                (120 * dp).toInt(), (120 * dp).toInt(),
                android.view.Gravity.CENTER,
            )
            bubble.scaleX = scale
            bubble.scaleY = scale
            bubble.isClickable = false
            bubble.isFocusable = false
            val bezelDrawable = when (theme) {
                "aluminum" -> R.drawable.bubble_bezel_aluminum
                "gunmetal" -> R.drawable.bubble_bezel_gunmetal
                else -> R.drawable.bubble_bg_idle
            }
            val knobDrawable = when (theme) {
                "aluminum" -> R.drawable.bubble_knob_aluminum
                "gunmetal" -> R.drawable.bubble_knob_gunmetal
                else -> R.drawable.bubble_inner_knob
            }
            bubble.findViewById<FrameLayout>(R.id.bubbleBezel)?.setBackgroundResource(bezelDrawable)
            bubble.findViewById<FrameLayout>(R.id.bubbleInner)?.setBackgroundResource(knobDrawable)
            bubble.findViewById<TextView>(R.id.bubbleLabel)?.apply {
                text = label
                val resolvedColor = labelColor ?: when (label) {
                    "REC" -> 0xFFFF6B6B.toInt()
                    "PROC" -> 0xFFFFB74D.toInt()
                    "SEND" -> 0xFF5AD15F.toInt()
                    else -> if (theme == "gunmetal") 0xFFC8B48A.toInt() else 0xFF555560.toInt()
                }
                setTextColor(resolvedColor)
            }
            container.addView(bubble)
        } catch (_: Exception) {
            container.addView(TextView(this).apply {
                text = "(preview)"
                setTextColor(0xFF888888.toInt())
                textSize = 9f
            })
        }
        return container
    }

    /**
     * Build the in-app Guide. Each section is a collapsible card with a
     * title + subtitle header and an HTML body that toggles on tap. Some
     * cards also embed a real visual: the "Bubble" card inflates the
     * actual `bubble.xml` layout (same one BubbleService uses) so the user
     * sees the precise control they're going to use, in their selected
     * theme.
     */
    private fun buildGuideContent() {
        val container = findViewById<LinearLayout>(R.id.contentGuide) ?: return
        val dp = resources.displayMetrics.density
        val creamColor = 0xFFC8B48A.toInt()
        val bodyColor = getColor(R.color.text_primary)
        val dimColor = getColor(R.color.text_dim)

        // Launcher card: opens the illustrated full-screen manual.
        // Lives at the top of the inline accordion so the premium HTML
        // is the first thing the user sees when they expand Guide.
        val manualLauncher = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(
                (16 * dp).toInt(), (16 * dp).toInt(),
                (16 * dp).toInt(), (16 * dp).toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (4 * dp).toInt()
                bottomMargin = (10 * dp).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GuideActivity::class.java))
            }
            addView(TextView(this@MainActivity).apply {
                text = "READ THE FULL MANUAL"
                setTextColor(creamColor)
                try {
                    typeface = Typeface.createFromAsset(assets, "limelight.ttf")
                } catch (_: Exception) { /* fall back to default if asset missing */ }
                textSize = 18f
                letterSpacing = 0.08f
                gravity = android.view.Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "13 CHAPTERS  ·  ILLUSTRATED  ·  FULL SCREEN"
                setTextColor(dimColor)
                textSize = 10f
                letterSpacing = 0.22f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
        }
        container.addView(manualLauncher)

        // Section data: (title, subtitle, html body, visualBuilder?). The
        // visualBuilder is invoked when the section expands; it returns a
        // View to drop above the body text. null = text-only card.
        data class GuideSection(
            val title: String,
            val subtitle: String,
            val body: String,
            val visual: (() -> android.view.View)? = null,
        )

        // Helper: build a horizontal row of bubble previews with optional caption labels.
        fun bubbleRow(
            previews: List<Pair<android.view.View, String?>>,
            scaleHint: Float = 0.6f,
        ): android.view.View {
            val sizePx = (120 * dp * scaleHint).toInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (10 * dp).toInt()
                    bottomMargin = (4 * dp).toInt()
                }
            }
            previews.forEachIndexed { i, (preview, caption) ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        sizePx, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (i > 0) marginStart = (8 * dp).toInt()
                    }
                }
                cell.addView(preview)
                if (!caption.isNullOrBlank()) {
                    cell.addView(TextView(this).apply {
                        text = caption
                        setTextColor(dimColor)
                        textSize = 9f
                        typeface = android.graphics.Typeface.MONOSPACE
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, (4 * dp).toInt(), 0, 0)
                    })
                }
                row.addView(cell)
            }
            return row
        }

        val sections = listOf(
            GuideSection(
                "What happens when you tap",
                "The pipeline, top to bottom",
                """<b>1. You tap the bubble and speak.</b><br>The mic captures your voice locally. No audio leaves the phone for Layer I.<br><br>
<b>2. Speech-to-text.</b><br>
&#8226; <b>Layer I</b> &#8212; whisper-tiny runs on your phone (free, no internet, no chirp).<br>
&#8226; <b>Layer II / III</b> &#8212; gpt-4o-transcribe in the cloud (your audio goes up so the model can hear inflection and disfluency before reconstruction).<br>
&#8226; <b>Layer IV</b> &#8212; Whisper-1 in the cloud (older Whisper preserves more disfluencies and exposes per-segment confidence the clinical prompt needs).<br><br>
<b>3. Rule cleanup (no AI).</b><br>
&#8226; "p-p-pop" &#8594; "pop"<br>
&#8226; "I I I want" &#8594; "I want" (but "bye bye", "had had" are kept)<br>
&#8226; um / uh / like / you know stripped<br>
&#8226; Whisper hallucinations like "thanks for watching" stripped<br><br>
<b>4. Reconstruction (Layer II+).</b><br>
&#8226; <b>L2/L3</b> &#8212; GPT-4o rewrites in your tone. Claude Haiku 4.5 then verifies the meaning didn't change.<br>
&#8226; <b>L4</b> &#8212; Claude Sonnet 4.6 with extended thinking. Reasons through speech-disfluency patterns; no separate verifier needed (the reasoning trace self-validates).<br><br>
<b>5. Output filters.</b><br>
&#8226; LLM leak guard: catches "I'm sorry, I can't help" responses.<br>
&#8226; Length guard: if AI output is more than 2.5&#215; the input length, it fabricated &#8212; we use the cleaned raw text instead.<br><br>
<b>6. Paste.</b><br>The final text is injected into the app you're in via Android's accessibility service.<br><br>
<b>7. Learning (L3+).</b><br>After WiM sees the same correction 3 times, it auto-adds it to your profile. Over time L1 and L3 get smarter for you specifically.""",
                visual = {
                    // The bubble's life cycle — what you see on screen as the
                    // pipeline runs. Same theme as the user's selection so it
                    // matches the bubble they're looking at every day.
                    val theme = WimApp.bubbleTheme()
                    bubbleRow(
                        listOf(
                            makeBubblePreview(theme, "IDLE", scale = 0.45f) to "1. tap",
                            makeBubblePreview(theme, "REC", scale = 0.45f) to "2. speak",
                            makeBubblePreview(theme, "PROC", scale = 0.45f) to "3. process",
                            makeBubblePreview(theme, "SEND", scale = 0.45f) to "4. tap to send",
                        ),
                        scaleHint = 0.45f,
                    )
                },
            ),

            GuideSection(
                "The Bubble",
                "Your floating control",
                """<b>Tap</b> &#8212; Start recording. Tap again to stop.<br><br>
<b>Hold</b> &#8212; Record while held, release to process.<br><br>
<b>Double-tap</b> &#8212; Open the bubble menu (opacity, size, theme, live transcript).<br><br>
<b>Drag</b> &#8212; Move it anywhere.<br><br>
<b>Drag to bottom</b> &#8212; Hide the bubble.<br><br>
<b>Double shake the phone</b> &#8212; Show or hide the bubble.<br><br>
<b>States:</b> IDLE (resting) &#8226; REC (recording) &#8226; PROC (processing) &#8226; SEND (auto-send ready, green).""",
                visual = {
                    val theme = WimApp.bubbleTheme()
                    bubbleRow(
                        listOf(makeBubblePreview(theme, "IDLE", scale = 0.85f) to "your bubble"),
                        scaleHint = 0.85f,
                    )
                },
            ),

            GuideSection(
                "Bubble themes",
                "Three coats on the rack",
                """Three bubble color variants &#8212; <b>Bronze</b>, <b>Aluminum</b>, <b>Gunmetal</b> &#8212; live in the dock at the bottom of this screen, between the Guide and your profile.<br><br>
<b>Pick one:</b> press a slot. The bubble appears at your finger.<br>
<b>Drag it out:</b> hold and drag &#8212; the floating bubble follows.<br>
<b>Magnetic dock-back:</b> drop the bubble over any slot, it snaps in. Drop on a different slot and the theme switches.""",
                visual = {
                    bubbleRow(
                        listOf(
                            makeBubblePreview("bronze", "IDLE", scale = 0.55f) to "bronze",
                            makeBubblePreview("aluminum", "IDLE", scale = 0.55f) to "aluminum",
                            makeBubblePreview("gunmetal", "IDLE", scale = 0.55f) to "gunmetal",
                        ),
                        scaleHint = 0.55f,
                    )
                },
            ),

            GuideSection(
                "Layers",
                "What each layer does",
                """<b>I. Transcribe</b> &#8212; whisper-tiny on-device + rule-based cleanup. Free, instant, no internet after the model lands.<br><br>
<b>II. Reconstruct</b> &#8212; GPT-4o cleans grammar and applies your tone. Claude Haiku 4.5 verifies meaning. ~2-4 seconds.<br><br>
<b>III. Profile</b> &#8212; Same as II + your personal vocabulary and learned corrections feed the prompt. Learns over time.<br><br>
<b>IV. Speech Disfluency</b> &#8212; Whisper-1 captures the audio with confidence signals. Claude Sonnet 4.6 with extended thinking reconstructs through clinical reasoning &#8212; phoneme difficulty map, avoidance pattern recognition, anticipatory-block detection. Slowest layer but the most accurate for disfluent speech."""
            ),

            GuideSection(
                "Smart Tone",
                "Auto + manual",
                """WiM detects what app you're in and matches the tone:<br><br>
<b>Work apps</b> (Gmail, Slack, Teams, LinkedIn) &#8594; Professional<br>
<b>Personal apps</b> (WhatsApp, Messenger, Telegram) &#8594; Casual<br><br>
You can override anywhere: <b>Settings &#8594; Tone</b>. Two tones: Casual and Professional. Tone only affects Layer II and up &#8212; Layer I has no AI, no tone."""
            ),

            GuideSection(
                "Auto-send",
                "Tap green to send",
                """When auto-send is on (you opted in during onboarding):<br><br>
&#8226; Speak. The text lands in the compose field.<br>
&#8226; The bubble turns <b>green</b> for 3 seconds, label says SEND.<br>
&#8226; Tap the green bubble &#8212; WiM clicks the app's Send button for you.<br>
&#8226; Don't tap, or wait 3 seconds, or tap outside &#8212; auto-send cancels and you can edit the text first.<br><br>
Works in Google Messages, Samsung Messages, Gmail, WhatsApp, Telegram, Signal, and more.<br><br>
Turn off in <b>Settings &#8594; Auto-send</b>.""",
                visual = {
                    val theme = WimApp.bubbleTheme()
                    bubbleRow(
                        listOf(makeBubblePreview(theme, "SEND", scale = 0.85f) to "tap to send"),
                        scaleHint = 0.85f,
                    )
                },
            ),

            GuideSection(
                "DAF",
                "Delayed auditory feedback",
                """Plays your voice back through your earpiece with a 30-300 ms delay. A documented fluency technique &#8212; hearing yourself on delay can reduce speech-disfluency severity.<br><br>
On in <b>Settings &#8594; DAF</b>. Use the slider to tune the delay."""
            ),

            GuideSection(
                "Privacy + Cloud",
                "What's saved, who can see it",
                """<b>Always local, never sent:</b> the audio itself. Always.<br><br>
<b>Local:</b> your settings, profile, console log. Stored on the phone in private app storage.<br><br>
<b>Cloud (Firestore, only if you sign in):</b> the text of each recording (raw + clean), tone, layer, timing, success/failure flags. Per session, ~1 KB. Used so your sessions and profile sync to a new phone when you sign in.<br><br>
<b>Toggle off:</b> Settings &#8594; Backup sessions to cloud.<br>
<b>Delete everything:</b> Profile &#8594; Delete data. Hard delete on the cloud, signs you out, immediate."""
            ),

            GuideSection(
                "Having trouble?",
                "Common fixes",
                """<b>Bubble gone?</b> Open the app &#8212; it restarts automatically. Or double-shake the phone.<br><br>
<b>Keeps disappearing?</b> Battery optimization is killing WiM in the background. Fix: phone Settings &#8594; Battery &#8594; Never sleeping apps &#8594; add What I Meant.<br><br>
<b>Text not pasting?</b> Make sure a text field is active before you speak. Some apps refuse to accept programmatic text &#8212; try the keyboard's clipboard paste instead.<br><br>
<b>Heard a chirp?</b> The on-device whisper-tiny model is silent, but Android's built-in recognizer chirps. If you hear a chirp during a Layer I recording, the whisper-tiny model probably hasn't finished downloading yet &#8212; wait a minute and try again."""
            ),
        )

        for (section in sections) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (4 * dp).toInt() }
            }

            val headerRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            headerRow.addView(TextView(this).apply {
                text = section.title
                setTextColor(creamColor)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            headerRow.addView(TextView(this).apply {
                text = section.subtitle
                setTextColor(dimColor)
                textSize = 10f
            })
            card.addView(headerRow)

            val bodyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            section.visual?.invoke()?.let { bodyContainer.addView(it) }
            bodyContainer.addView(TextView(this).apply {
                text = android.text.Html.fromHtml(section.body, android.text.Html.FROM_HTML_MODE_COMPACT)
                setTextColor(bodyColor)
                textSize = 11f
                setLineSpacing(0f, 1.3f)
            })
            card.addView(bodyContainer)

            card.setOnClickListener {
                bodyContainer.visibility =
                    if (bodyContainer.visibility == android.view.View.GONE) android.view.View.VISIBLE
                    else android.view.View.GONE
            }

            container.addView(card)
        }
    }

    // ── Operations Manual (old overlay — kept for reference) ──────────

    // Guide overlay state — track contentGuide's original parent + index so
    // we can put it back when the overlay is dismissed.
    private var guideOverlay: View? = null
    private var guideContentOriginalParent: android.view.ViewGroup? = null
    private var guideContentOriginalIndex: Int = -1

    /**
     * Open the inline GUIDE content (built by buildGuideContent) as a
     * full-screen overlay. Reparents R.id.contentGuide into the overlay so
     * we don't duplicate the rich section data — it's the SAME guide the
     * user used to see inline.
     */
    private fun showGuideOverlay() {
        if (guideOverlay != null) return
        val contentGuide = findViewById<LinearLayout>(R.id.contentGuide) ?: return
        val rootFrame = findViewById<FrameLayout>(android.R.id.content)
        val dp = resources.displayMetrics.density

        val originalParent = contentGuide.parent as? android.view.ViewGroup
        if (originalParent != null) {
            guideContentOriginalParent = originalParent
            guideContentOriginalIndex = originalParent.indexOfChild(contentGuide)
            originalParent.removeView(contentGuide)
        }
        contentGuide.visibility = View.VISIBLE

        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xFF1A1A1E.toInt() else 0xFFE8E8EC.toInt()

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
            isClickable = true
            setPadding(0, (40 * dp).toInt(), 0, 0)
        }

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((16 * dp).toInt(), (40 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
            addView(contentGuide, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        overlay.addView(scroll)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(getColor(R.color.copper_gold))
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END }
            setOnClickListener { dismissGuideOverlay() }
        }
        overlay.addView(closeBtn)

        rootFrame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        guideOverlay = overlay
    }

    private fun dismissGuideOverlay() {
        val overlay = guideOverlay ?: return
        val contentGuide = overlay.findViewById<LinearLayout>(R.id.contentGuide)

        if (contentGuide != null) {
            (contentGuide.parent as? android.view.ViewGroup)?.removeView(contentGuide)
            contentGuide.visibility = View.GONE
            val parent = guideContentOriginalParent
            if (parent != null && guideContentOriginalIndex >= 0) {
                parent.addView(contentGuide, guideContentOriginalIndex)
            }
        }
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        guideOverlay = null
        guideContentOriginalParent = null
        guideContentOriginalIndex = -1
    }

    private fun showGuide() {
        val dp = resources.displayMetrics.density
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xFF1A1A1E.toInt() else 0xFFe8e8ec.toInt()
        val cardColor = if (isNight) 0xFF252530.toInt() else 0xFFd8d8dc.toInt()
        val textColor = if (isNight) 0xFFe0e0e4.toInt() else 0xFF2a2a30.toInt()
        val dimColor = if (isNight) 0xFF8a8a92.toInt() else 0xFF666670.toInt()
        val accentColor = getColor(R.color.orange_label)

        val sections = listOf(
            Triple("How It Works", "What happens when you tap the bubble", """
<b><font color="#EE5A24">1. You speak</b><br>Tap the bubble to start recording. Your voice is captured through the microphone.<br><br>
<b><font color="#EE5A24">2. On-device transcription</b><br>Android's built-in speech recognition converts your voice to text, right on your phone. No internet needed for this step, no cost.<br><br>
<b><font color="#EE5A24">3. Quick cleanup</b><br>Obvious disfluencies are stripped automatically (instant, free):<br>
&#8226; Repeated syllables: "p-p-pop" &#8594; "pop"<br>
&#8226; Repeated words: "I I I want" &#8594; "I want"<br>
&#8226; Fillers: "um", "uh", "like", "you know"<br>
&#8226; False starts and prolongations<br>
Natural phrases like "bye bye" are kept.<br><br>
<b><font color="#EE5A24">4. Depends on your Layer:</b><br><br>
<b>Layer I</b> &#8212; Done. Cleaned text is pasted. No AI, no cost.<br><br>
<b>Layer II</b> &#8212; GPT rewrites using your tone (casual, professional, etc). No personal data used.<br><br>
<b>Layer III</b> &#8212; Same as II, plus GPT knows your preferred words and learned corrections.<br><br>
<b>Layer IV</b> &#8212; Full disfluency-aware engine. Stronger AI model, plus your personal phoneme map, avoidance patterns, and block detection.<br><br>
<b><font color="#EE5A24">5. Safety check</b><br>A second AI verifies the meaning wasn't changed. If it was, your original text is used instead. Numbers and dollar amounts are double-checked.<br><br>
<b><font color="#EE5A24">6. Paste</b><br>The final text is injected into whatever text field you're typing in.""".trimIndent()),

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
<b>IV. Speech Disfluency</b> &#8212; Full clinical mode. Stronger AI model, disfluency-aware prompts, phoneme difficulty map, avoidance detection.""".trimIndent()),

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
Plays your voice back to you with a slight delay (30-300ms). This is a well-known fluency technique &#8212; hearing yourself on a delay can reduce speech-disfluency severity.<br><br>
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
            intArrayOf(0xFF3A3530.toInt(), 0xFF2A2A2F.toInt())
        )
        toggle.scaleX = 0.75f
        toggle.scaleY = 0.75f
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
        val pm = getSystemService(android.os.PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val notifOk = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        // Status pills on each permission row: granted = muted cream-gold
        // "✓ GRANTED", missing = warm maroon "✗ TAP TO GRANT". Labels stay
        // static text_bright; only the pill changes. Dashboard tile color
        // still flips green/red for at-a-glance state.
        val green = 0xFF40A065.toInt()
        val red = 0xFFEF4444.toInt()
        val grantedColor = 0x99C8B48A.toInt()
        val missingColor = 0xFFD96A4A.toInt()
        fun paintPill(pillId: Int, ok: Boolean) {
            val tv = findViewById<TextView>(pillId) ?: return
            tv.text = if (ok) "✓ GRANTED" else "✗ TAP TO GRANT"
            tv.setTextColor(if (ok) grantedColor else missingColor)
        }
        paintPill(R.id.overlayPermStatus, overlayOk)
        paintPill(R.id.accessibilityPermStatus, accessibilityOk)
        paintPill(R.id.micPermStatus, micOk)
        paintPill(R.id.batteryPermStatus, batteryOk)
        paintPill(R.id.notifPermStatus, notifOk)

        // PERMISSIONS dashboard tile: green only when ALL FIVE are granted.
        val allPermsOk = overlayOk && accessibilityOk && micOk && batteryOk && notifOk
        val tileColor = if (allPermsOk) green else red
        findViewById<TextView>(R.id.tilePermissionsLabel)?.setTextColor(tileColor)
        findViewById<TextView>(R.id.tilePermissionsChevron)?.setTextColor(tileColor)

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
        // Footer Profile (left of WM tile) + Footer Guide (right of WM tile)
        // — moved here from the top brand plate per George 2026-04-30.
        // Guide opens an OVERLAY (not the in-page section, which is now hidden).
        findViewById<View>(R.id.footerProfile)?.setOnClickListener {
            showProfileOverlay()
        }
        findViewById<View>(R.id.footerGuide)?.setOnClickListener {
            showGuideOverlay()
        }
        // Push the frozen bottom (Sign-in + Footer) ABOVE the Android
        // navigation bar — without this, on edge-to-edge devices the
        // nav bar overlays the bottom of our footer.
        findViewById<View>(R.id.frozenBottom)?.let { fb ->
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(fb) { v, insets ->
                val sysBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, sysBars.bottom)
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(fb)
        }

        // Dual-tile rows — Layer/Tone + Permissions/Settings.
        // Tap a tile → reparents the (hidden) section into a slide-in overlay.
        findViewById<View>(R.id.tileLayer)?.setOnClickListener {
            openBottomSheet(R.id.contentLayer, "LAYER", 0.55f)
        }
        findViewById<View>(R.id.tileTone)?.setOnClickListener {
            openBottomSheet(R.id.contentTone, "TONE", 0.40f)
        }
        findViewById<View>(R.id.tilePermissions)?.setOnClickListener {
            openBottomSheet(R.id.contentPermissions, "PERMISSIONS", 0.60f)
        }
        findViewById<View>(R.id.tileSettings)?.setOnClickListener {
            openSectionOverlay(R.id.contentSettings, "SETTINGS")
        }
    }

    // Bottom sheet tracking — used to auto-dismiss the Permissions sheet
    // when the user returns from Android Settings with all perms granted.
    private var currentBottomSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var currentBottomSheetContentId: Int = 0

    // Section-overlay state — track the original parent + index for the
    // currently-shown section so we can put it back on dismiss.
    private var sectionOverlay: View? = null
    private var sectionContentView: View? = null
    private var sectionContentParent: android.view.ViewGroup? = null
    private var sectionContentIndex: Int = -1
    private var sectionContentOriginalVisibility: Int = View.GONE

    /**
     * Generic section overlay — reparents an existing in-layout content
     * view into a full-screen overlay for the duration of viewing. On
     * dismiss the content goes back to its original parent + index.
     */
    private fun openSectionOverlay(contentResId: Int, title: String) {
        if (sectionOverlay != null) return
        val content = findViewById<View>(contentResId) ?: return
        val rootFrame = findViewById<FrameLayout>(android.R.id.content)
        val dp = resources.displayMetrics.density

        val originalParent = content.parent as? android.view.ViewGroup ?: return
        sectionContentView = content
        sectionContentParent = originalParent
        sectionContentIndex = originalParent.indexOfChild(content)
        sectionContentOriginalVisibility = content.visibility
        originalParent.removeView(content)
        content.visibility = View.VISIBLE

        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xFF1A1A1E.toInt() else 0xFFE8E8EC.toInt()

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
            isClickable = true
            elevation = 16f * dp
        }

        // Header bar with title + close
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(getColor(R.color.copper_gold))
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(getColor(R.color.copper_gold))
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { dismissSectionOverlay() }
        }
        headerBar.addView(titleTv)
        headerBar.addView(closeBtn)

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (24 * dp).toInt())
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContent.addView(headerBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        scrollContent.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        scroll.addView(scrollContent)
        overlay.addView(scroll)

        // Slide in from right
        overlay.translationX = rootFrame.width.toFloat()
        rootFrame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlay.animate().translationX(0f).setDuration(220).start()

        sectionOverlay = overlay
    }

    private fun dismissSectionOverlay() {
        val overlay = sectionOverlay ?: return
        val content = sectionContentView ?: return
        overlay.animate().translationX(overlay.width.toFloat()).setDuration(180).withEndAction {
            (content.parent as? android.view.ViewGroup)?.removeView(content)
            content.visibility = sectionContentOriginalVisibility
            val parent = sectionContentParent
            if (parent != null && sectionContentIndex >= 0) {
                parent.addView(content, sectionContentIndex)
            }
            (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
            sectionOverlay = null
            sectionContentView = null
            sectionContentParent = null
            sectionContentIndex = -1
        }.start()
    }

    /**
     * Bottom-sheet variant of openSectionOverlay. Reparents an existing
     * in-layout content view into a Material BottomSheetDialog. On dismiss
     * the content goes back to its original parent + index. heightFraction
     * is the portion of screen height the sheet should peek at when shown.
     */
    private fun openBottomSheet(contentResId: Int, title: String, heightFraction: Float) {
        val content = findViewById<View>(contentResId) ?: return
        val originalParent = content.parent as? android.view.ViewGroup ?: return
        val originalIndex = originalParent.indexOfChild(content)
        val originalVisibility = content.visibility
        val dp = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xFF1A1A1E.toInt() else 0xFFE8E8EC.toInt()

        // Outer container — vertical: drag handle + header + content scroll.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        // Drag handle (centered pill)
        val dragHandle = View(this).apply {
            val lp = LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                topMargin = (10 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            layoutParams = lp
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33C8B48A)
                cornerRadius = 2f * dp
            }
        }
        container.addView(dragHandle)

        // Header — title left, close right
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(getColor(R.color.copper_gold))
            try { typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geist_bold) } catch (_: Exception) {}
            letterSpacing = 0.18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(getColor(R.color.copper_gold))
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        headerBar.addView(titleTv)
        headerBar.addView(closeBtn)
        container.addView(headerBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Cream-gold underline (32dp at 55% alpha) — visual DNA from settings
        val underline = View(this).apply {
            val lp = LinearLayout.LayoutParams((32 * dp).toInt(), (1.5f * dp).toInt()).apply {
                leftMargin = (20 * dp).toInt()
                bottomMargin = (12 * dp).toInt()
            }
            layoutParams = lp
            setBackgroundColor(0x8CC8B48A.toInt())
        }
        container.addView(underline)

        // Reparent the content view into a scroll
        originalParent.removeView(content)
        content.visibility = View.VISIBLE

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(content, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        dialog.setContentView(container)

        // Set peek height + expanded behavior
        val peek = (screenH * heightFraction).toInt()
        dialog.behavior.peekHeight = peek
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = false

        // Restore content on dismiss
        dialog.setOnDismissListener {
            (content.parent as? android.view.ViewGroup)?.removeView(content)
            content.visibility = originalVisibility
            if (originalIndex >= 0) {
                originalParent.addView(content, originalIndex)
            } else {
                originalParent.addView(content)
            }
            if (currentBottomSheet === dialog) {
                currentBottomSheet = null
                currentBottomSheetContentId = 0
            }
        }

        currentBottomSheet = dialog
        currentBottomSheetContentId = contentResId
        dialog.show()
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

        // Data rights — export/delete (only when signed in)
        setupDataRights(overlay)
    }

    private fun setupDataRights(overlay: View) {
        val section = overlay.findViewById<View>(R.id.dataRightsSection) ?: return
        if (!AuthManager.isSignedIn) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE

        overlay.findViewById<TextView>(R.id.exportDataBtn)?.setOnClickListener {
            lifecycleScope.launch {
                val data = UserDataClient.exportData()
                if (data == null) {
                    android.widget.Toast.makeText(this@MainActivity, "Export failed — check your connection", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Share the JSON via Android's share sheet
                val jsonText = data.toString(2)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "WiM profile data")
                    putExtra(Intent.EXTRA_TEXT, jsonText)
                }
                startActivity(Intent.createChooser(intent, "Export WiM data"))
            }
        }

        overlay.findViewById<TextView>(R.id.deleteDataBtn)?.setOnClickListener {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Delete all cloud data?")
                .setMessage("This permanently deletes your synced profile from our servers. It cannot be undone. Your local data stays on this device until you uninstall. You will be signed out.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        val ok = UserDataClient.deleteData()
                        if (ok) {
                            AuthManager.signOut()
                            android.widget.Toast.makeText(this@MainActivity, "Cloud data deleted. Signed out.", android.widget.Toast.LENGTH_LONG).show()
                            dismissProfileOverlay()
                            recreate()
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "Delete failed — try again", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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

        // ── L1 / first language ──
        // profile_l1 = "" or "en" → no pack (native English path).
        // profile_l1 = "russian" | "spanish" | "mandarin" → L1PackHelper loads the
        // matching JSON and ReconstructClient injects its prompt block at L2/L3.
        // Source of truth is this Profile pref; do NOT create a parallel key.
        val savedL1 = prefs.getString("profile_l1", "") ?: ""
        val l1LanguageGroup = overlay.findViewById<View>(R.id.profileL1LanguageGroup)
        val nativeIsEnglish = savedL1.isBlank() || savedL1 == "en"
        l1LanguageGroup?.visibility = if (nativeIsEnglish) View.GONE else View.VISIBLE
        val l1NativeIds = listOf(R.id.l1NativeYes, R.id.l1NativeNo)
        val l1NativeValues = listOf("yes", "no")
        val savedNative = if (nativeIsEnglish) "yes" else "no"
        setupProfileGroup(overlay, l1NativeIds, l1NativeValues, "profile_l1_native", savedNative) { _, value ->
            if (value == "yes") {
                prefs.edit().putString("profile_l1", "en").apply()
                l1LanguageGroup?.visibility = View.GONE
            } else {
                l1LanguageGroup?.visibility = View.VISIBLE
                // Don't auto-set profile_l1 — wait for the language tap so we never
                // load a pack the user hasn't actually picked.
            }
        }
        val l1LangIds = listOf(R.id.l1Russian, R.id.l1Spanish, R.id.l1Mandarin, R.id.l1Hindi, R.id.l1Arabic, R.id.l1Farsi, R.id.l1French, R.id.l1German, R.id.l1Korean, R.id.l1Japanese, R.id.l1Other)
        val l1LangValues = listOf("russian", "spanish", "mandarin", "hindi", "arabic", "farsi", "french", "german", "korean", "japanese", "other")
        val savedLangChoice = if (savedL1 in l1LangValues) savedL1 else ""
        setupProfileGroup(overlay, l1LangIds, l1LangValues, "profile_l1", savedLangChoice)

        // ── Work hours ──
        val hoursIds = listOf(R.id.hoursMorning, R.id.hoursDay, R.id.hoursEvening, R.id.hoursFlexible)
        val hoursValues = listOf("morning", "nine_to_five", "evening", "flexible")
        val savedHours = prefs.getString("profile_hours", "nine_to_five") ?: "nine_to_five"
        setupProfileGroup(overlay, hoursIds, hoursValues, "profile_hours", savedHours)

        // ── Profile editor: vocabulary, corrections, triggers — card-bg pills ──
        // Each pill matches the Work/Life/Industry/Hours selector aesthetic
        // (card_bg + text_dim + monospace 11sp). Tap anywhere on a pill removes
        // it. Pills wrap into multiple rows as needed (ChipGroup handles flow).
        val profile = WimApp.profile()
        val pillDp = resources.displayMetrics.density

        fun renderLearned() {
            val summary = profile.learnedSummary()
            overlay.findViewById<TextView>(R.id.learnedCounts)?.text =
                "${summary.vocabulary} words  ·  ${summary.corrections} corrections  ·  ${summary.triggerWords} triggers"
            overlay.findViewById<TextView>(R.id.learnedOnsets)?.text =
                if (summary.difficultOnsets.isEmpty()) "Still learning — no reliable pattern yet"
                else summary.difficultOnsets.joinToString("   ") { (sound, weight) -> "$sound ${"%.1f".format(weight)}" }
            val acoustic = profile.prosodicBaseline()
            val wps = profile.baselineWps()
            overlay.findViewById<TextView>(R.id.learnedBaseline)?.text = when {
                acoustic != null -> buildString {
                    append("${acoustic.samples} recordings · ${"%.1f".format(acoustic.rateMean)} syllables/sec")
                    if (wps != null) append(" · ${"%.1f".format(wps)} words/sec")
                }
                else -> "Calibrating: ${profile.prosodicSampleCount()}/5 L4 recordings"
            }
        }

        overlay.findViewById<TextView>(R.id.clearSpeechBaselineBtn)?.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Reset personal baseline?")
                .setMessage("WiM will forget your learned speaking rate, pitch, and energy. Your vocabulary and corrections will stay.")
                .setPositiveButton("Reset") { _, _ ->
                    profile.clearPersonalSpeechBaseline()
                    renderLearned()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        renderLearned()

        fun makePill(label: String, onTap: () -> Unit): View {
            return TextView(this).apply {
                text = "$label  ✕"
                setTextColor(getColor(R.color.text_dim))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundResource(R.drawable.card_bg)
                setPadding((10 * pillDp).toInt(), (8 * pillDp).toInt(), (10 * pillDp).toInt(), (8 * pillDp).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { onTap() }
            }
        }

        // VOCABULARY pills
        val vocabChips = overlay.findViewById<com.google.android.material.chip.ChipGroup>(R.id.vocabChips)
        val vocabAddInput = overlay.findViewById<EditText>(R.id.vocabAddInput)
        val vocabAddBtn = overlay.findViewById<TextView>(R.id.vocabAddBtn)
        fun renderVocab() {
            vocabChips?.removeAllViews()
            profile.getVocabulary().forEach { word ->
                vocabChips?.addView(makePill(word) {
                    profile.removeVocabulary(word)
                    renderVocab()
                    renderLearned()
                })
            }
        }
        renderVocab()
        val addVocab: () -> Unit = {
            val w = vocabAddInput?.text?.toString()?.trim().orEmpty()
            if (w.isNotEmpty()) {
                profile.addVocabulary(w)
                vocabAddInput?.setText("")
                renderVocab()
                renderLearned()
            }
        }
        vocabAddBtn?.setOnClickListener { addVocab() }
        vocabAddInput?.setOnEditorActionListener { _, _, _ -> addVocab(); true }

        // CORRECTIONS pills ("from → to")
        val corrChips = overlay.findViewById<com.google.android.material.chip.ChipGroup>(R.id.corrChips)
        val corrAddFrom = overlay.findViewById<EditText>(R.id.corrAddFrom)
        val corrAddTo = overlay.findViewById<EditText>(R.id.corrAddTo)
        val corrAddBtn = overlay.findViewById<TextView>(R.id.corrAddBtn)
        fun renderCorr() {
            corrChips?.removeAllViews()
            profile.getCorrections().forEach { (from, to) ->
                corrChips?.addView(makePill("$from → $to") {
                    profile.removeCorrection(from)
                    renderCorr()
                    renderLearned()
                })
            }
        }
        renderCorr()
        val addCorr: () -> Unit = {
            val from = corrAddFrom?.text?.toString()?.trim().orEmpty()
            val to = corrAddTo?.text?.toString()?.trim().orEmpty()
            if (from.isNotEmpty() && to.isNotEmpty()) {
                profile.addCorrection(from, to)
                corrAddFrom?.setText("")
                corrAddTo?.setText("")
                renderCorr()
                renderLearned()
            }
        }
        corrAddBtn?.setOnClickListener { addCorr() }
        corrAddTo?.setOnEditorActionListener { _, _, _ -> addCorr(); true }

        // TRIGGER WORDS pills
        val triggerChips = overlay.findViewById<com.google.android.material.chip.ChipGroup>(R.id.triggerChips)
        val triggerAddInput = overlay.findViewById<EditText>(R.id.triggerAddInput)
        val triggerAddBtn = overlay.findViewById<TextView>(R.id.triggerAddBtn)
        fun renderTriggers() {
            triggerChips?.removeAllViews()
            profile.getTriggerWords().forEach { word ->
                triggerChips?.addView(makePill(word) {
                    profile.removeTriggerWord(word)
                    renderTriggers()
                    renderLearned()
                })
            }
        }
        renderTriggers()
        val addTrigger: () -> Unit = {
            val w = triggerAddInput?.text?.toString()?.trim().orEmpty()
            if (w.isNotEmpty()) {
                profile.addTriggerWord(w)
                triggerAddInput?.setText("")
                renderTriggers()
                renderLearned()
            }
        }
        triggerAddBtn?.setOnClickListener { addTrigger() }
        triggerAddInput?.setOnEditorActionListener { _, _, _ -> addTrigger(); true }

        // Clarifier sensitivity — three-state segmented control. Reuses the
        // setupProfileGroup helper so the visual idiom matches Work/Life,
        // Native English, etc.
        val sensIds = listOf(R.id.clarSensLow, R.id.clarSensMedium, R.id.clarSensHigh)
        val sensValues = listOf("low", "medium", "high")
        val currentSens = WimApp.prefs.getString("clarifier_sensitivity", "medium") ?: "medium"
        setupProfileGroup(overlay, sensIds, sensValues, "clarifier_sensitivity", currentSens)

        // Clarifier no-flag apps — chip group + "Add current app" button.
        // The button captures whichever foreground app the user was last in
        // (via accessibility's `currentForegroundPackage`), so the user can
        // pop into Chase / 1Password / Signal, return to WiM, and tap to
        // add it without ever needing to know the package name.
        val blacklistChips = overlay.findViewById<com.google.android.material.chip.ChipGroup>(R.id.clarBlacklistChips)
        val blacklistAddBtn = overlay.findViewById<TextView>(R.id.clarBlacklistAddBtn)
        fun renderBlacklist() {
            blacklistChips?.removeAllViews()
            WimApp.clarifierBlacklistedApps().forEach { pkg ->
                val chip = com.google.android.material.chip.Chip(this).apply {
                    text = pkg
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        val updated = WimApp.clarifierBlacklistedApps() - pkg
                        WimApp.prefs.edit().putString("clarifier_blacklist_apps", updated.joinToString("\n")).apply()
                        renderBlacklist()
                    }
                }
                blacklistChips.addView(chip)
            }
        }
        renderBlacklist()
        blacklistAddBtn?.setOnClickListener {
            val pkg = WimAccessibilityService.currentForegroundPackage
            if (pkg.isNullOrBlank() || pkg == packageName) {
                Toast.makeText(this, "Switch to the app you want to add, then come back and tap again", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val current = WimApp.clarifierBlacklistedApps()
            if (pkg in current) {
                Toast.makeText(this, "Already added", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updated = current + pkg
            WimApp.prefs.edit().putString("clarifier_blacklist_apps", updated.joinToString("\n")).apply()
            renderBlacklist()
            Toast.makeText(this, "Added: $pkg", Toast.LENGTH_SHORT).show()
        }

        // ── L1 pack contribution opt-in switch ──
        val switchContributeL1 = overlay.findViewById<android.widget.Switch>(R.id.switchContributeL1)
        switchContributeL1?.isChecked = WimApp.profile().contributeL1Data()
        switchContributeL1?.setOnCheckedChangeListener { _, checked ->
            WimApp.profile().setContributeL1Data(checked)
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

    // Slots cached for the dock-refresh callback; populated in setupBubbleDock.
    private var dockSlots: Map<String, android.view.View>? = null

    // ── Bubble dock: 3 parked bubble variants — coat rack model. ──
    // At rest: all three are "on the hanger." Tap one to pick it up — that
    // slot ghosts out (you took the coat) and the floating bubble appears.
    // Drag the bubble back onto any slot to hang it up again — that slot
    // becomes solid, the bubble disappears. Each slot mirrors the live
    // floating bubble's geometry (72dp bezel, 44dp knob, tick, IDLE label).
    private fun setupBubbleDock() {
        val dockBronze = findViewById<android.view.View>(R.id.dockBronze) ?: return
        val dockAluminum = findViewById<android.view.View>(R.id.dockAluminum) ?: return
        val dockGunmetal = findViewById<android.view.View>(R.id.dockGunmetal) ?: return
        val slots = mapOf(
            "bronze" to dockBronze,
            "aluminum" to dockAluminum,
            "gunmetal" to dockGunmetal
        )
        dockSlots = slots

        // Touch handler (not click) so we can read the finger's screen position
        // and spawn the bubble AT that spot — "natural pickup" feel. Tap and
        // drag both pick it up; drag just uses the latest finger position.
        val dragThresholdPx = (8f * resources.displayMetrics.density)
        slots.forEach { (key, view) ->
            var startRawX = 0f
            var startRawY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var handled = false
            view.setOnTouchListener { tapped, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startRawX = event.rawX
                        startRawY = event.rawY
                        handled = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        if (!handled) {
                            // Drag past threshold → spawn bubble at current finger.
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (kotlin.math.hypot(dx, dy) > dragThresholdPx) {
                                handled = true
                                pickUpBubble(key, tapped, event.rawX.toInt(), event.rawY.toInt())
                            }
                        } else {
                            // Already picked up — pipe the finger's screen coords
                            // straight to the service so the overlay tracks the
                            // drag even though its window never received the touch.
                            BubbleService.instance?.moveBubbleTo(
                                event.rawX.toInt(),
                                event.rawY.toInt()
                            )
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!handled) {
                            // Tap (no drag) — spawn at slot center.
                            val loc = IntArray(2)
                            tapped.getLocationOnScreen(loc)
                            val cx = loc[0] + tapped.width / 2
                            val cy = loc[1] + tapped.height / 2
                            pickUpBubble(key, tapped, cx, cy)
                        } else {
                            // Drag finished — if the user released over any
                            // dock slot, magnetic-snap the bubble into it.
                            // Same-slot release re-parks (returns the bubble
                            // to its original home). Different-slot release
                            // both parks AND swaps the theme. Single-gesture
                            // pickup-and-redock without lifting the finger.
                            BubbleService.instance?.tryDockSnap()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        handled = false
                        true
                    }
                    else -> false
                }
            }
        }

        // Initial paint — covers the case where BubbleService is already
        // running with a visible bubble when MainActivity opens.
        refreshDockGhost()

        // Publish dock rects once layout settles so BubbleService's drag-snap
        // knows where the dock is. Re-published on every resume (rotation /
        // window-metric changes would otherwise leave stale rects).
        dockBronze.post { publishDockRects(slots) }
    }

    /**
     * Swap the bubble theme, summon the floating bubble AT the given screen
     * coordinates (finger position), play the pickup haptic + slot squish.
     * Bubble appears centered on the finger so it reads as "picked up."
     */
    private fun pickUpBubble(theme: String, slotView: android.view.View, spawnCx: Int, spawnCy: Int) {
        WimApp.prefs.edit().putString("bubble_theme", theme).apply()
        BubbleService.instance?.refreshBubbleTheme()
        val intent = Intent(this, BubbleService::class.java).apply {
            action = "SHOW"
            putExtra("spawn_cx", spawnCx)
            putExtra("spawn_cy", spawnCy)
        }
        startForegroundService(intent)
        slotView.animate()
            .scaleX(0.82f).scaleY(0.82f)
            .setDuration(90)
            .withEndAction {
                slotView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.4f))
                    .setDuration(260)
                    .start()
            }
            .start()
        try {
            slotView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        } catch (_: Exception) {}
        refreshDockGhost()
    }

    /**
     * Coat-rack update — pure alpha crossfade. Picked-up slot goes invisible,
     * the other two stay at rest. No scale animation (scale-with-overshoot
     * reads as "shuffle" — neighboring slots appear to pop when one ghosts).
     */
    private fun refreshDockGhost() {
        val slots = dockSlots ?: return
        val activeTheme = WimApp.bubbleTheme()
        val bubbleOut = BubbleService.instance?.isBubbleVisible() == true
        slots.forEach { (theme, view) ->
            val isPickedUp = bubbleOut && theme == activeTheme
            view.animate()
                .alpha(if (isPickedUp) 0f else 1f)
                .setDuration(200)
                .start()
        }
    }

    private fun publishDockRects(slots: Map<String, android.view.View>) {
        val loc = IntArray(2)
        val rects = mutableMapOf<String, android.graphics.Rect>()
        slots.forEach { (theme, view) ->
            if (view.width > 0 && view.height > 0) {
                view.getLocationOnScreen(loc)
                rects[theme] = android.graphics.Rect(
                    loc[0], loc[1],
                    loc[0] + view.width, loc[1] + view.height
                )
            }
        }
        BubbleService.dockSlotRects = if (rects.isNotEmpty()) rects else null
    }

    // ── Auto-send toggle ──
    // User flips this on → after any successful injectText into a supported
    // app (Messages, Gmail, WhatsApp, Telegram, Signal), bubble goes green
    // with "SEND" label. Tap the green bubble → accessibility ACTION_CLICK
    // fires the app's Send button. First three taps show a disclaimer
    // overlay explaining the mechanism.
    private fun setupAutoSendToggle() {
        val toggle = findViewById<MaterialSwitch>(R.id.autoSendToggle) ?: return
        toggle.isChecked = WimApp.autoSendEnabled()
        styleToggle(toggle)
        toggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("auto_send_enabled", checked).apply()
        }
    }

    private fun setupFacehuggerToggle() {
        val toggle = findViewById<MaterialSwitch>(R.id.facehuggerSendToggle) ?: return
        toggle.isChecked = WimApp.facehuggerSendEnabled()
        styleToggle(toggle)
        toggle.setOnCheckedChangeListener { _, checked ->
            WimApp.prefs.edit().putBoolean("facehugger_send", checked).apply()
        }
    }

    // ── WiM logo = summon. Tap the gold Limelight letters to spawn the bubble. ──
    // Replaces the old "SUMMON" chip which was a wide wasted bar above LAYER.
    private fun setupLogoSummon() {
        val logo = findViewById<TextView>(R.id.wimLogoSummon) ?: return
        logo.setOnClickListener {
            val intent = Intent(this, BubbleService::class.java).apply { action = "SHOW" }
            startForegroundService(intent)
            try {
                logo.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            } catch (_: Exception) {}
            // Subtle flash so the tap registers visually.
            logo.animate()
                .scaleX(1.12f).scaleY(1.12f)
                .setDuration(110)
                .withEndAction {
                    logo.animate()
                        .scaleX(1f).scaleY(1f)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                        .setDuration(220)
                        .start()
                }
                .start()
            Toast.makeText(this, "Bubble summoned", Toast.LENGTH_SHORT).show()
        }
    }
}
