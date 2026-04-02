package com.wim.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Premium animated onboarding — 5 screens with personality.
 */
class OnboardingActivity : AppCompatActivity() {

    private var currentScreen = 0
    private lateinit var container: FrameLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this).apply { setBackgroundColor(0xFF1A1A2E.toInt()) }
        setContentView(container)
        showScreen(0)
    }

    private fun showScreen(index: Int) {
        currentScreen = index
        container.removeAllViews()
        when (index) {
            0 -> showWelcome()
            1 -> showDemo()
            2 -> showHowItWorks()
            3 -> showPermissions()
            4 -> showMeetBubble()
            5 -> showAllSet()
        }
    }

    // ══════════════════════════════════════════
    // SCREEN 1.5: ANIMATED DEMO
    // ══════════════════════════════════════════

    private fun showDemo() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Animation view — takes most of the screen
        val demo = ReconstructionDemoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        layout.addView(demo)
        demo.startAnimation()

        // Button at bottom
        val btnFrame = FrameLayout(this).apply {
            setPadding(dp(32), dp(12), dp(32), dp(32))
        }
        val btn = makeActionButton("Next") {
            demo.stopAnimation()
            showScreen(2)
        }
        btnFrame.addView(btn)
        layout.addView(btnFrame)

        container.addView(layout)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Entrance animations ──

    private fun fadeSlideIn(view: View, delayMs: Long, fromY: Float = 60f) {
        view.alpha = 0f
        view.translationY = fromY
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(delayMs)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun pulseIn(view: View, delayMs: Long) {
        view.alpha = 0f
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setStartDelay(delayMs)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    // ══════════════════════════════════════════
    // SCREEN 1: WELCOME
    // ══════════════════════════════════════════

    private fun showWelcome() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(40), dp(100), dp(40), dp(80))
        }

        // WiM logo
        val logo = TextView(this).apply {
            text = "WiM"
            textSize = 56f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            try { typeface = resources.getFont(R.font.limelight) } catch (_: Exception) {
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
        }
        layout.addView(logo)
        pulseIn(logo, 200)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "What I Meant"
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            setPadding(0, dp(4), 0, 0)
        }
        layout.addView(subtitle)
        fadeSlideIn(subtitle, 500)

        // Divider line
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(2)).apply {
                gravity = Gravity.CENTER
                topMargin = dp(32)
                bottomMargin = dp(32)
            }
            setBackgroundColor(0xFFEE5A24.toInt())
        }
        layout.addView(divider)
        fadeSlideIn(divider, 700)

        // Tagline
        val tagline = TextView(this).apply {
            text = "Speak messy.\nGet clean text."
            textSize = 20f
            setTextColor(0xDDFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        layout.addView(tagline)
        fadeSlideIn(tagline, 900)

        // Second tagline
        val tagline2 = TextView(this).apply {
            text = "Voice-to-text that understands\nwhat you meant to say."
            textSize = 13f
            setTextColor(0x88FFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(16), 0, 0)
        }
        layout.addView(tagline2)
        fadeSlideIn(tagline2, 1100)

        // Spacer
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        // Full Setup — walks through demo, how-it-works, permissions, meet bubble
        val btnFull = makeActionButton("Full Setup") { showScreen(1) }
        layout.addView(btnFull)
        fadeSlideIn(btnFull, 1400)

        // Quick Setup — straight to permissions, get the bubble running fast
        val btnQuick = TextView(this).apply {
            text = "Quick Setup — just the essentials"
            textSize = 13f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setOnClickListener { showScreen(3) }
        }
        layout.addView(btnQuick)
        fadeSlideIn(btnQuick, 1600)

        val quickHint = TextView(this).apply {
            text = "Grant permissions, meet your bubble, go."
            textSize = 10f
            setTextColor(0x66FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        layout.addView(quickHint)
        fadeSlideIn(quickHint, 1700)

        container.addView(layout)
    }

    // ══════════════════════════════════════════
    // SCREEN 2: HOW IT WORKS
    // ══════════════════════════════════════════

    private fun showHowItWorks() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(32), dp(60), dp(32), dp(60))
        }

        val title = makeScreenTitle("How It Works")
        layout.addView(title)
        fadeSlideIn(title, 100)

        val steps = listOf(
            Triple("🎙", "SPEAK", "Talk naturally into the floating\nbubble. Don't worry about how\nit comes out."),
            Triple("✨", "CLEAN", "Fillers stripped. Repetitions removed.\nGrammar fixed. Intent preserved.\nAll automatic."),
            Triple("📋", "PASTE", "Clean text appears right where\nyou're typing. Any app. Instantly.")
        )

        steps.forEachIndexed { i, (icon, title2, desc) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF252530.toInt())
                setPadding(dp(20), dp(16), dp(20), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            }

            card.addView(TextView(this).apply {
                text = icon
                textSize = 32f
                setPadding(0, 0, dp(16), 0)
            })

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = title2
                textSize = 16f
                setTextColor(0xFFEE5A24.toInt())
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
            })
            textCol.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(0xAAFFFFFF.toInt())
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(textCol)

            layout.addView(card)
            fadeSlideIn(card, 300L + (i * 250L))
        }

        // Arrow between steps would be nice but text works
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        val btn = makeActionButton("Next") { showScreen(3) }
        layout.addView(btn)
        fadeSlideIn(btn, 1100)

        container.addView(layout)
    }

    // ══════════════════════════════════════════
    // SCREEN 3: PERMISSIONS
    // ══════════════════════════════════════════

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

        val title = makeScreenTitle("Before We Start")
        layout.addView(title)
        fadeSlideIn(title, 100)

        val sub = TextView(this).apply {
            text = "WiM needs these to work properly.\nTap each one to enable."
            textSize = 13f
            setTextColor(0x88FFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, dp(16))
        }
        layout.addView(sub)
        fadeSlideIn(sub, 250)

        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val accessibilityOk = isAccessibilityEnabled()
        val batteryOk = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

        data class Perm(val icon: String, val name: String, val why: String, val granted: Boolean, val action: () -> Unit)

        val perms = listOf(
            Perm("🔲", "Display Over Apps", "The floating bubble needs to\nappear on top of other apps", overlayOk) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            Perm("♿", "Accessibility", "WiM detects text fields and\npastes your clean text into them", accessibilityOk) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Perm("🎤", "Microphone", "WiM needs to hear your voice\nto transcribe it", micOk) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            },
            Perm("🔋", "Battery", "Keeps the bubble alive.\nWithout this, your phone\nwill kill WiM in the background", batteryOk) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            },
            Perm("🔔", "Notifications", "Required for the foreground\nservice that keeps your\nbubble running", android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
                }
            }
        )

        // Google sign-in recommendation
        val signInHint = TextView(this).apply {
            text = "💡 Sign in with Google to unlock Layer III+\nYour corrections teach WiM over time."
            textSize = 11f
            setTextColor(0xAAD4A574.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(20), 0, 0)
        }
        layout.addView(signInHint)
        fadeSlideIn(signInHint, 400 + perms.size * 150L)

        perms.forEachIndexed { i, perm ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (perm.granted) 0xFF1E2E1E.toInt() else 0xFF2E2530.toInt())
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                if (!perm.granted) setOnClickListener { perm.action(); }
            }

            row.addView(TextView(this).apply {
                text = perm.icon
                textSize = 24f
                setPadding(0, 0, dp(14), 0)
            })

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = perm.name
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                text = perm.why
                textSize = 11f
                setTextColor(0x99FFFFFF.toInt())
                setLineSpacing(0f, 1.2f)
            })
            row.addView(col)

            row.addView(TextView(this).apply {
                text = if (perm.granted) "✓" else "TAP"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (perm.granted) 0xFF44DD44.toInt() else 0xFFD4A574.toInt())
                gravity = Gravity.CENTER
                minWidth = dp(44)
            })

            layout.addView(row)
            fadeSlideIn(row, 400L + (i * 200L))
        }

        // Samsung warning
        if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            val samsung = TextView(this).apply {
                text = "📱 Samsung: Also add WiM to\nSettings → Battery → Never sleeping apps"
                textSize = 11f
                setTextColor(0xFFEE5A24.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
                setLineSpacing(0f, 1.3f)
            }
            layout.addView(samsung)
            fadeSlideIn(samsung, 1200)
        }

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        val allGranted = overlayOk && accessibilityOk && micOk && batteryOk
        val btn = makeActionButton(if (allGranted) "Next" else "Grant permissions above") {
            if (allGranted) {
                showScreen(4)
            } else {
                Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show()
            }
        }
        if (!allGranted) btn.alpha = 0.5f
        layout.addView(btn)
        fadeSlideIn(btn, 1300)

        // Refresh
        val refresh = TextView(this).apply {
            text = "↻ I've granted them, refresh"
            textSize = 12f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { showScreen(3) }
        }
        layout.addView(refresh)
        fadeSlideIn(refresh, 1400)

        container.addView(layout)
    }

    // ══════════════════════════════════════════
    // SCREEN 4: MEET THE BUBBLE
    // ══════════════════════════════════════════

    private var demoScript: BubbleDemoScript? = null

    private fun showMeetBubble() {
        // Launch the bubble
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
            setPadding(dp(32), dp(80), dp(32), dp(60))
        }

        val title = makeScreenTitle("The Bubble's Show")
        layout.addView(title)
        fadeSlideIn(title, 100)

        val sub = TextView(this).apply {
            text = "Watch the bubble. It'll teach you everything."
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        layout.addView(sub)
        fadeSlideIn(sub, 300)

        // Status text that updates during the demo
        val statusText = TextView(this).apply {
            text = "Starting the show..."
            textSize = 12f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        layout.addView(statusText)
        pulseIn(statusText, 500)

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        // Button appears after demo completes
        val btn = makeActionButton("My Turn") {
            demoScript?.cancel()
            showScreen(5)
        }
        btn.alpha = 0f
        layout.addView(btn)

        container.addView(layout)

        // Start the demo after a short delay (let bubble appear)
        handler.postDelayed({
            statusText.text = "👀 Watch the bubble..."
            demoScript = BubbleDemoScript {
                // Demo complete — show the button
                runOnUiThread {
                    statusText.text = "Your turn. Try tapping the bubble."
                    btn.animate().alpha(1f).setDuration(500).start()
                }
            }
            demoScript?.start()
        }, 1500)
    }

    // ══════════════════════════════════════════
    // SCREEN 5: ALL SET
    // ══════════════════════════════════════════

    private fun showAllSet() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(40), dp(80), dp(40), dp(80))
        }

        // Checkmark
        val check = TextView(this).apply {
            text = "✓"
            textSize = 64f
            setTextColor(0xFF44DD44.toInt())
            gravity = Gravity.CENTER
        }
        layout.addView(check)
        pulseIn(check, 200)

        val title = TextView(this).apply {
            text = "You're All Set"
            textSize = 28f
            setTextColor(0xFFD4A574.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        }
        layout.addView(title)
        fadeSlideIn(title, 500)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(2)).apply {
                gravity = Gravity.CENTER
                topMargin = dp(24)
                bottomMargin = dp(24)
            }
            setBackgroundColor(0xFFEE5A24.toInt())
        }
        layout.addView(divider)
        fadeSlideIn(divider, 700)

        val msg = TextView(this).apply {
            text = "Close this app.\n\nThe bubble is everything you need.\nIt works in every app, even when\nWiM is closed.\n\nJust tap, speak, and go."
            textSize = 15f
            setTextColor(0xBBFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        }
        layout.addView(msg)
        fadeSlideIn(msg, 900)

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        val btn = makeActionButton("Let's Go") {
            WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        layout.addView(btn)
        fadeSlideIn(btn, 1200)

        container.addView(layout)
    }

    // ── Shared components ──

    private fun makeScreenTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTextColor(0xFFD4A574.toInt())
        gravity = Gravity.CENTER
        typeface = Typeface.create("serif", Typeface.BOLD)
        letterSpacing = 0.04f
        setPadding(0, 0, 0, dp(8))
    }

    private fun makeActionButton(text: String, onClick: () -> Unit): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            setBackgroundColor(0xFFD4A574.toInt())
            setOnClickListener { onClick() }
            elevation = dp(4).toFloat()
        }

        frame.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF1A1A2E.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        return frame
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
        if (currentScreen == 3) showScreen(3)
    }
}
