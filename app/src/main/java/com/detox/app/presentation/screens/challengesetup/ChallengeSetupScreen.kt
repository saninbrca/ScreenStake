package com.detox.app.presentation.screens.challengesetup

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.detox.app.presentation.components.StepperField
import com.detox.app.presentation.components.TimeSpinnerPicker
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
            is PaymentSheetResult.Canceled  -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed    -> viewModel.onPaymentCancelled()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.Success) onChallengeCreated()
    }
    LaunchedEffect(uiState) {
        if (uiState is ChallengeSetupUiState.AwaitingPayment) {
            val s = uiState as ChallengeSetupUiState.AwaitingPayment
            paymentSheet.presentWithPaymentIntent(
                paymentIntentClientSecret = s.clientSecret,
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {

            // ── Header ──────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (formState.blockingType == BlockingType.APP)
                    stringResource(R.string.challenge_setup_app_label, formState.displayName)
                else
                    stringResource(R.string.challenge_type_website_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            // Domain suggestion chips — inline, no separate section (APP mode only)
            if (formState.blockingType == BlockingType.APP && formState.domainToggles.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    formState.domainToggles.forEach { (pkg, enabled) ->
                        val domains = APP_DOMAIN_MAP[pkg] ?: return@forEach
                        FilterChip(
                            selected = enabled,
                            onClick = { viewModel.toggleDomain(pkg) },
                            label = { Text("Also block ${domains.joinToString(", ")}?") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ── Website Blocking (WEBSITE mode only) ─────────────────────────────
            // For APP mode, domain suggestions are shown inline in the header above.
            if (formState.blockingType == BlockingType.WEBSITE) {
                Text(
                    text = stringResource(R.string.challenge_setup_websites_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))
                if (formState.blockedDomains.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        formState.blockedDomains.forEach { domain ->
                            FilterChip(selected = true, onClick = {}, label = { Text(domain) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.challenge_setup_adult_content_label),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (formState.blockAdultContent) {
                            Text(
                                text = stringResource(R.string.challenge_setup_adult_content_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Switch(
                        checked = formState.blockAdultContent,
                        onCheckedChange = { viewModel.updateBlockAdultContent(it) }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Custom domain input
            Spacer(Modifier.height(12.dp))
            CustomDomainInput(
                inputValue = formState.customDomainInput,
                onInputChange = { viewModel.updateCustomDomainInput(it) },
                onAdd = { viewModel.addCustomDomain() }
            )
            if (formState.customDomains.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ── Usage Schedule ───────────────────────────────────────────────────
            UsageScheduleSection(
                scheduleStartTime = formState.scheduleStartTime,
                scheduleEndTime = formState.scheduleEndTime,
                activeDays = formState.activeDays,
                onStartTimeChange = { viewModel.updateScheduleStartTime(it) },
                onEndTimeChange = { viewModel.updateScheduleEndTime(it) },
                onToggleDay = { viewModel.toggleActiveDay(it) },
                onClearSchedule = { viewModel.clearSchedule() }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ── Mode ─────────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_mode_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.challenge_setup_amount_label, formState.amountEuros),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(10.dp))
                // StepperField: €5–€50 in steps of €5
                StepperField(
                    value = formState.amountEuros,
                    onValueChange = { viewModel.updateAmountEuros(it) },
                    label = "Amount (€)",
                    min = 5,
                    max = 50,
                    step = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.challenge_setup_hard_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Limit Type ───────────────────────────────────────────────────────
            if (formState.blockAdultContent) {
                Text(
                    text = stringResource(R.string.challenge_setup_adult_content_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = stringResource(R.string.challenge_setup_limit_type),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                // Horizontal scroll row so chips never wrap/stack vertically
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = formState.limitType == LimitType.TIME,
                        onClick = { viewModel.updateLimitType(LimitType.TIME) },
                        label = { Text(stringResource(R.string.challenge_setup_time_limit), maxLines = 1) }
                    )
                    FilterChip(
                        selected = formState.limitType == LimitType.SESSIONS,
                        onClick = { viewModel.updateLimitType(LimitType.SESSIONS) },
                        label = { Text(stringResource(R.string.challenge_setup_session_limit), maxLines = 1) }
                    )
                    FilterChip(
                        selected = formState.limitType == LimitType.TIME_BUDGET,
                        onClick = { viewModel.updateLimitType(LimitType.TIME_BUDGET) },
                        label = { Text(stringResource(R.string.challenge_setup_budget_limit), maxLines = 1) }
                    )
                    FilterChip(
                        selected = formState.limitType == LimitType.TIME_WINDOW,
                        onClick = { viewModel.updateLimitType(LimitType.TIME_WINDOW) },
                        label = { Text(stringResource(R.string.challenge_setup_time_window_limit), maxLines = 1) }
                    )
                }

                Spacer(Modifier.height(12.dp))

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
                    LimitType.TIME_WINDOW -> Text(
                        text = stringResource(R.string.challenge_setup_time_window_info),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Duration ─────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_duration),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))

            if (formState.mode == ChallengeMode.SOFT) {
                // Soft Mode: "No end date" toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.challenge_setup_no_end_date),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Switch(
                        checked = formState.noEndDate,
                        onCheckedChange = { viewModel.updateNoEndDate(it) }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (!formState.noEndDate) {
                val isHardMode = formState.mode == ChallengeMode.HARD
                StepperField(
                    value = formState.durationDays,
                    onValueChange = { viewModel.updateDurationDays(it) },
                    label = stringResource(R.string.challenge_setup_duration_field_label),
                    suffix = "days",
                    min = if (isHardMode) 14 else 1,
                    max = 365,
                    step = 1,
                    error = formState.durationDaysError,
                    modifier = Modifier.fillMaxWidth()
                )
                // Quick-pick presets
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = if (formState.mode == ChallengeMode.HARD)
                        listOf(14, 21, 30, 60)
                    else
                        listOf(1, 7, 14, 30)
                    presets.forEach { days ->
                        FilterChip(
                            selected = formState.durationDays == days,
                            onClick = { viewModel.updateDurationDays(days) },
                            label = { Text(stringResource(R.string.challenge_setup_days, days), maxLines = 1) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Motivation ───────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.challenge_setup_motivation_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = formState.motivationText,
                onValueChange = { if (it.length <= 200) viewModel.updateMotivationText(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.challenge_setup_motivation_hint)) },
                minLines = 2,
                maxLines = 4
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (formState.mode == ChallengeMode.SOFT)
                    stringResource(R.string.challenge_setup_mode_soft)
                else
                    stringResource(R.string.challenge_setup_hard_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = if (formState.mode == ChallengeMode.HARD)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.height(16.dp))

            if (uiState is ChallengeSetupUiState.Error) {
                Text(
                    text = (uiState as ChallengeSetupUiState.Error).message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            val isLoading = uiState is ChallengeSetupUiState.Loading ||
                    uiState is ChallengeSetupUiState.AwaitingPayment

            val scheduleTimeError: String? = if (
                formState.scheduleStartTime.length == 5 && formState.scheduleEndTime.length == 5
            ) {
                val (sH, sM) = parseTimeString(formState.scheduleStartTime)
                val (eH, eM) = parseTimeString(formState.scheduleEndTime)
                if (sH * 60 + sM >= eH * 60 + eM) "End time must be after start time" else null
            } else null

            Button(
                onClick = { viewModel.createChallenge() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && scheduleTimeError == null,
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
                        text = if (formState.mode == ChallengeMode.HARD)
                            "${stringResource(R.string.challenge_setup_start_button)} · €${formState.amountEuros}"
                        else
                            stringResource(R.string.challenge_setup_start_button),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
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

private fun parseTimeString(time: String): Pair<Int, Int> {
    if (time.length != 5) return 0 to 0
    return try {
        val parts = time.split(":")
        parts[0].toInt() to parts[1].toInt()
    } catch (e: Exception) {
        0 to 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var showTimePicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val (startHour, startMinute) = parseTimeString(scheduleStartTime)
    val (endHour, endMinute) = parseTimeString(scheduleEndTime)

    val hasSchedule = scheduleStartTime.isNotBlank() || scheduleEndTime.isNotBlank() || activeDays.isNotEmpty()

    // Validate: end must be after start (only when both are set)
    val timeError: String? = if (scheduleStartTime.length == 5 && scheduleEndTime.length == 5) {
        val startMins = startHour * 60 + startMinute
        val endMins = endHour * 60 + endMinute
        if (startMins >= endMins) "End time must be after start time" else null
    } else null

    val timeDisplay = when {
        scheduleStartTime.length == 5 && scheduleEndTime.length == 5 -> "$scheduleStartTime – $scheduleEndTime"
        scheduleStartTime.length == 5 -> "$scheduleStartTime – ??:??"
        scheduleEndTime.length == 5 -> "??:?? – $scheduleEndTime"
        else -> "Tap to set time range"
    }

    Text(
        text = stringResource(R.string.challenge_setup_schedule_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.challenge_setup_schedule_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    // Single tappable row — opens BottomSheet on tap
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .border(
                width = 1.dp,
                color = if (timeError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small
            )
            .clickable { showTimePicker = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = timeDisplay,
            style = MaterialTheme.typography.bodyLarge,
            color = if (scheduleStartTime.length == 5 || scheduleEndTime.length == 5)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (timeError != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = timeError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(12.dp))

    // Compact day chip row
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ALL_DAYS.forEach { day ->
            FilterChip(
                selected = activeDays.contains(day),
                onClick = { onToggleDay(day) },
                label = { Text(DAY_LABELS[day] ?: day, maxLines = 1) }
            )
        }
    }

    if (hasSchedule) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClearSchedule) {
            Text(stringResource(R.string.challenge_setup_schedule_clear))
        }
    }

    // Time picker BottomSheet
    if (showTimePicker) {
        ModalBottomSheet(
            onDismissRequest = { showTimePicker = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.challenge_setup_schedule_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    TimeSpinnerPicker(
                        hour = startHour,
                        minute = startMinute,
                        label = stringResource(R.string.challenge_setup_schedule_from),
                        onTimeChange = { h, m -> onStartTimeChange("%02d:%02d".format(h, m)) }
                    )
                    TimeSpinnerPicker(
                        hour = endHour,
                        minute = endMinute,
                        label = stringResource(R.string.challenge_setup_schedule_to),
                        onTimeChange = { h, m -> onEndTimeChange("%02d:%02d".format(h, m)) }
                    )
                }
                if (timeError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = timeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showTimePicker = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
                Spacer(Modifier.height(32.dp))
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
    StepperField(
        value = limitMinutes,
        onValueChange = onValueChange,
        label = stringResource(R.string.challenge_setup_minutes_field_label),
        suffix = "min",
        min = 1,
        max = 600,
        step = 5,
        error = error,
        modifier = Modifier.fillMaxWidth()
    )
    if (error == null && limitMinutes >= 5) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.challenge_setup_time_summary, displayName, limitMinutes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    StepperField(
        value = limitSessions,
        onValueChange = onSessionsChange,
        label = stringResource(R.string.challenge_setup_sessions_count_label),
        suffix = "opens",
        min = 1,
        max = 50,
        step = 1,
        error = sessionsError,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    StepperField(
        value = sessionMinutes,
        onValueChange = onSessionMinsChange,
        label = stringResource(R.string.challenge_setup_session_mins_label),
        suffix = "min",
        min = 1,
        max = 120,
        step = 1,
        error = sessionMinsError,
        modifier = Modifier.fillMaxWidth()
    )
    if (sessionsError == null && sessionMinsError == null && limitSessions >= 1 && sessionMinutes >= 1) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.challenge_setup_session_summary,
                displayName, limitSessions, sessionMinutes
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    StepperField(
        value = budgetMinutes,
        onValueChange = onValueChange,
        label = stringResource(R.string.challenge_setup_budget_field_label),
        suffix = "min",
        min = 1,
        max = 600,
        step = 5,
        error = error,
        modifier = Modifier.fillMaxWidth()
    )
    if (error == null && budgetMinutes >= 1) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.challenge_setup_budget_summary, displayName, budgetMinutes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
