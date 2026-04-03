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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

@Composable
fun ChallengeSetupScreen(
    onChallengeCreated: () -> Unit,
    viewModel: ChallengeSetupViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Stripe PaymentSheet
    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentConfirmed()
            is PaymentSheetResult.Canceled -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed -> viewModel.onPaymentCancelled()
        }
    }

    // Navigate away on final success
    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.Success) {
            onChallengeCreated()
        }
    }

    // Present Stripe PaymentSheet when backend returns client secret
    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.AwaitingPayment) {
            val state = uiState as ChallengeSetupUiState.AwaitingPayment
            paymentSheet.presentWithPaymentIntent(
                paymentIntentClientSecret = state.clientSecret,
                configuration = PaymentSheet.Configuration(
                    merchantDisplayName = "Detox App"
                )
            )
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

            // ── Mode toggle: Soft / Hard ────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_mode_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = formState.mode == ChallengeMode.SOFT,
                    onClick = { viewModel.updateMode(ChallengeMode.SOFT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.challenge_setup_mode_soft_label))
                }
                SegmentedButton(
                    selected = formState.mode == ChallengeMode.HARD,
                    onClick = { viewModel.updateMode(ChallengeMode.HARD) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.challenge_setup_mode_hard_label))
                }
            }

            // ── Hard Mode: amount at stake slider ──────────────────────────────
            if (formState.mode == ChallengeMode.HARD) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.challenge_setup_amount_label, formState.amountEuros),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // €5 to €50 in €5 steps (steps param = number of internal stops = 8)
                Slider(
                    value = formState.amountEuros.toFloat(),
                    onValueChange = { viewModel.updateAmountEuros((it / 5).toInt() * 5) },
                    valueRange = 5f..50f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.challenge_setup_hard_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Limit Type ──────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_limit_type),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Spacer(modifier = Modifier.height(16.dp))

            // ── Limit Value — text inputs ───────────────────────────────────────
            when (formState.limitType) {
                LimitType.TIME -> {
                    TimeLimitInput(
                        limitMinutes = formState.limitMinutes,
                        error = formState.limitMinutesError,
                        displayName = formState.displayName,
                        onValueChange = { viewModel.updateLimitMinutes(it) }
                    )
                }

                LimitType.SESSIONS -> {
                    SessionLimitInput(
                        limitSessions = formState.limitSessions,
                        sessionMinutes = formState.sessionMinutes,
                        sessionsError = formState.limitSessionsError,
                        sessionMinsError = formState.sessionMinutesError,
                        displayName = formState.displayName,
                        onSessionsChange = { viewModel.updateLimitSessions(it) },
                        onSessionMinsChange = { viewModel.updateSessionMinutes(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Duration ────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_duration),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { days ->
                    FilterChip(
                        selected = formState.durationDays == days,
                        onClick = { viewModel.updateDurationDays(days) },
                        label = { Text(stringResource(R.string.challenge_setup_days, days)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Motivation ──────────────────────────────────────────────────────
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
                text = if (formState.mode == ChallengeMode.SOFT) {
                    stringResource(R.string.challenge_setup_mode_soft)
                } else {
                    stringResource(R.string.challenge_setup_hard_warning)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (formState.mode == ChallengeMode.HARD)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Error message ───────────────────────────────────────────────────
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

            // ── Start Button ────────────────────────────────────────────────────
            val isLoading = uiState is ChallengeSetupUiState.Loading ||
                    uiState is ChallengeSetupUiState.AwaitingPayment

            Button(
                onClick = { viewModel.createChallenge() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = if (formState.mode == ChallengeMode.HARD) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.height(20.dp)
                    )
                } else {
                    Text(
                        text = if (formState.mode == ChallengeMode.HARD) {
                            "${stringResource(R.string.challenge_setup_start_button)} · €${formState.amountEuros}"
                        } else {
                            stringResource(R.string.challenge_setup_start_button)
                        }
                    )
                }
            }
        }
    }
}

// ── Time limit input ───────────────────────────────────────────────────────────

@Composable
private fun TimeLimitInput(
    limitMinutes: Int,
    error: String?,
    displayName: String,
    onValueChange: (Int) -> Unit
) {
    // Local string state — decoupled from the Int in formState so the user can
    // type freely (e.g. clear the field) without the ViewModel resetting it.
    var text by remember { mutableStateOf(limitMinutes.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            text = digits
            onValueChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(stringResource(R.string.challenge_setup_minutes_field_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    if (error == null && (text.toIntOrNull() ?: 0) >= 5) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.challenge_setup_time_summary,
                displayName,
                text.toIntOrNull() ?: limitMinutes
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ── Session limit input ────────────────────────────────────────────────────────

@Composable
private fun SessionLimitInput(
    limitSessions: Int,
    sessionMinutes: Int,
    sessionsError: String?,
    sessionMinsError: String?,
    displayName: String,
    onSessionsChange: (Int) -> Unit,
    onSessionMinsChange: (Int) -> Unit
) {
    var sessionsText by remember { mutableStateOf(limitSessions.toString()) }
    var sessionMinsText by remember { mutableStateOf(sessionMinutes.toString()) }

    OutlinedTextField(
        value = sessionsText,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(3)
            sessionsText = digits
            onSessionsChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(stringResource(R.string.challenge_setup_sessions_count_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = sessionsError != null,
        supportingText = if (sessionsError != null) {
            { Text(sessionsError) }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = sessionMinsText,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(3)
            sessionMinsText = digits
            onSessionMinsChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(stringResource(R.string.challenge_setup_session_mins_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = sessionMinsError != null,
        supportingText = if (sessionMinsError != null) {
            { Text(sessionMinsError) }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    val sessionsVal = sessionsText.toIntOrNull() ?: 0
    val minsVal = sessionMinsText.toIntOrNull() ?: 0
    if (sessionsError == null && sessionMinsError == null && sessionsVal >= 1 && minsVal >= 1) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.challenge_setup_session_summary,
                displayName,
                sessionsVal,
                minsVal
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

