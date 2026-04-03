package com.detox.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.detox.app.R
import com.detox.app.presentation.screens.activechallenge.ActiveChallengeScreen
import com.detox.app.presentation.screens.appselection.AppSelectionScreen
import com.detox.app.presentation.screens.challengesetup.ChallengeSetupScreen
import com.detox.app.presentation.screens.challenges.ChallengesScreen
import com.detox.app.presentation.screens.dashboard.DashboardScreen
import com.detox.app.presentation.screens.pointshop.PointShopScreen
import com.detox.app.presentation.screens.profile.ProfileScreen
import com.detox.app.presentation.screens.statistics.StatisticsScreen
import java.net.URLEncoder

private sealed class BottomNavTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    data object Dashboard : BottomNavTab("dashboard", R.string.nav_dashboard, Icons.Filled.Home)
    data object Challenges : BottomNavTab("challenges", R.string.nav_challenges, Icons.Filled.List)
    data object Profile : BottomNavTab("profile", R.string.nav_profile, Icons.Filled.Person)

    companion object {
        val all = listOf(Dashboard, Challenges, Profile)
    }
}

@Composable
fun MainScreen(onLoggedOut: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                BottomNavTab.all.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute?.startsWith(tab.route) == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavTab.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Dashboard tab ───────────────────────────────────────────────────
            composable(BottomNavTab.Dashboard.route) {
                DashboardScreen(
                    onAddChallenge = {
                        navController.navigate("app_selection")
                    },
                    onChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    },
                    onOpenShop = {
                        navController.navigate("point_shop")
                    },
                    onOpenStats = {
                        navController.navigate("statistics")
                    }
                )
            }

            // ── Challenges tab ──────────────────────────────────────────────────
            composable(BottomNavTab.Challenges.route) {
                ChallengesScreen(
                    onAddChallenge = {
                        navController.navigate("app_selection")
                    },
                    onChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    }
                )
            }

            // ── Profile tab ─────────────────────────────────────────────────────
            composable(BottomNavTab.Profile.route) {
                ProfileScreen(onLoggedOut = onLoggedOut)
            }

            // ── App Selection ───────────────────────────────────────────────────
            composable("app_selection") {
                AppSelectionScreen(
                    onAppSelected = { packageName, displayName ->
                        val encodedName = URLEncoder.encode(displayName, "UTF-8")
                        navController.navigate("challenge_setup/$packageName/$encodedName")
                    }
                )
            }

            // ── Challenge Setup ─────────────────────────────────────────────────
            composable(
                route = "challenge_setup/{packageName}/{displayName}",
                arguments = listOf(
                    navArgument("packageName") { type = NavType.StringType },
                    navArgument("displayName") { type = NavType.StringType }
                )
            ) {
                ChallengeSetupScreen(
                    onChallengeCreated = {
                        navController.navigate(BottomNavTab.Dashboard.route) {
                            popUpTo("app_selection") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            // ── Active Challenge Detail ──────────────────────────────────────────
            composable(
                route = "active_challenge/{challengeId}",
                arguments = listOf(
                    navArgument("challengeId") { type = NavType.StringType }
                )
            ) {
                ActiveChallengeScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // ── Point Shop ───────────────────────────────────────────────────────
            composable("point_shop") {
                PointShopScreen(onBack = { navController.popBackStack() })
            }

            // ── Statistics ───────────────────────────────────────────────────────
            composable("statistics") {
                StatisticsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
