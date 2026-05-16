package com.detox.app.presentation.screens.groupchallenge.detail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.presentation.screens.activechallenge.DetoxCard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import timber.log.Timber

private val BgGray = Color(0xFFF2F2F7)
private val CardWhite = Color.White
private val CardBorder = Color(0x0F000000)
private val TextSecondary = Color(0xFF8E8E93)
private val AccentGreen = Color(0xFF00C853)
private val AbandonRed = Color(0xFFFF3B30)
private val DividerColor = Color(0xFFF2F2F7)

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

    // Win dialog (business logic unchanged)
    winDialogInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissWinDialog() },
            title = {
                Text(
                    text = if (info.bonusCents > 0)
                        "🎉 Du hast €${info.bonusCents / 100} gewonnen!"
                    else "🎉 Du hast gewonnen!"
                )
            },
            text = {
                Text(
                    text = if (info.hasIban)
                        "Dein Gewinn wird an deine hinterlegte IBAN überwiesen (1–2 Werktage)."
                    else "Hinterlege deine IBAN um deinen Gewinn zu erhalten."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissWinDialog() }) { Text("OK") }
            },
            dismissButton = if (!info.hasIban) {
                {
                    TextButton(onClick = {
                        viewModel.dismissWinDialog()
                        onNavigateToProfile()
                    }) { Text("Jetzt IBAN eingeben") }
                }
            } else null
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = Color.Black
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgGray)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgGray
    ) { innerPadding ->
        when (val state = uiState) {
            GroupDetailUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            is GroupDetailUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
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
                    myOpensToday = state.myOpensToday,
                    myTimeUsedMinutes = state.myTimeUsedMinutes,
                    isStarting = startState is StartChallengeState.Loading,
                    isCurrentUserFailed = isCurrentUserFailed,
                    isQuitting = quitState is QuitState.Loading,
                    onStartChallenge = viewModel::startChallenge,
                    onQuit = viewModel::quitChallenge,
                    onNavigateToProfile = onNavigateToProfile,
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
    myOpensToday: Int,
    myTimeUsedMinutes: Int,
    isStarting: Boolean,
    isCurrentUserFailed: Boolean,
    isQuitting: Boolean,
    onStartChallenge: () -> Unit,
    onQuit: () -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    var showQuitDialog by remember { mutableStateOf(false) }

    val remainingMs = gc.endDate - now
    val remainingDays = remainingMs / (24L * 60 * 60 * 1000)
    val remainingHours = (remainingMs % (24L * 60 * 60 * 1000)) / (60 * 60 * 1000)
    val endTimeStr = remember(gc.endDate) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(gc.endDate))
    }
    val endDateValid = gc.endDate > Calendar.getInstance().apply { set(2024, 0, 1) }.timeInMillis && gc.endDate > now
    val dateFmt = SimpleDateFormat("dd. MMM yyyy", Locale.getDefault())

    LaunchedEffect(gc.endDate) {
        Timber.d("GroupDetail: endDate=${gc.endDate} remaining=${remainingDays}d ${remainingHours}h")
    }

    // Sorted participants (active ascending by opens, failed at bottom)
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

    // Quit confirmation dialog (business logic unchanged)
    if (showQuitDialog) {
        val amountEuros = gc.buyInCents / 100
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text(stringResource(R.string.group_detail_quit_dialog_title)) },
            text = {
                Text(stringResource(R.string.group_detail_quit_dialog_message, amountEuros))
            },
            confirmButton = {
                Button(
                    onClick = { showQuitDialog = false; onQuit() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BgGray),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Failed user banner ─────────────────────────────────────────────────
        if (isCurrentUserFailed) {
            item { FailedUserBanner(gc) }
        }

        // ── Card 1: Header ─────────────────────────────────────────────────────
        item {
            GroupHeaderCard(
                gc = gc,
                remainingDays = remainingDays,
                currentUserId = currentUserId,
                endDateValid = endDateValid,
                dateFmt = dateFmt,
                isStarting = isStarting,
                onStartChallenge = onStartChallenge,
                onShare = {
                    val shareText = context.getString(
                        R.string.group_create_share_text,
                        gc.code, gc.appDisplayName, gc.durationDays, gc.buyInCents / 100
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.group_create_share_chooser))
                    )
                }
            )
        }

        // ── Result summary for COMPLETED / CANCELLED (existing, unchanged) ─────
        if (gc.status == GroupChallengeStatus.COMPLETED || gc.status == GroupChallengeStatus.CANCELLED) {
            item {
                ResultSummaryCard(
                    gc = gc,
                    currentUserId = currentUserId,
                    onConnectBank = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://detox-33208.web.app/connect"))
                        )
                    }
                )
            }
            if (gc.status == GroupChallengeStatus.COMPLETED) {
                val myParticipantResult = gc.participants.find { it.userId == currentUserId }
                if (myParticipantResult != null && myParticipantResult.status != ParticipantStatus.FAILED) {
                    item {
                        PayoutResultCard(
                            participant = myParticipantResult,
                            gc = gc,
                            onConnectBank = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://detox-33208.web.app/connect"))
                                )
                            }
                        )
                    }
                }
            }
        }

        // ── Leaderboard section (ACTIVE or COMPLETED) ──────────────────────────
        if (gc.status == GroupChallengeStatus.ACTIVE || gc.status == GroupChallengeStatus.COMPLETED) {
            item {
                Text(
                    text = stringResource(R.string.group_detail_leaderboard_section),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )
            }

            if (gc.participants.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.group_detail_no_participants),
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            } else {
                item {
                    LeaderboardCard(
                        sorted = sorted,
                        gc = gc,
                        currentUserId = currentUserId
                    )
                }
            }
        }

        // ── My session section (ACTIVE, not failed) ────────────────────────────
        if (gc.status == GroupChallengeStatus.ACTIVE && !isCurrentUserFailed) {
            item {
                Text(
                    text = stringResource(R.string.group_detail_session_section),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                    color = TextSecondary
                )
            }
            item {
                // Own progress reads from Room DailyLog (myOpensToday / myTimeUsedMinutes),
                // NOT from Firestore participants array which can lag.
                SessionCard(
                    gc = gc,
                    myOpensToday = myOpensToday,
                    myTimeUsedMinutes = myTimeUsedMinutes
                )
            }
        }

        // ── Challenge aufgeben (text only, #FF3B30) ────────────────────────────
        if (gc.status == GroupChallengeStatus.ACTIVE && !isCurrentUserFailed) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showQuitDialog = true }
                        .padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isQuitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = stringResource(R.string.group_detail_quit_button),
                            fontSize = 14.sp,
                            color = AbandonRed
                        )
                    }
                }
            }
        }
    }
}

