package com.detox.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
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
import com.detox.app.presentation.screens.groupchallenge.create.GroupChallengeCreateViewModel
import com.detox.app.presentation.screens.activechallenge.ActiveChallengeScreen
import com.detox.app.presentation.screens.appselection.AppSelectionScreen
import com.detox.app.presentation.screens.challengecreation.ChallengeCreationScreen
import com.detox.app.presentation.screens.challenges.ChallengesScreen
import com.detox.app.presentation.screens.dashboard.DashboardScreen
import com.detox.app.presentation.screens.friends.FriendsHubScreen
import com.detox.app.presentation.screens.groupchallenge.create.GroupChallengeCreateScreen
import com.detox.app.presentation.screens.groupchallenge.detail.GroupChallengeDetailScreen
import com.detox.app.presentation.screens.groupchallenge.join.GroupChallengeJoinScreen
import com.detox.app.presentation.screens.profile.ProfileScreen
import com.detox.app.presentation.screens.settings.SettingsScreen
import com.detox.app.presentation.screens.statistics.StatisticsScreen
import java.net.URLEncoder

private sealed class BottomNavTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    data object Dashboard : BottomNavTab("dashboard", R.string.nav_dashboard, Icons.Filled.Home)
    data object Challenges : BottomNavTab("challenges", R.string.nav_challenges, Icons.Filled.List)
    data object Friends : BottomNavTab("friends", R.string.nav_friends, Icons.Filled.Group)
    data object Profile : BottomNavTab("profile", R.string.nav_profile, Icons.Filled.Person)

    companion object {
        val all = listOf(Dashboard, Challenges, Friends, Profile)
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
                        navController.navigate("challenge_creation")
                    },
                    onChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
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
                        navController.navigate("challenge_creation")
                    },
                    onChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    }
                )
            }

            // ── Profile tab ─────────────────────────────────────────────────────
            composable(BottomNavTab.Profile.route) {
                ProfileScreen(
                    onLoggedOut = onLoggedOut,
                    onOpenSettings = { navController.navigate("settings") }
                )
            }

            // ── Friends / Group Challenges tab ──────────────────────────────────
            composable(BottomNavTab.Friends.route) {
                FriendsHubScreen(
                    onCreateGroupChallenge = {
                        navController.navigate("group_create_app_selection")
                    },
                    onJoinGroupChallenge = {
                        navController.navigate("group_join")
                    },
                    onGroupChallengeClick = { groupId ->
                        navController.navigate("group_detail/$groupId")
                    }
                )
            }

            // ── Challenge Creation wizard ───────────────────────────────────────
            composable("challenge_creation") {
                ChallengeCreationScreen(
                    onFinished = {
                        navController.navigate(BottomNavTab.Dashboard.route) {
                            popUpTo("challenge_creation") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onDiscarded = {
                        navController.popBackStack()
                    },
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

            // ── Statistics ───────────────────────────────────────────────────────
            composable("statistics") {
                StatisticsScreen(onBack = { navController.popBackStack() })
            }

            // ── Settings ─────────────────────────────────────────────────────────
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToLogin = onLoggedOut
                )
            }

            // ── Group Challenge — App selection (reuses AppSelectionScreen) ─────
            composable("group_create_app_selection") {
                AppSelectionScreen(
                    onAppsSelected = { packages, displayName ->
                        val encodedPackages = URLEncoder.encode(packages.joinToString(","), "UTF-8")
                        val encodedName = URLEncoder.encode(displayName, "UTF-8")
                        navController.navigate(
                            "group_create?packageNames=$encodedPackages&displayName=$encodedName"
                        )
                    }
                )
            }

            // ── Group Challenge — Create (two-step settings + review) ────────────
            composable(
                route = "group_create?packageNames={packageNames}&displayName={displayName}",
                arguments = listOf(
                    navArgument("packageNames") {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = true
                    },
                    navArgument("displayName") {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = true
                    }
                )
            ) { backStackEntry ->
                val packageNames = backStackEntry.arguments?.getString("packageNames") ?: ""
                val displayName = backStackEntry.arguments?.getString("displayName") ?: ""
                val viewModel = androidx.hilt.navigation.compose.hiltViewModel<GroupChallengeCreateViewModel>()
                // Pre-fill packages from nav args on first composition
                androidx.compose.runtime.LaunchedEffect(packageNames) {
                    if (packageNames.isNotBlank()) {
                        viewModel.setSelectedPackages(packageNames, displayName)
                    }
                }
                GroupChallengeCreateScreen(
                    onBack = { navController.popBackStack() },
                    onSelectApps = {
                        navController.navigate("group_create_app_selection")
                    },
                    onCreated = { groupId ->
                        navController.navigate("group_detail/$groupId") {
                            popUpTo("group_create_app_selection") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    viewModel = viewModel
                )
            }

            // ── Group Challenge — Join ────────────────────────────────────────────
            composable("group_join") {
                GroupChallengeJoinScreen(
                    onBack = { navController.popBackStack() },
                    onJoined = { groupId ->
                        navController.navigate("group_detail/$groupId") {
                            popUpTo("group_join") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            // ── Group Challenge — Detail / Leaderboard ───────────────────────────
            composable(
                route = "group_detail/{groupId}",
                arguments = listOf(
                    navArgument("groupId") { type = NavType.StringType }
                )
            ) {
                GroupChallengeDetailScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
