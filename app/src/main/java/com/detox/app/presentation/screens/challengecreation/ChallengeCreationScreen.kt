package com.detox.app.presentation.screens.challengecreation

import com.detox.app.BuildConfig
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.MaterialTheme
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
import com.detox.app.presentation.components.AccessibilityDisclosureDialog
import com.detox.app.presentation.components.AppWebsiteSelectionStep
import com.detox.app.presentation.components.DetoxHorizontalPicker
import com.detox.app.presentation.components.WIZARD_TRANSITION_MS
import com.detox.app.presentation.components.WizardFeeBreakdownCard
import com.detox.app.presentation.components.WizardHeader
import com.detox.app.presentation.components.WizardLimitTypeCard
import com.detox.app.presentation.components.WizardMissingPermissionRow
import com.detox.app.presentation.components.WizardSummaryDividerRow
import com.detox.app.presentation.components.WizardTransitionEasing
import com.detox.app.presentation.components.WizardWaiverCheckboxRow
import com.detox.app.presentation.components.formatEuroCents
import com.detox.app.presentation.components.SCHEDULE_WEEKDAYS
import com.detox.app.presentation.components.activeDaysSummary
import com.detox.app.presentation.components.timeWindowSummary
import com.detox.app.presentation.components.weekdayShortLabel
import com.detox.app.presentation.components.TimeSpinnerPicker
import com.detox.app.presentation.util.pressScaleFeedback
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.FeatureFlags
import com.detox.app.util.HapticManager
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.
// Icon circles use the soft* family (one tinted container per hue, soft*Icon glyph).

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
                    Text(stringResource(R.string.discard), color = detoxColors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }

    // ── Adult-block exclusivity dialogs (both directions, no silent clearing) ──
    if (state.showAdultExclusiveDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAdultExclusiveDialog,
            title = { Text(stringResource(R.string.adult_exclusive_dialog_title)) },
            text = { Text(stringResource(R.string.adult_exclusive_dialog_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAdultExclusive) {
                    Text(stringResource(R.string.adult_exclusive_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAdultExclusiveDialog) {
                    Text(stringResource(R.string.adult_exclusive_dialog_dismiss))
                }
            },
        )
    }

    if (state.pendingAdultAppPackage != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAppOverAdultDialog,
            title = { Text(stringResource(R.string.adult_exclusive_dialog_title)) },
            text = { Text(stringResource(R.string.adult_exclusive_app_dialog_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAppOverAdult) {
                    Text(stringResource(R.string.adult_exclusive_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAppOverAdultDialog) {
                    Text(stringResource(R.string.adult_exclusive_dialog_dismiss))
                }
            },
        )
    }

    // ── Pre-flight permission gate ────────────────────────────────────────────
    // Prominent-disclosure gate for the AccessibilityService (Play policy): the settings intent
    // fires ONLY after the affirmative tap — same pattern as OnboardingScreen.
    val context = LocalContext.current
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showAccessibilityDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDismiss = { showAccessibilityDisclosure = false },
        )
    }

    (uiState as? ChallengeCreationUiState.MissingPermissions)?.let { missing ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPermissionDialog,
            title = { Text(stringResource(R.string.challenge_permission_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.challenge_permission_dialog_body))
                    if (missing.needsUsage) {
                        WizardMissingPermissionRow(
                            name = stringResource(R.string.challenge_permission_usage),
                            onGrant = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            },
                        )
                    }
                    if (missing.needsAccessibility) {
                        WizardMissingPermissionRow(
                            name = stringResource(R.string.challenge_permission_accessibility),
                            onGrant = { showAccessibilityDisclosure = true },
                        )
                    }
                    if (missing.needsOverlay) {
                        WizardMissingPermissionRow(
                            name = stringResource(R.string.challenge_permission_overlay),
                            onGrant = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPermissionDialog) {
                    Text(stringResource(R.string.challenge_permission_dialog_cancel))
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
                    Text(stringResource(R.string.root_warning_cancel), color = detoxColors.danger)
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = detoxColors.screenBackground,
        // Resolves LocalContentColor (ripples + default text) — the static Black
        // default is invisible on the dark background.
        contentColor = detoxColors.label,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // The displayed "Schritt X von Y" counter is the position within the path's
            // visible-step list (APP: 7, APP+TIME_WINDOW: 6, Website/Adult block path: 4),
            // so skipped internal steps never surface as a visibly missing number.
            val steps = visibleSteps(state)
            val displayedTotal = steps.size
            val displayedStep = (steps.indexOf(state.currentStep) + 1).coerceAtLeast(1)

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
                    (slideInHorizontally(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing)) { it * direction } +
                            fadeIn(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing)) { -it * direction } +
                                    fadeOut(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing)))
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
                HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
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
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                            disabledContentColor = detoxColors.subtext,
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
            color = detoxColors.label,
        )
        Text(
            text = stringResource(R.string.wizard_mode_subtitle),
            fontSize = 14.sp,
            color = detoxColors.subtext,
        )
        Spacer(modifier = Modifier.height(4.dp))

        ModeCard(
            icon = Icons.Default.Star,
            iconTint = detoxColors.softGreenIcon,
            iconBg = detoxColors.softGreenBg,
            title = "Soft Mode",
            description = stringResource(R.string.wizard_mode_soft_desc),
            badge = stringResource(R.string.wizard_mode_soft_badge),
            badgeBg = detoxColors.softGreenBg,
            badgeText = detoxColors.softGreenText,
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
                iconTint = detoxColors.softOrangeIcon,
                iconBg = detoxColors.softOrangeBg,
                euroIcon = true,
                title = "Hard Mode",
                description = stringResource(R.string.wizard_mode_hard_desc),
                badge = stringResource(R.string.wizard_mode_hard_badge),
                badgeBg = detoxColors.softOrangeBg,
                badgeText = detoxColors.softOrangeText,
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
        targetValue = if (isSelected) detoxColors.accent else detoxColors.cardBorder,
        animationSpec = tween(150), label = "mode_border_color",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.5.dp,
        animationSpec = tween(150), label = "mode_border_width",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) detoxColors.selectedSurface else detoxColors.cardBackground,
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
                        color = detoxColors.label,
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
                    color = detoxColors.subtext,
                    lineHeight = 18.sp,
                )
                if (!enabled && disabledNote != null) {
                    Text(
                        text = disabledNote,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = detoxColors.softOrangeText,
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
                                .background(detoxColors.cardBackground)
                                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = detoxColors.accent,
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
            color = detoxColors.label,
        )
        Text(
            text = stringResource(R.string.wizard_limit_type_subtitle),
            fontSize = 14.sp,
            color = detoxColors.subtext,
        )
        Spacer(modifier = Modifier.height(4.dp))

        WizardLimitTypeCard(
            icon = Icons.Outlined.Schedule,
            iconTint = detoxColors.softPurpleIcon,
            iconBg = detoxColors.softPurpleBg,
            title = stringResource(R.string.wizard_limit_time_title),
            description = stringResource(R.string.wizard_limit_time_desc),
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        WizardLimitTypeCard(
            icon = Icons.Outlined.TouchApp,
            iconTint = detoxColors.softGreenIcon,
            iconBg = detoxColors.softGreenBg,
            title = stringResource(R.string.wizard_limit_sessions_title),
            description = stringResource(R.string.wizard_limit_sessions_desc),
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        WizardLimitTypeCard(
            icon = Icons.Outlined.HourglassTop,
            iconTint = detoxColors.softOrangeIcon,
            iconBg = detoxColors.softOrangeBg,
            title = stringResource(R.string.wizard_limit_budget_title),
            description = stringResource(R.string.wizard_limit_budget_desc),
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
        WizardLimitTypeCard(
            icon = Icons.Outlined.CalendarToday,
            iconTint = detoxColors.softBlueIcon,
            iconBg = detoxColors.softBlueBg,
            title = stringResource(R.string.wizard_limit_window_title),
            description = stringResource(R.string.wizard_limit_window_desc),
            isSelected = selected == LimitType.TIME_WINDOW,
            onClick = { onSelect(LimitType.TIME_WINDOW) },
        )
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
            color = detoxColors.label,
        )

        when (state.limitType) {
            LimitType.TIME -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (5..120).toList(),
                    selectedValue = state.limitValueMinutes.coerceIn(5, 120),
                    onValueChange = onUpdateLimitMinutes,
                    unit = stringResource(R.string.wizard_set_limit_minutes_unit),
                    surfaceColor = detoxColors.screenBackground,
                )
            }

            LimitType.SESSIONS -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (1..20).toList(),
                    selectedValue = state.limitValueSessions.coerceAtMost(20),
                    onValueChange = onUpdateLimitSessions,
                    unit = stringResource(R.string.wizard_set_limit_opens_unit),
                    surfaceColor = detoxColors.screenBackground,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.wizard_set_limit_session_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (1..30).toList(),
                    selectedValue = state.sessionDurationMinutes.coerceAtMost(30),
                    onValueChange = onUpdateSessionDuration,
                    unit = stringResource(R.string.wizard_set_limit_session_unit),
                    surfaceColor = detoxColors.screenBackground,
                )
            }

            LimitType.TIME_BUDGET -> {
                Spacer(modifier = Modifier.height(8.dp))
                DetoxHorizontalPicker(
                    values = (5..120).toList(),
                    selectedValue = state.dailyBudgetMinutes.coerceIn(5, 120),
                    onValueChange = onUpdateDailyBudget,
                    unit = stringResource(R.string.wizard_set_limit_budget_unit),
                    surfaceColor = detoxColors.screenBackground,
                )
            }

            // Unreachable in normal flow: goNext/goBack skip internal step 4 for TIME_WINDOW
            // (3 ↔ 5), since the window is configured on the schedule step. Kept only to keep
            // this `when` exhaustive — renders nothing if ever reached.
            LimitType.TIME_WINDOW -> Unit

            null -> {
                Text(
                text = stringResource(R.string.error_select_limit_type),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Step 5: Schedule ──────────────────────────────────────────────────────────

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
            color = detoxColors.subtext,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = time,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSet) detoxColors.label else detoxColors.hint,
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
                    if (startH * 60 + startM >= endH * 60 + endM) stringResource(R.string.error_time_end_after_start) else null
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
                color = detoxColors.subtext,
                letterSpacing = 0.8.sp,
            )
        }
        Text(
            text = stringResource(R.string.wizard_schedule_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = detoxColors.label,
        )
        Text(
            text = stringResource(R.string.wizard_schedule_subtitle),
            fontSize = 13.sp,
            color = detoxColors.subtext,
            lineHeight = 18.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Time input row — split Von / Bis card. Each column opens the SAME bottom sheet.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(CardShape)
                .background(detoxColors.cardBackground)
                .border(0.5.dp, detoxColors.cardBorder, CardShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScheduleTimeColumn(
                label = stringResource(R.string.challenge_schedule_from),
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
                    .background(detoxColors.cardBorder),
            )
            ScheduleTimeColumn(
                label = stringResource(R.string.challenge_schedule_until),
                time = endText,
                isSet = endSet,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showTimePicker = true },
            )
        }

        if (timeError != null) {
            Text(text = timeError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        // Weekday circles inside a card, with the "no selection = every day" hint below them.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(detoxColors.cardBackground)
                .border(0.5.dp, detoxColors.cardBorder, CardShape)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SCHEDULE_WEEKDAYS.forEach { day ->
                    val isSelected = activeDays.contains(day)
                    val dayBg by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else detoxColors.insetSurface,
                        animationSpec = tween(150), label = "day_bg",
                    )
                    val dayText by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else detoxColors.subtext,
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
                            text = weekdayShortLabel(day),
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
                color = detoxColors.subtext,
            )
        }

        if (hasSchedule) {
            TextButton(onClick = onClearSchedule) {
                Text(stringResource(R.string.delete_schedule), color = detoxColors.danger, fontSize = 14.sp)
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
                    color = detoxColors.subtext,
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
                text = stringResource(R.string.challenge_schedule_set_window),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label,
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
                label = stringResource(R.string.challenge_schedule_from),
                        onTimeChange = { h, m -> onStartChange("%02d:%02d".format(h, m)) },
                    )
                    TimeSpinnerPicker(
                        hour = endH,
                        minute = endM,
                label = stringResource(R.string.challenge_schedule_until),
                        onTimeChange = { h, m -> onEndChange("%02d:%02d".format(h, m)) },
                    )
                }
                if (timeError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = timeError,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { showTimePicker = false },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = BtnShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
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
            color = detoxColors.label,
        )

        if (isHardMode) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.challenge_stake),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = detoxColors.label,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DetoxHorizontalPicker(
                values = (safeStakeMin..safeStakeMax).toList(),
                selectedValue = state.amountEuros.coerceIn(safeStakeMin, safeStakeMax),
                onValueChange = onUpdateAmount,
                unit = stringResource(R.string.challenge_stake_unit),
                surfaceColor = detoxColors.screenBackground,
            )
            Text(
                text = stringResource(R.string.challenge_stake_warning, state.amountEuros),
                fontSize = 13.sp,
                color = detoxColors.danger,
            )
            HorizontalDivider(color = detoxColors.divider)
        }

        if (!isHardMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(detoxColors.cardBackground)
                    .border(0.5.dp, detoxColors.cardBorder, CardShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                text = stringResource(R.string.challenge_no_end_date),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = detoxColors.label,
                    )
                    Switch(
                        checked = state.noEndDate,
                        onCheckedChange = onToggleNoEndDate,
                        colors = SwitchDefaults.colors(
                            // M3 defaults render identically in light (thumb=onPrimary,
                            // track=primary; the old explicit border matched the track).
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
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
                unit = stringResource(R.string.wizard_duration_days_unit),
                surfaceColor = detoxColors.screenBackground,
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
            color = detoxColors.label,
        )

        // Summary card with dividers
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(detoxColors.cardBackground)
                .border(0.5.dp, detoxColors.cardBorder, CardShape),
        ) {
            Column {
                val modeLabel = when (state.selectedMode) {
                    ChallengeMode.HARD -> stringResource(R.string.wizard_review_mode_hard)
                    else -> stringResource(R.string.wizard_review_mode_soft)
                }
                // Block-only path (Website tab): the target row lists the domains (or "Adult-Block"
                // for adult-only) and the limit row reads "Immer blockiert" — these challenges skip
                // the limit/schedule steps and are hard-blocked 24/7.
                val isBlockPath = state.activeTab == 1
                val appNames = appListState.trackableApps
                    .filter { it.packageName in state.selectedApps }
                    .map { it.appName }
                val targetLabel = if (isBlockPath) stringResource(R.string.wizard_review_blocked_label)
                    else stringResource(R.string.wizard_review_apps_label)
                val targetNames = if (isBlockPath) state.manualDomains else appNames
                val appsLabel = when {
                    isBlockPath && targetNames.isEmpty() -> stringResource(R.string.adult_block_display_name)
                    targetNames.size == 1 -> targetNames[0]
                    targetNames.size == 2 -> "${targetNames[0]}, ${targetNames[1]}"
                    targetNames.size >= 3 -> stringResource(
                        R.string.wizard_review_apps_overflow_format,
                        targetNames[0], targetNames[1], targetNames.size - 2,
                    )
                    state.selectedApps.isNotEmpty() ->
                        stringResource(R.string.wizard_review_apps_count, state.selectedApps.size)
                    else -> stringResource(R.string.wizard_review_apps_count, 0)
                }
                val limitLabel = if (isBlockPath) stringResource(R.string.wizard_review_always_blocked)
                    else when (state.limitType) {
                        LimitType.TIME        -> stringResource(R.string.wizard_review_limit_time_format, state.limitValueMinutes)
                        LimitType.SESSIONS    -> stringResource(R.string.wizard_review_limit_sessions_format, state.limitValueSessions, state.sessionDurationMinutes)
                        LimitType.TIME_BUDGET -> stringResource(R.string.wizard_review_limit_budget_format, state.dailyBudgetMinutes)
                        LimitType.TIME_WINDOW -> stringResource(R.string.wizard_limit_window_title)
                        null                  -> "—"
                    }
                val durationLabel = if (state.noEndDate) stringResource(R.string.challenge_no_end_date)
                    else stringResource(R.string.wizard_review_days_format, state.durationDays)

                WizardSummaryDividerRow(stringResource(R.string.wizard_review_mode_label), modeLabel, isFirst = true)
                WizardSummaryDividerRow(targetLabel, appsLabel)
                if (isBlockPath && state.blockAdultContent && state.manualDomains.isNotEmpty()) {
                    WizardSummaryDividerRow(
                        stringResource(R.string.adult_block_display_name),
                        stringResource(R.string.wizard_review_adult_active),
                    )
                }
                WizardSummaryDividerRow(stringResource(R.string.wizard_review_limit_label), limitLabel)
                // Time window + weekdays — same wording as the detail screen (ScheduleSummary
                // helpers). Block path is 24/7 by definition, so the rows are suppressed there.
                if (!isBlockPath) {
                    WizardSummaryDividerRow(
                        stringResource(R.string.detail_info_time_window),
                        timeWindowSummary(
                            state.scheduleStart.takeIf { it.length == 5 },
                            state.scheduleEnd.takeIf { it.length == 5 },
                        ),
                    )
                    WizardSummaryDividerRow(
                        stringResource(R.string.detail_info_active_days),
                        activeDaysSummary(state.activeDays),
                    )
                }
                WizardSummaryDividerRow(stringResource(R.string.wizard_review_duration_label), durationLabel)
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

            WizardFeeBreakdownCard(
                stakeLabel = stringResource(R.string.fee_your_stake),
                stakeValue = formatEuroCents(stakeCents),
                refundValue = stringResource(
                    R.string.fee_value_format, formatEuroCents(refundCents), 80,
                ),
                feeValue = stringResource(
                    R.string.fee_value_format, formatEuroCents(feeCents), 20,
                ),
            )

            WizardWaiverCheckboxRow(
                checked = waiverChecked,
                onToggle = { waiverChecked = !waiverChecked },
            )

            WizardWaiverCheckboxRow(
                checked = forfeitChecked,
                onToggle = { forfeitChecked = !forfeitChecked },
                label = stringResource(R.string.uninstall_forfeit_consent_text),
            )
        }

        if (uiState is ChallengeCreationUiState.Error) {
            Text(
                text = uiState.message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
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
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                disabledContentColor = detoxColors.subtext,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
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
        targetValue = if (isFocused) detoxColors.accent else detoxColors.cardBorder,
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
                    .background(detoxColors.softGreenBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = detoxColors.softGreenIcon,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = stringResource(R.string.wizard_review_motivation_label),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = detoxColors.label,
            )
            Text(
                text = stringResource(R.string.wizard_review_motivation_optional),
                fontSize = 12.sp,
                color = detoxColors.subtext,
            )
        }

        // Single-border card field, green-on-focus
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 200) onValueChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(detoxColors.cardBackground)
                .border(borderWidth, borderColor, CardShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = TextStyle(fontSize = 14.sp, color = detoxColors.label),
            cursorBrush = SolidColor(detoxColors.accent),
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
                                color = detoxColors.hint,
                            )
                        }
                        innerTextField()
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wizard_review_motivation_counter, value.length),
                        fontSize = 11.sp,
                        color = detoxColors.hint,
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
            color = detoxColors.subtext,
            lineHeight = 16.sp,
        )
    }
}

