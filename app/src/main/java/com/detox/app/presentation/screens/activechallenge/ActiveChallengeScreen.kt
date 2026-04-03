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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.DailyLimitStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onAbandon: () -> Unit
) {
    var showAbandonDialog by remember { mutableStateOf(false) }

    if (showAbandonDialog) {
        AlertDialog(
            onDismissRequest = { showAbandonDialog = false },
            title = { Text(stringResource(R.string.active_challenge_abandon_confirm_title)) },
            text = { Text(stringResource(R.string.active_challenge_abandon_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showAbandonDialog = false
                        onAbandon()
                    },
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
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = if (challenge.mode == ChallengeMode.HARD) {
                            stringResource(R.string.active_challenge_mode_hard)
                        } else {
                            stringResource(R.string.active_challenge_mode_soft)
                        }
                    )
                },
                colors = if (challenge.mode == ChallengeMode.HARD) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            )
        }

        // Hard Mode: amount at stake
        if (challenge.mode == ChallengeMode.HARD && challenge.amountCents != null) {
            Text(
                text = stringResource(
                    R.string.active_challenge_amount_at_stake,
                    challenge.amountCents / 100f
                ),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
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
        }
        Text(
            text = limitText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

        // End date
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        Text(
            text = stringResource(
                R.string.active_challenge_end_date,
                dateFormat.format(Date(challenge.endDate))
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Abandon button
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
