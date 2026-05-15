package com.detox.app.presentation.screens.history

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.presentation.screens.profile.HistoryItem
import com.detox.app.presentation.screens.profile.GroupHistoryRow
import com.detox.app.presentation.screens.profile.SoloHistoryRow
import kotlinx.coroutines.flow.collectLatest

enum class HistoryFilter {
    ALL, SOLO, GROUP, WON, FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onGroupChallengeClick: (String) -> Unit = {},
    onSoloChallengeClick: (String) -> Unit = {},
    onRedemptionStarted: () -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val allItems by viewModel.historyItems.collectAsStateWithLifecycle()
    var activeFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var pendingRedemptionChallenge by remember { mutableStateOf<ChallengeEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.redemptionStarted.collectLatest { onRedemptionStarted() }
    }

    val filtered = remember(allItems, activeFilter) {
        when (activeFilter) {
            HistoryFilter.ALL -> allItems
            HistoryFilter.SOLO -> allItems.filterIsInstance<HistoryItem.Solo>()
            HistoryFilter.GROUP -> allItems.filterIsInstance<HistoryItem.Group>()
            HistoryFilter.WON -> allItems.filter { item ->
                when (item) {
                    is HistoryItem.Solo -> item.entity.status == "completed"
                    is HistoryItem.Group -> item.myResult == "won"
                }
            }
            HistoryFilter.FAILED -> allItems.filter { item ->
                when (item) {
                    is HistoryItem.Solo -> item.entity.status == "failed"
                    is HistoryItem.Group -> item.myResult == "eliminated"
                }
            }
        }
    }

    pendingRedemptionChallenge?.let { challenge ->
        RedemptionConfirmSheet(
            challenge = challenge,
            onDismiss = { pendingRedemptionChallenge = null },
            onConfirm = {
                viewModel.startRedemption(challenge.id)
                pendingRedemptionChallenge = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Filter chips ───────────────────────────────────────────────────
            val filters = listOf(
                HistoryFilter.ALL to stringResource(R.string.history_filter_all),
                HistoryFilter.SOLO to stringResource(R.string.history_filter_solo),
                HistoryFilter.GROUP to stringResource(R.string.history_filter_group),
                HistoryFilter.WON to stringResource(R.string.history_filter_won),
                HistoryFilter.FAILED to stringResource(R.string.history_filter_failed),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { (filter, label) ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(label) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.profile_no_recent_activity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { item ->
                        when (item) {
                            is HistoryItem.Solo -> "solo_${item.entity.id}"
                            is HistoryItem.Group -> "group_${item.entity.groupId}"
                        }
                    }) { item ->
                        when (item) {
                            is HistoryItem.Solo -> {
                                val now = System.currentTimeMillis()
                                val entity = item.entity
                                val redemptionAvailable = entity.status == "failed" &&
                                    entity.mode == "hard" &&
                                    entity.redemptionEligible == 1 &&
                                    entity.redemptionChallengeId == null &&
                                    entity.redemptionShowAfter != null &&
                                    entity.redemptionShowAfter <= now &&
                                    entity.redemptionDeadline != null &&
                                    entity.redemptionDeadline > now &&
                                    entity.isRedemption == 0
                                SoloHistoryRow(
                                    challenge = entity,
                                    onClick = { onSoloChallengeClick(entity.id) },
                                    onStartRedemption = if (redemptionAvailable) {
                                        { pendingRedemptionChallenge = entity }
                                    } else null
                                )
                            }
                            is HistoryItem.Group -> GroupHistoryRow(
                                item = item,
                                onClick = { onGroupChallengeClick(item.entity.groupId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedemptionConfirmSheet(
    challenge: ChallengeEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val refundEuros = (challenge.redemptionRefundAmount ?: 0) / 100
    val redemptionDays = challenge.redemptionDays ?: 0
    val redemptionLimit = challenge.redemptionLimit ?: 0
    val isSessionLimit = challenge.limitType == "sessions"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.redemption_confirm_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "App: ${challenge.appDisplayName}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.redemption_confirm_duration, redemptionDays),
                style = MaterialTheme.typography.bodyMedium
            )
            if (isSessionLimit) {
                Text(
                    text = stringResource(R.string.redemption_confirm_limit_sessions, redemptionLimit),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = stringResource(R.string.redemption_confirm_limit_minutes, redemptionLimit),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.redemption_confirm_payment),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.redemption_confirm_win, refundEuros),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.redemption_confirm_lose),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3E0)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ ${stringResource(R.string.redemption_confirm_warning)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE65100),
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.redemption_confirm_cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35)
                    )
                ) {
                    Text(stringResource(R.string.redemption_confirm_start))
                }
            }
        }
    }
}
