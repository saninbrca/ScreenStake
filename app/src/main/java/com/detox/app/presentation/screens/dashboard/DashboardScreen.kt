package com.detox.app.presentation.screens.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.util.FeatureFlags
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.presentation.components.ChallengeCard
import com.detox.app.presentation.util.pressScaleFeedback
import androidx.compose.ui.platform.LocalContext
import com.detox.app.ui.theme.DetoxAlertColors
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.DateUtils

@Composable
fun DashboardScreen(
    onAddChallenge: () -> Unit,
    onChallengeClick: (String) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val successDialogState by viewModel.successDialogState.collectAsStateWithLifecycle()
    val failedHardChallenge by viewModel.failedHardChallenge.collectAsStateWithLifecycle()
    val redemptionChallenges by viewModel.redemptionChallenges.collectAsStateWithLifecycle()
    val showUpdateBanner by viewModel.showUpdateBanner.collectAsStateWithLifecycle()
    val updateUrl by viewModel.updateUrl.collectAsStateWithLifecycle()
    val broadcast by viewModel.broadcast.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var overlayGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(context.packageName) == true
        )
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadStats()
            overlayGranted = Settings.canDrawOverlays(context)
            accessibilityGranted = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(context.packageName) == true
        }
    }

    val isEmpty = uiState is DashboardUiState.Empty
    val fabInfiniteTransition = rememberInfiniteTransition(label = "fabPulse")
    val fabRawPulse by fabInfiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fabPulseScale"
    )
    val fabScale = if (isEmpty) fabRawPulse else 1f

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onAddChallenge,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.graphicsLayer(scaleX = fabScale, scaleY = fabScale)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.dashboard_add_challenge)
                    )
                }
            }
        ) { innerPadding ->
            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    DashboardSkeleton(innerPadding = innerPadding)
                }

                is DashboardUiState.Empty -> {
                    EmptyState(
                        onAddChallenge = onAddChallenge,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }

                is DashboardUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                is DashboardUiState.Success -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = innerPadding.calculateTopPadding() + 24.dp,
                            bottom = innerPadding.calculateBottomPadding() + 80.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "header") {
                            DashboardHeader(
                                activeCount = state.activeChallenges.size
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (!accessibilityGranted || !overlayGranted) {
                            item(key = "permission_banner") {
                                PermissionWarningBanner(
                                    accessibilityGranted = accessibilityGranted,
                                    overlayGranted = overlayGranted
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        if (FeatureFlags.moneyEnabled && redemptionChallenges.isNotEmpty()) {
                            item(key = "redemption_banner") {
                                RedemptionBanner(
                                    challenges = redemptionChallenges,
                                    onStartRedemption = onOpenHistory,
                                    onDismiss = { viewModel.dismissRedemptionBanner() }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        if (showUpdateBanner) {
                            item(key = "update_banner") {
                                UpdateAvailableBanner(
                                    onUpdate = {
                                        val url = updateUrl.ifBlank {
                                            "https://play.google.com/store/apps/details?id=${context.packageName}"
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            )
                                        }
                                    },
                                    onDismiss = { viewModel.dismissUpdateBanner() }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        items(
                            items = state.activeChallenges,
                            key = { it.challengeId }
                        ) { stats ->
                            ChallengeCard(
                                dailyStats = stats,
                                onClick = { onChallengeClick(stats.challengeId) }
                            )
                        }
                    }
                }
            }
        }

        successDialogState?.let { state ->
            ChallengeSuccessDialog(
                challenge = state.challenge,
                allLogs = state.allLogs,
                streak = state.streak,
                onDismiss = { viewModel.dismissSuccessDialog() },
                onStartNewChallenge = {
                    viewModel.dismissSuccessDialog()
                    onAddChallenge()
                },
                onViewHistory = { viewModel.openSuccessChallengeHistory() }
            )
        }

        failedHardChallenge?.let { state ->
            ChallengeFailedDialog(
                challenge = state.challenge,
                allLogs = state.allLogs,
                onDismiss = { viewModel.dismissHardFailOverlay() },
                onStartNewChallenge = {
                    viewModel.dismissHardFailOverlay()
                    onAddChallenge()
                }
            )
        }

        broadcast?.let { msg ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissBroadcast() },
                title = { Text(text = msg.title, fontWeight = FontWeight.Bold) },
                text = { Text(text = msg.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissBroadcast() }) {
                        Text(text = stringResource(R.string.broadcast_acknowledge))
                    }
                }
            )
        }
    }
}

@Composable
private fun DashboardHeader(activeCount: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dashboard_title),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dashboard_active_count, activeCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * First-load placeholder shown only while [DashboardUiState.Loading]. Mirrors the eventual
 * Success layout (header + a few [ChallengeCard]-shaped rows) so the screen doesn't jump when the
 * real data arrives, with a soft alpha pulse to read as "loading". Purely visual — no data access.
 */
@Composable
private fun DashboardSkeleton(
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "skeletonPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.18f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header placeholders (title + active-count line)
        SkeletonBlock(width = 180.dp, height = 34.dp, color = placeholderColor)
        SkeletonBlock(width = 130.dp, height = 16.dp, color = placeholderColor)
        Spacer(modifier = Modifier.height(4.dp))
        repeat(3) { SkeletonCard(color = placeholderColor) }
    }
}

