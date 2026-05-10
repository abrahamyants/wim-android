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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bouncer)

        // Limelight font for WiM logo
        limelight = Typeface.createFromAsset(assets, "limelight.ttf")
        findViewById<TextView>(R.id.bouncerLogo).typeface = limelight

        setupBackNavigation()

        // Permission row tap handlers
        findViewById<LinearLayout>(R.id.permOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<LinearLayout>(R.id.permAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<LinearLayout>(R.id.permMic).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        findViewById<LinearLayout>(R.id.permBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
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
            showPage(R.id.pageBubble)
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
            showPage(R.id.pageBubble)
        }
        findViewById<LinearLayout>(R.id.optionPersonal).setOnClickListener {
            WimApp.prefs.edit()
                .putString("tone", "casual")
                .apply()
            showPage(R.id.pageBubble)
        }
        findViewById<LinearLayout>(R.id.optionBoth).setOnClickListener {
            WimApp.prefs.edit()
                .putString("tone", "casual")
                .apply()
            showPage(R.id.pageBubble)
        }

        // Slide navigation buttons
        findViewById<View>(R.id.btnNextToBubbleDemo).setOnClickListener {
            showPage(R.id.pageRecon)
        }
        findViewById<View>(R.id.btnNextToFinal).setOnClickListener {
            showPage(R.id.pageAutoSend)
        }

        // Auto-send acknowledgment row — checkbox = single consent gate. If
        // the user ticks it, auto-send is enabled and the runtime popup
        // disclaimer is permanently dismissed. Skipping (Next without tick)
        // leaves auto-send off — they can still turn it on later in Settings.
        // Next is always enabled; the checkbox is optional, not a gate.
        val ackRow = findViewById<LinearLayout>(R.id.autoSendAck)
        val ackBox = findViewById<TextView>(R.id.autoSendCheck)
        val nextBtn = findViewById<View>(R.id.btnAutoSendNext)
        nextBtn.alpha = 1.0f // optional consent — Next always actionable
        ackRow.setOnClickListener {
            autoSendAcked = !autoSendAcked
            if (autoSendAcked) {
                ackBox.text = "☑"
                ackBox.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            } else {
                ackBox.text = "☐"
                ackBox.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            }
        }
        nextBtn.setOnClickListener {
            if (autoSendAcked) {
                // Single act of consent: enable auto-send + dismiss the runtime
                // disclaimer counter for life. User can still flip it off in
                // Settings → Auto-send.
                WimApp.prefs.edit()
                    .putBoolean("auto_send_enabled", true)
                    .putInt("auto_send_disclaimer_count", 99)
                    .apply()
            }
            // Samsung devices get an extra deep-sleep permission page between
            // auto-send ack and the final launch screen.
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                showPage(R.id.pageSamsungSleep)
            } else {
                findViewById<TextView>(R.id.finalLogo).typeface = limelight
                showPage(R.id.pageFinal)
            }
        }

        // Samsung deep-sleep page — three actions
        findViewById<LinearLayout>(R.id.btnSamsungOpenSettings).setOnClickListener {
            openSamsungDeepSleepSettings()
        }
        findViewById<LinearLayout>(R.id.btnSamsungDone).setOnClickListener {
            findViewById<TextView>(R.id.finalLogo).typeface = limelight
            showPage(R.id.pageFinal)
        }
        findViewById<LinearLayout>(R.id.btnSamsungSkip).setOnClickListener {
            findViewById<TextView>(R.id.finalLogo).typeface = limelight
            showPage(R.id.pageFinal)
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
        R.id.pagePermissions, R.id.pageTier, R.id.pageTone,
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
                    R.id.pageBubble -> showPage(if (chosenTier == "free") R.id.pageTier else R.id.pageTone)
                    R.id.pageTone -> showPage(R.id.pageTier)
                    R.id.pageTier -> {
                        showPage(R.id.pagePermissions)
                        hasAdvancedFromPermissions = false
                    }
                    // Permissions page — don't exit
                }
            }
        })
    }
}
