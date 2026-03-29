package com.detox.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.detox.app.presentation.screens.appselection.AppSelectionScreen
import com.detox.app.presentation.screens.onboarding.OnboardingScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Dashboard : Screen("dashboard")
    data object AppSelection : Screen("app_selection")
    data object ChallengeSetup : Screen("challenge_setup")
    data object ActiveChallenge : Screen("active_challenge")
    data object PointShop : Screen("point_shop")
    data object Settings : Screen("settings")
}

@Composable
fun DetoxNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Onboarding.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Screen.AppSelection.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.AppSelection.route) {
            AppSelectionScreen(
                onAppSelected = { packageName ->
                    // Will navigate to ChallengeSetup in Phase 2
                }
            )
        }
    }
}
