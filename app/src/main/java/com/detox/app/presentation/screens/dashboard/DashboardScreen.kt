package com.detox.app.presentation.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.detox.app.data.local.db.entity.ChallengeEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.detox.app.util.DateUtils
import com.detox.app.util.HapticManager
import kotlinx.coroutines.delay
import timber.log.Timber

@Composable
fun DashboardScreen(
    onAddChallenge: () -> Unit,
    onChallengeClick: (String) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val completedChallenge by viewModel.completedChallenge.collectAsStateWithLifecycle()
    val redemptionChallenges by viewModel.redemptionChallenges.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    LaunchedEffect(completedChallenge) {
        if (completedChallenge != null) {
            HapticManager.success(context)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadStats()
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
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
                        if (redemptionChallenges.isNotEmpty()) {
                            item(key = "redemption_banner") {
                                RedemptionBanner(
                                    challenges = redemptionChallenges,
                                    onStartRedemption = onOpenHistory,
                                    onDismiss = { viewModel.dismissRedemptionBanner() }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        itemsIndexed(
                            items = state.activeChallenges,
                            key = { _, it -> it.challengeId }
                        ) { index, stats ->
                            val density = LocalDensity.current
                            val offsetPx = with(density) { 30.dp.roundToPx() }
                            var cardVisible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                delay(index * 80L)
                                cardVisible = true
                            }
                            AnimatedVisibility(
                                visible = cardVisible,
                                enter = fadeIn(tween(350, easing = FastOutSlowInEasing)) +
                                    slideInVertically(
                                        initialOffsetY = { offsetPx },
                                        animationSpec = tween(350, easing = FastOutSlowInEasing)
                                    )
                            ) {
                                ChallengeCard(
                                    dailyStats = stats,
                                    onClick = { onChallengeClick(stats.challengeId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        completedChallenge?.let { challenge ->
            HardModeSuccessOverlay(
                challenge = challenge,
                onStartNewChallenge = {
                    viewModel.dismissCompletionOverlay()
                    onAddChallenge()
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
private fun HardModeSuccessOverlay(
    challenge: Challenge,
    onStartNewChallenge: () -> Unit
) {
    Timber.d("endDate type: ${if (challenge.endDate > 1700000000000L) "timestamp" else "days"} value=${challenge.endDate}")
    val endDateMs = if (challenge.endDate > 1700000000000L) {
        challenge.endDate // already a timestamp
    } else {
        challenge.startDate + (challenge.endDate * DateUtils.MILLIS_PER_DAY)
    }
    val durationDays = ((endDateMs - challenge.startDate) / DateUtils.MILLIS_PER_DAY).toInt()
    val amountEuros = (challenge.amountCents ?: 0) / 100

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "🎉",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(
                    text = stringResource(R.string.success_overlay_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.success_overlay_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.success_overlay_duration, durationDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (amountEuros > 0) {
                    Text(
                        text = stringResource(R.string.success_overlay_refund, amountEuros),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onStartNewChallenge,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(text = stringResource(R.string.success_overlay_new_challenge))
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
            .border(1.dp, Color(0xFFFF6B35), MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
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
                    color = Color(0xFFE65100)
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
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B35))
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
