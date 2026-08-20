package com.finite.focus.presentation.screens.groupchallenge.create

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.outlined.HourglassTop
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.finite.focus.R
import com.finite.focus.domain.model.GroupParticipantLimits
import com.finite.focus.domain.model.GroupStartWindow
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.model.maxPossibleWinCents
import com.finite.focus.presentation.components.AccessibilityDisclosureDialog
import com.finite.focus.presentation.components.AppWebsiteSelectionStep
import com.finite.focus.presentation.components.DetoxHorizontalPicker
import com.finite.focus.presentation.components.WIZARD_TRANSITION_MS
import com.finite.focus.presentation.components.WizardDatePickerDialog
import com.finite.focus.presentation.components.WizardFeeBreakdownCard
import com.finite.focus.presentation.components.WizardHeader
import com.finite.focus.presentation.components.WizardLimitTypeCard
import com.finite.focus.presentation.components.WizardMissingPermissionRow
import com.finite.focus.presentation.components.WizardSummaryDividerRow
import com.finite.focus.presentation.components.WizardTransitionEasing
import com.finite.focus.presentation.components.WizardWaiverCheckboxRow
import com.finite.focus.presentation.components.formatEuroCents
import com.finite.focus.presentation.util.pressScaleFeedback
import com.finite.focus.ui.theme.detoxColors
import com.finite.focus.util.HapticManager
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Design tokens ─────────────────────────────────────────────────────────────
// Colors are theme-resolved at each call site (detoxColors / MaterialTheme.colorScheme);
// only the shared shapes remain file-local constants.

private val GCardShape      = RoundedCornerShape(16.dp)
private val GBtnShape       = RoundedCornerShape(14.dp)

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
    val appConfig by viewModel.appConfig.collectAsStateWithLifecycle()
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

    // ── Adult-block exclusivity dialogs (both directions, no silent clearing) — mirrors Solo/Hard ──
    if (formState.showAdultExclusiveDialog) {
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

    if (formState.pendingAdultAppPackage != null) {
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

    // ── Pre-flight permission gate (mirrors Solo/Hard) ────────────────────────
    val permissionContext = LocalContext.current
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showAccessibilityDisclosure = false
                permissionContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDismiss = { showAccessibilityDisclosure = false },
        )
    }

    (uiState as? GroupCreateUiState.MissingPermissions)?.let { missing ->
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
                                permissionContext.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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
                                permissionContext.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${permissionContext.packageName}"),
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

    val isLoading = uiState is GroupCreateUiState.Loading || uiState is GroupCreateUiState.AwaitingPayment

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = detoxColors.screenBackground,
        // Resolves LocalContentColor (ripples + default text) — the static Black
        // default is invisible on the dark background.
        contentColor = detoxColors.label,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(snackbarHostState)

            // Block-only (Websites tab) skips step 2, so the displayed step NUMBER is the position
            // within the path's visible-step list — never a visibly missing number. The size feeds
            // the progress bar only; it is no longer displayed as a total (see WizardHeader).
            val steps = visibleGroupSteps(formState)
            val displayedTotal = steps.size
            val displayedStep = (steps.indexOf(formState.currentStep) + 1).coerceAtLeast(1)

            WizardHeader(
                currentStep = displayedStep,
                totalSteps = displayedTotal,
                onBack = {
                    if (formState.currentStep == 1) onBack() else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = formState.currentStep,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    // ~300ms ease-out, synced with the WizardHeader progress-bar animation so the
                    // bar fill and the step content move together.
                    (slideInHorizontally(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing)) { it * dir } +
                            fadeIn(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing)) { -it * dir } +
                                    fadeOut(animationSpec = tween(WIZARD_TRANSITION_MS, easing = WizardTransitionEasing)))
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
                        isBlockOnly = formState.activeTab == 1,
                        onUpdateLimitMinutes = viewModel::setLimitValueMinutes,
                        onUpdateLimitSessions = viewModel::setLimitValueSessions,
                        onUpdateSessionDuration = viewModel::setSessionMinutes,
                        onUpdateDailyBudget = viewModel::setDailyBudgetMinutes,
                        onUpdateDuration = viewModel::setDurationDays,
                    )
                    4 -> GStep4BuyIn(
                        buyIn = formState.buyInEuros,
                        onBuyInChange = viewModel::setBuyInEuros,
                        maxParticipants = formState.maxParticipants,
                        onMaxParticipantsChange = viewModel::setMaxParticipants,
                        buyInMin = appConfig.groupMinBuyIn,
                        buyInMax = appConfig.groupMaxBuyIn,
                    )
                    5 -> GStep5StartDate(
                        startDateEnabled = formState.startDateEnabled,
                        startDateMs = formState.startDateMs,
                        startDateError = formState.startDateError,
                        onStartDateEnabledToggle = viewModel::setStartDateEnabled,
                        onStartDateChange = viewModel::setStartDate,
                    )
                    6 -> GStep6Review(
                        formState = formState,
                        isBlockOnly = formState.activeTab == 1,
                        isLoading = isLoading,
                        onCreateChallenge = viewModel::createChallenge,
                    )
                }
            }

            if (formState.currentStep < GROUP_WIZARD_TOTAL_STEPS) {
                val context = LocalContext.current
                HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
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
            text = stringResource(R.string.group_wizard_limit_type_subtitle),
            fontSize = 14.sp,
            color = detoxColors.subtext,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Same cards, icons and hue-per-option as Solo/Hard step 3. TIME_WINDOW is absent by
        // design: the group wizard has no schedule step, so a time window has nowhere to be set.
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
    }
}

