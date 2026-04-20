package com.detox.app.presentation.screens.groupchallenge.create

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SelectableDates
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.components.StepperField
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeCreateScreen(
    onBack: () -> Unit,
    onSelectApps: () -> Unit,
    onCreated: (groupId: String) -> Unit,
    viewModel: GroupChallengeCreateViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Stripe PaymentSheet for creator's buy-in
    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentSuccess()
            is PaymentSheetResult.Canceled -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed -> viewModel.onPaymentCancelled()
        }
    }

    // Two-step state: step 1 = settings, step 2 = review (shown after payment)
    var currentStep by remember { mutableIntStateOf(1) }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GroupCreateUiState.AwaitingPayment -> {
                // Launch PaymentSheet for creator's buy-in
                paymentSheet.presentWithPaymentIntent(
                    paymentIntentClientSecret = s.clientSecret,
                    configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App")
                )
            }
            is GroupCreateUiState.Created -> {
                // Payment confirmed — show review step then navigate
                currentStep = 2
            }
            is GroupCreateUiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    // Observe back-stack result for selected packages (from AppSelectionScreen)
    // This is handled by the parent nav host passing data back via savedStateHandle.

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentStep == 1) stringResource(R.string.group_create_title_step1)
                        else stringResource(R.string.group_create_title_step2)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentStep == 2) currentStep = 1
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (currentStep == 1) {
            Step1Settings(
                formState = formState,
                modifier = Modifier.padding(innerPadding),
                onSelectApps = onSelectApps,
                onLimitTypeChange = viewModel::setLimitType,
                onLimitMinutesChange = viewModel::setLimitValueMinutes,
                onLimitSessionsChange = viewModel::setLimitValueSessions,
                onDurationChange = viewModel::setDurationDays,
                onBuyInChange = viewModel::setBuyInEuros,
                onMaxParticipantsChange = viewModel::setMaxParticipants,
                onStartDateChange = viewModel::setStartDate,
                onBonusToggle = viewModel::setBonusEnabled,
                onShowTooltip = viewModel::setShowBonusTooltip,
                onNext = {
                    if (viewModel.validateStep1()) {
                        viewModel.createChallenge()
                    }
                },
                isLoading = uiState is GroupCreateUiState.Loading || uiState is GroupCreateUiState.AwaitingPayment
            )
        } else {
            Step2Review(
                formState = formState,
                modifier = Modifier.padding(innerPadding),
                onShare = {
                    val code = formState.generatedCode
                    val limitSummary = when (formState.limitType) {
                        LimitType.TIME -> "${formState.limitValueMinutes} min/day"
                        LimitType.SESSIONS -> "${formState.limitValueSessions} opens/day"
                        LimitType.TIME_BUDGET -> "Budget ${formState.limitValueMinutes} min/day"
                        else -> "Time window"
                    }
                    val shareText = context.getString(
                        R.string.group_create_share_text,
                        code,
                        formState.displayName,
                        formState.durationDays,
                        formState.buyInEuros
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.group_create_share_chooser)))
                },
                onDone = {
                    (uiState as? GroupCreateUiState.Created)?.let { onCreated(it.groupId) }
                }
            )
        }
    }
}

