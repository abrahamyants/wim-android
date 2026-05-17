package com.wim.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wim.app.onboarding.screens.OverlayPermissionScreen
import com.wim.app.onboarding.screens.WelcomeScreen

/**
 * Onboarding destinations. Order matches brand.local.md flow expectation:
 *
 *   welcome → any-app → layers → tones → learns
 *     → permissions-intro
 *     → overlay → accessibility → mic → battery → samsung (if Samsung)
 *     → interactive-demo → final
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
                onContinue = { navController.navigate(OnboardingRoutes.PERMISSIONS_INTRO) }
            )
        }
        composable(OnboardingRoutes.PERMISSIONS_INTRO) {
            StubScreen(label = "permissions-intro") {
                navController.navigate(OnboardingRoutes.OVERLAY)
            }
        }
        composable(OnboardingRoutes.OVERLAY) {
            OverlayPermissionScreen(
                onContinue = { navController.navigate(OnboardingRoutes.ACCESSIBILITY) },
                onSkip = { navController.navigate(OnboardingRoutes.ACCESSIBILITY) }
            )
        }
        composable(OnboardingRoutes.ANY_APP)            { StubScreen("any-app")           { navController.navigate(OnboardingRoutes.LAYERS) } }
        composable(OnboardingRoutes.LAYERS)             { StubScreen("layers")            { navController.navigate(OnboardingRoutes.TONES) } }
        composable(OnboardingRoutes.TONES)              { StubScreen("tones")             { navController.navigate(OnboardingRoutes.LEARNS) } }
        composable(OnboardingRoutes.LEARNS)             { StubScreen("learns")            { navController.navigate(OnboardingRoutes.PERMISSIONS_INTRO) } }
        composable(OnboardingRoutes.ACCESSIBILITY)      { StubScreen("accessibility")     { navController.navigate(OnboardingRoutes.MIC) } }
        composable(OnboardingRoutes.MIC)                { StubScreen("mic")               { navController.navigate(OnboardingRoutes.BATTERY) } }
        composable(OnboardingRoutes.BATTERY)            { StubScreen("battery")           {
            if (PermissionStatus.isSamsung()) navController.navigate(OnboardingRoutes.SAMSUNG_SLEEP)
            else navController.navigate(OnboardingRoutes.INTERACTIVE_DEMO)
        } }
        composable(OnboardingRoutes.SAMSUNG_SLEEP)      { StubScreen("samsung-sleep")     { navController.navigate(OnboardingRoutes.INTERACTIVE_DEMO) } }
        composable(OnboardingRoutes.INTERACTIVE_DEMO)   { StubScreen("interactive-demo")  { navController.navigate(OnboardingRoutes.FINAL) } }
        composable(OnboardingRoutes.FINAL)              { StubScreen("final")             { onComplete() } }
    }
}
