package com.detox.app.presentation.screens.groupchallenge.detail

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeDetailScreen(
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToFriendsHub: () -> Unit = {},
    viewModel: GroupChallengeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val startState by viewModel.startState.collectAsStateWithLifecycle()
    val nudgeEvent by viewModel.nudgeEvent.collectAsStateWithLifecycle()
    val winDialogInfo by viewModel.winDialogInfo.collectAsStateWithLifecycle()
    val quitState by viewModel.quitState.collectAsStateWithLifecycle()
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
        val msg = nudgeEvent
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearNudgeEvent()
        }
    }

    LaunchedEffect(quitState) {
        when (val qs = quitState) {
            is QuitState.Success -> {
                viewModel.clearQuitState()
                onNavigateToFriendsHub()
            }
            is QuitState.Error -> {
                snackbarHostState.showSnackbar(qs.message)
                viewModel.clearQuitState()
            }
            else -> Unit
        }
    }

    winDialogInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissWinDialog() },
            title = {
                Text(
                    text = if (info.bonusCents > 0)
                        "🎉 Du hast €${info.bonusCents / 100} gewonnen!"
                    else
                        "🎉 Du hast gewonnen!"
                )
            },
            text = {
                Text(
                    text = if (info.hasIban)
                        "Dein Gewinn wird an deine hinterlegte IBAN überwiesen (1–2 Werktage)."
                    else
                        "Hinterlege deine IBAN um deinen Gewinn zu erhalten."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissWinDialog() }) {
                    Text("OK")
                }
            },
            dismissButton = if (!info.hasIban) {
                {
                    TextButton(onClick = {
                        viewModel.dismissWinDialog()
                        onNavigateToProfile()
                    }) {
                        Text("Jetzt IBAN eingeben")
                    }
                }
            } else null
        )
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
                val myParticipant = state.groupChallenge.participants
                    .find { it.userId == currentUserId }
                val isCurrentUserFailed = myParticipant?.status == ParticipantStatus.FAILED
                GroupDetailContent(
                    gc = state.groupChallenge,
                    currentUserId = currentUserId,
                    myStreak = state.myStreak,
                    // Own progress comes from Room DailyLog (source of truth), not Firestore array.
                    myOpensToday = state.myOpensToday,
                    myTimeUsedMinutes = state.myTimeUsedMinutes,
                    isStarting = startState is StartChallengeState.Loading,
                    isCurrentUserFailed = isCurrentUserFailed,
                    isQuitting = quitState is QuitState.Loading,
                    onStartChallenge = viewModel::startChallenge,
                    onNudge = viewModel::nudgeParticipant,
                    onQuit = viewModel::quitChallenge,
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
    /** Own opens today — sourced from Room DailyLog, not Firestore participants array. */
    myOpensToday: Int = 0,
    /** Own time used today (minutes) — sourced from Room DailyLog. */
    myTimeUsedMinutes: Int = 0,
    isStarting: Boolean,
    isCurrentUserFailed: Boolean = false,
    isQuitting: Boolean = false,
    onStartChallenge: () -> Unit,
    onNudge: (String) -> Unit,
    onQuit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    var showQuitDialog by remember { mutableStateOf(false) }

    val cal2024 = Calendar.getInstance().apply { set(2024, 0, 1) }.timeInMillis
    val endDateValid = gc.endDate > cal2024 && gc.endDate > now
    val dateFmt = SimpleDateFormat("dd. MMM yyyy", Locale.getDefault())
    val startFmt = SimpleDateFormat("dd. MMM", Locale.getDefault())

    val remainingMs = gc.endDate - now
    val remainingDays = remainingMs / (24L * 60 * 60 * 1000)
    val remainingHours = (remainingMs % (24L * 60 * 60 * 1000)) / (60 * 60 * 1000)
    val endTimeStr = remember(gc.endDate) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(gc.endDate))
    }
    val remainingLabel = when {
        remainingMs <= 0 -> "Abgelaufen"
        remainingDays > 1 -> "Noch $remainingDays Tage"
        remainingDays == 1L -> "Endet morgen"
        remainingHours > 0 -> "Endet heute um $endTimeStr Uhr"
        else -> "Abgelaufen"
    }

    LaunchedEffect(gc.endDate) {
        Timber.d("endDate=${gc.endDate} remaining=${remainingDays}d ${remainingHours}h")
    }

    // Sort: ACTIVE participants first (ascending opensToday — fewer opens = leader),
    // then FAILED participants (descending opensToday — most-failed at the very bottom).
    val sorted = gc.participants.sortedWith(Comparator { a, b ->
        val aFailed = a.status == ParticipantStatus.FAILED
        val bFailed = b.status == ParticipantStatus.FAILED
        when {
            aFailed != bFailed -> if (aFailed) 1 else -1
            aFailed -> b.opensToday.compareTo(a.opensToday)
            else -> {
                val byOpens = a.opensToday.compareTo(b.opensToday)
                if (byOpens != 0) byOpens else a.timeUsedMinutes.compareTo(b.timeUsedMinutes)
            }
        }
    })
    Timber.d("Leaderboard sorted: ${sorted.map { "${it.displayName}:${it.status}" }}")
    val myParticipant = gc.participants.find { it.userId == currentUserId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Failed user banner ──────────────────────────────────────────────────
        if (isCurrentUserFailed) {
            item { FailedUserBanner(gc) }
        }

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

                    // Blocked websites
                    if (gc.blockedDomains.isNotEmpty()) {
                        Text(
                            text = "🌐 Blocked: ${gc.blockedDomains.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // End date + remaining time
                    if (endDateValid) {
                        Text(
                            text = stringResource(R.string.group_detail_end_date, dateFmt.format(Date(gc.endDate))),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (gc.status == GroupChallengeStatus.ACTIVE) {
                            Text(
                                text = remainingLabel,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
            item {
                ResultSummaryCard(
                    gc = gc,
                    currentUserId = currentUserId,
                    onConnectBank = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://detox-33208.web.app/connect"))
                        )
                    }
                )
            }
            if (gc.status == GroupChallengeStatus.COMPLETED) {
                val myParticipantResult = gc.participants.find { it.userId == currentUserId }
                if (myParticipantResult != null && myParticipantResult.status != ParticipantStatus.FAILED) {
                    item { PayoutResultCard(participant = myParticipantResult, gc = gc, onConnectBank = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://detox-33208.web.app/connect"))
                        )
                    }) }
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
                isCurrentUserFailed = isCurrentUserFailed,
                onNudge = { onNudge(participant.userId) }
            )
        }

        // ── My Status ─────────────────────────────────────────────────────────
        if (myParticipant != null && gc.status == GroupChallengeStatus.ACTIVE) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Own progress reads from Room DailyLog (myOpensToday / myTimeUsedMinutes),
                // NOT from myParticipant.opensToday / timeUsedMinutes (Firestore — can lag).
                MyStatusCard(
                    gc = gc,
                    streak = myStreak,
                    myOpensToday = myOpensToday,
                    myTimeUsedMinutes = myTimeUsedMinutes,
                )
            }
        }

        // ── Aufgeben button — only for ACTIVE participant in ACTIVE challenge ──
        if (gc.status == GroupChallengeStatus.ACTIVE && !isCurrentUserFailed) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showQuitDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isQuitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    if (isQuitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        Text(stringResource(R.string.group_detail_quit_button))
                    }
                }
            }
        }
    }

    // ── Aufgeben confirmation dialog ───────────────────────────────────────
    if (showQuitDialog) {
        val amountEuros = gc.buyInCents / 100
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text(stringResource(R.string.group_detail_quit_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.group_detail_quit_dialog_message, amountEuros)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showQuitDialog = false
                        onQuit()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.group_detail_quit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitDialog = false }) {
                    Text(stringResource(R.string.group_detail_quit_cancel))
                }
            }
        )
    }
}

