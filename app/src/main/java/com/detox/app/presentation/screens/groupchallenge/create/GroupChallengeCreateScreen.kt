package com.detox.app.presentation.screens.groupchallenge.create

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.R
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.components.AppWebsiteSelectionStep
import com.detox.app.presentation.components.DetoxHorizontalPicker
import com.detox.app.presentation.util.pressScaleFeedback
import com.detox.app.util.HapticManager
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Design tokens ─────────────────────────────────────────────────────────────

private val GWizBg          = Color(0xFFF2F2F7)
private val GCardBg         = Color(0xFFFFFFFF)
private val GCardBorder     = Color(0x0F000000)
private val GGreenPrimary   = Color(0xFF00C853)
private val GGreenLight     = Color(0xFFE8F8EF)
private val GGreenSelected  = Color(0xFFF0FDF4)
private val GTextPrimary    = Color(0xFF000000)
private val GTextSecondary  = Color(0xFF8E8E93)
private val GOrangeLight    = Color(0xFFFFF0E8)
private val GBlueLight      = Color(0xFFE8F0FF)
private val GPurpleLight    = Color(0xFFEEF0FF)
private val GGreenBadgeText = Color(0xFF1E7A3C)
private val GOrangeBadgeText= Color(0xFFC05A00)
private val GOrangeIcon     = Color(0xFFFF6B35)
private val GBlueIcon       = Color(0xFF2979FF)
private val GPurpleIcon     = Color(0xFF7B61FF)
private val GDisabledBg     = Color(0xFFE0E0E5)
private val GDisabledText   = Color(0xFF8E8E93)
private val GDividerColor   = Color(0xFFF2F2F7)
private val GCardShape      = RoundedCornerShape(16.dp)
private val GBtnShape       = RoundedCornerShape(14.dp)

// Placeholder — keeps existing review logic working
val APP_DOMAIN_MAP: Map<String, List<String>> = mapOf(
    "Social Media" to listOf("facebook.com", "instagram.com", "twitter.com"),
    "Video Streaming" to listOf("youtube.com", "netflix.com"),
    "News" to listOf("nytimes.com", "cnn.com"),
)

// ── Screen ────────────────────────────────────────────────────────────────────

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
            is GroupCreateUiState.Created -> onCreated(s.groupId)
            is GroupCreateUiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    BackHandler {
        if (formState.currentStep == 1) onBack() else viewModel.goBack()
    }

    val isLoading = uiState is GroupCreateUiState.Loading || uiState is GroupCreateUiState.AwaitingPayment

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GWizBg,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(snackbarHostState)

            GWizardHeader(
                currentStep = formState.currentStep,
                totalSteps = GROUP_WIZARD_TOTAL_STEPS,
                onBack = {
                    if (formState.currentStep == 1) onBack() else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = formState.currentStep,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * dir } + fadeOut())
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
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onToggleApp = viewModel::toggleApp,
                        onReloadApps = viewModel::loadApps,
                        onTabChange = viewModel::updateActiveTab,
                        onToggleDomain = viewModel::toggleDomain,
                        onManualDomainInputChange = viewModel::updateManualDomainInput,
                        onAddManualDomain = viewModel::addManualDomain,
                        onRemoveManualDomain = viewModel::removeManualDomain,
                        onBlockAdultContentChange = viewModel::updateBlockAdultContent,
                    )
                    2 -> GStep2LimitType(
                        selected = formState.limitType,
                        onSelect = viewModel::setLimitType,
                    )
                    3 -> GStep3LimitAndDuration(
                        formState = formState,
                        onUpdateLimitMinutes = viewModel::setLimitValueMinutes,
                        onUpdateLimitSessions = viewModel::setLimitValueSessions,
                        onUpdateSessionDuration = viewModel::setSessionMinutes,
                        onUpdateDailyBudget = viewModel::setDailyBudgetMinutes,
                        onUpdateDuration = viewModel::setDurationDays,
                    )
                    4 -> GStep4BuyIn(
                        buyIn = formState.buyInEuros,
                        onBuyInChange = viewModel::setBuyInEuros,
                    )
                    5 -> GStep5StartDateAndBonus(
                        startDateEnabled = formState.startDateEnabled,
                        startDateMs = formState.startDateMs,
                        startDateError = formState.startDateError,
                        bonusEnabled = formState.bonusEnabled,
                        onStartDateEnabledToggle = viewModel::setStartDateEnabled,
                        onStartDateChange = viewModel::setStartDate,
                        onBonusToggle = viewModel::setBonusEnabled,
                    )
                    6 -> GStep6Review(
                        formState = formState,
                        isLoading = isLoading,
                        onCreateChallenge = viewModel::createChallenge,
                    )
                }
            }

            if (formState.currentStep < GROUP_WIZARD_TOTAL_STEPS) {
                val context = LocalContext.current
                HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val enabled = viewModel.canGoNext() && !isLoading
                    Button(
                        onClick = {
                            HapticManager.light(context)
                            viewModel.goNext()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .pressScaleFeedback(),
                        enabled = enabled,
                        shape = GBtnShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GGreenPrimary,
                            contentColor = Color.White,
                            disabledContainerColor = GDisabledBg,
                            disabledContentColor = GDisabledText,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.wizard_btn_next),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

// ── Wizard header ─────────────────────────────────────────────────────────────

@Composable
private fun GWizardHeader(
    currentStep: Int,
    totalSteps: Int,
    onBack: () -> Unit,
) {
    val progress = currentStep.toFloat() / totalSteps.toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zurück",
                    tint = GTextPrimary,
                )
            }
            Text(
                text = "Schritt $currentStep von $totalSteps",
                fontSize = 13.sp,
                color = GTextSecondary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = GGreenPrimary,
            trackColor = GDisabledBg,
        )
    }
}