// ── Card 1: Group header ──────────────────────────────────────────────────────

@Composable
private fun GroupHeaderCard(
    gc: GroupChallenge,
    remainingDays: Long,
    currentUserId: String?,
    endDateValid: Boolean,
    dateFmt: SimpleDateFormat,
    isStarting: Boolean,
    onStartChallenge: () -> Unit,
    onShare: () -> Unit,
) {
    DetoxCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Badge + duration/days label
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupStatusBadge(gc.status)
                val rightLabel = when (gc.status) {
                    GroupChallengeStatus.ACTIVE ->
                        if (endDateValid) stringResource(R.string.group_detail_remaining_days, remainingDays)
                        else "–"
                    GroupChallengeStatus.WAITING ->
                        stringResource(R.string.group_detail_duration_label, gc.durationDays)
                    else -> ""
                }
                if (rightLabel.isNotEmpty()) {
                    Text(text = rightLabel, fontSize = 12.sp, color = TextSecondary)
                }
            }

            // App name
            Text(
                text = gc.appDisplayName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                letterSpacing = (-0.3).sp
            )

            // Limit subtitle
            val limitSubtitle = when (gc.limitType) {
                LimitType.SESSIONS ->
                    stringResource(R.string.group_detail_limit_sessions, gc.limitValueSessions ?: 0)
                LimitType.TIME ->
                    stringResource(R.string.group_detail_limit_time, gc.limitValueMinutes)
                LimitType.TIME_BUDGET ->
                    stringResource(R.string.group_detail_limit_budget, gc.limitValueMinutes)
                LimitType.TIME_WINDOW ->
                    stringResource(R.string.group_detail_limit_window)
            }
            Text(text = limitSubtitle, fontSize = 13.sp, color = TextSecondary)

            // Stats row (3 columns) — only for ACTIVE
            if (gc.status == GroupChallengeStatus.ACTIVE) {
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

                val totalPotCents = gc.participants.sumOf { it.amountCents }
                val activeCount = gc.participants.count { it.status == ParticipantStatus.ACTIVE }
                val failedCents = gc.participants
                    .filter { it.status == ParticipantStatus.FAILED }
                    .sumOf { it.amountCents }
                val prizePool = (failedCents * 0.9).toLong()
                val myGewinnCents = if (failedCents > 0 && activeCount > 0) {
                    (gc.buyInCents * 0.8).toLong() + prizePool / activeCount
                } else gc.buyInCents.toLong()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupStatColumn(
                        label = stringResource(R.string.group_detail_gesamtpot_label),
                        value = stringResource(R.string.group_detail_pot_val, totalPotCents / 100),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(48.dp)
                            .background(DividerColor)
                    )
                    GroupStatColumn(
                        label = stringResource(R.string.group_detail_teilnehmer_label),
                        value = stringResource(
                            R.string.group_detail_participants_val,
                            activeCount, gc.maxParticipants
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(48.dp)
                            .background(DividerColor)
                    )
                    GroupStatColumn(
                        label = stringResource(R.string.group_detail_gewinn_label),
                        value = stringResource(R.string.group_detail_gewinn_val, myGewinnCents / 100),
                        valueColor = AccentGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // WAITING: join code + share + player count + start button
            if (gc.status == GroupChallengeStatus.WAITING) {
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

                Text(
                    text = stringResource(R.string.group_detail_join_code_label),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = gc.code,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 6.sp
                    )
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.group_detail_share_code)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.group_detail_join_code_hint),
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                val joined = gc.participants.size
                Text(
                    text = stringResource(R.string.group_detail_waiting_players, joined, gc.maxParticipants),
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                if (gc.creatorUserId == currentUserId) {
                    val canStart = joined >= 2
                    Button(
                        onClick = onStartChallenge,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canStart && !isStarting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen
                        )
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.group_detail_start_button),
                                color = Color.White
                            )
                        }
                    }
                    if (!canStart) {
                        Text(
                            text = stringResource(R.string.group_detail_need_players),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupStatColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Black
) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.Center
        )
    }
}

