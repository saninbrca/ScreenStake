package com.detox.app.presentation.screens.groupchallenge.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeDetailScreen(
    onBack: () -> Unit,
    viewModel: GroupChallengeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState is GroupDetailUiState.Success)
                            (uiState as GroupDetailUiState.Success).groupChallenge.appDisplayName
                        else
                            stringResource(R.string.group_detail_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            GroupDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            is GroupDetailUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            is GroupDetailUiState.Success -> {
                GroupDetailContent(
                    gc = state.groupChallenge,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun GroupDetailContent(gc: GroupChallenge, modifier: Modifier = Modifier) {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Summary card ────────────────────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = gc.appDisplayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        StatusBadge(gc.status)
                    }

                    Text(
                        text = "Code: ${gc.code}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val limitSummary = when (gc.limitType) {
                        LimitType.TIME -> "Max ${gc.limitValueMinutes} min/day"
                        LimitType.SESSIONS -> "Max ${gc.limitValueSessions} opens/day"
                        LimitType.TIME_BUDGET -> "Budget: ${gc.limitValueMinutes} min/day"
                    }
                    Text(text = limitSummary, style = MaterialTheme.typography.bodyMedium)

                    val daysLeft = TimeUnit.MILLISECONDS.toDays(gc.endDate - System.currentTimeMillis())
                    val dateInfo = if (gc.status == GroupChallengeStatus.ACTIVE && daysLeft > 0) {
                        "$daysLeft days remaining"
                    } else {
                        "Ends ${sdf.format(Date(gc.endDate))}"
                    }
                    Text(text = dateInfo, style = MaterialTheme.typography.bodyMedium)

                    Text(
                        text = "Buy-in: €${gc.buyInCents / 100} per player",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (gc.bonusEnabled) {
                        Text(
                            text = "🏆 Bonus for best performer (10% of pot)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Waiting state: participant count
                    if (gc.status == GroupChallengeStatus.WAITING) {
                        val joined = gc.participants.size
                        val max = gc.maxParticipants
                        Text(
                            text = "$joined/$max joined${if (joined < 2) " — needs at least 2 to start" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (joined < 2) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val diffMs = gc.startDate - System.currentTimeMillis()
                        if (diffMs > 0) {
                            val d = TimeUnit.MILLISECONDS.toDays(diffMs)
                            val h = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
                            Text(
                                text = "Starts in ${d}d ${h}h",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Leaderboard ─────────────────────────────────────────────────────────
        item {
            Text(
                text = stringResource(R.string.group_detail_leaderboard),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Sort: active participants first by performance, failed at the bottom
        val sorted = gc.participants
            .sortedWith(
                compareBy<Participant> { it.status == ParticipantStatus.FAILED }
                    .thenBy { it.opensToday }
                    .thenByDescending { it.timeUsedMinutes }
            )

        itemsIndexed(sorted) { index, participant ->
            LeaderboardRow(
                rank = index + 1,
                participant = participant
            )
            if (index < sorted.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (gc.participants.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.group_detail_no_participants),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, participant: Participant) {
    val isFailed = participant.status == ParticipantStatus.FAILED
    val isSuccess = participant.status == ParticipantStatus.SUCCESS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Rank
        Text(
            text = if (isFailed) "❌" else "#$rank",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (isFailed) MaterialTheme.colorScheme.error
            else if (rank == 1) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )

        // Name + status
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = participant.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            val statusLabel = when (participant.status) {
                ParticipantStatus.ACTIVE -> "Active ✓"
                ParticipantStatus.FAILED -> "Eliminated"
                ParticipantStatus.SUCCESS -> "Succeeded ✓"
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = when (participant.status) {
                    ParticipantStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    ParticipantStatus.FAILED -> MaterialTheme.colorScheme.error
                    ParticipantStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
                }
            )
        }

        // Stats
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${participant.opensToday} opens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${participant.timeUsedMinutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(status: GroupChallengeStatus) {
    val (label, color) = when (status) {
        GroupChallengeStatus.WAITING -> "Waiting" to MaterialTheme.colorScheme.secondary
        GroupChallengeStatus.ACTIVE -> "LIVE" to MaterialTheme.colorScheme.primary
        GroupChallengeStatus.COMPLETED -> "Completed" to MaterialTheme.colorScheme.tertiary
        GroupChallengeStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.error
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
