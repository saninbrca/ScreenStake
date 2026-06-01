package com.detox.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.detox.app.presentation.screens.auth.AuthScreen
import com.detox.app.presentation.screens.auth.AuthTab
import com.detox.app.presentation.screens.auth.EmailVerificationScreen
import com.detox.app.presentation.screens.onboarding.OnboardingScreen
import com.detox.app.presentation.screens.system.AccountDisabledScreen
import com.detox.app.presentation.screens.system.ForceUpdateScreen
import com.detox.app.presentation.screens.system.MaintenanceScreen
import com.detox.app.presentation.screens.username.UsernameSelectionScreen
import com.detox.app.presentation.screens.welcome.WelcomeOnboardingScreen

sealed class Screen(val route: String) {
    data object Welcome : Screen("welcome")
    data object Auth : Screen("auth")
    data object EmailVerification : Screen("email_verification")
    data object UsernameSelection : Screen("username_selection")
    data object Onboarding : Screen("onboarding")
    data object Main : Screen("main")
    data object ForceUpdate : Screen("force_update")
    data object Maintenance : Screen("maintenance")
    data object AccountDisabled : Screen("account_disabled")
}

@Composable
fun DetoxNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Auth.route,
    permissionMissing: Boolean = false,
    accessibilityMissing: Boolean = false,
    onOpenPermissionSettings: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    // Where the maintenance screen sends the user once maintenance is cleared (the
    // destination the app would normally have started on).
    maintenanceClearedDestination: String = Screen.Main.route,
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
                    // Google sign-up (no email-verification step) → username selection first.
                    navController.navigate("username_selection?fromRegister=true") {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                    }
                },
                onLoggedIn = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                    }
                },
                onNeedsUsername = {
                    // Existing verified user logging in without a username.
                    navController.navigate("username_selection?fromRegister=false") {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                    }
                },
                onNeedsEmailVerification = { fromRegister ->
                    navController.navigate("email_verification?fromRegister=$fromRegister")
                }
            )
        }

        // ── Email verification ──────────────────────────────────────────────────
        composable(
            route = "email_verification?fromRegister={fromRegister}",
            arguments = listOf(
                navArgument("fromRegister") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val fromRegister = backStackEntry.arguments?.getBoolean("fromRegister") ?: false
            EmailVerificationScreen(
                onVerified = {
                    // Every verified user must pass through username selection; the screen
                    // self-skips if a username already exists. fromRegister decides where it
                    // lands afterwards (onboarding vs. dashboard).
                    navController.navigate("username_selection?fromRegister=$fromRegister") {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackToRegister = {
                    navController.navigate("auth?tab=register") {
                        popUpTo("auth?tab={tab}") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── Username selection ──────────────────────────────────────────────────
        composable(
            route = "username_selection?fromRegister={fromRegister}",
            arguments = listOf(
                navArgument("fromRegister") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val fromRegister = backStackEntry.arguments?.getBoolean("fromRegister") ?: false
            UsernameSelectionScreen(
                onComplete = {
                    // After registration → permissions onboarding; otherwise → dashboard.
                    val destination = if (fromRegister) Screen.Onboarding.route else Screen.Main.route
                    navController.navigate(destination) {
                        popUpTo("username_selection?fromRegister={fromRegister}") { inclusive = true }
                        launchSingleTop = true
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

        // ── Force update (hard block — version below minVersionCode) ────────────
        composable(Screen.ForceUpdate.route) {
            ForceUpdateScreen()
        }

        // ── Maintenance mode (block until cleared) ──────────────────────────────
        composable(Screen.Maintenance.route) {
            MaintenanceScreen(
                onMaintenanceCleared = {
                    navController.navigate(maintenanceClearedDestination) {
                        popUpTo(Screen.Maintenance.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── Account disabled (ban — hard block) ─────────────────────────────────
        composable(Screen.AccountDisabled.route) {
            AccountDisabledScreen()
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
