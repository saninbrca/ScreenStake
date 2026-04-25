package com.detox.app.presentation.navigation

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import com.detox.app.service.TrackedAppEventBus
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
import com.detox.app.presentation.screens.history.HistoryScreen
import com.detox.app.presentation.screens.profile.ProfileScreen
import com.detox.app.presentation.screens.settings.SettingsScreen
import com.detox.app.presentation.screens.statistics.StatisticsScreen
import timber.log.Timber

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
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
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
        val all = listOf(Dashboard, Friends, Profile)
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

    LaunchedEffect(Unit) {
        Timber.d("Challenges tab removed from bottom nav")
        TrackedAppEventBus.navigateToGroupDetail.collect { groupId ->
            navController.navigate("group_detail/$groupId") {
                launchSingleTop = true
            }
            TrackedAppEventBus.clearGroupDetailNavigation()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (permissionMissing || accessibilityMissing) {
                PermissionBanner(
                    overlayMissing = permissionMissing,
                    accessibilityMissing = accessibilityMissing,
                    onOpenPermissionSettings = onOpenPermissionSettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
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
                    },
                    onGroupChallengeClick = { groupId ->
                        navController.navigate("group_detail/$groupId")
                    },
                    onSoloChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    },
                    onShowAllHistory = {
                        navController.navigate("history")
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
                GroupChallengeDetailScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToProfile = {
                        navController.navigate(BottomNavTab.Profile.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // ── Challenge History ─────────────────────────────────────────────────
            composable("history") {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onGroupChallengeClick = { groupId ->
                        navController.navigate("group_detail/$groupId")
                    },
                    onSoloChallengeClick = { challengeId ->
                        navController.navigate("active_challenge/$challengeId")
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    overlayMissing: Boolean,
    accessibilityMissing: Boolean,
    onOpenPermissionSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    LaunchedEffect(Unit) {
        Timber.d("Critical permission banner shown")
    }

    val bodyText = when {
        overlayMissing && accessibilityMissing ->
            "Overlay + Accessibility fehlen.\nDein Geld wird eingezogen wenn du nicht sofort handelst!"
        overlayMissing ->
            "Overlay Permission wurde deaktiviert.\nDein Geld ist in Gefahr wenn du nicht sofort handelst!"
        else ->
            "Accessibility Service wurde deaktiviert.\nDein Geld ist in Gefahr wenn du nicht sofort handelst!"
    }
    val onBeheben = if (overlayMissing) onOpenPermissionSettings else onOpenAccessibilitySettings

    val pulse = rememberInfiniteTransition(label = "bannerPulse")
    val bgColor by pulse.animateColor(
        initialValue = Color(0xFFD32F2F),
        targetValue  = Color(0xFFB71C1C),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
    ) {
        // Left accent stripe
        Box(
            modifier = Modifier
                .width(6.dp)
                .matchParentSize()
                .background(Color(0xFF7F0000))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "⚠️", fontSize = 32.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "🚨 ACHTUNG — Challenge in Gefahr!",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = bodyText,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 42.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onBeheben,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFD32F2F)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "JETZT BEHEBEN →",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFFD32F2F)
                )
            }
        }
    }
}