// ── Step 3: Limit value + duration ────────────────────────────────────────────

@Composable
private fun GStep3LimitAndDuration(
    formState: GroupCreateFormState,
    isBlockOnly: Boolean,
    onUpdateLimitMinutes: (Int) -> Unit,
    onUpdateLimitSessions: (Int) -> Unit,
    onUpdateSessionDuration: (Int) -> Unit,
    onUpdateDailyBudget: (Int) -> Unit,
    onUpdateDuration: (Int) -> Unit,
) {
    // Group merges Solo's step 4 (limit values) and step 6 (duration) into one step, but each
    // section is styled exactly like its Solo counterpart: 22sp/16sp headings and pickers sitting
    // bare on the screen background, never boxed in a card.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Block-only (Websites/adult) is a 24/7 hard block with no minute limit, so the limit picker
        // is hidden — this step configures only the duration.
        if (!isBlockOnly) {
            Text(
                text = stringResource(R.string.wizard_set_limit_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = detoxColors.label,
            )

            when (formState.limitType) {
                LimitType.TIME -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    DetoxHorizontalPicker(
                        values = (5..120).toList(),
                        selectedValue = formState.limitValueMinutes.coerceIn(5, 120),
                        onValueChange = onUpdateLimitMinutes,
                        unit = stringResource(R.string.wizard_set_limit_minutes_unit),
                        surfaceColor = detoxColors.screenBackground,
                    )
                }

                LimitType.SESSIONS -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    DetoxHorizontalPicker(
                        values = (1..20).toList(),
                        selectedValue = formState.limitValueSessions.coerceAtMost(20),
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
                        selectedValue = formState.sessionMinutes.coerceAtMost(30),
                        onValueChange = onUpdateSessionDuration,
                        unit = stringResource(R.string.wizard_set_limit_session_unit),
                        surfaceColor = detoxColors.screenBackground,
                    )
                }

                LimitType.TIME_BUDGET -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    DetoxHorizontalPicker(
                        values = (5..120).toList(),
                        selectedValue = formState.dailyBudgetMinutes.coerceIn(5, 120),
                        onValueChange = onUpdateDailyBudget,
                        unit = stringResource(R.string.wizard_set_limit_budget_unit),
                        surfaceColor = detoxColors.screenBackground,
                    )
                }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = detoxColors.divider)
        }

        Text(
            text = stringResource(R.string.wizard_duration_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = detoxColors.label,
        )

        DetoxHorizontalPicker(
            values = (3..30).toList(),
            selectedValue = formState.durationDays.coerceIn(3, 30),
            onValueChange = onUpdateDuration,
            unit = stringResource(R.string.wizard_duration_days_unit),
            surfaceColor = detoxColors.screenBackground,
        )
    }
}