@Composable
private fun SkeletonBlock(
    width: Dp,
    height: Dp,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}

/** A single placeholder card matching [ChallengeCard]'s shape (icon + two text lines + progress bar). */
@Composable
private fun SkeletonCard(color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(width = 150.dp, height = 16.dp, color = color)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBlock(width = 90.dp, height = 12.dp, color = color)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun EmptyState(
    onAddChallenge: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Icon(
            imageVector = Icons.Outlined.PhoneAndroid,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.dashboard_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.dashboard_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onAddChallenge,
            modifier = Modifier.fillMaxWidth().pressScaleFeedback(),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = stringResource(R.string.dashboard_start_first),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermissionWarningBanner(
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Ruling A: this permission alarm folds onto the design-fixed alarm red (was
    // #FF3B30 → #D32F2F), matching MainScreen's banner. An alarm must not soften in
    // dark, so it is deliberately mode-invariant (DetoxAlertColors), not `danger`.
    val bannerRed = DetoxAlertColors.Red

    val infiniteTransition = rememberInfiniteTransition(label = "bannerPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bannerScale"
    )

    val bodyText = when {
        !accessibilityGranted && !overlayGranted -> stringResource(R.string.permission_banner_both_body)
        !accessibilityGranted -> stringResource(R.string.permission_banner_accessibility_body)
        else -> stringResource(R.string.permission_banner_overlay_body)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bannerRed),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "⚠️", fontSize = 20.sp)
                Text(
                    text = stringResource(R.string.permission_banner_title),
                    color = DetoxAlertColors.OnAlert,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = bodyText,
                color = DetoxAlertColors.OnAlert.copy(alpha = 0.8f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DetoxAlertColors.OnAlert,
                        contentColor = bannerRed
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp
                    )
                ) {
                    Text(
                        text = stringResource(R.string.permission_banner_cta),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RedemptionBanner(
    challenges: List<ChallengeEntity>,
    onStartRedemption: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val challenge = challenges.firstOrNull() ?: return
    val refundEuros = (challenge.redemptionRefundAmount ?: 0) / 100
    val daysLeft = challenge.redemptionDeadline?.let {
        val remaining = it - System.currentTimeMillis()
        (remaining / DateUtils.MILLIS_PER_DAY).toInt().coerceAtLeast(0)
    } ?: 0

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, detoxColors.warning, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = detoxColors.softOrangeBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.redemption_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = detoxColors.softOrangeText
                )
                Text(
                    text = stringResource(R.string.redemption_banner_body, refundEuros),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.redemption_banner_deadline, daysLeft),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onStartRedemption,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = detoxColors.warning)
                ) {
                    Text(
                        text = stringResource(R.string.redemption_banner_cta),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, detoxColors.accent, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = detoxColors.softGreenBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = detoxColors.softGreenText
                )
                TextButton(
                    onClick = onUpdate,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = detoxColors.accent)
                ) {
                    Text(
                        text = stringResource(R.string.update_banner_cta),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
