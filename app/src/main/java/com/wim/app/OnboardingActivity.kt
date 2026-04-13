package com.wim.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView-based onboarding — 6 tiles loaded from assets/onboarding.html.
 * Communicates with HTML via JavascriptInterface for permissions and completion.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(0xFF1A1A1E.toInt())
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pushPermissionState()
                }
            }
            addJavascriptInterface(OnboardingBridge(), "Android")
            loadUrl("file:///android_asset/onboarding.html")
        }
        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        pushPermissionState()
    }

    private fun pushPermissionState() {
        val perms = JSONArray()

        fun add(id: String, name: String, why: String, granted: Boolean) {
            perms.put(JSONObject().apply {
                put("id", id)
                put("name", name)
                put("why", why)
                put("granted", granted)
            })
        }

        add("overlay", "Display Over Apps", "Floating bubble on top of other apps",
            Settings.canDrawOverlays(this))
        add("mic", "Microphone", "WiM needs to hear your voice",
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        add("accessibility", "Accessibility", "Detects text fields, pastes clean text",
            isAccessibilityEnabled())
        add("battery", "Battery Optimization", "Keeps bubble alive in background",
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName))
        add("notifications", "Notifications", "Required for foreground service",
            android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)

        val js = "setPermissions(${perms});"
        webView.evaluateJavascript(js, null)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("$packageName/${WimAccessibilityService::class.java.canonicalName}")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pushPermissionState()
    }

    inner class OnboardingBridge {
        @JavascriptInterface
        fun requestPermission(id: String) {
            runOnUiThread {
                when (id) {
                    "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    "mic" -> ActivityCompat.requestPermissions(this@OnboardingActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                    "accessibility" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    "battery" -> try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                    "notifications" -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                        ActivityCompat.requestPermissions(this@OnboardingActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onboardingComplete() {
            runOnUiThread {
                // Launch bubble
                try {
                    startForegroundService(Intent(this@OnboardingActivity, BubbleService::class.java).apply { action = "SHOW" })
                } catch (_: Exception) {}

                WimApp.prefs.edit().putBoolean("onboarding_complete", true).apply()
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
