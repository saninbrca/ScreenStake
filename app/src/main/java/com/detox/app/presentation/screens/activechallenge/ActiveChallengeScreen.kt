package com.detox.app.presentation.screens.activechallenge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.DailyLimitStatus
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveChallengeScreen(
    onBack: () -> Unit,
    viewModel: ActiveChallengeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val abandonSuccess by viewModel.abandonSuccess.collectAsStateWithLifecycle()

    LaunchedEffect(abandonSuccess) {
        if (abandonSuccess) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.active_challenge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is ActiveChallengeUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is ActiveChallengeUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }

                is ActiveChallengeUiState.Success -> {
                    ActiveChallengeContent(
                        challenge = state.challenge,
                        status = state.status,
                        streak = state.streak,
                        onAbandon = { viewModel.abandonChallenge() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveChallengeContent(
    challenge: Challenge,
    status: DailyLimitStatus?,
    streak: Int,
    onAbandon: () -> Unit
) {
    var showAbandonDialog by remember { mutableStateOf(false) }
    val isHardMode = challenge.mode == ChallengeMode.HARD
    val darkGreen = Color(0xFF2E7D32)

    if (showAbandonDialog) {
        if (isHardMode && challenge.amountCents != null) {
            AlertDialog(
                onDismissRequest = { showAbandonDialog = false },
                title = { Text(stringResource(R.string.active_challenge_abandon_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.active_challenge_abandon_hard_message,
                            challenge.amountCents / 100f
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { showAbandonDialog = false; onAbandon() }
                    ) {
                        Text(
                            text = stringResource(R.string.active_challenge_abandon_hard_yes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showAbandonDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = darkGreen)
                    ) {
                        Text(
                            text = stringResource(R.string.active_challenge_abandon_hard_no),
                            color = Color.White
                        )
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showAbandonDialog = false },
                title = { Text(stringResource(R.string.active_challenge_abandon_confirm_title)) },
                text = { Text(stringResource(R.string.active_challenge_abandon_confirm_message)) },
                confirmButton = {
                    Button(
                        onClick = { showAbandonDialog = false; onAbandon() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.active_challenge_abandon_confirm_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAbandonDialog = false }) {
                        Text(stringResource(R.string.active_challenge_abandon_confirm_no))
                    }
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App name + mode badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = challenge.appDisplayName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isHardMode) MaterialTheme.colorScheme.error else darkGreen
            ) {
                Text(
                    text = if (isHardMode) stringResource(R.string.active_challenge_mode_hard)
                    else stringResource(R.string.active_challenge_mode_soft),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Hard Mode: amount at stake (dark green)
        if (isHardMode && challenge.amountCents != null) {
            Text(
                text = stringResource(
                    R.string.active_challenge_amount_at_stake,
                    challenge.amountCents / 100f
                ),
                style = MaterialTheme.typography.titleLarge,
                color = darkGreen
            )
        }

        // Streak row
        if (streak > 0) {
            Text(
                text = stringResource(R.string.active_challenge_streak, streak),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Limit info
        val limitText = when (challenge.limitType) {
            LimitType.TIME -> stringResource(
                R.string.challenge_card_time_limit,
                challenge.limitValueMinutes
            )
            LimitType.SESSIONS -> stringResource(
                R.string.challenge_card_session_limit,
                challenge.limitValueSessions ?: 0,
                challenge.limitValueMinutes
            )
            LimitType.TIME_BUDGET -> stringResource(
                R.string.challenge_card_budget_limit,
                challenge.dailyBudgetMinutes ?: 0
            )
            LimitType.TIME_WINDOW -> stringResource(R.string.challenge_card_time_window_limit)
        }
        Text(
            text = limitText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Adult content blocking
        if (challenge.blockAdultContent) {
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(20.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.active_challenge_adult_blocked),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider()
        }

        // Today's usage progress
        status?.let { s ->
            val (progressValue, progressLabel) = when (challenge.limitType) {
                LimitType.TIME -> {
                    val p = if (challenge.limitValueMinutes > 0)
                        s.todayMinutes.toFloat() / challenge.limitValueMinutes else 0f
                    p to stringResource(
                        R.string.challenge_card_time_progress,
                        s.todayMinutes,
                        challenge.limitValueMinutes
                    )
                }
                LimitType.SESSIONS -> {
                    val max = challenge.limitValueSessions ?: 1
                    val p = if (max > 0) s.todayOpens.toFloat() / max else 0f
                    p to stringResource(
                        R.string.challenge_card_session_progress,
                        s.todayOpens,
                        max
                    )
                }
                LimitType.TIME_BUDGET -> {
                    val budget = challenge.dailyBudgetMinutes ?: 1
                    val p = if (budget > 0) s.todayMinutes.toFloat() / budget else 0f
                    val remaining = maxOf(0, budget - s.todayMinutes)
                    p to stringResource(
                        R.string.challenge_card_budget_progress,
                        s.todayMinutes, budget, remaining
                    )
                }
                LimitType.TIME_WINDOW -> 0f to stringResource(
                    R.string.challenge_card_time_window_progress,
                    s.todayMinutes
                )
            }

            Text(
                text = progressLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            LinearProgressIndicator(
                progress = { progressValue.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = if (s.limitExceeded) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        val now = System.currentTimeMillis()
        if (challenge.endDate > 0L) {
            Timber.d("endDate type: ${if (challenge.endDate > 1700000000000L) "timestamp" else "days"} value=${challenge.endDate}")
            val endDateMs = if (challenge.endDate > 1700000000000L) {
                challenge.endDate // already a timestamp
            } else {
                challenge.startDate + (challenge.endDate * 24L * 60L * 60L * 1000L)
            }
            val remainingMs = endDateMs - now
            val remainingDays = (remainingMs / (24L * 60 * 60 * 1000)).toInt()
            Timber.d("Challenge ${challenge.id} endDate=$endDateMs remaining=$remainingDays days")
            val (endText, endColor) = when {
                remainingDays <= 0 -> stringResource(R.string.challenge_card_ends_today) to Color(0xFFE65100)
                remainingDays == 1 -> stringResource(R.string.challenge_card_tomorrow) to MaterialTheme.colorScheme.onSurfaceVariant
                else -> stringResource(R.string.challenge_card_days_left, remainingDays) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = endText,
                style = MaterialTheme.typography.bodyMedium,
                color = endColor
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Abandon only available while the challenge is still active
        if (challenge.status == ChallengeStatus.ACTIVE) {
            Button(
                onClick = { showAbandonDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(text = stringResource(R.string.active_challenge_abandon))
            }
        }
    }
}
