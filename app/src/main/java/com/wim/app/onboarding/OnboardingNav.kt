package com.wim.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wim.app.onboarding.screens.AboutYouScreen
import com.wim.app.onboarding.screens.AccessibilityPermissionScreen
import com.wim.app.onboarding.screens.AutoSendScreen
import com.wim.app.onboarding.screens.DriveItScreen
import com.wim.app.onboarding.screens.BatteryPermissionScreen
import com.wim.app.onboarding.screens.FinalScreen
import com.wim.app.onboarding.screens.MicLevelScreen
import com.wim.app.onboarding.screens.MicPermissionScreen
import com.wim.app.onboarding.screens.NotificationsPermissionScreen
import com.wim.app.onboarding.screens.OverlayPermissionScreen
import com.wim.app.onboarding.screens.PermissionsIntroScreen
import com.wim.app.onboarding.screens.ProfileScreen
import com.wim.app.onboarding.screens.ReconstructionScreen
import com.wim.app.onboarding.screens.SamsungSleepScreen
import com.wim.app.onboarding.screens.SettingsMenuScreen
import com.wim.app.onboarding.screens.WatchItScreen
import com.wim.app.onboarding.screens.WelcomeScreen

/**
 * Onboarding destinations. Every route below is implemented and reachable —
 * the graph is the flow, with no parked or placeholder branches:
 *
 *   welcome → reconstruction → permissions-intro
 *     → overlay → accessibility → mic → mic-level → battery → notifications → samsung (if Samsung)
 *     → profile → about-you → drive-it → watch-it → autosend → settings-menu → final
 *
 * Deleted 2026-07-24: the four parked explainer tiles (any-app → layers →
 * tones → learns) and interactive-demo. All five were orphaned — unreachable
 * from WELCOME — but still compiled into the release bundle, where they
 * rendered a "[TODO] <route>" placeholder if anything ever navigated to them.
 * Welcome's typewriter console already carries the value pitch. Restore from
 * git history if the explainer chain is ever wanted back.
 */
object OnboardingRoutes {
    const val WELCOME            = "welcome"
    const val PERMISSIONS_INTRO  = "permissions-intro"
    const val OVERLAY            = "overlay"
    const val ACCESSIBILITY      = "accessibility"
    const val MIC                = "mic"
    const val MIC_LEVEL          = "mic-level"
    const val BATTERY            = "battery"
    const val NOTIFICATIONS      = "notifications"
    const val SAMSUNG_SLEEP      = "samsung-sleep"
    const val FINAL              = "final"
    const val RECONSTRUCTION     = "reconstruction"
    const val PROFILE            = "profile"
    const val ABOUT_YOU          = "about-you"
    const val AUTOSEND           = "autosend"
    const val DRIVE_IT           = "drive-it"
    const val WATCH_IT           = "watch-it"
    const val SETTINGS_MENU      = "settings-menu"
}

@Composable
fun OnboardingNavHost(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startRoute: String = OnboardingRoutes.WELCOME
) {
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier
    ) {
        composable(OnboardingRoutes.WELCOME) {
            WelcomeScreen(
                onContinue = { navController.navigate(OnboardingRoutes.RECONSTRUCTION) }
            )
        }
        composable(OnboardingRoutes.RECONSTRUCTION) {
            ReconstructionScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.PERMISSIONS_INTRO) }
            )
        }
        composable(OnboardingRoutes.PERMISSIONS_INTRO) {
            PermissionsIntroScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.OVERLAY) }
            )
        }
        composable(OnboardingRoutes.OVERLAY) {
            OverlayPermissionScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.ACCESSIBILITY) },
                onSkip = { navController.navigate(OnboardingRoutes.ACCESSIBILITY) }
            )
        }
        composable(OnboardingRoutes.ACCESSIBILITY) {
            AccessibilityPermissionScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.MIC) },
                onSkip = { navController.navigate(OnboardingRoutes.MIC) }
            )
        }
        // Microphone is the one permission with no skip. Overlay and
        // accessibility change how WiM reaches you; without the microphone
        // there is nothing to reach you with, and the level measurement on the
        // next screen cannot run at all.
        composable(OnboardingRoutes.MIC) {
            MicPermissionScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.MIC_LEVEL) }
            )
        }
        composable(OnboardingRoutes.MIC_LEVEL) {
            MicLevelScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.BATTERY) },
                onSkip = { navController.navigate(OnboardingRoutes.BATTERY) }
            )
        }
        composable(OnboardingRoutes.BATTERY) {
            val next = { navController.navigate(OnboardingRoutes.NOTIFICATIONS) }
            BatteryPermissionScreen(onBack = { navController.popBackStack() }, onContinue = next, onSkip = next)
        }
        composable(OnboardingRoutes.NOTIFICATIONS) {
            val next = {
                if (PermissionStatus.isSamsung()) navController.navigate(OnboardingRoutes.SAMSUNG_SLEEP)
                else navController.navigate(OnboardingRoutes.PROFILE)
            }
            NotificationsPermissionScreen(onBack = { navController.popBackStack() }, onContinue = next, onSkip = next)
        }
        composable(OnboardingRoutes.SAMSUNG_SLEEP) {
            SamsungSleepScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.PROFILE) }
            )
        }
        composable(OnboardingRoutes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.ABOUT_YOU) }
            )
        }
        composable(OnboardingRoutes.ABOUT_YOU) {
            AboutYouScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.DRIVE_IT) }
            )
        }
        composable(OnboardingRoutes.DRIVE_IT) {
            DriveItScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.WATCH_IT) }
            )
        }
        composable(OnboardingRoutes.WATCH_IT) {
            WatchItScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.AUTOSEND) }
            )
        }
        composable(OnboardingRoutes.AUTOSEND) {
            AutoSendScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.SETTINGS_MENU) }
            )
        }
        composable(OnboardingRoutes.SETTINGS_MENU) {
            SettingsMenuScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.FINAL) }
            )
        }
        composable(OnboardingRoutes.FINAL)              { FinalScreen(onBack = { navController.popBackStack() }, onDone = { onComplete() }) }
    }
}
