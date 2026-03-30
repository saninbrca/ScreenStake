package com.detox.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.detox.app.presentation.screens.activechallenge.ActiveChallengeScreen
import com.detox.app.presentation.screens.appselection.AppSelectionScreen
import com.detox.app.presentation.screens.challengesetup.ChallengeSetupScreen
import com.detox.app.presentation.screens.dashboard.DashboardScreen
import com.detox.app.presentation.screens.onboarding.OnboardingScreen
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Dashboard : Screen("dashboard")
    data object AppSelection : Screen("app_selection")
    data object ChallengeSetup : Screen("challenge_setup/{packageName}/{displayName}") {
        fun createRoute(packageName: String, displayName: String): String {
            val encodedName = URLEncoder.encode(displayName, "UTF-8")
            return "challenge_setup/$packageName/$encodedName"
        }
    }
    data object ActiveChallenge : Screen("active_challenge/{challengeId}") {
        fun createRoute(challengeId: String) = "active_challenge/$challengeId"
    }
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

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onAddChallenge = {
                    navController.navigate(Screen.AppSelection.route)
                },
                onChallengeClick = { challengeId ->
                    navController.navigate(Screen.ActiveChallenge.createRoute(challengeId))
                }
            )
        }

        composable(Screen.AppSelection.route) {
            AppSelectionScreen(
                onAppSelected = { packageName, displayName ->
                    navController.navigate(
                        Screen.ChallengeSetup.createRoute(packageName, displayName)
                    )
                }
            )
        }

        composable(
            route = Screen.ChallengeSetup.route,
            arguments = listOf(
                navArgument("packageName") { type = NavType.StringType },
                navArgument("displayName") { type = NavType.StringType }
            )
        ) {
            ChallengeSetupScreen(
                onChallengeCreated = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.AppSelection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.ActiveChallenge.route,
            arguments = listOf(
                navArgument("challengeId") { type = NavType.StringType }
            )
        ) {
            ActiveChallengeScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
