package com.detox.app.presentation.navigation

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.detox.app.service.TrackedAppEventBus
import androidx.compose.ui.Modifier
import com.detox.app.ui.theme.DetoxAlertColors
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import android.net.Uri
import androidx.navigation.navArgument
import com.detox.app.R
import com.detox.app.presentation.screens.activechallenge.ActiveChallengeScreen
import com.detox.app.presentation.screens.challengecreation.ChallengeCreationScreen
import com.detox.app.presentation.screens.challenges.ChallengesScreen
import com.detox.app.presentation.screens.dashboard.DashboardScreen
import com.detox.app.presentation.screens.friends.FriendsHubScreen
import com.detox.app.presentation.screens.groupchallenge.create.GroupChallengeCreateScreen
import com.detox.app.presentation.screens.groupchallenge.detail.GroupChallengeDetailScreen
import com.detox.app.presentation.screens.groupchallenge.results.GroupChallengeResultsScreen
import com.detox.app.presentation.screens.groupchallenge.join.GroupChallengeJoinScreen
import com.detox.app.presentation.screens.history.HistoryDetailScreen
import com.detox.app.presentation.screens.history.HistoryScreen
import com.detox.app.presentation.screens.profile.ProfileScreen
import com.detox.app.presentation.screens.settings.SettingsScreen
import com.detox.app.presentation.screens.support.FaqScreen
import com.detox.app.presentation.screens.support.SupportScreen
import com.detox.app.presentation.screens.softfail.SoftFailResultScreen
import com.detox.app.presentation.screens.statistics.StatisticsScreen
import com.detox.app.util.FeatureFlags
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
        // Friends/Group tab is a real-money surface (buy-ins), so it is hidden for the
        // soft-mode-only release via the build-level money floor. The Friends route + group
        // screens stay registered but unreachable (also route-guarded below). Flipping
        // MONEY_FEATURES_ENABLED back on restores the tab. Selection is route/identity-based,
        // so shortening this list never off-by-ones any index logic.
        val all: List<BottomNavTab> = buildList {
            add(Dashboard)
            if (FeatureFlags.moneyEnabled) add(Friends)
            add(Profile)
        }
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
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        Timber.d("Challenges tab removed from bottom nav")
        TrackedAppEventBus.navigateToGroupDetail.collect { groupId ->
            navController.navigate("group_detail/$groupId") {
                launchSingleTop = true
            }
            TrackedAppEventBus.clearGroupDetailNavigation()
        }
    }

    LaunchedEffect(Unit) {
        TrackedAppEventBus.navigateToSoftFailResult.collect { (challengeId, streak) ->
            navController.navigate("soft_fail_result/$challengeId/$streak") {
                launchSingleTop = true
            }
            TrackedAppEventBus.clearSoftFailResultNavigation()
        }
    }

    // ── Notification deep-link collectors ──────────────────────────────────────
    LaunchedEffect(Unit) {
        TrackedAppEventBus.navigateToDashboard.collect {
            navController.navigate(BottomNavTab.Dashboard.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            TrackedAppEventBus.clearDashboardNavigation()
        }
    }

    LaunchedEffect(Unit) {
        TrackedAppEventBus.navigateToProfile.collect {
            navController.navigate(BottomNavTab.Profile.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            TrackedAppEventBus.clearProfileNavigation()
        }
    }

    LaunchedEffect(Unit) {
        TrackedAppEventBus.navigateToChallengeDetail.collect { challengeId ->
            navController.navigate("active_challenge/$challengeId") {
                launchSingleTop = true
            }
            TrackedAppEventBus.clearChallengeDetailNavigation()
        }
    }

    LaunchedEffect(Unit) {
        TrackedAppEventBus.navigateToHistoryDetail.collect { challengeId ->
            navController.navigate("history_detail/$challengeId") {
                launchSingleTop = true
            }
            TrackedAppEventBus.clearHistoryDetailNavigation()
        }
    }

    // Replay any deep link that arrived while the user was logged out / onboarding.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("detox_settings", android.content.Context.MODE_PRIVATE)
        val target = prefs.getString("pending_deep_link_target", null) ?: return@LaunchedEffect
        val arg = prefs.getString("pending_deep_link_arg", null)
        prefs.edit()
            .remove("pending_deep_link_target")
            .remove("pending_deep_link_arg")
            .apply()
        when (target) {
            "dashboard" -> TrackedAppEventBus.emitNavigateToDashboard()
            "profile" -> TrackedAppEventBus.emitNavigateToProfile()
            "group_detail" -> arg?.let { TrackedAppEventBus.emitNavigateToGroupDetail(it) }
            "challenge_detail" -> arg?.let { TrackedAppEventBus.emitNavigateToChallengeDetail(it) }
            "history_detail" -> arg?.let { TrackedAppEventBus.emitNavigateToHistoryDetail(it) }
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
                                restoreState = false
                            }
                        },
                        icon = {
                            val iconScale = remember { Animatable(1f) }
                            LaunchedEffect(isSelected) {
                                if (isSelected) {
                                    iconScale.animateTo(1.2f, tween(100))
                                    iconScale.animateTo(
                                        1f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = null,
                                modifier = Modifier.graphicsLayer(
                                    scaleX = iconScale.value,
                                    scaleY = iconScale.value
                                )
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
                    onOpenHistory = { navController.navigate("history") }
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

            // ── Challenge Creation wizard (optional prePackage pre-selection) ──
            composable(
                route = "challenge_creation?prePackage={prePackage}",
                arguments = listOf(navArgument("prePackage") {
                    type = NavType.StringType
                    defaultValue = ""
                })
            ) {
                ChallengeCreationScreen(
                    onFinished = {
                        navController.navigate(BottomNavTab.Dashboard.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                                inclusive = false
                            }
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
                    onNavigateToLogin = onLoggedOut,
                    onNavigateToHistory = { navController.navigate("history") },
                    onNavigateToSupport = { navController.navigate("support") },
                    onNavigateToFaq = { navController.navigate("faq") }
                )
            }

            // ── Support contact form ─────────────────────────────────────────────
            composable("support") {
                SupportScreen(onBack = { navController.popBackStack() })
            }

            // ── FAQ ──────────────────────────────────────────────────────────────
            composable("faq") {
                FaqScreen(onBack = { navController.popBackStack() })
            }

            // ── Group Challenge — Create wizard ───────────────────────────────────
            // Money-floor route guards: even though the Friends tab is hidden, a stale deep link
            // (notification / TrackedAppEventBus) could still target a group route. When money
            // features are gated off we render nothing and pop back so no buy-in surface can open.
            composable("group_create") {
                if (FeatureFlags.moneyEnabled) {
                    GroupChallengeCreateScreen(
                        onBack = { navController.popBackStack() },
                        onCreated = { groupId ->
                            navController.navigate("group_detail/$groupId") {
                                popUpTo("group_create") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            // ── Group Challenge — Join ────────────────────────────────────────────
            composable("group_join") {
                if (FeatureFlags.moneyEnabled) {
                    GroupChallengeJoinScreen(
                        onBack = { navController.popBackStack() },
                        onJoined = { groupId ->
                            navController.navigate("group_detail/$groupId") {
                                popUpTo("group_join") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            // ── Group Challenge — Detail / Leaderboard ───────────────────────────
            composable(
                route = "group_detail/{groupId}",
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) {
                if (!FeatureFlags.moneyEnabled) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
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
                    },
                    onNavigateToFriendsHub = {
                        navController.navigate(BottomNavTab.Friends.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToResults = { groupId ->
                        navController.navigate("group_challenge_results/$groupId") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            // ── Group Challenge — Results / Podium ───────────────────────────────
            composable(
                route = "group_challenge_results/{groupId}",
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) {
                if (!FeatureFlags.moneyEnabled) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                GroupChallengeResultsScreen(
                    onWeiter = { navController.popBackStack() }
                )
            }

            // ── Soft Mode fail result ─────────────────────────────────────────────
            composable(
                route = "soft_fail_result/{challengeId}/{streak}",
                arguments = listOf(
                    navArgument("challengeId") { type = NavType.StringType },
                    navArgument("streak") { type = NavType.IntType },
                )
            ) {
                // The {streak} route arg is intentionally unused: the screen's ViewModel derives
                // CALENDAR days survived from Room (the emitted streak is a gap-blind log-row count).
                SoftFailResultScreen(
                    onNewChallenge = {
                        navController.navigate("challenge_creation") {
                            popUpTo(BottomNavTab.Dashboard.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onHome = {
                        navController.navigate(BottomNavTab.Dashboard.route) {
                            popUpTo(BottomNavTab.Dashboard.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }

            // ── Challenge History ─────────────────────────────────────────────────
            composable("history") {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onChallengeClick = { challengeId ->
                        navController.navigate("history_detail/$challengeId")
                    }
                )
            }

            // ── History Detail ────────────────────────────────────────────────────
            composable(
                route = "history_detail/{challengeId}",
                arguments = listOf(navArgument("challengeId") { type = NavType.StringType })
            ) {
                HistoryDetailScreen(
                    onBack = { navController.popBackStack() },
                    onStartAgain = { pkgs ->
                        navController.navigate(
                            "challenge_creation?prePackage=${Uri.encode(pkgs)}"
                        )
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
            stringResource(R.string.permission_banner_body_both)
        overlayMissing ->
            stringResource(R.string.permission_banner_body_overlay)
        else ->
            stringResource(R.string.permission_banner_body_accessibility)
    }
    val onBeheben = if (overlayMissing) onOpenPermissionSettings else onOpenAccessibilitySettings

    val pulse = rememberInfiniteTransition(label = "bannerPulse")
    val bgColor by pulse.animateColor(
        initialValue = DetoxAlertColors.Red,
        targetValue  = DetoxAlertColors.RedDeep,
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
                .background(DetoxAlertColors.RedBackground)
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
                    text = stringResource(R.string.permission_danger_title),
                    color = DetoxAlertColors.OnAlert,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = bodyText,
                color = DetoxAlertColors.OnAlert,
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
                    containerColor = DetoxAlertColors.OnAlert,
                    contentColor = DetoxAlertColors.Red
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = stringResource(R.string.permission_fix_now),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = DetoxAlertColors.Red
                )
            }
        }
    }
}
