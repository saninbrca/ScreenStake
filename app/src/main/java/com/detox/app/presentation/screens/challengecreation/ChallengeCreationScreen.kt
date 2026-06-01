package com.detox.app.presentation.screens.challengecreation

import com.detox.app.BuildConfig
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.components.AppWebsiteSelectionStep
import com.detox.app.presentation.components.DetoxHorizontalPicker
import com.detox.app.presentation.components.TimeSpinnerPicker
import com.detox.app.presentation.util.pressScaleFeedback
import com.detox.app.util.HapticManager
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

// ── Design tokens ─────────────────────────────────────────────────────────────

private val WizBg         = Color(0xFFF2F2F7)
private val CardBg        = Color(0xFFFFFFFF)
private val CardBorder    = Color(0x0F000000)   // rgba(0,0,0,0.06)
private val GreenPrimary  = Color(0xFF00C853)
private val GreenLight    = Color(0xFFE8F8EF)
private val GreenSelected = Color(0xFFF0FDF4)
private val TextPrimary   = Color(0xFF000000)
private val TextSecondary = Color(0xFF8E8E93)
private val TextHint      = Color(0xFFC7C7CC)
private val OrangeLight   = Color(0xFFFFF0E8)
private val PurpleLight   = Color(0xFFEEF0FF)
private val BlueLight     = Color(0xFFE8F0FF)
private val GreenBadgeText   = Color(0xFF1E7A3C)
private val OrangeBadgeText  = Color(0xFFC05A00)
private val OrangeIcon       = Color(0xFFFF6B35)
private val PurpleIcon       = Color(0xFF7B61FF)
private val BlueIcon         = Color(0xFF2979FF)
private val DisabledBg    = Color(0xFFE0E0E5)
private val DisabledText  = Color(0xFF8E8E93)
private val DividerColor  = Color(0xFFF2F2F7)

private val CardShape   = RoundedCornerShape(16.dp)
private val BtnShape    = RoundedCornerShape(14.dp)
private val PillShape   = RoundedCornerShape(999.dp)

// ── Screen entry point ────────────────────────────────────────────────────────

