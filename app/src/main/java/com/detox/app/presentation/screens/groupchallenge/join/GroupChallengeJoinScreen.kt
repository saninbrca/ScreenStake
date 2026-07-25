package com.detox.app.presentation.screens.groupchallenge.join

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.maxPossibleWinCents
import com.detox.app.presentation.components.WizardFeeBreakdownCard
import com.detox.app.presentation.components.WizardSummaryDividerRow
import com.detox.app.presentation.components.WizardWaiverCheckboxRow
import com.detox.app.presentation.components.formatEuroCents
import com.detox.app.presentation.screens.activechallenge.DetoxCard
import com.detox.app.ui.theme.detoxColors
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Join a group challenge by code.
 *
 * Two visual states, and the second REPLACES the first: once a code resolves, the entry
 * form collapses to a one-line "Code ABC123 / change" row so the screen is about the
 * challenge rather than the form the user has already finished with.
 *
 * The preview is held to the same honesty standard as the create wizard: a joiner is
 * committing real money, so everything the creator confirmed on their review step is
 * shown here too — what is blocked (apps, domains, adult), the limit incl. session
 * length, the duration, the REAL start (scheduled date or manual), the buy-in, the
 * 80/20 split on their own stake, what they could win, and the authorization deadline.
 * The rows are literally the wizard's [WizardSummaryDividerRow], so the joiner reads the
 * same summary shape the creator signed off on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeJoinScreen(
    onBack: () -> Unit,
    onJoined: (groupId: String) -> Unit,
    viewModel: GroupChallengeJoinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val codeInput by viewModel.codeInput.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val paymentCancelledMessage = stringResource(R.string.join_group_payment_cancelled)
    val paymentFailedMessage = stringResource(R.string.join_group_payment_failed)
    // Hoisted to screen level so a cancelled PaymentSheet (Preview → AwaitingPayment →
    // Preview) does not silently untick a consent the user already gave.
    var waiverChecked by remember { mutableStateOf(false) }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentSuccess()
            is PaymentSheetResult.Canceled -> {
                viewModel.onPaymentCancelled()
                coroutineScope.launch { snackbarHostState.showSnackbar(paymentCancelledMessage) }
            }
            is PaymentSheetResult.Failed -> {
                viewModel.onPaymentCancelled()
                coroutineScope.launch { snackbarHostState.showSnackbar(paymentFailedMessage) }
            }
        }
    }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GroupJoinUiState.JoinedSuccessfully -> onJoined(s.groupId)
            is GroupJoinUiState.AwaitingPayment -> {
                paymentSheet.presentWithPaymentIntent(
                    paymentIntentClientSecret = s.paymentData.clientSecret,
                    configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App")
                )
            }
            is GroupJoinUiState.Error -> {
                // Post-payment errors show inline retry button — no snackbar needed.
                if (s.retryGroupChallenge == null) {
                    snackbarHostState.showSnackbar(s.message)
                }
            }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = detoxColors.screenBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.join_group_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                // Matches GroupChallengeDetailScreen's top bar — the group surfaces share one
                // chrome. WizardHeader is deliberately NOT used here: it is step-based.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = detoxColors.screenBackground,
                    titleContentColor = detoxColors.label,
                    navigationIconContentColor = detoxColors.label,
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val previewGc: GroupChallenge? = when (val s = uiState) {
            is GroupJoinUiState.Preview -> s.groupChallenge
            is GroupJoinUiState.ProcessingPayment -> s.groupChallenge
            is GroupJoinUiState.AwaitingPayment -> s.groupChallenge
            is GroupJoinUiState.ConfirmingJoin -> s.groupChallenge
            is GroupJoinUiState.Error -> s.retryGroupChallenge  // keep card visible for post-payment errors
            else -> null
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (previewGc == null) {
                JoinCodeEntry(
                    codeInput = codeInput,
                    isLookingUp = uiState is GroupJoinUiState.LookingUp,
                    isError = uiState is GroupJoinUiState.Error,
                    onCodeChanged = viewModel::onCodeChanged,
                    onLookup = viewModel::lookupCode,
                )
            } else {
                JoinPreview(
                    gc = previewGc,
                    uiState = uiState,
                    code = codeInput,
                    waiverChecked = waiverChecked,
                    onWaiverToggle = { waiverChecked = !waiverChecked },
                    onChangeCode = viewModel::resetToCodeEntry,
                    onPay = { viewModel.initiatePayment(previewGc) },
                    onRetry = viewModel::clearError,
                )
            }
        }
    }
}