// ── Step 2: Limit type ────────────────────────────────────────────────────────

@Composable
private fun GStep2LimitType(
    selected: LimitType?,
    onSelect: (LimitType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_limit_type_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GTextPrimary,
        )
        Text(
            text = stringResource(R.string.group_wizard_limit_type_subtitle),
            fontSize = 14.sp,
            color = GTextSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        GGroupLimitTypeCard(
            iconBg = GOrangeLight,
            iconContent = { Text("⏱", fontSize = 18.sp) },
            title = stringResource(R.string.wizard_limit_time_title),
            description = stringResource(R.string.wizard_limit_time_desc),
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        GGroupLimitTypeCard(
            iconBg = GBlueLight,
            iconContent = { Text("🔢", fontSize = 18.sp) },
            title = stringResource(R.string.wizard_limit_sessions_title),
            description = stringResource(R.string.wizard_limit_sessions_desc),
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        GGroupLimitTypeCard(
            iconBg = GPurpleLight,
            iconContent = { Text("💰", fontSize = 18.sp) },
            title = stringResource(R.string.wizard_limit_budget_title),
            description = stringResource(R.string.wizard_limit_budget_desc),
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
    }
}

@Composable
private fun GGroupLimitTypeCard(
    iconBg: Color,
    iconContent: @Composable () -> Unit,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) GGreenPrimary else GCardBorder
    val borderWidth = if (isSelected) 2.dp else 0.5.dp
    val bgColor = if (isSelected) GGreenSelected else GCardBg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GCardShape)
            .background(bgColor)
            .border(borderWidth, borderColor, GCardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                iconContent()
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GTextPrimary,
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = GTextSecondary,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = GGreenPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(50))
                        .border(1.5.dp, GTextSecondary, RoundedCornerShape(50)),
                )
            }
        }
    }
}

// ── Step 3: Limit value + duration ────────────────────────────────────────────