@Composable
fun ChallengeCreationScreen(
    onFinished: () -> Unit,
    onDiscarded: () -> Unit,
    viewModel: ChallengeCreationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appListState by viewModel.appListState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appConfig by viewModel.appConfig.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadApps()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeCreationUiState.Success) onFinished()
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentConfirmed()
            is PaymentSheetResult.Canceled  -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed    -> viewModel.onPaymentCancelled()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeCreationUiState.AwaitingPayment) {
            val s = uiState as ChallengeCreationUiState.AwaitingPayment
            paymentSheet.presentWithPaymentIntent(
                paymentIntentClientSecret = s.clientSecret,
                configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App"),
            )
        }
    }

    var showDiscardDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (state.currentStep == 1) showDiscardDialog = true
        else viewModel.goBack()
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.cancel_challenge_title)) },
            text = { Text(stringResource(R.string.cancel_challenge_body)) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onDiscarded() }) {
                    Text(stringResource(R.string.discard), color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }

    if (uiState is ChallengeCreationUiState.RootedDeviceWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRootWarning,
            title = { Text(stringResource(R.string.root_warning_title)) },
            text = { Text(stringResource(R.string.root_warning_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeRootWarningAndProceed) {
                    Text(stringResource(R.string.root_warning_proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRootWarning) {
                    Text(stringResource(R.string.root_warning_cancel), color = Color(0xFFFF3B30))
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WizBg,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            WizardHeader(
                currentStep = state.currentStep,
                totalSteps = TOTAL_STEPS,
                onBack = {
                    if (state.currentStep == 1) showDiscardDialog = true
                    else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * direction } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * direction } + fadeOut())
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "wizard_step",
            ) { step ->
                when (step) {
                    1 -> Step1ModeSelection(
                        selectedMode = state.selectedMode,
                        onSelectMode = viewModel::selectMode,
                        hardModeEnabled = appConfig.hardModeEnabled,
                    )
                    2 -> AppWebsiteSelectionStep(
                        appListState = appListState,
                        selectedApps = state.selectedApps,
                        searchQuery = state.searchQuery,
                        activeTab = state.activeTab,
                        domainToggles = state.domainToggles,
                        manualDomains = state.manualDomains,
                        manualDomainInput = state.manualDomainInput,
                        manualDomainError = state.manualDomainError,
                        blockAdultContent = state.blockAdultContent,
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
                    3 -> Step3LimitType(
                        selected = state.limitType,
                        onSelect = viewModel::selectLimitType,
                    )
                    4 -> Step4LimitValues(
                        state = state,
                        onUpdateLimitMinutes = viewModel::updateLimitMinutes,
                        onUpdateLimitSessions = viewModel::updateLimitSessions,
                        onUpdateSessionDuration = viewModel::updateSessionDurationMinutes,
                        onUpdateDailyBudget = viewModel::updateDailyBudgetMinutes,
                    )
                    5 -> Step5Schedule(
                        scheduleStart = state.scheduleStart,
                        scheduleEnd = state.scheduleEnd,
                        activeDays = state.activeDays,
                        isRequired = state.limitType == LimitType.TIME_WINDOW,
                        onStartChange = viewModel::updateScheduleStart,
                        onEndChange = viewModel::updateScheduleEnd,
                        onToggleDay = viewModel::toggleActiveDay,
                        onClearSchedule = viewModel::clearSchedule,
                        onSkip = viewModel::goNext,
                    )
                    6 -> Step6Duration(
                        state = state,
                        onUpdateDuration = viewModel::updateDurationDays,
                        onToggleNoEndDate = viewModel::updateNoEndDate,
                        onUpdateAmount = viewModel::updateAmountEuros,
                        stakeMin = appConfig.hardModeMinStake,
                        stakeMax = appConfig.hardModeMaxStake,
                    )
                    7 -> Step7Confirm(
                        state = state,
                        appListState = appListState,
                        uiState = uiState,
                        onUpdateMotivation = viewModel::updateMotivationText,
                        onCreateChallenge = viewModel::createChallenge,
                    )
                }
            }

            if (state.currentStep < TOTAL_STEPS) {
                val context = LocalContext.current
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            HapticManager.light(context)
                            viewModel.goNext()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .pressScaleFeedback(),
                        enabled = viewModel.canGoNext(),
                        shape = BtnShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenPrimary,
                            contentColor = Color.White,
                            disabledContainerColor = DisabledBg,
                            disabledContentColor = DisabledText,
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
private fun WizardHeader(
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
                    tint = TextPrimary,
                )
            }
            Text(
                text = "Schritt $currentStep von $totalSteps",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = TextSecondary,
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
            color = GreenPrimary,
            trackColor = DisabledBg,
        )
    }
}

// ── Step 1: Mode selection ────────────────────────────────────────────────────

@Composable
private fun Step1ModeSelection(
    selectedMode: ChallengeMode?,
    onSelectMode: (ChallengeMode) -> Unit,
    hardModeEnabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_mode_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = stringResource(R.string.wizard_mode_subtitle),
            fontSize = 14.sp,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        ModeCard(
            icon = Icons.Default.Star,
            iconTint = GreenPrimary,
            iconBg = GreenLight,
            title = "Soft Mode",
            description = stringResource(R.string.wizard_mode_soft_desc),
            badge = stringResource(R.string.wizard_mode_soft_badge),
            badgeBg = GreenLight,
            badgeText = GreenBadgeText,
            isSelected = selectedMode == ChallengeMode.SOFT,
            onClick = { onSelectMode(ChallengeMode.SOFT) },
        )

        ModeCard(
            icon = null,
            iconTint = OrangeIcon,
            iconBg = OrangeLight,
            euroIcon = true,
            title = "Hard Mode",
            description = stringResource(R.string.wizard_mode_hard_desc),
            badge = stringResource(R.string.wizard_mode_hard_badge),
            badgeBg = OrangeLight,
            badgeText = OrangeBadgeText,
            isSelected = selectedMode == ChallengeMode.HARD,
            onClick = { onSelectMode(ChallengeMode.HARD) },
            enabled = hardModeEnabled,
            disabledNote = stringResource(R.string.feature_temporarily_unavailable),
        )
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector?,
    iconTint: Color,
    iconBg: Color,
    euroIcon: Boolean = false,
    title: String,
    description: String,
    badge: String,
    badgeBg: Color,
    badgeText: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    disabledNote: String? = null,
) {
    val borderColor = if (isSelected) GreenPrimary else CardBorder
    val borderWidth = if (isSelected) 2.dp else 0.5.dp
    val bgColor = if (isSelected) GreenSelected else CardBg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(bgColor)
            .border(borderWidth, borderColor, CardShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Icon circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                if (euroIcon) {
                    Text(
                        text = "€",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTint,
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeText,
                        )
                    }
                }
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp,
                )
                if (!enabled && disabledNote != null) {
                    Text(
                        text = disabledNote,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = OrangeBadgeText,
                    )
                }
            }

            // Right indicator — hidden when the card is disabled
            if (enabled) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(CardBg)
                            .border(1.5.dp, Color(0xFFD1D1D6), CircleShape),
                    )
                }
            }
        }
    }
}

