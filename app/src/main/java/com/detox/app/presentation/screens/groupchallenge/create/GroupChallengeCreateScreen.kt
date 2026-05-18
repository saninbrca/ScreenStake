package com.detox.app.presentation.screens.groupchallenge.create

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import com.detox.app.R
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.components.AppWebsiteSelectionStep
import com.detox.app.presentation.components.DetoxHorizontalPicker
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Placeholder for APP_DOMAIN_MAP
// In a real application, this would likely be loaded from a more central location
// or a dedicated data source. For now, we'll define a simple map to resolve the compilation errors.
val APP_DOMAIN_MAP: Map<String, List<String>> = mapOf(
    "Social Media" to listOf("facebook.com", "instagram.com", "twitter.com"),
    "Video Streaming" to listOf("youtube.com", "netflix.com"),
    "News" to listOf("nytimes.com", "cnn.com")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeCreateScreen(
    onBack: () -> Unit,
    onCreated: (groupId: String) -> Unit,
    viewModel: GroupChallengeCreateViewModel = hiltViewModel(),
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appListState by viewModel.appListState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val paymentCancelledMessage = stringResource(R.string.group_create_payment_cancelled)
    val paymentFailedMessage = stringResource(R.string.group_create_payment_failed)

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadApps()
        }
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentSuccess()
            is PaymentSheetResult.Canceled -> {
                viewModel.onPaymentCancelled()
                coroutineScope.launch { snackbarHostState.showSnackbar(paymentCancelledMessage) }
            }
            is PaymentSheetResult.Failed -> {
                viewModel.onPaymentFailed()
                coroutineScope.launch { snackbarHostState.showSnackbar(paymentFailedMessage) }
            }
        }
    }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GroupCreateUiState.AwaitingPayment -> {
                paymentSheet.presentWithPaymentIntent(
                    paymentIntentClientSecret = s.clientSecret,
                    configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App"),
                )
            }
            is GroupCreateUiState.Created -> {
                onCreated(s.groupId)
            }
            is GroupCreateUiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    BackHandler {
        if (formState.currentStep == 1) onBack()
        else viewModel.goBack()
    }

    val isLoading = uiState is GroupCreateUiState.Loading || uiState is GroupCreateUiState.AwaitingPayment

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(snackbarHostState)

            WizardHeader(
                currentStep = formState.currentStep,
                totalSteps = GROUP_WIZARD_TOTAL_STEPS,
                onBack = {
                    if (formState.currentStep == 1) onBack()
                    else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = formState.currentStep,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * direction } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * direction } + fadeOut())
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "group_wizard_step",
            ) { step ->
                when (step) {
                    1 -> AppWebsiteSelectionStep(
                        appListState = appListState,
                        selectedApps = formState.packageNames.toSet(),
                        searchQuery = formState.searchQuery,
                        activeTab = formState.activeTab,
                        domainToggles = formState.domainToggles,
                        manualDomains = formState.manualDomains,
                        manualDomainInput = formState.manualDomainInput,
                        manualDomainError = formState.manualDomainError,
                        blockAdultContent = formState.blockAdultContent,
                        partialBlockDomains = formState.partialBlockDomains,
                        partialBlockSections = formState.partialBlockSections,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onToggleApp = viewModel::toggleApp,
                        onReloadApps = viewModel::loadApps,
                        onTabChange = viewModel::updateActiveTab,
                        onToggleDomain = viewModel::toggleDomain,
                        onManualDomainInputChange = viewModel::updateManualDomainInput,
                        onAddManualDomain = viewModel::addManualDomain,
                        onRemoveManualDomain = viewModel::removeManualDomain,
                        onBlockAdultContentChange = viewModel::updateBlockAdultContent,
                        onTogglePartialBlock = viewModel::togglePartialBlockDomain,
                        onTogglePartialSection = viewModel::togglePartialSection,
                    )
                    2 -> Step2LimitType(
                        selected = formState.limitType,
                        onSelect = viewModel::setLimitType,
                    )
                    3 -> Step3LimitAndDuration(
                        formState = formState,
                        onUpdateLimitMinutes = viewModel::setLimitValueMinutes,
                        onUpdateLimitSessions = viewModel::setLimitValueSessions,
                        onUpdateSessionDuration = viewModel::setSessionMinutes,
                        onUpdateDailyBudget = viewModel::setDailyBudgetMinutes,
                        onUpdateDuration = viewModel::setDurationDays,
                    )
                    4 -> Step4BuyIn(
                        buyIn = formState.buyInEuros,
                        onBuyInChange = viewModel::setBuyInEuros,
                    )
                    5 -> Step5StartDateAndBonus(
                        startDateEnabled = formState.startDateEnabled,
                        startDateMs = formState.startDateMs,
                        startDateError = formState.startDateError,
                        bonusEnabled = formState.bonusEnabled,
                        onStartDateEnabledToggle = viewModel::setStartDateEnabled,
                        onStartDateChange = viewModel::setStartDate,
                        onBonusToggle = viewModel::setBonusEnabled,
                    )
                    6 -> Step6Review(
                        formState = formState,
                        isLoading = isLoading,
                        onCreateChallenge = viewModel::createChallenge,
                    )
                }
            }

            if (formState.currentStep < GROUP_WIZARD_TOTAL_STEPS) {
                HorizontalDivider()
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = viewModel::goNext,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.canGoNext() && !isLoading,
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

// ── Wizard header ─────────────────────────────────────────────────────────────

@Composable
private fun WizardHeader(
    currentStep: Int,
    totalSteps: Int,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Step $currentStep of $totalSteps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
        LinearProgressIndicator(
            progress = { currentStep.toFloat() / totalSteps.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ── Step 2: Limit type ────────────────────────────────────────────────────────

@Composable
private fun Step2LimitType(
    selected: LimitType?,
    onSelect: (LimitType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Set a limit type",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "How do you want to restrict the group?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        GroupLimitTypeCard(
            emoji = "⏱",
            title = "Time Limit",
            description = "Block after X minutes per day",
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        GroupLimitTypeCard(
            emoji = "🔢",
            title = "Session Limit",
            description = "Block after X conscious opens per day",
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        GroupLimitTypeCard(
            emoji = "💰",
            title = "Daily Budget",
            description = "Split your time across the day",
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
    }
}

@Composable
private fun GroupLimitTypeCard(
    emoji: String,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 2.dp, color = borderColor, shape = MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Step 3: Limit value + duration ────────────────────────────────────────────

@Composable
private fun Step3LimitAndDuration(
    formState: GroupCreateFormState,
    onUpdateLimitMinutes: (Int) -> Unit,
    onUpdateLimitSessions: (Int) -> Unit,
    onUpdateSessionDuration: (Int) -> Unit,
    onUpdateDailyBudget: (Int) -> Unit,
    onUpdateDuration: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Set your limit",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        when (formState.limitType) {
            LimitType.TIME -> {
                DetoxHorizontalPicker(
                    values = (1..480).toList(),
                    selectedValue = formState.limitValueMinutes,
                    onValueChange = onUpdateLimitMinutes,
                    unit = "Minuten pro Tag",
                )
            }
            LimitType.SESSIONS -> {
                DetoxHorizontalPicker(
                    values = (1..50).toList(),
                    selectedValue = formState.limitValueSessions,
                    onValueChange = onUpdateLimitSessions,
                    unit = "Öffnungen pro Tag",
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "How long per session?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (1..120).toList(),
                    selectedValue = formState.sessionMinutes,
                    onValueChange = onUpdateSessionDuration,
                    unit = "Minuten pro Session",
                )
            }
            LimitType.TIME_BUDGET -> {
                DetoxHorizontalPicker(
                    values = (1..480).toList(),
                    selectedValue = formState.dailyBudgetMinutes,
                    onValueChange = onUpdateDailyBudget,
                    unit = "Minuten Tagesbudget",
                )
            }
            else -> Unit
        }

        HorizontalDivider()

        Text(
            text = "Duration",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        DetoxHorizontalPicker(
            values = (3..365).toList(),
            selectedValue = formState.durationDays,
            onValueChange = onUpdateDuration,
            unit = "Tage",
        )
    }
}

// ── Step 4: Buy-in ────────────────────────────────────────────────────────────

@Composable
private fun Step4BuyIn(
    buyIn: Int,
    onBuyInChange: (Int) -> Unit,
) {
    val estimatedPot = buyIn * 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Buy-In Per Player",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "How much does each player pay to join?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetoxHorizontalPicker(
            values = (10..500).toList(),
            selectedValue = buyIn,
            onValueChange = onBuyInChange,
            unit = "Euro pro Spieler",
        )
        Text(
            text = "Total pot with 20 players: €$estimatedPot",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ── Step 5: Start date + bonus ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step5StartDateAndBonus(
    startDateEnabled: Boolean,
    startDateMs: Long,
    startDateError: String?,
    bonusEnabled: Boolean,
    onStartDateEnabledToggle: (Boolean) -> Unit,
    onStartDateChange: (Long) -> Unit,
    onBonusToggle: (Boolean) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()
    val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { onStartDateChange(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Start date & bonus",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        // ── Set start date toggle ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Set start date",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(checked = startDateEnabled, onCheckedChange = onStartDateEnabledToggle)
        }

        if (!startDateEnabled) {
            Text(
                text = "Challenge will start manually — tap \"Start Challenge\" in the group screen when everyone has joined.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (startDateEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Start Date",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        if (startDateMs > 0L) sdf.format(Date(startDateMs))
                        else "Pick a date…"
                    )
                }
                if (startDateError != null) {
                    Text(
                        text = startDateError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        HorizontalDivider()

        val tooltipState = rememberTooltipState()
        val tooltipScope = rememberCoroutineScope()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Bonus for winner 🏆",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = {
                        PlainTooltip {
                            Text("Best performer gets 10% extra from the pot. App takes 5% fee.")
                        }
                    },
                    state = tooltipState,
                ) {
                    IconButton(
                        onClick = { tooltipScope.launch { tooltipState.show() } },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Bonus info",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Switch(checked = bonusEnabled, onCheckedChange = onBonusToggle)
        }

        if (bonusEnabled) {
            Text(
                text = "The participant with the best performance receives an extra 10% of the total pot. The app takes a 5% fee.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Step 6: Review & create ───────────────────────────────────────────────────

@Composable
private fun Step6Review(
    formState: GroupCreateFormState,
    isLoading: Boolean,
    onCreateChallenge: () -> Unit,
) {
    val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val estimatedPot = formState.buyInEuros * 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Review & Create",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val appSummary = if (formState.packageNames.size == 1) formState.displayName
                    else "${formState.displayName} +${formState.packageNames.size - 1} more"
                ReviewRow("Apps", appSummary)

                val checkedDomains = formState.domainToggles.entries
                    .filter { it.value }
                    .flatMap { APP_DOMAIN_MAP[it.key] ?: emptyList() }
                val allBlockedDomains = (checkedDomains + formState.manualDomains).distinct()
                if (allBlockedDomains.isNotEmpty()) {
                    ReviewRow("+ Websites", allBlockedDomains.joinToString(", "))
                }

                val limitSummary = when (formState.limitType) {
                    LimitType.TIME -> "⏱ ${formState.limitValueMinutes} min/day"
                    LimitType.SESSIONS -> "🔢 ${formState.limitValueSessions}× · ${formState.sessionMinutes} min each"
                    LimitType.TIME_BUDGET -> "💰 ${formState.dailyBudgetMinutes} min budget/day"
                    else -> "—"
                }
                ReviewRow("Limit", limitSummary)
                ReviewRow("Duration", "${formState.durationDays} days")
                ReviewRow("Buy-in", "€${formState.buyInEuros} per player")
                ReviewRow(
                    "Start",
                    if (formState.startDateEnabled && formState.startDateMs > 0L)
                        sdf.format(Date(formState.startDateMs))
                    else
                        "Manual start 🚀"
                )
                ReviewRow("Winner bonus", if (formState.bonusEnabled) "Enabled (10% of pot)" else "Disabled")
                ReviewRow("Max players", "20")

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Estimated pot:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Up to €$estimatedPot with 20 players",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onCreateChallenge,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Create Group Challenge")
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}