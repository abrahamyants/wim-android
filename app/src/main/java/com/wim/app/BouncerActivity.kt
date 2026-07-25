package com.wim.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat

/**
 * The Bouncer — gatekeeps OS permissions, tier choice, and tone before
 * the bubble can deploy.
 *
 * Page 1: 5 permission rows. Auto-advances when overlay + accessibility + mic granted.
 * Page 2: Free or Upgrade.
 * Page 3: Work or Personal (upgrade only).
 *
 * After all pages: finish() → BubbleService ONBOARDING → BubbleDemoScript.
 */
class BouncerActivity : AppCompatActivity() {

    private var hasAdvancedFromPermissions = false
    private var chosenTier = "free"
    private var autoSendAcked = false
    private lateinit var limelight: Typeface

    // Polls for Settings-detour permission grants (overlay, etc.) so the
    // user is snapped back to the bouncer instead of having to navigate
    // out of Android Settings manually. Mirror of the accessibility-side
    // auto-return wired in WimAccessibilityService.onServiceConnected.
    // DO NOT REVERT — first-impression UX requirement. See CLAUDE.md "DO".
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bouncer)

        // Mark this as the active onboarding host for the accessibility
        // snap-back (WimAccessibilityService.onServiceConnected). The Compose
        // OnboardingActivity writes "compose"; last-opened host wins.
        WimApp.prefs.edit().putString("onboarding_host", "bouncer").apply()

        // Limelight font for WiM logo
        limelight = Typeface.createFromAsset(assets, "limelight.ttf")
        findViewById<TextView>(R.id.bouncerLogo).typeface = limelight

        setupBackNavigation()

        // Permission row tap handlers
        findViewById<LinearLayout>(R.id.permOverlay).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) { return@setOnClickListener }
            // Auto-return-to-bouncer once the user flips the toggle in Settings.
            // The OS has no callback for overlay grants — polling is the standard
            // workaround. DO NOT REVERT.
            pollForOverlayGrant()
        }
        findViewById<LinearLayout>(R.id.permAccessibility).setOnClickListener {
            // Deep-link straight to WiM's accessibility-service toggle page
            // (undocumented but widely-supported extras) instead of dumping
            // the user on the master Accessibility list. Falls back to the
            // master list if the deep-link extras are ignored.
            try {
                val componentName = ComponentName(this, WimAccessibilityService::class.java).flattenToString()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    putExtra(":settings:fragment_args_key", componentName)
                    putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                        putString(":settings:fragment_args_key", componentName)
                    })
                }
                startActivity(intent)
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
        }
        findViewById<LinearLayout>(R.id.permMic).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        findViewById<LinearLayout>(R.id.permBattery).setOnClickListener {
            try {
                // Battery-optimization LIST, not the one-tap dialog — the dialog
                // needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which WiM no longer
                // declares (Play reviews it case by case). See PermissionLauncher.
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
        findViewById<LinearLayout>(R.id.permNotifications).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }

        // Tier choice handlers — onboarding never writes the `layer` pref. The
        // post-onboarding default is L1 (WimApp.layer() falls back to 1). The
        // user opts into higher layers via the layer buttons on MainActivity
        // when they're ready. Tone preferences ARE saved here.
        findViewById<LinearLayout>(R.id.optionFree).setOnClickListener {
            chosenTier = "free"
            WimApp.prefs.edit()
                .putString("tone", "casual")
                .apply()
            showPage(R.id.pageQuestions)
        }
        findViewById<LinearLayout>(R.id.optionUpgrade).setOnClickListener {
            chosenTier = "upgrade"
            showPage(R.id.pageTone)
        }

        // Tone choice handlers
        findViewById<LinearLayout>(R.id.optionWork).setOnClickListener {
            WimApp.prefs.edit()
                .putString("tone", "professional")
                .apply()
            showPage(R.id.pageQuestions)
        }
        findViewById<LinearLayout>(R.id.optionPersonal).setOnClickListener {
            WimApp.prefs.edit()
                .putString("tone", "casual")
                .apply()
            showPage(R.id.pageQuestions)
        }
        findViewById<LinearLayout>(R.id.optionBoth).setOnClickListener {
            WimApp.prefs.edit()
                .putString("tone", "casual")
                .apply()
            showPage(R.id.pageQuestions)
        }

        // ── Intro page (page 0) — "Let's set it up" advances to permissions ──
        findViewById<View>(R.id.btnIntroNext).setOnClickListener {
            showPage(R.id.pagePermissions)
        }

        // ── Quick set-up questions page ──
        // Three chip groups + 3 watch-word EditTexts + Yes/No language with
        // spinner + free-text "Other". Selections persist immediately to
        // SharedPreferences so config changes don't lose them.
        val whenIds = mapOf(
            R.id.qWhen95 to "9to5",
            R.id.qWhenNightOwl to "night_owl",
            R.id.qWhenShifts to "shifts",
            R.id.qWhenOffClock to "off_clock"
        )
        val whereIds = mapOf(
            R.id.qWhereOffice to "office",
            R.id.qWhereHybrid to "hybrid",
            R.id.qWhereRemote to "remote",
            R.id.qWhereMobile to "mobile"
        )
        val industryIds = mapOf(
            R.id.qIndFinance to "finance",
            R.id.qIndLegal to "legal",
            R.id.qIndMedical to "medical",
            R.id.qIndTech to "tech",
            R.id.qIndSales to "sales",
            R.id.qIndTrades to "trades",
            R.id.qIndFood to "food",
            R.id.qIndEducation to "education",
            R.id.qIndOther to "other"
        )
        fun setChipPicked(chipId: Int, picked: Boolean) {
            findViewById<TextView>(chipId).setTextColor(
                ContextCompat.getColor(this, if (picked) R.color.section_header else R.color.text_dim)
            )
        }
        fun pickFromGroup(groupIds: Set<Int>, pickedId: Int) {
            groupIds.forEach { setChipPicked(it, it == pickedId) }
        }
        whenIds.forEach { (id, value) ->
            findViewById<TextView>(id).setOnClickListener {
                WimApp.prefs.edit().putString("work_hours", value).apply()
                pickFromGroup(whenIds.keys, id)
            }
        }
        whereIds.forEach { (id, value) ->
            findViewById<TextView>(id).setOnClickListener {
                // `work_location`, NOT `work_setup`. This group asks where the
                // user works (office/hybrid/remote/mobile); the Compose
                // onboarding's `work_setup` asks what they use WiM for
                // (work/life/both) and feeds ToneRouting. They were sharing one
                // key with incompatible vocabularies, so whichever screen the
                // user happened to see decided what the other one meant.
                WimApp.prefs.edit().putString("work_location", value).apply()
                pickFromGroup(whereIds.keys, id)
            }
        }
        industryIds.forEach { (id, value) ->
            findViewById<TextView>(id).setOnClickListener {
                WimApp.prefs.edit().putString("profile_industry", value).apply()
                pickFromGroup(industryIds.keys, id)
            }
        }

        // Q4 first language: Yes/No → spinner → optional "Other" free-text.
        val l1Codes = listOf("russian", "spanish", "mandarin", "hindi", "arabic", "farsi", "french", "german", "korean", "japanese", "other")
        val l1Labels = listOf("Russian", "Spanish", "Mandarin", "Hindi", "Arabic", "Farsi", "French", "German", "Korean", "Japanese", "Other (type below)")
        val langSpinner = findViewById<android.widget.Spinner>(R.id.qLangSpinner)
        val langOther = findViewById<android.widget.EditText>(R.id.qLangOther)
        val langAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, l1Labels)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        langSpinner.adapter = langAdapter
        findViewById<TextView>(R.id.qLangYes).setOnClickListener {
            WimApp.prefs.edit().putString("profile_l1", "").apply()
            setChipPicked(R.id.qLangYes, true)
            setChipPicked(R.id.qLangNoBtn, false)
            langSpinner.visibility = View.GONE
            langOther.visibility = View.GONE
        }
        findViewById<TextView>(R.id.qLangNoBtn).setOnClickListener {
            setChipPicked(R.id.qLangYes, false)
            setChipPicked(R.id.qLangNoBtn, true)
            langSpinner.visibility = View.VISIBLE
        }
        langSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = l1Codes[position]
                if (code == "other") {
                    langOther.visibility = View.VISIBLE
                } else {
                    langOther.visibility = View.GONE
                    WimApp.prefs.edit().putString("profile_l1", code).apply()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // "All set" — adds watch-words to vocabulary, finalises "Other" L1
        // free-text if used, then advances to the bubble demo.
        findViewById<View>(R.id.btnQuestionsNext).setOnClickListener {
            listOf(R.id.qWord1, R.id.qWord2, R.id.qWord3).forEach { wid ->
                val word = findViewById<android.widget.EditText>(wid).text.toString().trim()
                if (word.isNotEmpty()) WimApp.profile().addVocabulary(word)
            }
            if (langSpinner.visibility == View.VISIBLE && langSpinner.selectedItemPosition >= 0) {
                val code = l1Codes[langSpinner.selectedItemPosition]
                if (code == "other") {
                    val typed = langOther.text.toString().trim()
                    WimApp.prefs.edit()
                        .putString("profile_l1", if (typed.isNotEmpty()) "other:$typed" else "")
                        .apply()
                }
            }
            showPage(R.id.pageBubble)
        }

        // Start the flow on the intro page. setContentView leaves
        // pagePermissions as the default-visible page in XML; calling
        // showPage here hides every page and reveals the intro.
        showPage(R.id.pageIntro)

        // Slide navigation buttons
        findViewById<View>(R.id.btnNextToBubbleDemo).setOnClickListener {
            showPage(R.id.pageRecon)
        }
        findViewById<View>(R.id.btnNextToFinal).setOnClickListener {
            showPage(R.id.pageAutoSend)
        }

        // Tap-to-send acknowledgment row — checkbox = single consent gate. If
        // the user ticks it, tap-to-send assist is enabled and the runtime popup
        // disclaimer is permanently dismissed. Skipping (Next without tick)
        // leaves tap-to-send assist off — they can still turn it on later in Settings.
        // Next is always enabled; the checkbox is optional, not a gate.
        val ackRow = findViewById<LinearLayout>(R.id.autoSendAck)
        val ackBox = findViewById<TextView>(R.id.autoSendCheck)
        val nextBtn = findViewById<View>(R.id.btnAutoSendNext)
        nextBtn.alpha = 1.0f // optional consent — Next always actionable
        androidx.core.view.ViewCompat.setAccessibilityDelegate(ackRow,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: android.view.View,
                    info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = autoSendAcked
                    info.className = "android.widget.CheckBox"
                    info.text = getString(R.string.bouncer_send_ack_cd)
                }
            })
        ackRow.setOnClickListener {
            autoSendAcked = !autoSendAcked
            if (autoSendAcked) {
                ackBox.text = "☑"
                ackBox.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            } else {
                ackBox.text = "☐"
                ackBox.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            }
            ackRow.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        nextBtn.setOnClickListener {
            if (autoSendAcked) {
                // Single act of consent: enable tap-to-send assist + dismiss the runtime
                // disclaimer counter for life. User can still flip it off in
                // Settings → Tap-to-send assist.
                WimApp.prefs.edit()
                    .putBoolean("auto_send_enabled", true)
                    .putInt("auto_send_disclaimer_count", 99)
                    .apply()
            }
            // Samsung devices get an extra deep-sleep permission page between
            // tap-to-send acknowledgment and the final launch screen.
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                showPage(R.id.pageSamsungSleep)
            } else {
                showFinalPage()
            }
        }

        // Samsung deep-sleep page — three actions
        findViewById<LinearLayout>(R.id.btnSamsungOpenSettings).setOnClickListener {
            openSamsungDeepSleepSettings()
        }
        findViewById<LinearLayout>(R.id.btnSamsungDone).setOnClickListener {
            showFinalPage()
        }
        findViewById<LinearLayout>(R.id.btnSamsungSkip).setOnClickListener {
            showFinalPage()
        }

        findViewById<View>(R.id.btnLaunchBubble).setOnClickListener {
            launchBubbleOnboarding()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasAdvancedFromPermissions) {
            refreshPermissions()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasAdvancedFromPermissions) {
            refreshPermissions()
        }
    }

    /**
     * Poll Settings.canDrawOverlays up to 30 seconds. As soon as the user
     * flips the toggle on, snap BouncerActivity back to the foreground so
     * they don't have to back-button out of Android Settings manually.
     *
     * Why polling: ACTION_MANAGE_OVERLAY_PERMISSION has no result callback.
     * The standard Android workaround is a polling loop. Compare to the
     * accessibility flow, which DOES get a callback via
     * WimAccessibilityService.onServiceConnected.
     *
     * DO NOT REVERT — first-impression UX requirement for sideloaded distribution.
     * See CLAUDE.md "DO" section.
     */
    private fun pollForOverlayGrant() {
        var attempts = 0
        val r = object : Runnable {
            override fun run() {
                attempts++
                if (Settings.canDrawOverlays(this@BouncerActivity)) {
                    val back = Intent(this@BouncerActivity, BouncerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try { startActivity(back) } catch (_: Exception) {}
                    return
                }
                if (attempts < 60) pollHandler.postDelayed(this, 500)
            }
        }
        pollHandler.postDelayed(r, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacksAndMessages(null)
    }

    private fun refreshPermissions() {
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = isAccessibilityEnabled()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val notifications = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        updatePermRow(R.id.permOverlay, R.id.permOverlayCheck, R.id.permOverlayTap, overlay)
        updatePermRow(R.id.permAccessibility, R.id.permAccessibilityCheck, R.id.permAccessibilityTap, accessibility)
        updatePermRow(R.id.permMic, R.id.permMicCheck, R.id.permMicTap, mic)
        updatePermRow(R.id.permBattery, R.id.permBatteryCheck, R.id.permBatteryTap, battery)
        updatePermRow(R.id.permNotifications, R.id.permNotificationsCheck, R.id.permNotificationsTap, notifications)

        // Auto-advance when ALL 5 permissions are granted
        if (overlay && accessibility && mic && battery && notifications && !hasAdvancedFromPermissions) {
            hasAdvancedFromPermissions = true
            showPage(R.id.pageTier)
        }
    }

    private fun updatePermRow(rowId: Int, checkId: Int, tapId: Int, granted: Boolean) {
        val check = findViewById<TextView>(checkId)
        val tap = findViewById<TextView>(tapId)
        if (granted) {
            check.text = "✓"
            check.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            tap.visibility = View.GONE
        } else {
            check.text = "!"
            check.setTextColor(ContextCompat.getColor(this, R.color.chip_value))
            tap.visibility = View.VISIBLE
        }
    }

    private val allPages = listOf(
        R.id.pageIntro,
        R.id.pagePermissions, R.id.pageTier, R.id.pageTone,
        R.id.pageQuestions,
        R.id.pageBubble, R.id.pageRecon, R.id.pageAutoSend,
        R.id.pageSamsungSleep, R.id.pageFinal
    )

    private fun showPage(pageId: Int) {
        allPages.forEach { findViewById<View>(it).visibility = View.GONE }
        findViewById<View>(pageId).visibility = View.VISIBLE
    }

    private fun currentPage(): Int = allPages.firstOrNull {
        findViewById<View>(it).visibility == View.VISIBLE
    } ?: R.id.pagePermissions

    private fun launchBubbleOnboarding() {
        WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()
        startForegroundService(Intent(this, BubbleService::class.java).apply {
            action = "SHOW"
        })
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openSamsungDeepSleepSettings() {
        // Primary: Samsung's own battery management UI (com.samsung.android.lool).
        // Ships on One UI 3+. If the component doesn't exist on an older or
        // carrier-modified build, fall through to the generic app-detail screen.
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            })
            return
        } catch (_: Exception) {}

        // Fallback: generic app details screen — user can navigate to battery
        // settings from there.
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {}
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("$packageName/${WimAccessibilityService::class.java.canonicalName}")
    }

    /**
     * Shows pageFinal and sets the slide counter correctly.
     * Samsung users arrive via pageSamsungSleep (slide 6/7 → 7/7).
     * Non-Samsung users arrive via pageAutoSend (slide 6/6).
     */
    private fun showFinalPage() {
        findViewById<TextView>(R.id.finalLogo).typeface = limelight
        val indicator = findViewById<TextView>(R.id.finalPageIndicator)
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            indicator.setText(R.string.bouncer_slide_7_7)
        } else {
            indicator.setText(R.string.bouncer_slide_6_6)
        }
        showPage(R.id.pageFinal)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentPage()) {
                    R.id.pageFinal -> showPage(
                        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true))
                            R.id.pageSamsungSleep else R.id.pageAutoSend
                    )
                    R.id.pageSamsungSleep -> showPage(R.id.pageAutoSend)
                    R.id.pageAutoSend -> showPage(R.id.pageRecon)
                    R.id.pageRecon -> showPage(R.id.pageBubble)
                    R.id.pageBubble -> showPage(R.id.pageQuestions)
                    R.id.pageQuestions -> showPage(if (chosenTier == "free") R.id.pageTier else R.id.pageTone)
                    R.id.pageTone -> showPage(R.id.pageTier)
                    R.id.pageTier -> {
                        showPage(R.id.pagePermissions)
                        hasAdvancedFromPermissions = false
                    }
                    R.id.pagePermissions -> showPage(R.id.pageIntro)
                    // Intro page — don't exit (first screen)
                }
            }
        })
    }
}