// ── Step 3: Limit type ────────────────────────────────────────────────────────

@Composable
private fun Step3LimitType(
    selected: LimitType?,
    onSelect: (LimitType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_limit_type_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = stringResource(R.string.wizard_limit_type_subtitle),
            fontSize = 14.sp,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        LimitTypeCard(
            icon = Icons.Default.AccessTime,
            iconTint = PurpleIcon,
            iconBg = PurpleLight,
            title = stringResource(R.string.wizard_limit_time_title),
            description = stringResource(R.string.wizard_limit_time_desc),
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        LimitTypeCard(
            icon = Icons.Default.Refresh,
            iconTint = GreenPrimary,
            iconBg = GreenLight,
            title = stringResource(R.string.wizard_limit_sessions_title),
            description = stringResource(R.string.wizard_limit_sessions_desc),
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        LimitTypeCard(
            icon = Icons.Default.AccessTime,
            iconTint = OrangeIcon,
            iconBg = OrangeLight,
            title = stringResource(R.string.wizard_limit_budget_title),
            description = stringResource(R.string.wizard_limit_budget_desc),
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
        LimitTypeCard(
            icon = Icons.Default.AccessTime,
            iconTint = BlueIcon,
            iconBg = BlueLight,
            title = stringResource(R.string.wizard_limit_window_title),
            description = stringResource(R.string.wizard_limit_window_desc),
            isSelected = selected == LimitType.TIME_WINDOW,
            onClick = { onSelect(LimitType.TIME_WINDOW) },
        )
    }
}

@Composable
private fun LimitTypeCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) GreenPrimary else CardBorder
    val borderWidth = if (isSelected) 2.dp else 0.5.dp
    val bgColor = if (isSelected) GreenSelected else CardBg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(bgColor)
            .border(borderWidth, borderColor, CardShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    maxLines = 2,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(CardBg)
                        .border(1.5.dp, Color(0xFFD1D1D6), CircleShape),
                )
            }
        }
    }
}

// ── Step 4: Limit values ──────────────────────────────────────────────────────

@Composable
private fun Step4LimitValues(
    state: ChallengeCreationState,
    onUpdateLimitMinutes: (Int) -> Unit,
    onUpdateLimitSessions: (Int) -> Unit,
    onUpdateSessionDuration: (Int) -> Unit,
    onUpdateDailyBudget: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_set_limit_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )

        when (state.limitType) {
            LimitType.TIME -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (5..120).toList(),
                    selectedValue = state.limitValueMinutes.coerceIn(5, 120),
                    onValueChange = onUpdateLimitMinutes,
                    unit = stringResource(R.string.wizard_set_limit_minutes_unit),
                )
            }

            LimitType.SESSIONS -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (1..20).toList(),
                    selectedValue = state.limitValueSessions.coerceAtMost(20),
                    onValueChange = onUpdateLimitSessions,
                    unit = stringResource(R.string.wizard_set_limit_opens_unit),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.wizard_set_limit_session_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (1..30).toList(),
                    selectedValue = state.sessionDurationMinutes.coerceAtMost(30),
                    onValueChange = onUpdateSessionDuration,
                    unit = stringResource(R.string.wizard_set_limit_session_unit),
                )
            }

            LimitType.TIME_BUDGET -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (5..120).toList(),
                    selectedValue = state.dailyBudgetMinutes.coerceIn(5, 120),
                    onValueChange = onUpdateDailyBudget,
                    unit = stringResource(R.string.wizard_set_limit_budget_unit),
                )
            }

            LimitType.TIME_WINDOW -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Das Zeitfenster legst du im nächsten Schritt fest.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                )
                Text(
                    text = "Die App ist nur innerhalb deines Zeitfensters zugänglich.",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GreenPrimary,
                )
            }

            null -> {
                Text(
                    text = "Bitte gehe zurück und wähle einen Limit-Typ.",
                    fontSize = 14.sp,
                    color = Color(0xFFFF3B30),
                )
            }
        }
    }
}

