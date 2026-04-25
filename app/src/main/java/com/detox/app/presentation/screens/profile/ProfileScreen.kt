package com.detox.app.presentation.screens.profile

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.data.local.db.entity.ChallengeEntity
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
    var ibanEditing by remember { mutableStateOf(false) }
    var ibanInput by remember { mutableStateOf("") }
    var ibanNameInput by remember { mutableStateOf("") }
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
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.profile_payout_title),
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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (payoutState) {
                        is PayoutState.Loading -> {
                            Text(
                                text = stringResource(R.string.profile_payout_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is PayoutState.Active -> {
                            Text(
                                text = stringResource(R.string.profile_payout_connected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.profile_payout_active),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (pendingPayoutCents > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.profile_payout_pending,
                                        pendingPayoutCents / 100
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Button(
                                    onClick = { viewModel.claimPendingPayouts() },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = payoutClaimState !is PayoutClaimState.Loading
                                ) {
                                    if (payoutClaimState is PayoutClaimState.Loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Text(stringResource(R.string.profile_payout_claim_button))
                                    }
                                }
                            }
                        }
                        is PayoutState.NotConnected, is PayoutState.OnboardingIncomplete -> {
                            Text(
                                text = stringResource(R.string.profile_payout_cta_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    viewModel.startOnboarding(
                                        onUrl = { url ->
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            )
                                        },
                                        onError = {}
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.profile_payout_connect_button))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

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
internal fun SoloHistoryRow(challenge: ChallengeEntity, onClick: () -> Unit = {}) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "📱", style = MaterialTheme.typography.bodyLarge)
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
                    text = challenge.mode.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
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