// ── Step 1 ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step1Settings(
    formState: GroupCreateFormState,
    modifier: Modifier = Modifier,
    onSelectApps: () -> Unit,
    onLimitTypeChange: (LimitType) -> Unit,
    onLimitMinutesChange: (Int) -> Unit,
    onLimitSessionsChange: (Int) -> Unit,
    onDurationChange: (Int) -> Unit,
    onBuyInChange: (Int) -> Unit,
    onMaxParticipantsChange: (Int) -> Unit,
    onStartDateChange: (Long) -> Unit,
    onBonusToggle: (Boolean) -> Unit,
    onShowTooltip: (Boolean) -> Unit,
    onNext: () -> Unit,
    isLoading: Boolean
) {
    var showDatePicker by remember { mutableStateOf(false) }
    // Tomorrow at UTC midnight — minimum selectable date in the picker
    val tomorrowUtcMidnight = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = tomorrowUtcMidnight,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= tomorrowUtcMidnight
        }
    )
    val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { onStartDateChange(it) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Apps to block ───────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.group_create_apps_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (formState.packageNames.isNotEmpty()) {
                Text(
                    text = formState.displayName + if (formState.packageNames.size > 1)
                        " +${formState.packageNames.size - 1} more" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedButton(
                onClick = onSelectApps,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (formState.packageNames.isEmpty()) stringResource(R.string.group_create_apps_select)
                    else stringResource(R.string.group_create_apps_change)
                )
            }
            formState.packageNamesError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── Limit type ──────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.challenge_setup_limit_type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            // Horizontal scrollable row — chips never wrap/stack
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(LimitType.TIME, LimitType.SESSIONS, LimitType.TIME_BUDGET).forEach { type ->
                    FilterChip(
                        selected = formState.limitType == type,
                        onClick = { onLimitTypeChange(type) },
                        label = {
                            Text(
                                text = when (type) {
                                    LimitType.TIME -> stringResource(R.string.challenge_setup_time_limit)
                                    LimitType.SESSIONS -> stringResource(R.string.challenge_setup_session_limit)
                                    LimitType.TIME_BUDGET -> stringResource(R.string.challenge_setup_budget_limit)
                                    else -> ""
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            when (formState.limitType) {
                LimitType.TIME, LimitType.TIME_BUDGET -> {
                    StepperField(
                        value = formState.limitValueMinutes,
                        onValueChange = onLimitMinutesChange,
                        label = stringResource(R.string.challenge_setup_minutes_field_label),
                        suffix = "min",
                        min = 5,
                        max = 240,
                        step = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                LimitType.SESSIONS -> {
                    StepperField(
                        value = formState.limitValueSessions,
                        onValueChange = onLimitSessionsChange,
                        label = stringResource(R.string.challenge_setup_sessions_count_label),
                        suffix = "opens/day",
                        min = 1,
                        max = 20,
                        step = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                LimitType.TIME_WINDOW -> Unit
            }
        }

        // ── Duration ────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.challenge_setup_duration),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StepperField(
                value = formState.durationDays,
                onValueChange = onDurationChange,
                label = "Duration",
                suffix = "days",
                min = 1,
                max = 30,
                step = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Buy-in per player ────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.group_create_buy_in_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StepperField(
                value = formState.buyInEuros,
                onValueChange = onBuyInChange,
                label = stringResource(R.string.group_create_buy_in_field_label),
                suffix = "€",
                min = 10,
                max = 50,
                step = 1,
                error = formState.buyInEurosError,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Max participants ────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.group_create_max_participants_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StepperField(
                value = formState.maxParticipants,
                onValueChange = onMaxParticipantsChange,
                label = "Max players",
                suffix = "players",
                min = 2,
                max = 20,
                step = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Start date ──────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.group_create_start_date_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(
                    if (formState.startDateMs > 0L)
                        sdf.format(Date(formState.startDateMs))
                    else
                        stringResource(R.string.group_create_start_date_hint)
                )
            }
            formState.startDateError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── Bonus toggle ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.group_create_bonus_label),
                    style = MaterialTheme.typography.bodyMedium
                )
                val tooltipState = rememberTooltipState()
                val tooltipScope = rememberCoroutineScope()
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = {
                        PlainTooltip {
                            Text(stringResource(R.string.group_create_bonus_tooltip))
                        }
                    },
                    state = tooltipState
                ) {
                    IconButton(
                        onClick = { tooltipScope.launch { tooltipState.show() } },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.group_create_bonus_info),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Switch(
                checked = formState.bonusEnabled,
                onCheckedChange = onBonusToggle
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(stringResource(R.string.group_create_next))
        }
    }
}

// ── Step 2 ────────────────────────────────────────────────────────────────────

@Composable
private fun Step2Review(
    formState: GroupCreateFormState,
    modifier: Modifier = Modifier,
    onShare: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Generated code display
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.group_create_code_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formState.generatedCode,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 8.dp.value.let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.group_create_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Summary card
        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SummaryRow(label = stringResource(R.string.group_create_summary_app), value = formState.displayName)
                val limitSummary = when (formState.limitType) {
                    LimitType.TIME -> "${formState.limitValueMinutes} min/day"
                    LimitType.SESSIONS -> "${formState.limitValueSessions} opens/day"
                    LimitType.TIME_BUDGET -> "Budget ${formState.limitValueMinutes} min/day"
                    else -> "Time window"
                }
                SummaryRow(label = stringResource(R.string.group_create_summary_limit), value = limitSummary)
                SummaryRow(
                    label = stringResource(R.string.group_create_summary_duration),
                    value = "${formState.durationDays} days"
                )
                SummaryRow(
                    label = stringResource(R.string.group_create_summary_buy_in),
                    value = "€${formState.buyInEuros} per player"
                )
                SummaryRow(
                    label = stringResource(R.string.group_create_summary_max_players),
                    value = "${formState.maxParticipants} players"
                )
                if (formState.bonusEnabled) {
                    SummaryRow(
                        label = stringResource(R.string.group_create_summary_bonus),
                        value = "Enabled (10% of pot)"
                    )
                }
            }
        }

        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.group_create_share_button))
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.group_create_done_button))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
