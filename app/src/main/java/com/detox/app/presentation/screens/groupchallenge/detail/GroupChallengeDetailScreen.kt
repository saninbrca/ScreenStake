package com.detox.app.presentation.screens.groupchallenge.detail

import android.content.Intent
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Summary card ────────────────────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = gc.appDisplayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        StatusBadge(gc.status)
                    }

                    // Code row with share button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = gc.code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(
                                6f, androidx.compose.ui.unit.TextUnitType.Sp
                            )
                        )
                        IconButton(onClick = {
                            val shareText = context.getString(
                                R.string.group_create_share_text,
                                gc.code,
                                gc.appDisplayName,
                                gc.durationDays,
                                gc.buyInCents / 100
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.group_create_share_chooser)))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.group_detail_share_code))
                        }
                    }
                    Text(
                        text = stringResource(R.string.group_detail_code_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    val limitSummary = when (gc.limitType) {
                        LimitType.TIME -> "Max ${gc.limitValueMinutes} min/day"
                        LimitType.SESSIONS -> "Max ${gc.limitValueSessions} opens/day, ${gc.sessionDurationMinutes} min each"
                        LimitType.TIME_BUDGET -> "Budget: ${gc.limitValueMinutes} min/day"
                        LimitType.TIME_WINDOW -> "Time window only"
                    }
                    Text(text = limitSummary, style = MaterialTheme.typography.bodyMedium)

                    val daysLeft = TimeUnit.MILLISECONDS.toDays(gc.endDate - System.currentTimeMillis())
                    val dateInfo = when {
                        gc.status == GroupChallengeStatus.ACTIVE && daysLeft > 0 -> "$daysLeft days remaining"
                        gc.status == GroupChallengeStatus.WAITING -> {
                            val diffMs = gc.startDate - System.currentTimeMillis()
                            if (diffMs > 0) {
                                val d = TimeUnit.MILLISECONDS.toDays(diffMs)
                                val h = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
                                "Starts in ${d}d ${h}h"
                            } else "Starts ${sdf.format(Date(gc.startDate))}"
                        }
                        else -> "Ended ${sdf.format(Date(gc.endDate))}"
                    }
                    Text(text = dateInfo, style = MaterialTheme.typography.bodyMedium)

                    // Total pot
                    val totalPotEuros = gc.participants.size * gc.buyInCents / 100
                    Text(
                        text = stringResource(R.string.group_detail_total_pot, totalPotEuros),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (gc.bonusEnabled) {
                        Text(
                            text = "🏆 Bonus for best performer (10% of pot)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Waiting state: participant count + needs info
                    if (gc.status == GroupChallengeStatus.WAITING) {
                        val joined = gc.participants.size
                        val max = gc.maxParticipants
                        Text(
                            text = "$joined/$max joined${if (joined < 2) " — needs at least 2 to start" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (joined < 2) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Result summary (COMPLETED / CANCELLED) ──────────────────────────────
        if (gc.status == GroupChallengeStatus.COMPLETED || gc.status == GroupChallengeStatus.CANCELLED) {
            item {
                ResultSummaryCard(gc)
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

        // Sort: active/success first by performance, failed at the bottom
        val sorted = gc.participants
            .sortedWith(
                compareBy<Participant> { it.status == ParticipantStatus.FAILED }
                    .thenBy { it.opensToday }
                    .thenBy { it.timeUsedMinutes }
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
private fun ResultSummaryCard(gc: GroupChallenge) {
    val failedCount = gc.participants.count { it.status == ParticipantStatus.FAILED }
    val succeededCount = gc.participants.count { it.status == ParticipantStatus.SUCCESS }
    val potCents = failedCount * gc.buyInCents

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                gc.status == GroupChallengeStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                failedCount == 0 -> MaterialTheme.colorScheme.tertiaryContainer
                succeededCount == 0 -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = when {
                    gc.status == GroupChallengeStatus.CANCELLED -> "⚠️ Challenge Cancelled"
                    failedCount == 0 -> "🎉 Everyone Succeeded!"
                    succeededCount == 0 -> "💸 Everyone Failed"
                    else -> "🏁 Challenge Complete"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            when {
                gc.status == GroupChallengeStatus.CANCELLED ->
                    Text("Not enough players joined. All buy-ins refunded.", style = MaterialTheme.typography.bodyMedium)
                failedCount == 0 ->
                    Text("All ${succeededCount} participants stayed within their limits. Full refunds incoming!", style = MaterialTheme.typography.bodyMedium)
                succeededCount == 0 ->
                    Text("All ${failedCount} participants exceeded their limits. All buy-ins captured.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("$succeededCount succeeded · $failedCount eliminated", style = MaterialTheme.typography.bodyMedium)
                    if (potCents > 0) {
                        Text(
                            text = "Pot of €${potCents / 100} from failed participants distributed to winners",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
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
            text = if (isFailed) "❌" else if (isSuccess && rank == 1) "🥇" else "#$rank",
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
