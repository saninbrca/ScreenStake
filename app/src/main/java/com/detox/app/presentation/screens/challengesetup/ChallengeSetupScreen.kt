package com.detox.app.presentation.screens.challengesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.LimitType

@Composable
fun ChallengeSetupScreen(
    onChallengeCreated: () -> Unit,
    viewModel: ChallengeSetupViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.Success) {
            onChallengeCreated()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.challenge_setup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.challenge_setup_app_label, formState.displayName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Limit Type Selection
            Text(
                text = stringResource(R.string.challenge_setup_limit_type),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = formState.limitType == LimitType.TIME,
                    onClick = { viewModel.updateLimitType(LimitType.TIME) },
                    label = { Text(stringResource(R.string.challenge_setup_time_limit)) }
                )
                FilterChip(
                    selected = formState.limitType == LimitType.SESSIONS,
                    onClick = { viewModel.updateLimitType(LimitType.SESSIONS) },
                    label = { Text(stringResource(R.string.challenge_setup_session_limit)) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Limit Value Configuration
            when (formState.limitType) {
                LimitType.TIME -> {
                    Text(
                        text = stringResource(R.string.challenge_setup_daily_limit, formState.limitMinutes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Slider(
                        value = formState.limitMinutes.toFloat(),
                        onValueChange = { viewModel.updateLimitMinutes(it.toInt()) },
                        valueRange = 10f..180f,
                        steps = 33,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                LimitType.SESSIONS -> {
                    Text(
                        text = stringResource(R.string.challenge_setup_max_opens, formState.limitSessions),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Slider(
                        value = formState.limitSessions.toFloat(),
                        onValueChange = { viewModel.updateLimitSessions(it.toInt()) },
                        valueRange = 1f..20f,
                        steps = 18,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.challenge_setup_session_duration, formState.sessionMinutes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Slider(
                        value = formState.sessionMinutes.toFloat(),
                        onValueChange = { viewModel.updateSessionMinutes(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Duration Selection
            Text(
                text = stringResource(R.string.challenge_setup_duration),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(7, 14, 30).forEach { days ->
                    FilterChip(
                        selected = formState.durationDays == days,
                        onClick = { viewModel.updateDurationDays(days) },
                        label = { Text(stringResource(R.string.challenge_setup_days, days)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Motivation Text
            Text(
                text = stringResource(R.string.challenge_setup_motivation_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = formState.motivationText,
                onValueChange = { if (it.length <= 200) viewModel.updateMotivationText(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.challenge_setup_motivation_hint)) },
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.challenge_setup_mode_soft),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Error message
            if (uiState is ChallengeSetupUiState.Error) {
                Text(
                    text = (uiState as ChallengeSetupUiState.Error).message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Start Button
            Button(
                onClick = { viewModel.createChallenge() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is ChallengeSetupUiState.Loading
            ) {
                if (uiState is ChallengeSetupUiState.Loading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                } else {
                    Text(text = stringResource(R.string.challenge_setup_start_button))
                }
            }
        }
    }
}
