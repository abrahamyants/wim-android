package com.wim.app

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * First-run onboarding — 5 screens:
 * 1. Welcome (logo + tagline)
 * 2. How it works (3-step visual)
 * 3. Permissions (one at a time)
 * 4. Meet the bubble (interactive)
 * 5. You're all set (close the app)
 */
class OnboardingActivity : AppCompatActivity() {

    private var currentScreen = 0
    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1A2E.toInt())
        }
        setContentView(container)
        showScreen(0)
    }

    private fun showScreen(index: Int) {
        currentScreen = index
        container.removeAllViews()
        when (index) {
            0 -> showWelcome()
            1 -> showHowItWorks()
            2 -> showPermissions()
            3 -> showMeetBubble()
            4 -> showAllSet()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(0xFFD4A574.toInt())
        typeface = Typeface.create("serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        letterSpacing = 0.05f
    }

    private fun makeBody(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xBBFFFFFF.toInt())
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(12), dp(32), dp(12))
        setLineSpacing(0f, 1.4f)
    }

    private fun makeButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF1A1A2E.toInt())
        setBackgroundColor(0xFFD4A574.toInt())
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(32), dp(14), dp(32), dp(14))
        setOnClickListener { onClick() }
    }

    // ── Screen 1: Welcome ──

    private fun showWelcome() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(32), dp(80), dp(32), dp(80))
        }

        // WiM in Limelight
        layout.addView(TextView(this).apply {
            text = "WiM"
            textSize = 48f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            try { typeface = resources.getFont(R.font.limelight) } catch (_: Exception) {}
        })

        layout.addView(TextView(this).apply {
            text = "What I Meant"
            textSize = 14f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, dp(4), 0, dp(40))
        })

        layout.addView(makeBody("Voice-to-text that understands you.\nSpeak messy, get clean text."))

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        layout.addView(makeButton("Get Started") { showScreen(1) })

        container.addView(layout)
    }

    // ── Screen 2: How it works ──

    private fun showHowItWorks() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(32), dp(60), dp(32), dp(60))
        }

        layout.addView(makeTitle("How It Works"))

        val steps = listOf(
            Pair("1. SPEAK", "Tap the floating bubble and talk.\nSay it however it comes out."),
            Pair("2. CLEAN", "Fillers removed. Grammar fixed.\nDisfluencies stripped automatically."),
            Pair("3. PASTE", "Clean text injected into whatever\napp you're typing in.")
        )

        for ((title, desc) in steps) {
            layout.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(0xFFEE5A24.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(4))
            })
            layout.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            })
        }

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        layout.addView(makeButton("Next") { showScreen(2) })

        container.addView(layout)
    }

    // ── Screen 3: Permissions ──

    private fun showPermissions() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(32), dp(60), dp(32), dp(60))
        }

        layout.addView(makeTitle("Permissions"))
        layout.addView(makeBody("WiM needs a few permissions to work.\nEach one has a purpose."))

        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val accessibilityOk = isAccessibilityEnabled()

        fun permRow(label: String, why: String, granted: Boolean, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(0xFF252530.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }

                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@OnboardingActivity).apply {
                        text = label
                        textSize = 13f
                        setTextColor(0xFFFFFFFF.toInt())
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@OnboardingActivity).apply {
                        text = why
                        textSize = 11f
                        setTextColor(0x99FFFFFF.toInt())
                    })
                })

                addView(TextView(this@OnboardingActivity).apply {
                    text = if (granted) "✓" else "GRANT"
                    textSize = 12f
                    setTextColor(if (granted) 0xFF44DD44.toInt() else 0xFFEE5A24.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    if (!granted) setOnClickListener { onClick() }
                })
            }
        }

        layout.addView(permRow("Overlay", "So the bubble floats over other apps", overlayOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        layout.addView(permRow("Accessibility", "So WiM can detect text fields and paste text", accessibilityOk) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        layout.addView(permRow("Microphone", "So WiM can hear your voice", micOk) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        })

        val batteryOk = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
        layout.addView(permRow("Battery", "So the bubble stays alive (recommended)", batteryOk) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        })

        // Samsung-specific
        if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            layout.addView(TextView(this).apply {
                text = "Samsung users: Also add WiM to Settings → Battery → Never sleeping apps"
                textSize = 10f
                setTextColor(0xFFEE5A24.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        val nextBtn = makeButton("Next") { showScreen(3) }
        // Only enable if core permissions granted
        if (!overlayOk || !accessibilityOk || !micOk) {
            nextBtn.alpha = 0.4f
            nextBtn.setOnClickListener {
                Toast.makeText(this, "Please grant overlay, accessibility, and microphone first", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(nextBtn)

        // Refresh button to recheck permissions
        layout.addView(TextView(this).apply {
            text = "Refresh"
            textSize = 12f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { showScreen(2) } // rebuild the screen
        })

        container.addView(layout)
    }

    // ── Screen 4: Meet the bubble ──

    private fun showMeetBubble() {
        // Start the bubble service so the user can see it
        try {
            startForegroundService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
        } catch (_: Exception) {}

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(32), dp(60), dp(32), dp(60))
        }

        layout.addView(makeTitle("Meet the Bubble"))
        layout.addView(makeBody("Your floating control center.\nIt works in any app, even when WiM is closed."))

        val tips = listOf(
            "TAP the bubble to start recording",
            "TAP again to stop and process",
            "HOLD to record, release to stop",
            "DOUBLE-TAP for quick settings",
            "DRAG to move it anywhere",
            "DRAG to bottom to hide it",
            "SHAKE phone twice to show/hide"
        )

        for (tip in tips) {
            layout.addView(TextView(this).apply {
                text = "• $tip"
                textSize = 13f
                setTextColor(0xBBFFFFFF.toInt())
                setPadding(dp(16), dp(6), dp(16), dp(6))
            })
        }

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        layout.addView(makeButton("Got it") { showScreen(4) })

        container.addView(layout)
    }

    // ── Screen 5: All set ──

    private fun showAllSet() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(32), dp(80), dp(32), dp(80))
        }

        layout.addView(makeTitle("You're All Set"))

        layout.addView(makeBody("Close this app.\nThe bubble is everything you need.\n\nIt floats over every app, records your voice, cleans it up, and pastes it where you're typing.\n\nThe bubble is your app now."))

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        layout.addView(makeButton("Let's Go") {
            // Mark onboarding complete
            WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        container.addView(layout)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("$packageName/${WimAccessibilityService::class.java.canonicalName}")
    }

    override fun onResume() {
        super.onResume()
        // Refresh permissions screen when returning from settings
        if (currentScreen == 2) showScreen(2)
    }
}
