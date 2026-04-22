package com.detox.app.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.detox.app.R
import com.detox.app.presentation.screens.activechallenge.ActiveChallengeScreen
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

private sealed class BottomNavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Dashboard : BottomNavTab(
        route = "dashboard",
        labelRes = R.string.nav_dashboard,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    )
    data object Challenges : BottomNavTab(
        route = "challenges",
        labelRes = R.string.nav_challenges,
        selectedIcon = Icons.Filled.List,
        unselectedIcon = Icons.Outlined.List
    )
    data object Friends : BottomNavTab(
        route = "friends",
        labelRes = R.string.nav_friends,
        selectedIcon = Icons.Filled.Group,
        unselectedIcon = Icons.Outlined.Group
    )
    data object Profile : BottomNavTab(
        route = "profile",
        labelRes = R.string.nav_profile,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )

    companion object {
        val all = listOf(Dashboard, Challenges, Friends, Profile)
    }
}

@Composable
fun MainScreen(
    onLoggedOut: () -> Unit,
    permissionMissing: Boolean = false,
    accessibilityMissing: Boolean = false,
    onOpenPermissionSettings: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                if (permissionMissing) {
                    PermissionWarningBanner(onBeheben = onOpenPermissionSettings)
                }
                if (accessibilityMissing) {
                    AccessibilityWarningBanner(onBeheben = onOpenAccessibilitySettings)
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                BottomNavTab.all.forEach { tab ->
                    val isSelected = currentRoute?.startsWith(tab.route) == true
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = null
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primary
                        )
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
                    onAddChallenge = { navController.navigate("challenge_creation") },
                    onChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    },
                    onOpenStats = { navController.navigate("statistics") }
                )
            }

            // ── Challenges tab ──────────────────────────────────────────────────
            composable(BottomNavTab.Challenges.route) {
                ChallengesScreen(
                    onAddChallenge = { navController.navigate("challenge_creation") },
                    onChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    }
                )
            }

            // ── Profile tab ─────────────────────────────────────────────────────
            composable(BottomNavTab.Profile.route) {
                ProfileScreen(
                    onLoggedOut = onLoggedOut,
                    onOpenSettings = { navController.navigate("settings") },
                    onNavigateToChallenges = {
                        navController.navigate(BottomNavTab.Challenges.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // ── Friends / Group Challenges tab ──────────────────────────────────
            composable(BottomNavTab.Friends.route) {
                FriendsHubScreen(
                    onCreateGroupChallenge = { navController.navigate("group_create") },
                    onJoinGroupChallenge = { navController.navigate("group_join") },
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
                    onDiscarded = { navController.popBackStack() }
                )
            }

            // ── Active Challenge Detail ──────────────────────────────────────────
            composable(
                route = "active_challenge/{challengeId}",
                arguments = listOf(navArgument("challengeId") { type = NavType.StringType })
            ) {
                ActiveChallengeScreen(onBack = { navController.popBackStack() })
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

            // ── Group Challenge — Create wizard ───────────────────────────────────
            composable("group_create") {
                GroupChallengeCreateScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { groupId ->
                        navController.navigate("group_detail/$groupId") {
                            popUpTo("group_create") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
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
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) {
                GroupChallengeDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun PermissionWarningBanner(onBeheben: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFD32F2F))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "⚠️ Overlay Permission fehlt — Challenge pausiert",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onBeheben,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text("Beheben", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AccessibilityWarningBanner(onBeheben: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE65100))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "⚠️ Accessibility Service auch deaktiviert",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onBeheben,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text("Beheben", fontWeight = FontWeight.Bold)
            }
        }
    }
}
