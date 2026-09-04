package com.wim.app

import android.os.Build

/**
 * Per-brand "stop killing WiM" instructions. Battery-optimization exemption is
 * not enough on these phones: each has its own sleeping-apps list that kills
 * foreground services regardless. Null when the brand has no known killer.
 * Sources: dontkillmyapp.com, 2026-09-04.
 */
object OemKillers {
    val brand: String get() = (Build.MANUFACTURER ?: "").lowercase()

    fun hint(): String? = when {
        brand.contains("samsung") ->
            "Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add What I Meant."
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
            "Xiaomi: Security → Autostart → What I Meant ON. Settings → Battery → App battery saver → What I Meant → No restrictions."
        brand.contains("huawei") || brand.contains("honor") ->
            "Huawei: Settings → Battery → App launch → What I Meant → Manage manually, all three switches ON."
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
            "OnePlus/Oppo: Settings → Battery → App battery usage → What I Meant → Allow background activity, Don't optimize."
        brand.contains("vivo") || brand.contains("iqoo") ->
            "Vivo: i Manager → App manager → Autostart → What I Meant ON. Battery → Background power → Allow."
        brand.contains("asus") ->
            "Asus: Mobile Manager → PowerMaster → Auto-start manager → What I Meant ON."
        brand.contains("motorola") || brand.contains("lenovo") ->
            "Motorola: Settings → Battery → Battery optimization → What I Meant → Don't optimize."
        else -> null
    }
}
