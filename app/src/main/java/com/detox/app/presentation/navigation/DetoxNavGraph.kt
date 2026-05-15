package com.detox.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.detox.app.presentation.screens.auth.AuthScreen
import com.detox.app.presentation.screens.auth.AuthTab
import com.detox.app.presentation.screens.onboarding.OnboardingScreen
import com.detox.app.presentation.screens.welcome.WelcomeOnboardingScreen

sealed class Screen(val route: String) {
    data object Welcome : Screen("welcome")
    data object Auth : Screen("auth")
    data object Onboarding : Screen("onboarding")
    data object Main : Screen("main")
}

@Composable
fun DetoxNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Auth.route,
    permissionMissing: Boolean = false,
    accessibilityMissing: Boolean = false,
    onOpenPermissionSettings: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // ── Welcome Onboarding (first run only) ─────────────────────────────────
        composable(Screen.Welcome.route) {
            WelcomeOnboardingScreen(
                onNavigateToRegister = {
                    navController.navigate("auth?tab=register") {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.navigate("auth?tab=login") {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Auth (Login / Register) ─────────────────────────────────────────────
        composable(
            route = "auth?tab={tab}",
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.StringType
                    defaultValue = "login"
                    nullable = true
                }
            )
        ) { backStackEntry ->
            val tabArg = backStackEntry.arguments?.getString("tab")
            val initialTab = if (tabArg == "register") AuthTab.REGISTER else AuthTab.LOGIN
            AuthScreen(
                initialTab = initialTab,
                onRegistered = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                    }
                },
                onLoggedIn = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                    }
                }
            )
        }

        // ── Permissions setup ───────────────────────────────────────────────────
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Main app (with bottom nav) ──────────────────────────────────────────
        composable(Screen.Main.route) {
            MainScreen(
                onLoggedOut = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                },
                permissionMissing = permissionMissing,
                accessibilityMissing = accessibilityMissing,
                onOpenPermissionSettings = onOpenPermissionSettings,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            )
        }
    }
}
