package com.wim.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wim.app.onboarding.screens.AccessibilityPermissionScreen
import com.wim.app.onboarding.screens.AutoSendScreen
import com.wim.app.onboarding.screens.BatteryPermissionScreen
import com.wim.app.onboarding.screens.FinalScreen
import com.wim.app.onboarding.screens.MicPermissionScreen
import com.wim.app.onboarding.screens.OverlayPermissionScreen
import com.wim.app.onboarding.screens.PermissionsIntroScreen
import com.wim.app.onboarding.screens.ProfileScreen
import com.wim.app.onboarding.screens.ReconstructionScreen
import com.wim.app.onboarding.screens.SamsungSleepScreen
import com.wim.app.onboarding.screens.SettingsMenuScreen
import com.wim.app.onboarding.screens.WelcomeScreen

/**
 * Onboarding destinations. Live flow as wired (2026-06-10):
 *
 *   welcome → permissions-intro
 *     → overlay → accessibility → mic → battery → samsung (if Samsung)
 *     → interactive-demo → final
 *
 * The four explainer tiles (any-app → layers → tones → learns) are parked as an
 * orphaned chain. brand.local.md's original order put them between welcome and
 * permissions-intro, but Welcome's typewriter console already carries the value
 * pitch, so the working recommendation is to cut them. George decides: wire
 * WELCOME → ANY_APP to restore them, or delete the four routes to commit.
 *
 * Screens not yet implemented render the StubScreen with a "[TODO] <route>" label
 * so the nav graph is complete and George can adb-launch the activity to test the
 * scaffolding without crashing on missing routes.
 */
object OnboardingRoutes {
    const val WELCOME            = "welcome"
    const val ANY_APP            = "any-app"
    const val LAYERS             = "layers"
    const val TONES              = "tones"
    const val LEARNS             = "learns"
    const val PERMISSIONS_INTRO  = "permissions-intro"
    const val OVERLAY            = "overlay"
    const val ACCESSIBILITY      = "accessibility"
    const val MIC                = "mic"
    const val BATTERY            = "battery"
    const val SAMSUNG_SLEEP      = "samsung-sleep"
    const val INTERACTIVE_DEMO   = "interactive-demo"
    const val FINAL              = "final"
    const val RECONSTRUCTION     = "reconstruction"
    const val PROFILE            = "profile"
    const val AUTOSEND           = "autosend"
    const val SETTINGS_MENU      = "settings-menu"
}

@Composable
fun OnboardingNavHost(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = OnboardingRoutes.WELCOME,
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
        composable(OnboardingRoutes.ANY_APP)            { StubScreen("any-app")           { navController.navigate(OnboardingRoutes.LAYERS) } }
        composable(OnboardingRoutes.LAYERS)             { StubScreen("layers")            { navController.navigate(OnboardingRoutes.TONES) } }
        composable(OnboardingRoutes.TONES)              { StubScreen("tones")             { navController.navigate(OnboardingRoutes.LEARNS) } }
        composable(OnboardingRoutes.LEARNS)             { StubScreen("learns")            { navController.navigate(OnboardingRoutes.PERMISSIONS_INTRO) } }
        composable(OnboardingRoutes.ACCESSIBILITY) {
            AccessibilityPermissionScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.MIC) },
                onSkip = { navController.navigate(OnboardingRoutes.MIC) }
            )
        }
        composable(OnboardingRoutes.MIC) {
            MicPermissionScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingRoutes.BATTERY) },
                onSkip = { navController.navigate(OnboardingRoutes.BATTERY) }
            )
        }
        composable(OnboardingRoutes.BATTERY) {
            val next = {
                if (PermissionStatus.isSamsung()) navController.navigate(OnboardingRoutes.SAMSUNG_SLEEP)
                else navController.navigate(OnboardingRoutes.PROFILE)
            }
            BatteryPermissionScreen(onBack = { navController.popBackStack() }, onContinue = next, onSkip = next)
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
        // interactive-demo: orphaned (the live bubble show is pinned); kept defined for later.
        composable(OnboardingRoutes.INTERACTIVE_DEMO)   { StubScreen("interactive-demo")  { navController.navigate(OnboardingRoutes.FINAL) } }
        composable(OnboardingRoutes.FINAL)              { FinalScreen(onBack = { navController.popBackStack() }, onDone = { onComplete() }) }
    }
}
