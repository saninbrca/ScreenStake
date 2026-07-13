package com.detox.app.presentation.screens.challengecreation

import com.detox.app.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TouchApp
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.detox.app.util.FeatureFlags
import com.detox.app.util.HapticManager
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

// ── Design tokens ─────────────────────────────────────────────────────────────

private val WizBg         = Color(0xFFF2F2F7)
private val CardBg        = Color(0xFFFFFFFF)
private val CardBorder    = Color(0x0F000000)   // rgba(0,0,0,0.06)
private val GreenPrimary  = Color(0xFF00C853)
private val GreenLight    = Color(0xFFE8F8EF)   // icon-circle + soft badge background
// Single source of truth for "selected" card/row SURFACES across the wizard (mode cards,
// limit cards, app-selection row). Kept distinct from GreenLight so a selected card never
// blends into its own green icon circle; #00C853 stays reserved for the accent line/check.
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

            // TIME_WINDOW skips internal step 4, so it has 6 effective steps. Renumber the
            // displayed counter contiguously (internal 5/6/7 → shown 4/5/6) so no number is
            // visibly skipped; the denominator becomes 6 once TIME_WINDOW is chosen on step 3.
            val isTimeWindow = state.limitType == LimitType.TIME_WINDOW
            val displayedTotal = if (isTimeWindow) TOTAL_STEPS - 1 else TOTAL_STEPS
            val displayedStep = if (isTimeWindow && state.currentStep >= 5)
                state.currentStep - 1 else state.currentStep

            WizardHeader(
                currentStep = displayedStep,
                totalSteps = displayedTotal,
                onBack = {
                    if (state.currentStep == 1) showDiscardDialog = true
                    else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    // ~300ms ease-out, synced with the WizardHeader progress-bar animation so the
                    // bar fill and the step content move together.
                    (slideInHorizontally(animationSpec = tween(300, easing = LinearOutSlowInEasing)) { it * direction } +
                            fadeIn(animationSpec = tween(300, easing = LinearOutSlowInEasing))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(300, easing = LinearOutSlowInEasing)) { -it * direction } +
                                    fadeOut(animationSpec = tween(300, easing = LinearOutSlowInEasing)))
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
                        // Build-level money floor AND the remote hardModeEnabled kill-switch.
                        hardModeEnabled = FeatureFlags.hardModeEnabled(appConfig.hardModeEnabled),
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
    // Progress fraction is unchanged (currentStep/totalSteps); only the RENDERED value is animated
    // so the bar fills smoothly between steps instead of jumping (~300ms ease-out).
    val progress = currentStep.toFloat() / totalSteps.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "wizard_progress",
    )

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
            progress = { animatedProgress },
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

        // Money-floor gated: in the soft-only release the Hard Mode card is HIDDEN entirely (a
        // permanently-dead surface, so no grey-out / no money framing). When money features are on
        // (debug, or a future money build) the card renders and `enabled = hardModeEnabled` still
        // greys it out for a temporary server-side kill-switch — hardModeEnabled already folds in
        // the build floor via FeatureFlags.hardModeEnabled(...).
        if (FeatureFlags.moneyEnabled) {
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
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) GreenPrimary else CardBorder,
        animationSpec = tween(150), label = "mode_border_color",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.5.dp,
        animationSpec = tween(150), label = "mode_border_width",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) GreenSelected else CardBg,
        animationSpec = tween(150), label = "mode_bg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressScaleFeedback() else Modifier)
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

            // Right indicator — hidden when the card is disabled. The check scales + fades in
            // on selection (~150ms); the empty ring marks the unselected state.
            if (enabled) {
                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    val checkScale by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0f,
                        animationSpec = tween(150), label = "mode_check",
                    )
                    if (!isSelected) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(CardBg)
                                .border(1.5.dp, Color(0xFFD1D1D6), CircleShape),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale },
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
            icon = Icons.Outlined.Schedule,
            iconTint = PurpleIcon,
            iconBg = PurpleLight,
            title = stringResource(R.string.wizard_limit_time_title),
            description = stringResource(R.string.wizard_limit_time_desc),
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        LimitTypeCard(
            icon = Icons.Outlined.TouchApp,
            iconTint = GreenPrimary,
            iconBg = GreenLight,
            title = stringResource(R.string.wizard_limit_sessions_title),
            description = stringResource(R.string.wizard_limit_sessions_desc),
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        LimitTypeCard(
            icon = Icons.Outlined.HourglassTop,
            iconTint = OrangeIcon,
            iconBg = OrangeLight,
            title = stringResource(R.string.wizard_limit_budget_title),
            description = stringResource(R.string.wizard_limit_budget_desc),
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
        LimitTypeCard(
            icon = Icons.Outlined.CalendarToday,
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
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) GreenPrimary else CardBorder,
        animationSpec = tween(150), label = "limit_border_color",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.5.dp,
        animationSpec = tween(150), label = "limit_border_width",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) GreenSelected else CardBg,
        animationSpec = tween(150), label = "limit_bg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScaleFeedback()
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
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                val checkScale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(150), label = "limit_check",
                )
                if (!isSelected) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(CardBg)
                            .border(1.5.dp, Color(0xFFD1D1D6), CircleShape),
                    )
                }
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale },
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
                    surfaceColor = WizBg,
                )
            }

            LimitType.SESSIONS -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (1..20).toList(),
                    selectedValue = state.limitValueSessions.coerceAtMost(20),
                    onValueChange = onUpdateLimitSessions,
                    unit = stringResource(R.string.wizard_set_limit_opens_unit),
                    surfaceColor = WizBg,
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
                    surfaceColor = WizBg,
                )
            }

            LimitType.TIME_BUDGET -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (5..120).toList(),
                    selectedValue = state.dailyBudgetMinutes.coerceIn(5, 120),
                    onValueChange = onUpdateDailyBudget,
                    unit = stringResource(R.string.wizard_set_limit_budget_unit),
                    surfaceColor = WizBg,
                )
            }

            // Unreachable in normal flow: goNext/goBack skip internal step 4 for TIME_WINDOW
            // (3 ↔ 5), since the window is configured on the schedule step. Kept only to keep
            // this `when` exhaustive — renders nothing if ever reached.
            LimitType.TIME_WINDOW -> Unit

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

