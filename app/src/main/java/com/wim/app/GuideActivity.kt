package com.wim.app

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen WebView host for the illustrated user manual at
 * `assets/user_guide.html`. Launched from MainActivity's Guide section
 * via the "READ THE FULL MANUAL" launcher card.
 *
 * JavaScript is disabled — the manual is pure HTML/CSS/SVG, no JS needed.
 * No filesystem or content-provider access — only the bundled asset.
 */
class GuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = false
                loadWithOverviewMode = false
                useWideViewPort = false
            }
            setBackgroundColor(0xFF1A1A1E.toInt())
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            loadUrl("file:///android_asset/user_guide.html")
        }
        setContentView(webView)
    }
}