@Composable
private fun GStep3LimitAndDuration(
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
            text = stringResource(R.string.wizard_set_limit_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GTextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(GCardBg)
                .border(0.5.dp, GCardBorder, GCardShape)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (formState.limitType) {
                    LimitType.TIME -> DetoxHorizontalPicker(
                        values = (5..120).toList(),
                        selectedValue = formState.limitValueMinutes.coerceIn(5, 120),
                        onValueChange = onUpdateLimitMinutes,
                        unit = stringResource(R.string.wizard_set_limit_minutes_unit),
                    )
                    LimitType.SESSIONS -> {
                        DetoxHorizontalPicker(
                            values = (1..20).toList(),
                            selectedValue = formState.limitValueSessions.coerceAtMost(20),
                            onValueChange = onUpdateLimitSessions,
                            unit = stringResource(R.string.wizard_set_limit_opens_unit),
                        )
                        HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
                        Text(
                            text = stringResource(R.string.wizard_set_limit_session_label),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = GTextSecondary,
                        )
                        DetoxHorizontalPicker(
                            values = (1..30).toList(),
                            selectedValue = formState.sessionMinutes.coerceAtMost(30),
                            onValueChange = onUpdateSessionDuration,
                            unit = stringResource(R.string.wizard_set_limit_session_unit),
                        )
                    }
                    LimitType.TIME_BUDGET -> DetoxHorizontalPicker(
                        values = (5..120).toList(),
                        selectedValue = formState.dailyBudgetMinutes.coerceIn(5, 120),
                        onValueChange = onUpdateDailyBudget,
                        unit = stringResource(R.string.wizard_set_limit_budget_unit),
                    )
                    else -> Unit
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.wizard_duration_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = GTextPrimary,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(GCardBg)
                .border(0.5.dp, GCardBorder, GCardShape)
                .padding(16.dp),
        ) {
            DetoxHorizontalPicker(
                values = (3..30).toList(),
                selectedValue = formState.durationDays.coerceIn(3, 30),
                onValueChange = onUpdateDuration,
                unit = "Tage",
            )
        }
    }
}

// ── Step 4: Buy-in ────────────────────────────────────────────────────────────

@Composable
private fun GStep4BuyIn(
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
            text = stringResource(R.string.group_wizard_buyin_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GTextPrimary,
        )
        Text(
            text = stringResource(R.string.group_wizard_buyin_subtitle),
            fontSize = 14.sp,
            color = GTextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(GCardBg)
                .border(0.5.dp, GCardBorder, GCardShape)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetoxHorizontalPicker(
                    values = (10..50).toList(),
                    selectedValue = buyIn.coerceAtMost(50),
                    onValueChange = onBuyInChange,
                    unit = stringResource(R.string.group_wizard_buyin_unit),
                )
                HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Pot-Schätzung (20 Spieler)",
                        fontSize = 13.sp,
                        color = GTextSecondary,
                    )
                    Text(
                        text = "€$estimatedPot",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = GGreenPrimary,
                    )
                }
            }
        }
    }
}