// ── Step 4: Buy-in + participant cap ──────────────────────────────────────────

@Composable
private fun GStep4BuyIn(
    buyIn: Int,
    onBuyInChange: (Int) -> Unit,
    maxParticipants: Int,
    onMaxParticipantsChange: (Int) -> Unit,
    buyInMin: Int = 10,
    buyInMax: Int = 50,
) {
    // Remote-controlled buy-in range (config/app) with hardcoded €10–€50 fallback.
    val safeMin = buyInMin.coerceAtLeast(1)
    val safeMax = buyInMax.coerceAtLeast(safeMin)
    // Both step-4 inputs are economic parameters, so the best-case figure below depends on
    // BOTH pickers and updates as either moves. See [maxPossibleWinCents] — it mirrors the
    // settlement Cloud Function's maths rather than the old "buy-in × full lobby" pot, which
    // implied a payout that could not happen (a pot of N stakes needs N failures — and then
    // there is no winner to pay).
    val maxWinCents = maxPossibleWinCents(stakeCents = buyIn * 100, maxParticipants = maxParticipants)

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
            color = detoxColors.label,
        )
        Text(
            text = stringResource(R.string.group_wizard_buyin_subtitle),
            fontSize = 14.sp,
            color = detoxColors.subtext,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(detoxColors.cardBackground)
                .border(0.5.dp, detoxColors.cardBorder, GCardShape)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetoxHorizontalPicker(
                    values = (safeMin..safeMax).toList(),
                    selectedValue = buyIn.coerceIn(safeMin, safeMax),
                    onValueChange = onBuyInChange,
                    unit = stringResource(R.string.group_wizard_buyin_unit),
                )
                HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
                Text(
                    text = stringResource(R.string.group_wizard_max_players_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label,
                )
                DetoxHorizontalPicker(
                    values = GroupParticipantLimits.PICKER_VALUES,
                    selectedValue = maxParticipants.coerceIn(
                        GroupParticipantLimits.PICKER_MIN, GroupParticipantLimits.MAX,
                    ),
                    onValueChange = onMaxParticipantsChange,
                    unit = stringResource(R.string.group_wizard_max_players_unit),
                )
                HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.group_pot_estimate),
                        fontSize = 13.sp,
                        color = detoxColors.subtext,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = formatEuroCents(maxWinCents),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = detoxColors.accent,
                    )
                }
                Text(
                    text = stringResource(R.string.group_pot_estimate_note),
                    fontSize = 12.sp,
                    color = detoxColors.subtext,
                )
                // The full money mechanic lives HERE, next to the figure it explains and at
                // the moment the stake is chosen — not on the review step, whose breakdown
                // is about the creator's OWN stake (80/20) and would only restate this.
                // Every line names its base: the pot vs. your own stake are different money.
                HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
                Text(
                    text = stringResource(R.string.group_pot_rules_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        R.string.group_pot_rules_stake_to_pot,
                        R.string.group_pot_rules_split_and_fee,
                        R.string.group_pot_rules_own_stake,
                        R.string.group_pot_rules_nobody_fails,
                    ).forEach { line ->
                        Text(
                            text = stringResource(line),
                            fontSize = 12.sp,
                            color = detoxColors.subtext,
                        )
                    }
                }
            }
        }
    }
}