// ── Leaderboard card (single white card, rows with dividers) ──────────────────

@Composable
private fun LeaderboardCard(
    sorted: List<Participant>,
    gc: GroupChallenge,
    currentUserId: String?
) {
    GroupDetoxCard {
        Column {
            sorted.forEachIndexed { index, participant ->
                LeaderboardRow(
                    rank = index + 1,
                    participant = participant,
                    gc = gc,
                    isCurrentUser = participant.userId == currentUserId
                )
                if (index < sorted.size - 1) {
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(
    rank: Int,
    participant: Participant,
    gc: GroupChallenge,
    isCurrentUser: Boolean
) {
    val isFailed = participant.status == ParticipantStatus.FAILED
    val rankColor = when (rank) {
        1 -> Color(0xFFFFD700)
        2 -> Color(0xFFB0BEC5)
        3 -> Color(0xFFCD7F32)
        else -> TextSecondary
    }
    val displayName = participant.displayName
        .takeIf { it.isNotBlank() }
        ?: participant.userId.substringBefore('@').ifBlank { participant.userId }

    val stat = when (gc.limitType) {
        LimitType.SESSIONS ->
            stringResource(
                R.string.group_detail_opens_stat,
                participant.opensToday, gc.limitValueSessions ?: 0
            )
        else ->
            stringResource(
                R.string.group_detail_time_stat,
                participant.timeUsedMinutes, gc.limitValueMinutes
            )
    }

    val rowBg = if (isCurrentUser) Color(0xFFF9FFF9) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank
        Text(
            text = "#$rank",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = rankColor,
            modifier = Modifier.width(32.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Avatar circle with initials
        AvatarCircle(name = displayName, size = 32.dp)

        Spacer(modifier = Modifier.width(10.dp))

        // Name + Du badge + status sub-label
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W500,
                    color = if (isFailed) Color(0xFFC7C7CC) else Color.Black,
                    textDecoration = if (isFailed) TextDecoration.LineThrough else TextDecoration.None
                )
                if (isCurrentUser) DuBadge()
            }
            val statusLabel = when (participant.status) {
                ParticipantStatus.ACTIVE -> stringResource(R.string.group_detail_row_aktiv)
                ParticipantStatus.FAILED -> stringResource(R.string.group_detail_row_failed)
                ParticipantStatus.SUCCESS -> stringResource(R.string.group_detail_row_won)
            }
            val statusColor = when (participant.status) {
                ParticipantStatus.ACTIVE -> AccentGreen
                else -> TextSecondary
            }
            Text(text = statusLabel, fontSize = 11.sp, color = statusColor)
        }

        // Stat
        Text(
            text = stat,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isFailed) Color(0xFFC7C7CC) else Color.Black
        )
    }
}

@Composable
private fun AvatarCircle(name: String, size: Dp = 32.dp) {
    val initials = name.trim()
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }
    val avatarColors = listOf(
        Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF26A69A),
        Color(0xFFEC407A), Color(0xFFAB47BC), Color(0xFF26C6DA)
    )
    val bgColor = avatarColors[Math.abs(name.hashCode()) % avatarColors.size]
    Box(
        modifier = Modifier
            .size(size)
            .background(bgColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun DuBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentGreen.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(R.string.group_detail_du_badge),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = AccentGreen,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ── Session card ──────────────────────────────────────────────────────────────

@Composable
private fun SessionCard(
    gc: GroupChallenge,
    myOpensToday: Int,
    myTimeUsedMinutes: Int
) {
    val limit = gc.limitValueSessions ?: 0
    val limitMin = gc.limitValueMinutes

    val verbrauchtVal = when (gc.limitType) {
        LimitType.SESSIONS ->
            stringResource(R.string.group_detail_verbraucht_val_opens, myOpensToday, limit)
        else ->
            stringResource(R.string.group_detail_verbraucht_val_time, myTimeUsedMinutes, limitMin)
    }
    val stillAvailable = when (gc.limitType) {
        LimitType.SESSIONS -> maxOf(0, limit - myOpensToday)
        else -> maxOf(0, limitMin - myTimeUsedMinutes)
    }
    val verfuegbarVal = when (gc.limitType) {
        LimitType.SESSIONS ->
            stringResource(R.string.group_detail_noch_verfuegbar_opens, stillAvailable)
        else ->
            stringResource(R.string.group_detail_noch_verfuegbar_min, stillAvailable)
    }
    val progress = when (gc.limitType) {
        LimitType.SESSIONS ->
            if (limit > 0) myOpensToday.toFloat() / limit else 0f
        else ->
            if (limitMin > 0) myTimeUsedMinutes.toFloat() / limitMin else 0f
    }.coerceIn(0f, 1f)

    GroupDetoxCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Info rows
            SessionInfoRow(
                label = stringResource(R.string.group_detail_verbraucht_label),
                value = verbrauchtVal
            )
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            SessionInfoRow(
                label = stringResource(R.string.group_detail_noch_verfuegbar_label),
                value = verfuegbarVal,
                valueColor = AccentGreen
            )

            Spacer(modifier = Modifier.height(4.dp))

            // EXISTING PROGRESS BAR — component unchanged
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun SessionInfoRow(label: String, value: String, valueColor: Color = Color.Black) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.W500, color = valueColor)
    }
}

// ── Status badge ──────────────────────────────────────────────────────────────

@Composable
private fun GroupStatusBadge(status: GroupChallengeStatus) {
    val (label, bgColor, textColor) = when (status) {
        GroupChallengeStatus.ACTIVE ->
            Triple(stringResource(R.string.group_detail_live_badge), Color(0xFFE8F5E9), Color(0xFF2E7D32))
        GroupChallengeStatus.WAITING ->
            Triple(stringResource(R.string.group_detail_waiting_badge), Color(0xFFF5F5F5), TextSecondary)
        GroupChallengeStatus.COMPLETED ->
            Triple("Abgeschlossen", Color(0xFFE8F5E9), Color(0xFF2E7D32))
        GroupChallengeStatus.CANCELLED ->
            Triple("Abgebrochen", Color(0xFFFFEBEE), MaterialTheme.colorScheme.error)
    }
    Surface(shape = RoundedCornerShape(50), color = bgColor) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ── Failed user banner (existing, adapted to new card style) ──────────────────

@Composable
private fun FailedUserBanner(gc: GroupChallenge) {
    val dateFmt = SimpleDateFormat("dd. MMM yyyy", Locale.getDefault())
    val endDateValid = gc.endDate > System.currentTimeMillis()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.group_detail_failed_banner),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (endDateValid) {
                Text(
                    text = stringResource(
                        R.string.group_detail_challenge_ends_on,
                        dateFmt.format(Date(gc.endDate))
                    ),
                    fontSize = 13.sp,
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
                    val pot = gc.participants.count { it.status == ParticipantStatus.FAILED } *
                            gc.buyInCents / 100
                    Text(
                        text = stringResource(R.string.group_detail_winner, name),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (pot > 0) {
                        Text(
                            text = stringResource(R.string.group_detail_total_prize, pot),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

// ── ResultSummaryCard + PayoutResultCard (existing, unchanged) ────────────────

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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                gc.status == GroupChallengeStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                iLost -> MaterialTheme.colorScheme.errorContainer
                failedCount == 0 -> MaterialTheme.colorScheme.tertiaryContainer
                succeededCount == 0 -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        elevation = CardDefaults.cardElevation(0.dp)
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
                fontWeight = FontWeight.Bold
            )
            when {
                gc.status == GroupChallengeStatus.CANCELLED ->
                    Text("Zu wenige Spieler. Alle Einsätze werden zurückgebucht.")
                iLost -> {
                    val lostCents = myParticipant?.amountCents ?: gc.buyInCents
                    Text("€${lostCents / 100} wurden eingezogen.")
                }
                failedCount == 0 ->
                    Text("Alle $succeededCount Teilnehmer im Limit. Einsätze werden zurückgebucht!")
                succeededCount == 0 ->
                    Text("Alle $failedCount Teilnehmer haben das Limit überschritten.")
                else -> {
                    Text("$succeededCount gewonnen · $failedCount ausgeschieden")
                    if (gc.perWinnerBonus > 0) {
                        Text("Bonus pro Gewinner: €${gc.perWinnerBonus / 100}", fontSize = 13.sp)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (bonus > 0) {
                Text(
                    text = "🎉 Du hast gewonnen! +€${bonus / 100} Bonus",
                    fontWeight = FontWeight.Bold
                )
            }
            Text("Dein Einsatz (€${buyIn / 100}) wird zurückgebucht")
            if (bonus > 0) {
                if (isPending) {
                    Text(
                        "⚠️ Verbinde dein Bankkonto um €${bonus / 100} zu erhalten",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onConnectBank, modifier = Modifier.fillMaxWidth()) {
                        Text("Bankkonto verbinden")
                    }
                } else {
                    Text("Bonus (€${bonus / 100}) wird auf dein Bankkonto überwiesen")
                }
            }
        }
    }
}

// ── Shared white card for group screens ───────────────────────────────────────

@Composable
private fun GroupDetoxCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(0.5.dp, CardBorder),
        content = content
    )
}

private fun isToday(timestampMs: Long): Boolean {
    if (timestampMs <= 0L) return false
    val cal = Calendar.getInstance()
    val today = cal.clone() as Calendar
    cal.timeInMillis = timestampMs
    return cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
            && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
}