// ── Step 5: Start date + bonus ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GStep5StartDateAndBonus(
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
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { onStartDateChange(it) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok), color = GGreenPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.dialog_cancel), color = GTextSecondary)
                }
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.group_wizard_start_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GTextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // ── Start date card ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(GCardBg)
                .border(0.5.dp, GCardBorder, GCardShape),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.group_wizard_start_date_label),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = GTextPrimary,
                    )
                    Switch(
                        checked = startDateEnabled,
                        onCheckedChange = onStartDateEnabledToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GGreenPrimary,
                        ),
                    )
                }

                if (!startDateEnabled) {
                    HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
                    Text(
                        text = stringResource(R.string.group_wizard_start_manual_desc),
                        fontSize = 13.sp,
                        color = GTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                if (startDateEnabled) {
                    HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.group_wizard_start_date_section),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = GTextSecondary,
                        )
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Default.CalendarToday,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(end = 4.dp),
                                tint = GGreenPrimary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (startDateMs > 0L) sdf.format(Date(startDateMs))
                                else stringResource(R.string.group_wizard_start_date_placeholder),
                                color = if (startDateMs > 0L) GTextPrimary else GTextSecondary,
                            )
                        }
                        if (startDateError != null) {
                            Text(
                                text = startDateError,
                                fontSize = 12.sp,
                                color = Color(0xFFFF3B30),
                            )
                        }
                    }
                }
            }
        }

        // ── Bonus card ──────────────────────────────────────────────────────
        val tooltipState = rememberTooltipState()
        val tooltipScope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(GCardBg)
                .border(0.5.dp, GCardBorder, GCardShape),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.group_wizard_bonus_label),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = GTextPrimary,
                        )
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(R.string.group_wizard_bonus_tooltip))
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
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = GTextSecondary,
                                )
                            }
                        }
                    }
                    Switch(
                        checked = bonusEnabled,
                        onCheckedChange = onBonusToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GGreenPrimary,
                        ),
                    )
                }

                if (bonusEnabled) {
                    HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
                    Text(
                        text = stringResource(R.string.group_wizard_bonus_desc),
                        fontSize = 13.sp,
                        color = GTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// ── Step 6: Review & create ───────────────────────────────────────────────────

@Composable
private fun GStep6Review(
    formState: GroupCreateFormState,
    isLoading: Boolean,
    onCreateChallenge: () -> Unit,
) {
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val estimatedPot = formState.buyInEuros * 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.group_wizard_review_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GTextPrimary,
        )

        // ── Summary card ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(GCardBg)
                .border(0.5.dp, GCardBorder, GCardShape),
        ) {
            Column {
                GSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_mode_label),
                    value = stringResource(R.string.wizard_review_mode_group),
                    isFirst = true,
                )
                val appSummary = if (formState.packageNames.size == 1) formState.displayName
                    else "${formState.displayName} +${formState.packageNames.size - 1}"
                GSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_apps_label),
                    value = appSummary,
                )
                val checkedDomains = formState.domainToggles.entries
                    .filter { it.value }
                    .flatMap { APP_DOMAIN_MAP[it.key] ?: emptyList() }
                val allBlockedDomains = (checkedDomains + formState.manualDomains).distinct()
                if (allBlockedDomains.isNotEmpty()) {
                    GSummaryDividerRow(
                        label = "+ Websites",
                        value = allBlockedDomains.take(3).joinToString(", ") +
                                if (allBlockedDomains.size > 3) " +${allBlockedDomains.size - 3}" else "",
                    )
                }
                val limitSummary = when (formState.limitType) {
                    LimitType.TIME -> "${formState.limitValueMinutes} Min / Tag"
                    LimitType.SESSIONS -> "${formState.limitValueSessions} Öffnungen / Tag"
                    LimitType.TIME_BUDGET -> "${formState.dailyBudgetMinutes} Min Budget"
                    else -> "—"
                }
                GSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_limit_label),
                    value = limitSummary,
                )
                GSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_duration_label),
                    value = "${formState.durationDays} Tage",
                )
                GSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_einsatz_label),
                    value = stringResource(R.string.group_wizard_review_einsatz_value, formState.buyInEuros),
                )
                GSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_start_label),
                    value = if (formState.startDateEnabled && formState.startDateMs > 0L)
                        sdf.format(Date(formState.startDateMs))
                    else stringResource(R.string.group_wizard_review_start_manual),
                )
                GSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_bonus_label),
                    value = if (formState.bonusEnabled)
                        stringResource(R.string.group_wizard_review_bonus_on)
                    else stringResource(R.string.group_wizard_review_bonus_off),
                )
                GSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_max_players_label),
                    value = "20",
                )
                GSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_pot_label),
                    value = stringResource(R.string.group_wizard_review_pot_value, estimatedPot),
                    valueColor = GGreenPrimary,
                )
            }
        }

        // ── Create button ───────────────────────────────────────────────────
        val context = LocalContext.current
        Button(
            onClick = {
                HapticManager.light(context)
                onCreateChallenge()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isLoading,
            shape = GBtnShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = GGreenPrimary,
                contentColor = Color.White,
                disabledContainerColor = GDisabledBg,
                disabledContentColor = GDisabledText,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(R.string.group_wizard_create_btn),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Shared summary row with divider ──────────────────────────────────────────

@Composable
private fun GSummaryDividerRow(
    label: String,
    value: String,
    valueColor: Color = GTextPrimary,
    isFirst: Boolean = false,
) {
    if (!isFirst) {
        HorizontalDivider(color = GDividerColor, thickness = 0.5.dp)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = GTextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}