// ── Step 5: Start date ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GStep5StartDate(
    startDateEnabled: Boolean,
    startDateMs: Long,
    startDateError: String?,
    onStartDateEnabledToggle: (Boolean) -> Unit,
    onStartDateChange: (Long) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    // A start date past the card-hold window produces a challenge that can never start (see
    // GroupStartWindow). Bound the picker so such a date cannot be chosen at all — the ViewModel
    // re-checks on Next as a backstop, which is also what catches a wizard left open past midnight.
    // `now` is captured once per entry into this step so the bounds don't shift under recomposition.
    val nowMs = remember { System.currentTimeMillis() }
    val selectableStartDates = remember(nowMs) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                GroupStartWindow.isSelectable(utcTimeMillis, nowMs)

            override fun isSelectableYear(year: Int): Boolean =
                year in GroupStartWindow.selectableYears(nowMs)
        }
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = startDateMs.takeIf { it > 0L },
        selectableDates = selectableStartDates,
    )
    val latestStartLabel = remember(nowMs) {
        sdf.format(Date(GroupStartWindow.latestSelectableUtcDay(nowMs)))
    }

    if (showDatePicker) {
        WizardDatePickerDialog(
            state = datePickerState,
            title = stringResource(R.string.wizard_date_picker_title),
            onConfirm = {
                datePickerState.selectedDateMillis?.let { onStartDateChange(it) }
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
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
            color = detoxColors.label,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // ── Start date card ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(detoxColors.cardBackground)
                .border(0.5.dp, detoxColors.cardBorder, GCardShape),
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
                        color = detoxColors.label,
                    )
                    Switch(
                        checked = startDateEnabled,
                        onCheckedChange = onStartDateEnabledToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                if (!startDateEnabled) {
                    HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
                    Text(
                        text = stringResource(R.string.group_wizard_start_manual_desc),
                        fontSize = 13.sp,
                        color = detoxColors.subtext,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                if (startDateEnabled) {
                    HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.group_wizard_start_date_section),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = detoxColors.subtext,
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
                                tint = detoxColors.accent,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (startDateMs > 0L) sdf.format(Date(startDateMs))
                                else stringResource(R.string.group_wizard_start_date_placeholder),
                                color = if (startDateMs > 0L) detoxColors.label else detoxColors.subtext,
                            )
                        }
                        // Always shown, not only on error: the constraint is a fact about the
                        // card hold, and a creator who understands it up front never hits the
                        // greyed-out days wondering why.
                        Text(
                            text = stringResource(
                                R.string.group_wizard_start_date_window_hint,
                                latestStartLabel,
                                GroupStartWindow.WINDOW_DAYS.toInt(),
                            ),
                            fontSize = 12.sp,
                            color = detoxColors.subtext,
                        )
                        if (startDateError != null) {
                            Text(
                                text = startDateError,
                                fontSize = 12.sp,
                                color = detoxColors.danger,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Step 6: Review & create ───────────────────────────────────────────────────

@Composable
private fun GStep6Review(
    formState: GroupCreateFormState,
    isBlockOnly: Boolean,
    isLoading: Boolean,
    onCreateChallenge: () -> Unit,
) {
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    // Same best-case maths as step 4 — the creator's chosen cap, not a fixed lobby size.
    val maxWinCents = maxPossibleWinCents(
        stakeCents = formState.buyInEuros * 100,
        maxParticipants = formState.maxParticipants,
    )
    var waiverChecked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.group_wizard_review_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = detoxColors.label,
        )

        // ── Summary card ────────────────────────────────────────────────────
        // Rows, labels and value formats are the same as Solo's step 7; only the
        // group-specific rows (stake, start, bonus, players, pot) are additional.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GCardShape)
                .background(detoxColors.cardBackground)
                .border(0.5.dp, detoxColors.cardBorder, GCardShape),
        ) {
            Column {
                WizardSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_mode_label),
                    value = stringResource(R.string.wizard_review_mode_group),
                    isFirst = true,
                )
                // Block-only path (Websites/adult): the target row lists the domains (or the adult
                // label for adult-only) and the limit row reads "always blocked" — 24/7 hard block.
                val targetNames = if (isBlockOnly) formState.manualDomains else formState.packageNames
                val targetLabel = if (isBlockOnly) stringResource(R.string.wizard_review_blocked_label)
                    else stringResource(R.string.wizard_review_apps_label)
                val targetValue = when {
                    isBlockOnly && targetNames.isEmpty() -> stringResource(R.string.adult_block_display_name)
                    isBlockOnly && targetNames.size == 1 -> targetNames[0]
                    isBlockOnly && targetNames.size == 2 -> targetNames[0] + ", " + targetNames[1]
                    isBlockOnly -> stringResource(
                        R.string.wizard_review_apps_overflow_format,
                        targetNames[0], targetNames[1], targetNames.size - 2,
                    )
                    // App path: the form only carries the first app's display name, so a single
                    // app shows its name and more than one falls back to the count.
                    targetNames.size == 1 && formState.displayName.isNotBlank() -> formState.displayName
                    else -> stringResource(R.string.wizard_review_apps_count, targetNames.size)
                }
                WizardSummaryDividerRow(label = targetLabel, value = targetValue)
                if (isBlockOnly && formState.blockAdultContent && targetNames.isNotEmpty()) {
                    WizardSummaryDividerRow(
                        label = stringResource(R.string.adult_block_display_name),
                        value = stringResource(R.string.wizard_review_adult_active),
                    )
                }
                val limitValue = if (isBlockOnly) stringResource(R.string.wizard_review_always_blocked)
                    else when (formState.limitType) {
                        LimitType.TIME -> stringResource(
                            R.string.wizard_review_limit_time_format, formState.limitValueMinutes,
                        )
                        LimitType.SESSIONS -> stringResource(
                            R.string.wizard_review_limit_sessions_format,
                            formState.limitValueSessions, formState.sessionMinutes,
                        )
                        LimitType.TIME_BUDGET -> stringResource(
                            R.string.wizard_review_limit_budget_format, formState.dailyBudgetMinutes,
                        )
                        else -> "—"
                    }
                WizardSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_limit_label),
                    value = limitValue,
                )
                WizardSummaryDividerRow(
                    label = stringResource(R.string.wizard_review_duration_label),
                    value = stringResource(R.string.wizard_review_days_format, formState.durationDays),
                )
                WizardSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_einsatz_label),
                    value = stringResource(R.string.group_wizard_review_einsatz_value, formState.buyInEuros),
                )
                WizardSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_start_label),
                    value = if (formState.startDateEnabled && formState.startDateMs > 0L)
                        sdf.format(Date(formState.startDateMs))
                    else stringResource(R.string.group_wizard_review_start_manual),
                )
                WizardSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_max_players_label),
                    value = formState.maxParticipants.toString(),
                )
                WizardSummaryDividerRow(
                    label = stringResource(R.string.group_wizard_review_pot_label),
                    value = stringResource(
                        R.string.group_wizard_review_pot_value, formatEuroCents(maxWinCents),
                    ),
                    valueColor = detoxColors.accent,
                )
            }
        }

        // ── Fee breakdown card (buy-in 80 / 20 split) ───────────────────────
        run {
            val buyInCents = formState.buyInEuros * 100
            val refundCents = (buyInCents * 80) / 100      // Math.floor of 80%
            val feeCents = buyInCents - refundCents          // remainder = 20%
            WizardFeeBreakdownCard(
                stakeLabel = stringResource(R.string.fee_your_buyin),
                stakeValue = formatEuroCents(buyInCents),
                // The 80% is not guaranteed for a group — it is a possible prize share. That
                // caveat is an asterisk on the value plus a footnote line under the rows, NOT
                // prose appended to the money value itself.
                refundValue = stringResource(
                    R.string.fee_value_format_footnote, formatEuroCents(refundCents), 80,
                ),
                feeValue = stringResource(
                    R.string.fee_value_format, formatEuroCents(feeCents), 20,
                ),
                notes = listOf(
                    stringResource(R.string.fee_return_on_success_group_note),
                    stringResource(R.string.fee_group_no_loser_note),
                ),
            )
        }

        // ── Withdrawal-rights waiver (FAGG § 18) ────────────────────────────
        WizardWaiverCheckboxRow(
            checked = waiverChecked,
            onToggle = { waiverChecked = !waiverChecked },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Create button ───────────────────────────────────────────────────
        val context = LocalContext.current
        Button(
            onClick = {
                HapticManager.light(context)
                onCreateChallenge()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .pressScaleFeedback(),
            // Legal gate: the waiver must be ticked before the buy-in payment starts.
            enabled = !isLoading && waiverChecked,
            shape = GBtnShape,
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
                    text = stringResource(R.string.group_wizard_create_btn),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