// ── Step 5: Schedule ──────────────────────────────────────────────────────────

private val ALL_DAYS   = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val DAY_LABELS = mapOf(
    "MON" to "Mo", "TUE" to "Di", "WED" to "Mi",
    "THU" to "Do", "FRI" to "Fr", "SAT" to "Sa", "SUN" to "So",
)

private fun parseTime(time: String): Pair<Int, Int> =
    if (time.length != 5) 0 to 0
    else runCatching { time.split(":").let { it[0].toInt() to it[1].toInt() } }.getOrDefault(0 to 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step5Schedule(
    scheduleStart: String,
    scheduleEnd: String,
    activeDays: Set<String>,
    isRequired: Boolean,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onToggleDay: (String) -> Unit,
    onClearSchedule: () -> Unit,
    onSkip: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val (startH, startM) = parseTime(scheduleStart)
    val (endH, endM) = parseTime(scheduleEnd)

    val timeError: String? = if (scheduleStart.length == 5 && scheduleEnd.length == 5) {
        if (startH * 60 + startM >= endH * 60 + endM) "Endzeit muss nach Startzeit liegen" else null
    } else null

    val timeDisplay = when {
        scheduleStart.length == 5 && scheduleEnd.length == 5 -> "$scheduleStart – $scheduleEnd"
        scheduleStart.length == 5 -> "$scheduleStart – ??:??"
        scheduleEnd.length == 5   -> "??:?? – $scheduleEnd"
        else -> stringResource(R.string.wizard_schedule_placeholder)
    }

    val hasSchedule = scheduleStart.isNotBlank() || scheduleEnd.isNotBlank() || activeDays.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isRequired) {
            Text(
                text = stringResource(R.string.wizard_optional_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                letterSpacing = 0.8.sp,
            )
        }
        Text(
            text = stringResource(R.string.wizard_schedule_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = stringResource(R.string.wizard_schedule_subtitle),
            fontSize = 13.sp,
            color = TextSecondary,
            lineHeight = 18.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Time input row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PillShape)
                .background(CardBg)
                .border(0.5.dp, CardBorder, PillShape)
                .clickable { showTimePicker = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = timeDisplay,
                    fontSize = 15.sp,
                    color = if (scheduleStart.length == 5 || scheduleEnd.length == 5)
                        TextPrimary else TextHint,
                )
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (timeError != null) {
            Text(text = timeError, fontSize = 12.sp, color = Color(0xFFFF3B30))
        }

        // Weekday pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ALL_DAYS.forEach { day ->
                val isSelected = activeDays.contains(day)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .widthIn(min = 36.dp)
                        .clip(PillShape)
                        .background(if (isSelected) GreenPrimary else WizBg)
                        .clickable { onToggleDay(day) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = DAY_LABELS[day] ?: day,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.wizard_schedule_days_hint),
            fontSize = 12.sp,
            color = TextSecondary,
        )

        if (hasSchedule) {
            TextButton(onClick = onClearSchedule) {
                Text(stringResource(R.string.delete_schedule), color = Color(0xFFFF3B30), fontSize = 14.sp)
            }
        }

        if (!isRequired) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.wizard_btn_skip_step),
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onSkip() },
                )
            }
        }
    }

    if (showTimePicker) {
        ModalBottomSheet(
            onDismissRequest = { showTimePicker = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Zeitfenster festlegen",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    TimeSpinnerPicker(
                        hour = startH,
                        minute = startM,
                        label = "Von",
                        onTimeChange = { h, m -> onStartChange("%02d:%02d".format(h, m)) },
                    )
                    TimeSpinnerPicker(
                        hour = endH,
                        minute = endM,
                        label = "Bis",
                        onTimeChange = { h, m -> onEndChange("%02d:%02d".format(h, m)) },
                    )
                }
                if (timeError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = timeError,
                        fontSize = 12.sp,
                        color = Color(0xFFFF3B30),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { showTimePicker = false },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = BtnShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenPrimary,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.done), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ── Step 6: Duration ──────────────────────────────────────────────────────────

@Composable
private fun Step6Duration(
    state: ChallengeCreationState,
    onUpdateDuration: (Int) -> Unit,
    onToggleNoEndDate: (Boolean) -> Unit,
    onUpdateAmount: (Int) -> Unit,
    stakeMin: Int = 5,
    stakeMax: Int = 100,
) {
    val isHardMode = state.selectedMode == ChallengeMode.HARD
    // Remote-controlled stake range (config/app) with hardcoded €5–€100 fallback.
    val safeStakeMin = stakeMin.coerceAtLeast(1)
    val safeStakeMax = stakeMax.coerceAtLeast(safeStakeMin)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_duration_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )

        if (isHardMode) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Einsatz",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DetoxHorizontalPicker(
                values = (safeStakeMin..safeStakeMax).toList(),
                selectedValue = state.amountEuros.coerceIn(safeStakeMin, safeStakeMax),
                onValueChange = onUpdateAmount,
                unit = "Euro Einsatz",
            )
            Text(
                text = "Wenn du das Limit überschreitest, werden €${state.amountEuros} sofort eingezogen.",
                fontSize = 13.sp,
                color = Color(0xFFFF3B30),
            )
            HorizontalDivider(color = DividerColor)
        }

        if (!isHardMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, CardShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Kein Enddatum",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Switch(
                        checked = state.noEndDate,
                        onCheckedChange = onToggleNoEndDate,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GreenPrimary,
                            checkedBorderColor = GreenPrimary,
                        ),
                    )
                }
            }
        }

        if (!state.noEndDate) {
            val minDays = when {
                isHardMode && BuildConfig.DEBUG -> 1
                isHardMode -> 7
                else -> 3
            }
            DetoxHorizontalPicker(
                values = (minDays..90).toList(),
                selectedValue = state.durationDays.coerceIn(minDays, 90),
                onValueChange = onUpdateDuration,
                unit = "Tage",
            )
        }
    }
}

