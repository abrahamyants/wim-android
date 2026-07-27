package com.wim.app.onboarding

import android.content.Context
import android.os.Build

/**
 * Android 16 can apply Enhanced Confirmation Mode to overlay access for apps
 * installed by the system sideload installer. The app cannot clear that OS
 * restriction itself, but it can recognize the exact install shape and guide
 * the user through Android's one-time App info approval.
 */
object RestrictedSettingsPolicy {

    private val SIDELOAD_INSTALLERS = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    fun needsOverlayUnlock(
        sdkInt: Int,
        installerPackage: String?,
        overlayGranted: Boolean
    ): Boolean =
        sdkInt >= 36 &&
            !overlayGranted &&
            installerPackage in SIDELOAD_INSTALLERS

    fun needsOverlayUnlock(context: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        return needsOverlayUnlock(
            sdkInt = Build.VERSION.SDK_INT,
            installerPackage = installer,
            overlayGranted = PermissionStatus.overlay(context)
        )
    }
}
