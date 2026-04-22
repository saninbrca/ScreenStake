package com.detox.app.presentation.screens.groupchallenge.detail

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Calendar
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
    val startState by viewModel.startState.collectAsStateWithLifecycle()
    val nudgeEvent by viewModel.nudgeEvent.collectAsStateWithLifecycle()
    val currentUserId = viewModel.currentUserId
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current

    LaunchedEffect(startState) {
        when (startState) {
            is StartChallengeState.Success -> {
                Toast.makeText(context, "Challenge started! App is now blocked.", Toast.LENGTH_LONG).show()
                viewModel.clearStartError()
            }
            is StartChallengeState.Error -> {
                snackbarHostState.showSnackbar((startState as StartChallengeState.Error).message)
                viewModel.clearStartError()
            }
            else -> Unit
        }
    }

    LaunchedEffect(nudgeEvent) {
        if (nudgeEvent != null) {
            snackbarHostState.showSnackbar(context.getString(R.string.group_detail_nudge_sent))
            viewModel.clearNudgeEvent()
        }
    }

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is GroupDetailUiState.Success -> {
                GroupDetailContent(
                    gc = state.groupChallenge,
                    currentUserId = currentUserId,
                    myStreak = state.myStreak,
                    isStarting = startState is StartChallengeState.Loading,
                    onStartChallenge = viewModel::startChallenge,
                    onNudge = viewModel::nudgeParticipant,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun GroupDetailContent(
    gc: GroupChallenge,
    currentUserId: String?,
    myStreak: Int,
    isStarting: Boolean,
    onStartChallenge: () -> Unit,
    onNudge: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()

    val cal2024 = Calendar.getInstance().apply { set(2024, 0, 1) }.timeInMillis
    val endDateValid = gc.endDate > cal2024 && gc.endDate > now
    val dateFmt = SimpleDateFormat("dd. MMM yyyy", Locale.getDefault())
    val startFmt = SimpleDateFormat("dd. MMM", Locale.getDefault())

    val sorted = gc.participants.sortedWith(
        compareBy<Participant> { it.status == ParticipantStatus.FAILED }
            .thenBy { it.opensToday }
            .thenBy { it.timeUsedMinutes }
    )
    val myParticipant = gc.participants.find { it.userId == currentUserId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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

                    // Code row — only visible when waiting; when active show participant count
                    when (gc.status) {
                        GroupChallengeStatus.WAITING -> {
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
                                        gc.code, gc.appDisplayName, gc.durationDays, gc.buyInCents / 100
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
                        }
                        GroupChallengeStatus.ACTIVE -> {
                            val activeCount = gc.participants.count { it.status == ParticipantStatus.ACTIVE }
                            Text(
                                text = stringResource(R.string.group_detail_active_participants, activeCount, gc.maxParticipants),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        else -> Unit
                    }

                    HorizontalDivider()

                    // Limit summary
                    val limitSummary = when (gc.limitType) {
                        LimitType.TIME -> "Max ${gc.limitValueMinutes} min/day"
                        LimitType.SESSIONS -> "Max ${gc.limitValueSessions} opens/day, ${gc.sessionDurationMinutes} min each"
                        LimitType.TIME_BUDGET -> "Budget: ${gc.limitValueMinutes} min/day"
                        LimitType.TIME_WINDOW -> "Time window only"
                    }
                    Text(text = limitSummary, style = MaterialTheme.typography.bodyMedium)

                    // End date (computed from startDate + durationDays)
                    if (endDateValid) {
                        Text(
                            text = stringResource(R.string.group_detail_end_date, dateFmt.format(Date(gc.endDate))),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.group_detail_duration_fallback, gc.durationDays),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Start date info (replaces "Started manually" text)
                    if (gc.status == GroupChallengeStatus.WAITING) {
                        val joined = gc.participants.size
                        val max = gc.maxParticipants
                        Text(
                            text = "$joined/$max players joined",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (gc.creatorUserId == currentUserId) {
                            val canStart = joined >= 2
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onStartChallenge,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = canStart && !isStarting,
                            ) {
                                if (isStarting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text("Start Challenge 🚀")
                                }
                            }
                            if (!canStart) {
                                Text(
                                    text = "Need at least 2 players to start",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        val startLabel = if (isToday(gc.startDate)) {
                            stringResource(R.string.group_detail_started_today)
                        } else if (gc.startDate > 0L) {
                            stringResource(R.string.group_detail_started_on, startFmt.format(Date(gc.startDate)))
                        } else null
                        startLabel?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Total pot
                    val totalPotEuros = gc.participants.size * gc.buyInCents / 100
                    if (totalPotEuros > 0) {
                        Text(
                            text = stringResource(R.string.group_detail_total_pot, totalPotEuros),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (gc.bonusEnabled) {
                        Text(
                            text = "🏆 Bonus for best performer (10% of pot)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ── Result summary (COMPLETED / CANCELLED) ──────────────────────────────
        if (gc.status == GroupChallengeStatus.COMPLETED || gc.status == GroupChallengeStatus.CANCELLED) {
            item { ResultSummaryCard(gc) }
        }

        // ── Leaderboard ─────────────────────────────────────────────────────────
        item {
            Text(
                text = stringResource(R.string.group_detail_leaderboard),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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

        itemsIndexed(sorted) { index, participant ->
            LeaderboardCard(
                rank = index + 1,
                participant = participant,
                gc = gc,
                isCurrentUser = participant.userId == currentUserId,
                onNudge = { onNudge(participant.userId) }
            )
        }

        // ── My Status ─────────────────────────────────────────────────────────
        if (myParticipant != null && gc.status == GroupChallengeStatus.ACTIVE) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                MyStatusCard(participant = myParticipant, gc = gc, streak = myStreak)
            }
        }
    }
}

@Composable
private fun LeaderboardCard(
    rank: Int,
    participant: Participant,
    gc: GroupChallenge,
    isCurrentUser: Boolean,
    onNudge: () -> Unit,
) {
    val rankEmoji = when (rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> "#$rank"
    }
    val displayName = participant.displayName
        .takeIf { it.isNotBlank() }
        ?: participant.userId.substringBefore('@').ifBlank { participant.userId }

    val limitMax = when (gc.limitType) {
        LimitType.SESSIONS -> (gc.limitValueSessions ?: 1).toFloat()
        else -> gc.limitValueMinutes.toFloat()
    }
    val progress = when (gc.limitType) {
        LimitType.SESSIONS -> if (limitMax > 0) participant.opensToday / limitMax else 0f
        else -> if (limitMax > 0) participant.timeUsedMinutes / limitMax else 0f
    }.coerceIn(0f, 1f)

    val progressColor = when (participant.status) {
        ParticipantStatus.FAILED -> MaterialTheme.colorScheme.error
        ParticipantStatus.SUCCESS -> Color(0xFF2E7D32)
        ParticipantStatus.ACTIVE -> if (progress > 0.8f) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentUser)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Rank medal
                Text(
                    text = rankEmoji,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp)
                )

                // Name + status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName + if (isCurrentUser) " (Du)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ParticipantStatusBadge(participant.status)
                }

                // Stats
                Column(horizontalAlignment = Alignment.End) {
                    val opensLabel = if (gc.limitType == LimitType.SESSIONS) {
                        stringResource(R.string.group_detail_opens_label, participant.opensToday, gc.limitValueSessions ?: 0)
                    } else {
                        "${participant.opensToday} opens"
                    }
                    Text(text = opensLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = stringResource(R.string.group_detail_time_label, participant.timeUsedMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Nerv ihn! button — only for others while challenge is active
            if (!isCurrentUser && gc.status == GroupChallengeStatus.ACTIVE
                && participant.status == ParticipantStatus.ACTIVE) {
                Button(
                    onClick = onNudge,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(text = stringResource(R.string.group_detail_nudge), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun MyStatusCard(participant: Participant, gc: GroupChallenge, streak: Int) {
    val maxOpens = gc.limitValueSessions ?: 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.group_detail_my_status_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            if (gc.limitType == LimitType.SESSIONS && maxOpens > 0) {
                Text(
                    text = stringResource(R.string.group_detail_my_opens, participant.opensToday, maxOpens),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = stringResource(R.string.group_detail_my_time, participant.timeUsedMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (streak > 0) {
                Text(
                    text = stringResource(R.string.group_detail_my_streak, streak),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ParticipantStatusBadge(status: ParticipantStatus) {
    val (label, color) = when (status) {
        ParticipantStatus.ACTIVE -> "Active ✓" to Color(0xFF2E7D32)
        ParticipantStatus.FAILED -> "Failed ✗" to MaterialTheme.colorScheme.error
        ParticipantStatus.SUCCESS -> "Succeeded ✓" to Color(0xFF2E7D32)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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
                    Text("All $succeededCount participants stayed within their limits. Full refunds incoming!", style = MaterialTheme.typography.bodyMedium)
                succeededCount == 0 ->
                    Text("All $failedCount participants exceeded their limits. All buy-ins captured.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("$succeededCount succeeded · $failedCount eliminated", style = MaterialTheme.typography.bodyMedium)
                    if (potCents > 0) {
                        Text("Pot of €${potCents / 100} from failed participants distributed to winners", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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

private fun isToday(timestampMs: Long): Boolean {
    if (timestampMs <= 0L) return false
    val cal = Calendar.getInstance()
    val today = cal.clone() as Calendar
    cal.timeInMillis = timestampMs
    return cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
            && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
}