// ── Step 7: Confirm ───────────────────────────────────────────────────────────

@Composable
private fun Step7Confirm(
    state: ChallengeCreationState,
    appListState: AppListState,
    uiState: ChallengeCreationUiState,
    onUpdateMotivation: (String) -> Unit,
    onCreateChallenge: () -> Unit,
) {
    val isLoading = uiState is ChallengeCreationUiState.Loading ||
            uiState is ChallengeCreationUiState.AwaitingPayment

    // Hard Mode requires a Stripe payment, so the fee breakdown + the legally
    // mandated withdrawal-rights waiver (FAGG § 18) are only shown for HARD.
    val isHardMode = state.selectedMode == ChallengeMode.HARD
    var waiverChecked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_review_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )

        // Summary card with dividers
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardBg)
                .border(0.5.dp, CardBorder, CardShape),
        ) {
            Column {
                val modeLabel = when (state.selectedMode) {
                    ChallengeMode.HARD -> stringResource(R.string.wizard_review_mode_hard)
                    else -> stringResource(R.string.wizard_review_mode_soft)
                }
                val appNames = appListState.trackableApps
                    .filter { it.packageName in state.selectedApps }
                    .map { it.appName }
                val appsLabel = when {
                    appNames.size == 1 -> appNames[0]
                    appNames.size == 2 -> "${appNames[0]}, ${appNames[1]}"
                    appNames.size >= 3 -> stringResource(
                        R.string.wizard_review_apps_overflow_format,
                        appNames[0], appNames[1], appNames.size - 2,
                    )
                    state.selectedApps.isNotEmpty() ->
                        stringResource(R.string.wizard_review_apps_count, state.selectedApps.size)
                    else -> stringResource(R.string.wizard_review_apps_count, 0)
                }
                val limitLabel = when (state.limitType) {
                    LimitType.TIME        -> stringResource(R.string.wizard_review_limit_time_format, state.limitValueMinutes)
                    LimitType.SESSIONS    -> stringResource(R.string.wizard_review_limit_sessions_format, state.limitValueSessions, state.sessionDurationMinutes)
                    LimitType.TIME_BUDGET -> stringResource(R.string.wizard_review_limit_budget_format, state.dailyBudgetMinutes)
                    LimitType.TIME_WINDOW -> "Nur Zeitfenster"
                    null                  -> "—"
                }
                val durationLabel = if (state.noEndDate) "Kein Enddatum"
                    else "${state.durationDays} Tage"

                SummaryDividerRow(stringResource(R.string.wizard_review_mode_label), modeLabel, isFirst = true)
                SummaryDividerRow(stringResource(R.string.wizard_review_apps_label), appsLabel)
                SummaryDividerRow(stringResource(R.string.wizard_review_limit_label), limitLabel)
                SummaryDividerRow(stringResource(R.string.wizard_review_duration_label), durationLabel, isLast = true)
            }
        }

        // Motivation field
        Text(
            text = stringResource(R.string.wizard_review_motivation_label),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardBg)
                .border(0.5.dp, CardBorder, CardShape),
        ) {
            OutlinedTextField(
                value = state.motivationText,
                onValueChange = { if (it.length <= 200) onUpdateMotivation(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.wizard_review_motivation_hint),
                        color = TextHint,
                    )
                },
                minLines = 2,
                maxLines = 4,
            )
        }

        // ── Fee breakdown + withdrawal-rights waiver (Hard Mode only) ──────────
        if (isHardMode) {
            val stakeCents = state.amountEuros * 100
            val refundCents = (stakeCents * 80) / 100      // Math.floor of 80%
            val feeCents = stakeCents - refundCents          // remainder = 20%

            FeeBreakdownCard(
                stakeLabel = stringResource(R.string.fee_your_stake),
                stakeValue = formatEuroCents(stakeCents),
                refundValue = stringResource(
                    R.string.fee_value_format, formatEuroCents(refundCents), 80,
                ),
                feeValue = stringResource(
                    R.string.fee_value_format, formatEuroCents(feeCents), 20,
                ),
            )

            WaiverCheckboxRow(
                checked = waiverChecked,
                onToggle = { waiverChecked = !waiverChecked },
            )
        }

        if (uiState is ChallengeCreationUiState.Error) {
            Text(
                text = uiState.message,
                fontSize = 14.sp,
                color = Color(0xFFFF3B30),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val context = LocalContext.current
        Button(
            onClick = {
                HapticManager.light(context)
                onCreateChallenge()
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            // Legal gate: the waiver checkbox must be ticked before a Hard Mode
            // payment can start. Soft Mode has no payment, so no waiver needed.
            enabled = !isLoading && (!isHardMode || waiverChecked),
            shape = BtnShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = GreenPrimary,
                contentColor = Color.White,
                disabledContainerColor = DisabledBg,
                disabledContentColor = DisabledText,
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
                    text = stringResource(R.string.wizard_review_start_btn),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SummaryDividerRow(
    label: String,
    value: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    if (!isFirst) {
        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = TextSecondary,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
    }
}

// ── Fee breakdown card + withdrawal-rights waiver (FAGG § 18) ──────────────────

/** Formats integer cents as a German money string, e.g. 800 → "€8,00". */
private fun formatEuroCents(cents: Int): String =
    "€%d,%02d".format(cents / 100, cents % 100)

private val FeeRowLabel  = Color(0xFF333333)
private val FeeReturnGreen = Color(0xFF00C853)

@Composable
private fun FeeBreakdownCard(
    stakeLabel: String,
    stakeValue: String,
    refundValue: String,
    feeValue: String,
    note: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg)
            .border(0.5.dp, CardBorder, CardShape),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.fee_overview_title).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeeRow(stakeLabel, stakeValue, TextPrimary)
            HorizontalDivider(
                color = DividerColor,
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            FeeRow(stringResource(R.string.fee_return_on_success), refundValue, FeeReturnGreen)
            HorizontalDivider(
                color = DividerColor,
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            FeeRow(stringResource(R.string.fee_service_fee), feeValue, TextSecondary)
            if (note != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = note,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun FeeRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = FeeRowLabel)
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}

@Composable
private fun WaiverCheckboxRow(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) GreenPrimary else Color.White)
                .border(
                    width = 1.5.dp,
                    color = if (checked) GreenPrimary else Color(0xFFE0E0E5),
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.withdrawal_waiver_text),
            fontSize = 14.sp,
            color = FeeRowLabel,
        )
    }
}
