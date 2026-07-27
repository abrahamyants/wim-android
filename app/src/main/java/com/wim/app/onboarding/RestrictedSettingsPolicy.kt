package com.wim.app.onboarding

import android.content.Context
import android.content.pm.PackageInstaller
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
        packageSource: Int,
        installerPackage: String?,
        overlayGranted: Boolean
    ): Boolean =
        sdkInt >= 36 &&
            !overlayGranted &&
            (
                packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                    packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
                    installerPackage in SIDELOAD_INSTALLERS
            )

    fun needsOverlayUnlock(context: Context): Boolean {
        val installSource = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
            } else {
                null
            }
        }.getOrNull()
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            installSource?.installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        val packageSource = if (Build.VERSION.SDK_INT >= 33) {
            installSource?.packageSource ?: PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
        } else {
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
        }

        return needsOverlayUnlock(
            sdkInt = Build.VERSION.SDK_INT,
            packageSource = packageSource,
            installerPackage = installer,
            overlayGranted = PermissionStatus.overlay(context)
        )
    }
}
