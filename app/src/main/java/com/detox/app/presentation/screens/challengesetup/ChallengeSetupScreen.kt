package com.detox.app.presentation.screens.challengesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.BlockingType
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

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentConfirmed()
            is PaymentSheetResult.Canceled -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed -> viewModel.onPaymentCancelled()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.Success) onChallengeCreated()
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.AwaitingPayment) {
            val state = uiState as ChallengeSetupUiState.AwaitingPayment
            paymentSheet.presentWithPaymentIntent(
                paymentIntentClientSecret = state.clientSecret,
                configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App")
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

            // Header subtitle
            if (formState.blockingType == BlockingType.APP) {
                Text(
                    text = stringResource(R.string.challenge_setup_app_label, formState.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = stringResource(R.string.challenge_type_website_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // ── Website Blocking section ────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_websites_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (formState.blockingType == BlockingType.APP) {
                AppDomainToggles(
                    domainToggles = formState.domainToggles,
                    onToggle = { viewModel.toggleDomain(it) }
                )
            } else {
                // WEBSITE mode: show pre-selected domains as chips (read-only)
                if (formState.blockedDomains.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        formState.blockedDomains.forEach { domain ->
                            FilterChip(selected = true, onClick = {}, label = { Text(domain) })
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Custom domain input (both modes)
            CustomDomainInput(
                inputValue = formState.customDomainInput,
                onInputChange = { viewModel.updateCustomDomainInput(it) },
                onAdd = { viewModel.addCustomDomain() }
            )
            if (formState.customDomains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    formState.customDomains.forEach { domain ->
                        InputChip(
                            selected = false,
                            onClick = { viewModel.removeCustomDomain(domain) },
                            label = { Text(domain) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── Usage Schedule section ──────────────────────────────────────────
            UsageScheduleSection(
                scheduleStartTime = formState.scheduleStartTime,
                scheduleEndTime = formState.scheduleEndTime,
                activeDays = formState.activeDays,
                onStartTimeChange = { viewModel.updateScheduleStartTime(it) },
                onEndTimeChange = { viewModel.updateScheduleEndTime(it) },
                onToggleDay = { viewModel.toggleActiveDay(it) },
                onClearSchedule = { viewModel.clearSchedule() }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── Mode toggle ─────────────────────────────────────────────────────
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
                ) { Text(stringResource(R.string.challenge_setup_mode_soft_label)) }
                SegmentedButton(
                    selected = formState.mode == ChallengeMode.HARD,
                    onClick = { viewModel.updateMode(ChallengeMode.HARD) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.challenge_setup_mode_hard_label)) }
            }

            if (formState.mode == ChallengeMode.HARD) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.challenge_setup_amount_label, formState.amountEuros),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
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
                FilterChip(
                    selected = formState.limitType == LimitType.TIME_BUDGET,
                    onClick = { viewModel.updateLimitType(LimitType.TIME_BUDGET) },
                    label = { Text(stringResource(R.string.challenge_setup_budget_limit)) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val targetLabel = if (formState.blockingType == BlockingType.APP)
                formState.displayName
            else
                stringResource(R.string.challenge_setup_website_label)

            when (formState.limitType) {
                LimitType.TIME -> TimeLimitInput(
                    limitMinutes = formState.limitMinutes,
                    error = formState.limitMinutesError,
                    displayName = targetLabel,
                    onValueChange = { viewModel.updateLimitMinutes(it) }
                )
                LimitType.SESSIONS -> SessionLimitInput(
                    limitSessions = formState.limitSessions,
                    sessionMinutes = formState.sessionMinutes,
                    sessionsError = formState.limitSessionsError,
                    sessionMinsError = formState.sessionMinutesError,
                    displayName = targetLabel,
                    onSessionsChange = { viewModel.updateLimitSessions(it) },
                    onSessionMinsChange = { viewModel.updateSessionMinutes(it) }
                )
                LimitType.TIME_BUDGET -> BudgetLimitInput(
                    budgetMinutes = formState.dailyBudgetMinutes,
                    error = formState.dailyBudgetMinutesError,
                    displayName = targetLabel,
                    onValueChange = { viewModel.updateDailyBudgetMinutes(it) }
                )
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
                listOf(1, 7, 14, 30).forEach { days ->
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
                text = if (formState.mode == ChallengeMode.SOFT)
                    stringResource(R.string.challenge_setup_mode_soft)
                else
                    stringResource(R.string.challenge_setup_hard_warning),
                style = MaterialTheme.typography.labelSmall,
                color = if (formState.mode == ChallengeMode.HARD)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(32.dp))

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

            val isLoading = uiState is ChallengeSetupUiState.Loading ||
                    uiState is ChallengeSetupUiState.AwaitingPayment

            Button(
                onClick = { viewModel.createChallenge() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = if (formState.mode == ChallengeMode.HARD)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.height(20.dp)
                    )
                } else {
                    Text(
                        if (formState.mode == ChallengeMode.HARD)
                            "${stringResource(R.string.challenge_setup_start_button)} · €${formState.amountEuros}"
                        else
                            stringResource(R.string.challenge_setup_start_button)
                    )
                }
            }
        }
    }
}

// ── App domain toggles (APP mode) ─────────────────────────────────────────────

@Composable
private fun AppDomainToggles(
    domainToggles: Map<String, Boolean>,
    onToggle: (String) -> Unit
) {
    if (domainToggles.isEmpty()) {
        Text(
            text = stringResource(R.string.challenge_setup_no_detected_domains),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        return
    }
    domainToggles.forEach { (pkg, enabled) ->
        val domain = APP_DOMAIN_MAP[pkg] ?: return@forEach
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = domain,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = enabled, onCheckedChange = { onToggle(pkg) })
        }
    }
}

// ── Custom domain input ───────────────────────────────────────────────────────

@Composable
private fun CustomDomainInput(
    inputValue: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputValue,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.challenge_setup_custom_domain_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onAdd() })
        )
        Button(onClick = onAdd) {
            Text(stringResource(R.string.challenge_setup_custom_domain_add))
        }
    }
}

// ── Usage Schedule section ────────────────────────────────────────────────────

private val ALL_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val DAY_LABELS = mapOf(
    "MON" to "Mo", "TUE" to "Tu", "WED" to "We",
    "THU" to "Th", "FRI" to "Fr", "SAT" to "Sa", "SUN" to "Su"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageScheduleSection(
    scheduleStartTime: String,
    scheduleEndTime: String,
    activeDays: Set<String>,
    onStartTimeChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onToggleDay: (String) -> Unit,
    onClearSchedule: () -> Unit
) {
    val hasSchedule = scheduleStartTime.isNotBlank() || scheduleEndTime.isNotBlank() || activeDays.isNotEmpty()

    Text(
        text = stringResource(R.string.challenge_setup_schedule_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.challenge_setup_schedule_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))

    // Time range row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = scheduleStartTime,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(4)
                val formatted = if (digits.length >= 3)
                    "${digits.take(2)}:${digits.drop(2)}"
                else digits
                onStartTimeChange(formatted)
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.challenge_setup_schedule_start_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(stringResource(R.string.challenge_setup_schedule_from)) }
        )
        Text(
            text = "–",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        OutlinedTextField(
            value = scheduleEndTime,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(4)
                val formatted = if (digits.length >= 3)
                    "${digits.take(2)}:${digits.drop(2)}"
                else digits
                onEndTimeChange(formatted)
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.challenge_setup_schedule_end_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(stringResource(R.string.challenge_setup_schedule_to)) }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Day selector
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ALL_DAYS.forEach { day ->
            FilterChip(
                selected = activeDays.contains(day),
                onClick = { onToggleDay(day) },
                label = { Text(DAY_LABELS[day] ?: day) }
            )
        }
    }

    if (hasSchedule) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClearSchedule) {
            Text(stringResource(R.string.challenge_setup_schedule_clear))
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
        supportingText = if (error != null) { { Text(error) } } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (error == null && (text.toIntOrNull() ?: 0) >= 5) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.challenge_setup_time_summary, displayName, text.toIntOrNull() ?: limitMinutes),
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
        supportingText = if (sessionsError != null) { { Text(sessionsError) } } else null,
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
        supportingText = if (sessionMinsError != null) { { Text(sessionMinsError) } } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    val sessionsVal = sessionsText.toIntOrNull() ?: 0
    val minsVal = sessionMinsText.toIntOrNull() ?: 0
    if (sessionsError == null && sessionMinsError == null && sessionsVal >= 1 && minsVal >= 1) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.challenge_setup_session_summary, displayName, sessionsVal, minsVal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ── Daily Time Budget input ────────────────────────────────────────────────────

@Composable
private fun BudgetLimitInput(
    budgetMinutes: Int,
    error: String?,
    displayName: String,
    onValueChange: (Int) -> Unit
) {
    var text by remember { mutableStateOf(budgetMinutes.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            text = digits
            onValueChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(stringResource(R.string.challenge_setup_budget_field_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    val budgetVal = text.toIntOrNull() ?: 0
    if (error == null && budgetVal >= 1) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.challenge_setup_budget_summary, displayName, budgetVal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
