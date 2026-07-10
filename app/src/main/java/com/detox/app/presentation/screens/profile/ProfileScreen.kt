package com.detox.app.presentation.screens.profile

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.BuildConfig
import com.detox.app.R
import com.detox.app.service.TrackedAppEventBus
import com.detox.app.util.DateUtils
import com.detox.app.util.FeatureFlags
import io.sentry.Sentry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLoggedOut: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onNavigateToChallenges: () -> Unit = {},
    onGroupChallengeClick: (String) -> Unit = {},
    onSoloChallengeClick: (String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // val recentChallenges by viewModel.recentChallenges.collectAsStateWithLifecycle() // Removed
    // val payoutState by viewModel.payoutState.collectAsStateWithLifecycle() // Removed
    // val pendingPayoutCents by viewModel.pendingPayoutCents.collectAsStateWithLifecycle() // Removed
    val payoutClaimState by viewModel.payoutClaimState.collectAsStateWithLifecycle()
    val ibanData by viewModel.ibanData.collectAsStateWithLifecycle()
    val pendingBalance by viewModel.pendingBalance.collectAsStateWithLifecycle()
    val payoutRequestState by viewModel.payoutRequestState.collectAsStateWithLifecycle()
    var showPayoutConfirmDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshOnResume()
        }
    }

    LaunchedEffect(payoutRequestState) {
        when (val s = payoutRequestState) {
            is PayoutRequestState.Success -> {
                snackbarHostState.showSnackbar(context.getString(R.string.payout_balance_success))
                viewModel.clearPayoutRequestState()
            }
            is PayoutRequestState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearPayoutRequestState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(payoutClaimState) {
        when (val s = payoutClaimState) {
            is PayoutClaimState.Success -> {
                if (s.transferredCents > 0) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.profile_payout_claimed, s.transferredCents / 100)
                    )
                }
                viewModel.clearPayoutClaimState()
            }
            is PayoutClaimState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearPayoutClaimState()
            }
            else -> Unit
        }
    }

    if (showPayoutConfirmDialog && FeatureFlags.moneyEnabled) {
        val balance = pendingBalance
        val iban = ibanData?.iban
        if (balance != null && iban != null) {
            val amountFormatted = "€%s".format(
                "%.2f".format(balance.totalCents / 100.0).replace(".", ",")
            )
            val ibanLast4 = iban.takeLast(4)
            AlertDialog(
                onDismissRequest = { showPayoutConfirmDialog = false },
                title = { Text(stringResource(R.string.payout_balance_confirm_title, amountFormatted)) },
                text = { Text(stringResource(R.string.payout_balance_confirm_iban, ibanLast4)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showPayoutConfirmDialog = false
                            viewModel.requestPayout()
                        }
                    ) {
                        Text(
                            stringResource(R.string.payout_balance_confirm_yes),
                            color = Color(0xFF00C853)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPayoutConfirmDialog = false }) {
                        Text(stringResource(R.string.redemption_confirm_cancel))
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.profile_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight(700),
                        color = Color.Black
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // ── Avatar ────────────────────────────────────────────────────────
            var avatarVisible by remember { mutableStateOf(false) }
            val avatarScale by animateFloatAsState(
                targetValue = if (avatarVisible) 1f else 0.8f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "avatarScale"
            )
            LaunchedEffect(Unit) { avatarVisible = true }

            val initials = viewModel.displayName
                ?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.take(2)
                ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                ?.joinToString("")
                ?.takeIf { it.isNotEmpty() }
                ?: viewModel.userEmail?.firstOrNull()?.uppercaseChar()?.toString()
                ?: ""

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer(scaleX = avatarScale, scaleY = avatarScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (initials.isNotEmpty()) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Unique @username (falls back to email prefix for legacy accounts).
            val handle = viewModel.usernameHandle
            val nameToShow = handle?.let { "@$it" }
                ?: viewModel.userEmail?.substringBefore("@")
                ?: stringResource(R.string.profile_unknown_email)
            Text(
                text = nameToShow,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            // Member since
            viewModel.memberSinceMs?.let { ms ->
                val dateStr = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.profile_member_since, dateStr),
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 💰 Guthaben card ──────────────────────────────────────────────
            // Money-floor gated: hidden in the soft-only release (the payout/balance surface is a
            // real-money feature). Empty state is already supported (card is conditional on balance).
            if (FeatureFlags.moneyEnabled) {
                pendingBalance?.let { balance ->
                    GuthabenCard(
                        balance = balance,
                        ibanData = ibanData,
                        onAddIban = onOpenSettings,
                        onRequestPayout = { showPayoutConfirmDialog = true },
                        payoutRequestState = payoutRequestState
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // ── Settings Card ─────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(0.5.dp, Color(0x0F000000))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.profile_settings),
                        fontSize = 17.sp,
                        fontWeight = FontWeight(600),
                        color = Color.Black,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(16.dp))
                DebugPanel(viewModel = viewModel)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun GuthabenCard(
    balance: PendingBalanceState,
    ibanData: IbanData?,
    onAddIban: () -> Unit,
    onRequestPayout: () -> Unit,
    payoutRequestState: PayoutRequestState,
    modifier: Modifier = Modifier
) {
    val amountFormatted = "€%s".format(
        "%.2f".format(balance.totalCents / 100.0).replace(".", ",")
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(0.5.dp, Color(0x0F000000))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.payout_balance_title),
                fontSize = 17.sp,
                fontWeight = FontWeight(600),
                color = Color.Black
            )
            Text(
                text = amountFormatted,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00C853)
            )
            Text(
                text = stringResource(R.string.payout_balance_source, balance.sourceCount),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8E8E93)
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (ibanData == null) {
                OutlinedButton(
                    onClick = onAddIban,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, Color(0xFFE0E0E5)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00C853))
                ) {
                    Text(
                        text = stringResource(R.string.payout_balance_iban_missing),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.payout_balance_iban_stored, ibanData.iban.takeLast(4)),
                    fontSize = 14.sp,
                    color = Color(0xFF8E8E93)
                )
                Button(
                    onClick = onRequestPayout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C853),
                        contentColor = Color.White
                    ),
                    enabled = payoutRequestState !is PayoutRequestState.Loading
                ) {
                    if (payoutRequestState is PayoutRequestState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.payout_balance_request_button),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SimpleHistoryRow(
    icon: String,
    name: String,
    statusLabel: String,
    statusBgColor: Color,
    statusTextColor: Color,
    dateStr: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, Color(0x0F000000))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = icon, fontSize = 20.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .background(statusBgColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusTextColor
                    )
                }
            }
            Text(
                text = dateStr,
                fontSize = 12.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}

// ── Debug Panel ───────────────────────────────────────────────────────────────

private val DebugOrange = Color(0xFFE65100)
private val DebugOrangeBorder = Color(0xFFFF6D00)
private val DebugBg = Color(0xFFFFF3E0)

@Composable
private fun DebugSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = DebugOrange,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun DebugButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        border = BorderStroke(1.dp, DebugOrangeBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DebugOrange)
    ) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun DebugToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        border = BorderStroke(1.dp, if (active) DebugOrange else DebugOrangeBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = DebugOrange,
            containerColor = if (active) DebugOrange.copy(alpha = 0.08f) else Color.Transparent
        )
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun GroupSelectDialog(
    title: String,
    groups: List<com.detox.app.data.local.db.entity.GroupChallengeEntity>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall, color = DebugOrange) },
        text = {
            Column {
                if (groups.isEmpty()) {
                    Text("No active group challenges", style = MaterialTheme.typography.bodySmall)
                } else {
                    groups.forEach { gc ->
                        TextButton(onClick = { onSelect(gc.groupId); onDismiss() }) {
                            Text("${gc.appDisplayName} (${gc.groupId.take(8)}…)", color = DebugOrange)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PayoutChallengeCard(
    payout: PayoutChallengeInfo,
    onIbanSetupClick: () -> Unit
) {
    val formatCents: (Int) -> String = { cents ->
        "€%,.2f".format(cents / 100.0).replace(",", "X").replace(".", ",").replace("X", ".")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val modeLabel = when {
                        payout.isGroup -> "Group Challenge"
                        payout.isRedemption -> "Comeback"
                        else -> "Hard Mode"
                    }
                    Text(
                        text = "$modeLabel — ${payout.challengeTitle}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (payout.durationDays > 0) {
                        Text(
                            text = "${payout.durationDays} Tage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                androidx.compose.material3.Badge(
                    containerColor = Color(0xFF00C853)
                ) {
                    Text(
                        text = "Abgeschlossen",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Stake refund row
            PayoutRow(
                label = stringResource(R.string.payout_stake_back, formatCents(payout.stakeRefundCents)),
                statusIcon = "✅"
            )

            // App fee row — shown for all challenge types
            when {
                payout.nobodyFailed -> PayoutRow(
                    label = stringResource(R.string.payout_app_fee_none),
                    statusIcon = null
                )
                payout.appFeeCents > 0 && payout.isGroup -> PayoutRow(
                    label = stringResource(R.string.payout_app_fee_20, formatCents(payout.appFeeCents)),
                    statusIcon = null
                )
                payout.appFeeCents > 0 && payout.isRedemption -> PayoutRow(
                    label = stringResource(R.string.payout_app_fee_40, formatCents(payout.appFeeCents)),
                    statusIcon = null
                )
                payout.appFeeCents > 0 -> PayoutRow(
                    label = stringResource(R.string.payout_app_fee_20, formatCents(payout.appFeeCents)),
                    statusIcon = null
                )
            }

            // Prize share row (group with losers only)
            if (payout.isGroup && !payout.nobodyFailed && payout.prizeShareCents > 0) {
                val prizeStatus = if (payout.payoutStatus == "pending_payout") "⏳" else "✅"
                PayoutRow(
                    label = stringResource(
                        R.string.payout_prize_share,
                        payout.winnersCount,
                        formatCents(payout.prizeShareCents)
                    ),
                    statusIcon = prizeStatus
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Total
            val total = payout.stakeRefundCents + payout.prizeShareCents
            Text(
                text = stringResource(R.string.payout_total, formatCents(total)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )

            // Status line
            val statusText = if (payout.payoutStatus == "pending_payout") {
                stringResource(R.string.payout_status_pending)
            } else {
                stringResource(R.string.payout_status_refunded)
            }
            Text(
                text = "Status: $statusText",
                style = MaterialTheme.typography.bodySmall,
                color = if (payout.payoutStatus == "pending_payout")
                    MaterialTheme.colorScheme.onSurfaceVariant
                else Color(0xFF00C853)
            )

            // Expected / confirmed date line
            if (payout.endDateMs > 0L) {
                val dateFormat = remember { SimpleDateFormat("d. MMM yyyy", Locale("de")) }
                val expectedMs = remember(payout.endDateMs) {
                    DateUtils.addBusinessDays(payout.endDateMs, 5)
                }
                val dateStr = dateFormat.format(Date(expectedMs))
                if (payout.payoutStatus == "refunded") {
                    Text(
                        text = stringResource(R.string.payout_date_credit, dateStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF000000)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.payout_date_expected, dateStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E8E93)
                    )
                }
            }

            // IBAN setup button for pending prize
            if (payout.payoutStatus == "pending_payout" && payout.prizeShareCents > 0) {
                TextButton(
                    onClick = onIbanSetupClick,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = stringResource(R.string.payout_iban_setup_button),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = stringResource(R.string.payout_prize_pending_iban_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E93)
                )
            }
        }
    }
}

@Composable
private fun PayoutRow(label: String, statusIcon: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        if (statusIcon != null) {
            Text(text = statusIcon, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Debug Panel ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugPanel(viewModel: ProfileViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("detox_settings", Context.MODE_PRIVATE) }

    var debugExpanded by remember { mutableStateOf(false) }

    // Section 3 toggles
    var minutesDaysOn by remember { mutableStateOf(prefs.getBoolean("debug_use_minutes_as_days", false)) }
    var hardMin1On by remember { mutableStateOf(prefs.getBoolean("debug_hard_mode_min_1", false)) }

    // Section 6 dialogs
    var showCompleteGroupDialog by remember { mutableStateOf(false) }
    var showFailGroupDialog by remember { mutableStateOf(false) }
    var showSetEndGroupDialog by remember { mutableStateOf(false) }

    // Section 8 dialogs
    var showLogsDialog by remember { mutableStateOf(false) }
    var showClearLogsConfirm by remember { mutableStateOf(false) }
    var showChallengesDialog by remember { mutableStateOf(false) }

    // Section 9 dialogs
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var permissionsText by remember { mutableStateOf("") }

    // Section 10 state
    var adultDomainTestInput by remember { mutableStateOf("") }
    var adultDomainTestResult by remember { mutableStateOf<String?>(null) }

    val activeGroupChallenges by viewModel.debugActiveGroupChallenges.collectAsStateWithLifecycle()
    val debugDailyLogs by viewModel.debugDailyLogs.collectAsStateWithLifecycle()
    val debugActiveChallenges by viewModel.debugActiveChallenges.collectAsStateWithLifecycle()

    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    fun isUsageStatsGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    // ── Group dialogs ─────────────────────────────────────────────────────────
    if (showCompleteGroupDialog) {
        GroupSelectDialog(
            title = "Complete Group Challenge",
            groups = activeGroupChallenges,
            onSelect = { groupId ->
                viewModel.debugCompleteGroupChallenge(groupId) { err ->
                    if (err == null) toast("completeGroupChallenge triggered for $groupId")
                    else toast("Error: $err")
                }
            },
            onDismiss = { showCompleteGroupDialog = false }
        )
    }
    if (showFailGroupDialog) {
        GroupSelectDialog(
            title = "Fail Me in Group Challenge",
            groups = activeGroupChallenges,
            onSelect = { groupId ->
                viewModel.debugFailMeInGroupChallenge(groupId) { err ->
                    if (err == null) toast("failParticipant triggered")
                    else toast("Error: $err")
                }
            },
            onDismiss = { showFailGroupDialog = false }
        )
    }
    if (showSetEndGroupDialog) {
        GroupSelectDialog(
            title = "Set Group Challenge End = Now",
            groups = activeGroupChallenges,
            onSelect = { groupId ->
                viewModel.debugSetGroupChallengeEndNow(groupId) { err ->
                    if (err == null) toast("Group challenge ends in 5 seconds")
                    else toast("Error: $err")
                }
            },
            onDismiss = { showSetEndGroupDialog = false }
        )
    }

    // ── Section 8 dialogs ─────────────────────────────────────────────────────
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Today's DailyLogs (${debugDailyLogs.size})", color = DebugOrange) },
            text = {
                Column {
                    if (debugDailyLogs.isEmpty()) {
                        Text("No logs for today", style = MaterialTheme.typography.bodySmall)
                    } else {
                        debugDailyLogs.forEach { log ->
                            Text(
                                text = """${log.challengeId.take(8)}…\nopens=${log.consciousOpens}  used=${log.budgetUsedMs / 60_000}min  rem=${log.budgetRemainingMs / 60_000}min""".trimIndent(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLogsDialog = false }) { Text("Close") } }
        )
    }
    if (showClearLogsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLogsConfirm = false },
            title = { Text("Clear Today's Logs?", color = DebugOrange) },
            text = { Text("This will reset all progress for today. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.debugClearDailyLogsToday()
                    showClearLogsConfirm = false
                    toast("All DailyLogs for today cleared")
                }) { Text("Clear", color = DebugOrange) }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsConfirm = false }) { Text("Cancel") }
            }
        )
    }
    if (showChallengesDialog) {
        AlertDialog(
            onDismissRequest = { showChallengesDialog = false },
            title = { Text("Active Challenges (${debugActiveChallenges.size})", color = DebugOrange) },
            text = {
                Column {
                    if (debugActiveChallenges.isEmpty()) {
                        Text("No active challenges", style = MaterialTheme.typography.bodySmall)
                    }
                    else {
                        debugActiveChallenges.forEach { c ->
                            Text(
                                text = "${c.id.take(8)}… | ${c.limitType} | ${c.status}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChallengesDialog = false }) { Text("Close") } }
        )
    }

    // ── Section 9 dialog ─────────────────────────────────────────────────────
    if (showPermissionsDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionsDialog = false },
            title = { Text("Permissions", color = DebugOrange) },
            text = { Text(permissionsText, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showPermissionsDialog = false }) { Text("OK") } }
        )
    }

    // ── Panel card ────────────────────────────────────────────────────────────
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DebugBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Header — tap to expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { debugExpanded = !debugExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Debug Tools",
                    style = MaterialTheme.typography.titleSmall,
                    color = DebugOrange,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (debugExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = DebugOrange
                )
            }

            if (debugExpanded) {
                HorizontalDivider(color = DebugOrangeBorder.copy(alpha = 0.3f))

                // ── SECTION 1: Onboarding ─────────────────────────────────────
                DebugSectionHeader("ONBOARDING")
                DebugButton("Reset Onboarding") {
                    prefs.edit().putBoolean("onboarding_completed", false).apply()
                    toast("Onboarding reset — restart app to see it")
                }
                // Test Blocking button (kept from old debug block)
                DebugButton("Test Blocking (simulate app open)") {
                    val target = TrackedAppEventBus.trackedPackages.value.firstOrNull()
                    if (target == null) toast("No tracked packages — start a challenge first")
                    else TrackedAppEventBus.emitAppOpen(target)
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 2: Daily Evaluation ───────────────────────────────
                DebugSectionHeader("DAILY EVALUATION")
                DebugButton("Run Daily Evaluation Now") {
                    toast("Daily evaluation started…")
                    viewModel.debugRunDailyEvaluation { msg -> toast(msg) }
                }
                DebugButton("Run Permission Check Now") {
                    toast("Permission check started…")
                    viewModel.debugRunPermissionCheck { msg -> toast(msg) }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 3: Challenge Time Manipulation ────────────────────
                DebugSectionHeader("CHALLENGE TIME MANIPULATION")
                DebugButton("Set All Challenges End = Now (+5s)") {
                    viewModel.debugSetAllChallengesEndNow()
                    toast("All challenges end in 5 seconds")
                }
                DebugToggleButton(
                    label = if (minutesDaysOn) "Duration Mode: MINUTES ✓" else "Duration Mode: DAYS",
                    active = minutesDaysOn
                ) {
                    minutesDaysOn = !minutesDaysOn
                    prefs.edit().putBoolean("debug_use_minutes_as_days", minutesDaysOn).apply()
                    toast(if (minutesDaysOn) "Duration mode: MINUTES" else "Duration mode: DAYS")
                }
                DebugToggleButton(
                    label = if (hardMin1On) "Hard Mode Min: 1 DAY ✓" else "Hard Mode Min: 14 DAYS",
                    active = hardMin1On
                ) {
                    hardMin1On = !hardMin1On
                    prefs.edit().putBoolean("debug_hard_mode_min_1", hardMin1On).apply()
                    toast(if (hardMin1On) "Hard Mode min: 1 DAY (debug)" else "Hard Mode min: 14 DAYS (normal)")
                }
                DebugButton("Fix stale challenges (endDate < 1 day)") {
                    viewModel.debugFixStaleChallenges { msg -> toast(msg) }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 4: Budget Testing ─────────────────────────────────
                DebugSectionHeader("BUDGET TESTING")
                DebugButton("Reset Budget Today (all challenges)") {
                    viewModel.debugResetBudgetToday()
                    toast("Budget reset for all challenges")
                }
                DebugButton("Exhaust Budget Now (all DAILY_BUDGET)") {
                    viewModel.debugExhaustBudgetNow()
                    toast("Budget exhausted — open blocked app to see overlay")
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 5: Session / Opens Testing ───────────────────────
                DebugSectionHeader("SESSION / OPENS TESTING")
                DebugButton("Reset Opens Today (all challenges)") {
                    viewModel.debugResetOpensToday()
                    toast("Opens reset for today")
                }
                DebugButton("Max Opens Now (all SESSION_LIMIT)") {
                    viewModel.debugMaxOpensNow()
                    toast("Opens maxed out — open blocked app to see limit overlay")
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 6: Group Challenge Testing ───────────────────────
                DebugSectionHeader("GROUP CHALLENGE TESTING")
                DebugButton("Force Complete Group Challenge") {
                    showCompleteGroupDialog = true
                }
                DebugButton("Force Fail Me in Group Challenge") {
                    showFailGroupDialog = true
                }
                DebugButton("Set Group Challenge End = Now (+5s)") {
                    showSetEndGroupDialog = true
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 7: Stripe Testing ─────────────────────────────────
                DebugSectionHeader("STRIPE TESTING")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DebugOrange.copy(alpha = 0.08f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Test card: 4242 4242 4242 4242",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = DebugOrange
                        )
                        Text(
                            "Expiry: 12/34   CVC: 123",
                            style = MaterialTheme.typography.bodySmall,
                            color = DebugOrange
                        )
                        Text(
                            "Use for all payment tests",
                            style = MaterialTheme.typography.labelSmall,
                            color = DebugOrange.copy(alpha = 0.7f)
                        )
                    }
                }
                DebugButton("Show Stripe Dashboard") {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://dashboard.stripe.com/test/payments"))
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 8: Room Database ──────────────────────────────────
                DebugSectionHeader("ROOM DATABASE")
                DebugButton("Show Today's DailyLogs") {
                    viewModel.debugLoadDailyLogsToday()
                    showLogsDialog = true
                }
                DebugButton("Clear All DailyLogs Today") {
                    showClearLogsConfirm = true
                }
                DebugButton("Show Active Challenges (Room)") {
                    viewModel.debugLoadActiveChallenges()
                    showChallengesDialog = true
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 9: Permissions ────────────────────────────────────
                DebugSectionHeader("PERMISSIONS")
                DebugButton("Check All Permissions") {
                    val overlay = Settings.canDrawOverlays(context)
                    val accessibility = isAccessibilityEnabled()
                    val usageStats = isUsageStatsGranted()
                    permissionsText = """Overlay: ${if (overlay) "✅" else "❌"}
Accessibility: ${if (accessibility) "✅" else "❌"}
Usage Stats: ${if (usageStats) "✅" else "❌"}""".trimIndent()
                    showPermissionsDialog = true
                }
                DebugButton("Simulate Permission Lost") {
                    prefs.edit()
                        .putLong("permission_lost_at", System.currentTimeMillis() - 2 * 3_600_000L)
                        .apply()
                    toast("Permission lost timer set to 2h ago — check notifications")
                }
                DebugButton("Reset Permission Lost Timer") {
                    prefs.edit().remove("permission_lost_at").apply()
                    toast("Permission lost timer cleared")
                }

                Text(
                    "Quick links for re-enabling permissions between test runs",
                    fontSize = 11.sp,
                    color = Color(0xFFE65100).copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF6D00)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFE65100)
                    )
                ) {
                    Text("Open Accessibility Settings ♿", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF6D00)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFE65100)
                    )
                ) {
                    Text("Open Overlay Permission Settings 🔲", fontSize = 13.sp)
                }
                if (Build.MANUFACTURER.lowercase() == "huawei") {
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent()
                                intent.component = ComponentName(
                                    "com.huawei.systemmanager",
                                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF6D00)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFE65100)
                        )
                    ) {
                        Text("Huawei: Open Protected Apps 🔋", fontSize = 13.sp)
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 10b: Permission Violation Tests ───────────────────
                DebugSectionHeader("PERMISSION VIOLATION TESTS")
                DebugButton("Simulate Permission Loss (Firestore)") {
                    viewModel.debugSimulatePermissionLossFirestore { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                DebugButton("Simulate Usage Violation") {
                    viewModel.debugSimulateUsageViolation { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                DebugButton("Check Root Status") {
                    viewModel.debugCheckRootStatus { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                DebugButton("Force CF Permission Check") {
                    viewModel.debugForceCheckPermissionViolations { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                DebugButton("Run Reconciliation Now") {
                    viewModel.debugRunReconciliation { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                DebugButton("Reset Permission Status (Firestore)") {
                    viewModel.debugResetPermissionStatusFirestore { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 10: Adult Domain Stats ────────────────────────────
                DebugSectionHeader("ADULT DOMAIN STATS")

                val (domainCount, domainSource) = remember(Unit) {
                    viewModel.debugGetAdultDomainStats()
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DebugOrange.copy(alpha = 0.08f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Domains loaded: $domainCount",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = DebugOrange
                        )
                        Text(
                            "Source: $domainSource",
                            style = MaterialTheme.typography.bodySmall,
                            color = DebugOrange
                        )
                    }
                }

                DebugButton("Force update now") {
                    viewModel.debugTriggerAdultDomainsUpdate(context) { msg ->
                        toast(msg)
                    }
                }

                // Test domain input
                OutlinedTextField(
                    value = adultDomainTestInput,
                    onValueChange = {
                        adultDomainTestInput = it
                        adultDomainTestResult = null
                    },
                    label = { Text("Test domain (e.g. pornhub.com)", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingIcon = {
                        TextButton(onClick = {
                            val domain = adultDomainTestInput.trim()
                            if (domain.isNotEmpty()) {
                                adultDomainTestResult = if (viewModel.debugTestAdultDomain(domain))
                                    "🔴 BLOCKED"
                                else
                                    "🟢 ALLOWED"
                            }
                        }) {
                            Text("Test", color = DebugOrange, fontSize = 12.sp)
                        }
                    }
                )
                adultDomainTestResult?.let { result ->
                    Text(
                        result,
                        fontWeight = FontWeight.Bold,
                        color = if (result.startsWith("🔴")) Color(0xFFFF3B30) else Color(0xFF00C853),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = DebugOrangeBorder.copy(alpha = 0.2f)
                )

                // ── SECTION 11: Sentry Crash Reporting ────────────────────────
                DebugSectionHeader("SENTRY")
                DebugButton("Trigger Test Crash") {
                    // Tag so beforeSend lets this through even in debug builds.
                    Sentry.setTag("test_crash", "true")
                    throw RuntimeException("Sentry test crash")
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