// One column ("Von" / "Bis") of the split schedule time card. Tapping either column opens
// the same time bottom sheet; only the visual is split. Time uses tabular figures.
@Composable
private fun ScheduleTimeColumn(
    label: String,
    time: String,
    isSet: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = time,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSet) TextPrimary else TextHint,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

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

    val startSet = scheduleStart.length == 5
    val endSet = scheduleEnd.length == 5
    val startText = if (startSet) scheduleStart else "--:--"
    val endText = if (endSet) scheduleEnd else "--:--"

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

        // Time input row — split Von / Bis card. Each column opens the SAME bottom sheet.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(CardShape)
                .background(CardBg)
                .border(0.5.dp, CardBorder, CardShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScheduleTimeColumn(
                label = "Von",
                time = startText,
                isSet = startSet,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showTimePicker = true },
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(0.5.dp)
                    .background(CardBorder),
            )
            ScheduleTimeColumn(
                label = "Bis",
                time = endText,
                isSet = endSet,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showTimePicker = true },
            )
        }

        if (timeError != null) {
            Text(text = timeError, fontSize = 12.sp, color = Color(0xFFFF3B30))
        }

        // Weekday circles inside a card, with the "no selection = every day" hint below them.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardBg)
                .border(0.5.dp, CardBorder, CardShape)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ALL_DAYS.forEach { day ->
                    val isSelected = activeDays.contains(day)
                    val dayBg by animateColorAsState(
                        targetValue = if (isSelected) GreenPrimary else WizBg,
                        animationSpec = tween(150), label = "day_bg",
                    )
                    val dayText by animateColorAsState(
                        targetValue = if (isSelected) Color.White else TextSecondary,
                        animationSpec = tween(150), label = "day_text",
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(dayBg)
                            .clickable { onToggleDay(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = DAY_LABELS[day] ?: day,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = dayText,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.wizard_schedule_days_hint),
                fontSize = 12.sp,
                color = TextSecondary,
            )
        }

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
            // M3 default is ~28dp top corners; pin to the wizard's 16dp card radius.
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
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
                surfaceColor = WizBg,
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
                surfaceColor = WizBg,
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
    // Second mandatory Hard Mode consent: deleting/disabling the app during an active
    // challenge forfeits the stake (server-side went-dark detection). Hard-blocks Start.
    var forfeitChecked by remember { mutableStateOf(false) }

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
        MotivationField(
            value = state.motivationText,
            onValueChange = onUpdateMotivation,
        )

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

            WaiverCheckboxRow(
                checked = forfeitChecked,
                onToggle = { forfeitChecked = !forfeitChecked },
                label = stringResource(R.string.uninstall_forfeit_consent_text),
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
            modifier = Modifier.fillMaxWidth().height(54.dp).pressScaleFeedback(),
            // Legal gate: BOTH the FAGG waiver and the uninstall-forfeit consent must be
            // ticked before a Hard Mode payment can start. Soft Mode has no payment/stake,
            // so neither consent is required.
            enabled = !isLoading && (!isHardMode || (waiverChecked && forfeitChecked)),
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

/**
 * Motivation input on the confirmation step. A single flat-card field (16dp corners, hairline
 * border that turns green on focus) matching the wizard's "Calm Authority" cards — replaces the
 * old OutlinedTextField-in-a-Box (double border / 4dp-vs-16dp mismatch / M3 blue focus). Stays a
 * live input bound to motivationText: same 200-char cap, 2–4 line multiline, and placeholder.
 */
@Composable
private fun MotivationField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) GreenPrimary else CardBorder,
        animationSpec = tween(150), label = "motivation_border_color",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 1.5.dp else 0.5.dp,
        animationSpec = tween(150), label = "motivation_border_width",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Label row: green icon-circle + "Deine Motivation" + "optional"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(GreenLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = stringResource(R.string.wizard_review_motivation_label),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = stringResource(R.string.wizard_review_motivation_optional),
                fontSize = 12.sp,
                color = TextSecondary,
            )
        }

        // Single-border card field, green-on-focus
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 200) onValueChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardBg)
                .border(borderWidth, borderColor, CardShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
            cursorBrush = SolidColor(GreenPrimary),
            minLines = 2,
            maxLines = 4,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Column {
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.wizard_review_motivation_hint),
                                fontSize = 14.sp,
                                color = TextHint,
                            )
                        }
                        innerTextField()
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wizard_review_motivation_counter, value.length),
                        fontSize = 11.sp,
                        color = TextHint,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )

        // Helper line below the field — these words are surfaced on the decision overlays.
        Text(
            text = stringResource(R.string.wizard_review_motivation_helper),
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 16.sp,
        )
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
    label: String = stringResource(R.string.withdrawal_waiver_text),
) {
    val boxBg by animateColorAsState(
        targetValue = if (checked) GreenPrimary else Color.White,
        animationSpec = tween(150), label = "waiver_bg",
    )
    val boxBorder by animateColorAsState(
        targetValue = if (checked) GreenPrimary else Color(0xFFE0E0E5),
        animationSpec = tween(150), label = "waiver_border",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(150), label = "waiver_check",
    )
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
                .background(boxBg)
                .border(
                    width = 1.5.dp,
                    color = boxBorder,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale },
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = FeeRowLabel,
        )
    }
}
