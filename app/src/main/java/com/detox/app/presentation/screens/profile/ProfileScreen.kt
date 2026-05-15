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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.detox.app.BuildConfig
import com.detox.app.R
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.service.TrackedAppEventBus
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
    onShowAllHistory: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recentChallenges by viewModel.recentChallenges.collectAsStateWithLifecycle()
    val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
    val payoutState by viewModel.payoutState.collectAsStateWithLifecycle()
    val pendingPayoutCents by viewModel.pendingPayoutCents.collectAsStateWithLifecycle()
    val payoutClaimState by viewModel.payoutClaimState.collectAsStateWithLifecycle()
    val ibanData by viewModel.ibanData.collectAsStateWithLifecycle()
    val ibanSaveState by viewModel.ibanSaveState.collectAsStateWithLifecycle()
    val ibanSetupState by viewModel.ibanSetupState.collectAsStateWithLifecycle()
    val completedPayouts by viewModel.completedPayouts.collectAsStateWithLifecycle()
    var ibanEditing by remember { mutableStateOf(false) }
    var ibanInput by remember { mutableStateOf("") }
    var ibanNameInput by remember { mutableStateOf("") }
    var showIbanSetupSheet by remember { mutableStateOf(false) }
    var ibanSetupInput by remember { mutableStateOf("") }
    var ibanSetupNameInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshOnResume()
        }
    }

    LaunchedEffect(ibanData) {
        if (ibanData != null && !ibanEditing) {
            ibanInput = ibanData!!.iban
            ibanNameInput = ibanData!!.name
        }
    }

    LaunchedEffect(ibanSaveState) {
        when (ibanSaveState) {
            is IbanSaveState.Success -> {
                ibanEditing = false
                snackbarHostState.showSnackbar(context.getString(R.string.profile_iban_saved))
                viewModel.clearIbanSaveState()
            }
            is IbanSaveState.Error -> {
                snackbarHostState.showSnackbar((ibanSaveState as IbanSaveState.Error).message)
                viewModel.clearIbanSaveState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(ibanSetupState) {
        when (val s = ibanSetupState) {
            is IbanSetupState.Success -> {
                showIbanSetupSheet = false
                snackbarHostState.showSnackbar(context.getString(R.string.payout_iban_saved_toast))
                viewModel.clearIbanSetupState()
            }
            is IbanSetupState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearIbanSetupState()
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

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.profile_logout_confirm_title)) },
            text = { Text(stringResource(R.string.profile_logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout(onComplete = onLoggedOut)
                }) {
                    Text(stringResource(R.string.profile_logout_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.profile_logout_confirm_no))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.profile_settings)
                        )
                    }
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

            // Display name
            val nameToShow = viewModel.displayName
                ?: viewModel.userEmail?.substringBefore("@")
                ?: stringResource(R.string.profile_unknown_email)
            Text(
                text = nameToShow,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            // Email (shown separately if display name is available)
            viewModel.userEmail?.let { email ->
                if (viewModel.displayName != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Member since
            viewModel.memberSinceMs?.let { ms ->
                val dateStr = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.profile_member_since, dateStr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Stats Row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    value = "🔥 ${stats.currentStreak}",
                    label = stringResource(R.string.profile_stat_streak_label),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    value = "✅ ${stats.challengesCompleted}",
                    label = stringResource(R.string.profile_stat_completed_label),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    value = "📱 ${stats.appsBlocked}",
                    label = stringResource(R.string.profile_stat_apps_label),
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Verlauf (History) ─────────────────────────────────────────────
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.profile_history_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (historyItems.isNotEmpty()) {
                    TextButton(
                        onClick = onShowAllHistory,
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.history_show_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (historyItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_no_recent_activity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            } else {
                historyItems.forEach { item ->
                    when (item) {
                        is HistoryItem.Solo -> {
                            SoloHistoryRow(
                                challenge = item.entity,
                                onClick = { onSoloChallengeClick(item.entity.id) }
                            )
                        }
                        is HistoryItem.Group -> {
                            GroupHistoryRow(
                                item = item,
                                onClick = { onGroupChallengeClick(item.entity.groupId) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ── 💰 Auszahlung (IBAN) ─────────────────────────────────────────
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.profile_iban_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ibanData != null && !ibanEditing) {
                        Text(
                            text = "IBAN: ${ibanData!!.iban}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.profile_iban_holder_label, ibanData!!.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                ibanInput = ibanData!!.iban
                                ibanNameInput = ibanData!!.name
                                ibanEditing = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(stringResource(R.string.profile_iban_edit_button))
                        }
                    } else {
                        OutlinedTextField(
                            value = ibanInput,
                            onValueChange = { ibanInput = it },
                            label = { Text("IBAN") },
                            placeholder = { Text("AT61 1904 3002 3457 3201") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = ibanNameInput,
                            onValueChange = { ibanNameInput = it },
                            label = { Text(stringResource(R.string.profile_iban_holder_field)) },
                            placeholder = { Text("Max Mustermann") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = { viewModel.saveIban(ibanInput, ibanNameInput) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = ibanInput.isNotBlank() && ibanNameInput.isNotBlank()
                                    && ibanSaveState !is IbanSaveState.Loading
                        ) {
                            if (ibanSaveState is IbanSaveState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.profile_iban_save_button))
                            }
                        }
                    }
                }
            }

            // ── 💰 Auszahlungen ───────────────────────────────────────────────
            if (completedPayouts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.payout_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                completedPayouts.forEach { payout ->
                    PayoutChallengeCard(
                        payout = payout,
                        onIbanSetupClick = { showIbanSetupSheet = true }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // IBAN Setup BottomSheet
            if (showIbanSetupSheet) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showIbanSetupSheet = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.payout_iban_bottom_sheet_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = ibanSetupNameInput,
                            onValueChange = { ibanSetupNameInput = it },
                            label = { Text(stringResource(R.string.payout_iban_holder_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        val ibanValid = ibanSetupInput.replace(" ", "").let {
                            it.uppercase().matches(Regex("AT[0-9]{18}"))
                        }
                        OutlinedTextField(
                            value = ibanSetupInput,
                            onValueChange = { ibanSetupInput = it },
                            label = { Text(stringResource(R.string.payout_iban_field_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = ibanSetupInput.isNotBlank() && !ibanValid
                        )
                        Button(
                            onClick = {
                                viewModel.setupPayoutAccount(
                                    iban = ibanSetupInput.replace(" ", "").uppercase(),
                                    accountHolderName = ibanSetupNameInput
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = ibanValid && ibanSetupNameInput.isNotBlank() &&
                                    ibanSetupState !is IbanSetupState.Loading
                        ) {
                            if (ibanSetupState is IbanSetupState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.payout_iban_save_cta))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            if (BuildConfig.DEBUG) {
                DebugPanel(viewModel = viewModel)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Settings ──────────────────────────────────────────────────────
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(stringResource(R.string.profile_settings))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Log Out ───────────────────────────────────────────────────────
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(stringResource(R.string.profile_logout))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SoloHistoryRow(
    challenge: ChallengeEntity,
    onClick: () -> Unit = {},
    onStartRedemption: (() -> Unit)? = null,
) {
    val isCompleted = challenge.status == "completed"
    val resultLabel = if (isCompleted) stringResource(R.string.history_result_success)
    else stringResource(R.string.history_result_failed)
    val dateStr = SimpleDateFormat("d. MMM yyyy", Locale.getDefault()).format(Date(challenge.endDate))
    val startDate = if (challenge.startDate > 0) challenge.startDate else challenge.createdAt
    val durationDays = ((challenge.endDate - startDate) / 86_400_000).coerceAtLeast(1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = if (challenge.isRedemption != 0) "🔥" else "📱", style = MaterialTheme.typography.bodyLarge)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = challenge.appDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (challenge.isRedemption != 0) "COMEBACK" else challenge.mode.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (challenge.isRedemption != 0) Color(0xFFFF6B35) else MaterialTheme.colorScheme.primary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = resultLabel, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.history_duration_days, durationDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (onStartRedemption != null) {
                val refundEuros = (challenge.redemptionRefundAmount ?: 0) / 100
                val redemptionDays = challenge.redemptionDays ?: 0
                val now = System.currentTimeMillis()
                val daysLeft = challenge.redemptionDeadline
                    ?.let { ((it - now) / 86_400_000L).coerceAtLeast(0) } ?: 0L

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "🔥 ${stringResource(R.string.redemption_history_title)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = androidx.compose.ui.graphics.Color(0xFFE65100)
                            )
                            Text(
                                text = stringResource(R.string.redemption_history_body, refundEuros),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.redemption_history_details, redemptionDays),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.redemption_history_deadline, daysLeft),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onStartRedemption,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF6B35)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.redemption_history_cta),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupHistoryRow(item: HistoryItem.Group, onClick: () -> Unit) {
    val entity = item.entity
    val resultLabel = when (item.myResult) {
        "won" -> stringResource(R.string.history_result_won)
        "eliminated" -> stringResource(R.string.history_result_eliminated)
        "cancelled" -> "⚠️ Abgebrochen"
        else -> stringResource(R.string.history_result_running)
    }
    val dateStr = SimpleDateFormat("d. MMM yyyy", Locale.getDefault()).format(Date(entity.endDate))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "👥", style = MaterialTheme.typography.bodyLarge)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = entity.appDisplayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.totalCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.history_players_won,
                            item.successCount,
                            item.totalCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = resultLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
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

            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

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

            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

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

            // IBAN setup button for pending prize
            if (payout.payoutStatus == "pending_payout" && payout.prizeShareCents > 0) {
                TextButton(
                    onClick = onIbanSetupClick,
                    modifier = androidx.compose.ui.Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = stringResource(R.string.payout_iban_setup_button),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PayoutRow(label: String, statusIcon: String?) {
    Row(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = androidx.compose.ui.Modifier.weight(1f)
        )
        if (statusIcon != null) {
            Text(text = statusIcon, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
                                text = "${log.challengeId.take(8)}…\n" +
                                    "opens=${log.consciousOpens}  " +
                                    "used=${log.budgetUsedMs / 60_000}min  " +
                                    "rem=${log.budgetRemainingMs / 60_000}min",
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
                    } else {
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
                    viewModel.runEvaluationNow()
                    toast("Daily evaluation started")
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
                    permissionsText = "Overlay: ${if (overlay) "✅" else "❌"}\n" +
                        "Accessibility: ${if (accessibility) "✅" else "❌"}\n" +
                        "Usage Stats: ${if (usageStats) "✅" else "❌"}"
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

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