@Composable
private fun LeaderboardCard(
    rank: Int,
    participant: Participant,
    gc: GroupChallenge,
    isCurrentUser: Boolean,
    isCurrentUserFailed: Boolean = false,
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

    val progress = when (gc.limitType) {
        LimitType.SESSIONS -> {
            val limit = gc.limitValueSessions ?: 0
            if (limit > 0) (participant.opensToday.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
        }
        else -> {
            val limit = gc.limitValueMinutes ?: 0
            if (limit > 0) (participant.timeUsedMinutes.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
        }
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
                    when (gc.limitType) {
                        LimitType.SESSIONS -> Text(
                            text = stringResource(R.string.group_detail_opens_label, participant.opensToday, gc.limitValueSessions ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            text = stringResource(R.string.group_detail_time_label, participant.timeUsedMinutes, gc.limitValueMinutes ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Nerv ihn! button — only for others while challenge is active and current user has not failed
            if (!isCurrentUser && !isCurrentUserFailed && gc.status == GroupChallengeStatus.ACTIVE
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

/**
 * Shows the current user's own status card.
 *
 * [myOpensToday] and [myTimeUsedMinutes] MUST come from Room DailyLog (via ViewModel),
 * NOT from the Firestore participants array — the array can lag by several seconds.
 */
@Composable
private fun MyStatusCard(
    gc: GroupChallenge,
    streak: Int,
    myOpensToday: Int,
    myTimeUsedMinutes: Int,
) {
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
                    // myOpensToday from Room DailyLog — source of truth
                    text = stringResource(R.string.group_detail_my_opens, myOpensToday, maxOpens),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                // myTimeUsedMinutes from Room DailyLog — source of truth
                text = stringResource(R.string.group_detail_my_time, myTimeUsedMinutes),
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
private fun ResultSummaryCard(
    gc: GroupChallenge,
    currentUserId: String?,
    onConnectBank: () -> Unit,
) {
    val failedCount = gc.participants.count { it.status == ParticipantStatus.FAILED }
    val succeededCount = gc.participants.count { it.status == ParticipantStatus.SUCCESS }
    val myParticipant = gc.participants.find { it.userId == currentUserId }
    val iWon = myParticipant?.status == ParticipantStatus.SUCCESS
    val iLost = myParticipant?.status == ParticipantStatus.FAILED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                gc.status == GroupChallengeStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                iLost -> MaterialTheme.colorScheme.errorContainer
                failedCount == 0 -> MaterialTheme.colorScheme.tertiaryContainer
                succeededCount == 0 -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = when {
                    gc.status == GroupChallengeStatus.CANCELLED -> "⚠️ Challenge Abgebrochen"
                    iLost -> "😔 Du hast verloren"
                    iWon -> "🎉 Du hast gewonnen!"
                    failedCount == 0 -> "🎉 Alle haben gewonnen!"
                    succeededCount == 0 -> "💸 Alle haben verloren"
                    else -> "🏁 Challenge beendet"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            when {
                gc.status == GroupChallengeStatus.CANCELLED ->
                    Text("Zu wenige Spieler. Alle Einsätze werden zurückgebucht.", style = MaterialTheme.typography.bodyMedium)
                iLost -> {
                    val lostCents = myParticipant?.amountCents ?: gc.buyInCents
                    Text(
                        "€${lostCents / 100} wurden eingezogen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                failedCount == 0 ->
                    Text("Alle $succeededCount Teilnehmer im Limit. Einsätze werden zurückgebucht!", style = MaterialTheme.typography.bodyMedium)
                succeededCount == 0 ->
                    Text("Alle $failedCount Teilnehmer haben das Limit überschritten.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("$succeededCount gewonnen · $failedCount ausgeschieden", style = MaterialTheme.typography.bodyMedium)
                    if (gc.perWinnerBonus > 0) {
                        Text(
                            "Bonus pro Gewinner: €${gc.perWinnerBonus / 100}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PayoutResultCard(
    participant: Participant,
    gc: GroupChallenge,
    onConnectBank: () -> Unit,
) {
    val bonus = gc.perWinnerBonus
    val buyIn = participant.amountCents
    val isPending = participant.payoutStatus == "pending_payout"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (bonus > 0) {
                Text(
                    text = "🎉 Du hast gewonnen! +€${bonus / 100} Bonus",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Dein Einsatz (€${buyIn / 100}) wird zurückgebucht",
                style = MaterialTheme.typography.bodyMedium
            )
            if (bonus > 0) {
                if (isPending) {
                    Text(
                        text = "⚠️ Verbinde dein Bankkonto um €${bonus / 100} zu erhalten",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onConnectBank, modifier = Modifier.fillMaxWidth()) {
                        Text("Bankkonto verbinden")
                    }
                } else {
                    Text(
                        text = "Bonus (€${bonus / 100}) wird auf dein Bankkonto überwiesen",
                        style = MaterialTheme.typography.bodyMedium
                    )
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

@Composable
private fun FailedUserBanner(gc: GroupChallenge) {
    val dateFmt = SimpleDateFormat("dd. MMM yyyy", Locale.getDefault())
    val endDateValid = gc.endDate > System.currentTimeMillis()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.group_detail_failed_banner),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (endDateValid) {
                Text(
                    text = stringResource(
                        R.string.group_detail_challenge_ends_on,
                        dateFmt.format(Date(gc.endDate))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
            if (gc.status == GroupChallengeStatus.COMPLETED) {
                val winner = gc.participants
                    .filter { it.status == ParticipantStatus.SUCCESS }
                    .minByOrNull { it.timeUsedMinutes }
                if (winner != null) {
                    val name = winner.displayName.takeIf { it.isNotBlank() }
                        ?: winner.userId.substringBefore('@')
                    val pot = gc.participants.count { it.status == ParticipantStatus.FAILED } * gc.buyInCents / 100
                    Text(
                        text = stringResource(R.string.group_detail_winner, name),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (pot > 0) {
                        Text(
                            text = stringResource(R.string.group_detail_total_prize, pot),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
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