// ── State 1: code entry ───────────────────────────────────────────────────────

@Composable
private fun JoinCodeEntry(
    codeInput: String,
    isLookingUp: Boolean,
    isError: Boolean,
    onCodeChanged: (String) -> Unit,
    onLookup: () -> Unit,
) {
    Text(
        text = stringResource(R.string.join_group_heading),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = detoxColors.label,
    )
    Text(
        text = stringResource(R.string.join_group_subtitle),
        fontSize = 14.sp,
        color = detoxColors.subtext,
    )
    Spacer(Modifier.height(4.dp))

    DetoxCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = onCodeChanged,
                label = { Text(stringResource(R.string.join_group_code_label)) },
                placeholder = { Text(stringResource(R.string.join_code_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { onLookup() }),
                isError = isError,
                enabled = !isLookingUp,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = detoxColors.accent,
                    cursorColor = detoxColors.accent,
                    focusedLabelColor = detoxColors.accent,
                    unfocusedLabelColor = detoxColors.subtext,
                    focusedTextColor = detoxColors.label,
                    unfocusedTextColor = detoxColors.label,
                    errorBorderColor = detoxColors.danger,
                    errorLabelColor = detoxColors.danger,
                ),
            )
            Button(
                onClick = onLookup,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = codeInput.length == 6 && !isLookingUp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledContentColor = detoxColors.subtext,
                ),
            ) {
                if (isLookingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(R.string.join_group_lookup),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    // Not decoration: this is the one thing a joiner should read BEFORE a code resolves,
    // and it is the same pot explanation the creator gets on wizard step 4 — same strings.
    DetoxCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.join_group_commit_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = detoxColors.label,
            )
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

// ── State 2: resolved challenge preview ───────────────────────────────────────

@Composable
private fun JoinPreview(
    gc: GroupChallenge,
    uiState: GroupJoinUiState,
    code: String,
    waiverChecked: Boolean,
    onWaiverToggle: () -> Unit,
    onChangeCode: () -> Unit,
    onPay: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val appIcon: Drawable? = remember(gc.appPackageNames) {
        gc.appPackageNames.firstOrNull()?.let { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
    }
    // A group with no packages is a websites/adult block — the same isBlockOnly split the
    // create wizard's review step makes, so both sides describe a group identically.
    val isBlockOnly = gc.appPackageNames.isEmpty()
    val isLoading = uiState is GroupJoinUiState.ProcessingPayment ||
        uiState is GroupJoinUiState.AwaitingPayment ||
        uiState is GroupJoinUiState.ConfirmingJoin

    // ── Collapsed code row ──────────────────────────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.join_group_code_entered, code),
            fontSize = 13.sp,
            color = detoxColors.subtext,
        )
        // Only before payment: once a hold exists, the code is no longer the user's to change.
        if (uiState is GroupJoinUiState.Preview) {
            TextButton(onClick = onChangeCode) {
                Text(
                    text = stringResource(R.string.join_group_change_code),
                    fontSize = 13.sp,
                    color = detoxColors.accent,
                )
            }
        }
    }

    // ── Identity card ───────────────────────────────────────────────────────
    DetoxCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (appIcon != null) {
                Image(
                    painter = rememberAsyncImagePainter(model = appIcon),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = if (isBlockOnly) Icons.Outlined.Language else Icons.Filled.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = detoxColors.subtext,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gc.appDisplayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = detoxColors.label,
                )
                if (gc.creatorDisplayName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.join_group_creator, gc.creatorDisplayName),
                        fontSize = 13.sp,
                        color = detoxColors.subtext,
                    )
                }
            }
        }
    }

    // ── Summary rows — the creator's review step, seen by the joiner ────────
    DetoxCard {
        Column {
            val targetNames = if (isBlockOnly) gc.blockedDomains else gc.appPackageNames
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
                gc.appDisplayName.isNotBlank() && targetNames.size == 1 -> gc.appDisplayName
                else -> stringResource(R.string.wizard_review_apps_count, targetNames.size)
            }
            WizardSummaryDividerRow(label = targetLabel, value = targetValue, isFirst = true)

            // Adult blocking is a rule the joiner is agreeing to enforce — never silent.
            // Skipped only when the target row above already IS the adult label.
            if (gc.blockAdultContent && !(isBlockOnly && targetNames.isEmpty())) {
                WizardSummaryDividerRow(
                    label = stringResource(R.string.adult_block_display_name),
                    value = stringResource(R.string.wizard_review_adult_active),
                )
            }

            val limitValue = if (isBlockOnly) stringResource(R.string.wizard_review_always_blocked)
                else when (gc.limitType) {
                    LimitType.TIME -> stringResource(
                        R.string.wizard_review_limit_time_format, gc.limitValueMinutes,
                    )
                    // sessionDurationMinutes was dropped before: the creator confirmed
                    // "3x opens · 30 min" while the joiner only saw the open count.
                    LimitType.SESSIONS -> stringResource(
                        R.string.wizard_review_limit_sessions_format,
                        gc.limitValueSessions ?: gc.limitValueMinutes,
                        gc.sessionDurationMinutes,
                    )
                    LimitType.TIME_BUDGET -> stringResource(
                        R.string.wizard_review_limit_budget_format, gc.limitValueMinutes,
                    )
                    LimitType.TIME_WINDOW -> stringResource(R.string.join_group_limit_time_window)
                }
            WizardSummaryDividerRow(
                label = stringResource(R.string.wizard_review_limit_label),
                value = limitValue,
            )
            WizardSummaryDividerRow(
                label = stringResource(R.string.wizard_review_duration_label),
                value = stringResource(R.string.wizard_review_days_format, gc.durationDays),
            )
            // The real start: startDate carries the creator's scheduled ms, 0 = manual start.
            // This row used to be hardcoded to "started manually by the creator".
            WizardSummaryDividerRow(
                label = stringResource(R.string.join_group_starts_label),
                value = if (gc.startDate > 0L) sdf.format(Date(gc.startDate))
                    else stringResource(R.string.join_group_starts_manual),
            )
            WizardSummaryDividerRow(
                label = stringResource(R.string.join_group_players_label),
                value = stringResource(
                    R.string.join_group_players_value, gc.participants.size, gc.maxParticipants,
                ),
            )
            WizardSummaryDividerRow(
                label = stringResource(R.string.group_pot_estimate),
                value = formatEuroCents(
                    maxPossibleWinCents(
                        stakeCents = gc.buyInCents,
                        maxParticipants = gc.maxParticipants,
                    )
                ),
                valueColor = detoxColors.accent,
            )
        }
    }

    // ── Stake breakdown — identical component to the creator's review step ──
    run {
        val refundCents = (gc.buyInCents * 80) / 100      // Math.floor of 80%
        val feeCents = gc.buyInCents - refundCents          // remainder = 20%
        WizardFeeBreakdownCard(
            stakeLabel = stringResource(R.string.fee_your_buyin),
            stakeValue = formatEuroCents(gc.buyInCents),
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

    // ── Authorization deadline ──────────────────────────────────────────────
    if (gc.authorizationExpiresAt > 0L) {
        val now = System.currentTimeMillis()
        val expiresSoon = gc.authorizationExpiresAt - now <= 24L * 60 * 60 * 1000
        Text(
            text = stringResource(
                R.string.join_group_auth_deadline,
                sdf.format(Date(gc.authorizationExpiresAt)),
            ),
            fontSize = 12.sp,
            color = if (expiresSoon) detoxColors.warningStrong else detoxColors.subtext,
        )
    }

    Spacer(Modifier.height(4.dp))

    // ── FAGG § 18 waiver + action ───────────────────────────────────────────
    when {
        isLoading -> {
            Button(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledContentColor = detoxColors.subtext,
                ),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = detoxColors.subtext,
                )
                Spacer(Modifier.width(10.dp))
                Text(text = stringResource(R.string.join_group_confirming), fontSize = 16.sp)
            }
        }
        uiState is GroupJoinUiState.Error -> {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = detoxColors.danger),
                border = BorderStroke(1.dp, detoxColors.danger),
            ) {
                Text(text = stringResource(R.string.join_group_retry), fontSize = 16.sp)
            }
        }
        else -> {
            // Same legal gate as the create wizard: the waiver must be ticked before the
            // buy-in payment can start.
            WizardWaiverCheckboxRow(checked = waiverChecked, onToggle = onWaiverToggle)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onPay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = uiState is GroupJoinUiState.Preview && waiverChecked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledContentColor = detoxColors.subtext,
                ),
            ) {
                Text(
                    text = stringResource(R.string.join_group_join_and_pay, gc.buyInCents / 100),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
