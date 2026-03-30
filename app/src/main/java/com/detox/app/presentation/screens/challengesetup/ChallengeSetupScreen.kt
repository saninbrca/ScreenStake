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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
    // Snapshot auth state once when the screen is first composed. This is a diagnostic
    // helper — read directly from the ViewModel property (not a Flow) so it does not
    // recompose on every frame.
    val authEmail = remember { viewModel.currentUserEmail }

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

    // Emergency code dialog — shown once after Hard Mode challenge is saved
    if (uiState is ChallengeSetupUiState.ShowEmergencyCode) {
        val code = (uiState as ChallengeSetupUiState.ShowEmergencyCode).code
        EmergencyCodeDialog(
            code = code,
            onConfirm = { viewModel.onEmergencyCodeConfirmed() }
        )
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

            // ── Temporary auth state banner ─────────────────────────────────────
            // Shows which Firebase user is active when this screen is composed.
            // Remove this block once the PERMISSION_DENIED issue is resolved.
            Spacer(modifier = Modifier.height(8.dp))
            if (authEmail != null) {
                Text(
                    text = "Auth: $authEmail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Text(
                    text = "Auth: Not signed in — Hard Mode will fail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // ───────────────────────────────────────────────────────────────────

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

            Spacer(modifier = Modifier.height(24.dp))

            // ── Limit Value ─────────────────────────────────────────────────────
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

// ── Emergency Code Dialog ──────────────────────────────────────────────────────

@Composable
private fun EmergencyCodeDialog(code: String, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* User must explicitly confirm they've saved the code */ },
        title = { Text(stringResource(R.string.emergency_code_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.emergency_code_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = code,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.emergency_code_dialog_confirm))
            }
        }
    )
}
