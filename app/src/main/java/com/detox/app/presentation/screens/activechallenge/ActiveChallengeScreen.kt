package com.detox.app.presentation.screens.activechallenge

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.util.DateUtils
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val BgGray = Color(0xFFF2F2F7)
private val CardWhite = Color.White
private val CardBorder = Color(0x0F000000)
private val TextSecondary = Color(0xFF8E8E93)
private val AccentGreen = Color(0xFF00C853)
private val AbandonRed = Color(0xFFFF3B30)
private val DividerColor = Color(0xFFF2F2F7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveChallengeScreen(
    onBack: () -> Unit,
    viewModel: ActiveChallengeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val abandonSuccess by viewModel.abandonSuccess.collectAsStateWithLifecycle()

    LaunchedEffect(abandonSuccess) {
        if (abandonSuccess) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = Color.Black
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgGray)
            )
        },
        containerColor = BgGray
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BgGray)
        ) {
            when (val state = uiState) {
                is ActiveChallengeUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ActiveChallengeUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                is ActiveChallengeUiState.Success -> {
                    ActiveChallengeContent(
                        challenge = state.challenge,
                        status = state.status,
                        streak = state.streak,
                        bestStreak = state.bestStreak,
                        successRatePct = state.successRatePct,
                        onAbandon = { viewModel.abandonChallenge() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveChallengeContent(
    challenge: Challenge,
    status: DailyLimitStatus?,
    streak: Int,
    bestStreak: Int,
    successRatePct: Int,
    onAbandon: () -> Unit
) {
    val isHardMode = challenge.mode == ChallengeMode.HARD
    val darkGreen = Color(0xFF2E7D32)
    var showAbandonDialog by remember { mutableStateOf(false) }

    // ── Dialogs (business logic unchanged) ────────────────────────────────────
    if (showAbandonDialog) {
        if (isHardMode && challenge.amountCents != null) {
            AlertDialog(
                onDismissRequest = { showAbandonDialog = false },
                title = { Text(stringResource(R.string.active_challenge_abandon_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.active_challenge_abandon_hard_message,
                            challenge.amountCents / 100f
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAbandonDialog = false; onAbandon() }) {
                        Text(
                            text = stringResource(R.string.active_challenge_abandon_hard_yes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showAbandonDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = darkGreen)
                    ) {
                        Text(
                            text = stringResource(R.string.active_challenge_abandon_hard_no),
                            color = Color.White
                        )
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showAbandonDialog = false },
                title = { Text(stringResource(R.string.active_challenge_abandon_confirm_title)) },
                text = { Text(stringResource(R.string.active_challenge_abandon_confirm_message)) },
                confirmButton = {
                    Button(
                        onClick = { showAbandonDialog = false; onAbandon() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.active_challenge_abandon_confirm_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAbandonDialog = false }) {
                        Text(stringResource(R.string.active_challenge_abandon_confirm_no))
                    }
                }
            )
        }
    }

    // ── Date helpers ─────────────────────────────────────────────────────────
    val now = System.currentTimeMillis()
    val dateFmt = remember { SimpleDateFormat("dd. MMM yyyy", Locale.getDefault()) }
    val startDateStr = remember(challenge.startDate) { dateFmt.format(Date(challenge.startDate)) }

    val endDateMs = remember(challenge.endDate, challenge.startDate) {
        when {
            challenge.endDate <= 0L -> 0L
            challenge.endDate > 1_700_000_000_000L -> challenge.endDate
            else -> challenge.startDate + challenge.endDate * DateUtils.MILLIS_PER_DAY
        }
    }
    val endDateStr = remember(endDateMs) {
        if (endDateMs > 0L) dateFmt.format(Date(endDateMs)) else null
    }
    val daysLeft = remember(endDateMs, now) {
        if (endDateMs > 0L) maxOf(0, ((endDateMs - now) / DateUtils.MILLIS_PER_DAY).toInt()) else null
    }
    Timber.d("DetailScreen: endDateMs=$endDateMs daysLeft=$daysLeft")

    // ── Motivational quote (rotates daily) ───────────────────────────────────
    val quotes = listOf(
        "Jeder Tag zählt.",
        "Du bist stärker als dein Handy.",
        "Kontrolle ist Freiheit.",
        "Weniger Screen, mehr Leben.",
        "Dein Streak ist dein Stolz.",
        "Kleine Schritte, große Wirkung.",
        "Der beste Moment ist jetzt."
    )
    val quote = remember { quotes[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % quotes.size] }

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Card 1: Header ────────────────────────────────────────────────────
        DetoxCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Badge row + end date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeBadge(isHardMode)
                    endDateStr?.let {
                        Text(text = it, fontSize = 12.sp, color = TextSecondary)
                    }
                }

                // App name
                Text(
                    text = challenge.appDisplayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    letterSpacing = (-0.3).sp
                )

                // Stats row (3 columns)
                Row(modifier = Modifier.fillMaxWidth()) {
                    DetailStatColumn(
                        label = stringResource(R.string.detail_streak_current_label),
                        value = "🔥 $streak",
                        modifier = Modifier.weight(1f)
                    )
                    DetailStatColumn(
                        label = if (isHardMode) stringResource(R.string.detail_einsatz_label)
                                else stringResource(R.string.detail_streak_best_label),
                        value = if (isHardMode) "€${"%.0f".format((challenge.amountCents ?: 0) / 100f)}"
                                else "$bestStreak",
                        valueColor = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    DetailStatColumn(
                        label = stringResource(R.string.detail_days_left_label),
                        value = daysLeft?.toString() ?: "∞",
                        valueColor = AccentGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Card 2: Progress ──────────────────────────────────────────────────
        status?.let { s ->
            DetoxCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.detail_header_today),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        val rightLabel = when (challenge.limitType) {
                            LimitType.SESSIONS ->
                                "${s.todayOpens} / ${challenge.limitValueSessions ?: 0} Öffnungen"
                            LimitType.TIME ->
                                "${s.todayMinutes} / ${challenge.limitValueMinutes} Min"
                            LimitType.TIME_BUDGET ->
                                "${s.todayMinutes} / ${challenge.dailyBudgetMinutes ?: 0} Min"
                            LimitType.TIME_WINDOW -> "–"
                        }
                        Text(
                            text = rightLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }

                    // EXISTING PROGRESS BAR — component unchanged
                    val progressValue = when (challenge.limitType) {
                        LimitType.TIME ->
                            if (challenge.limitValueMinutes > 0)
                                s.todayMinutes.toFloat() / challenge.limitValueMinutes else 0f
                        LimitType.SESSIONS -> {
                            val max = challenge.limitValueSessions ?: 1
                            if (max > 0) s.todayOpens.toFloat() / max else 0f
                        }
                        LimitType.TIME_BUDGET -> {
                            val budget = challenge.dailyBudgetMinutes ?: 1
                            if (budget > 0) s.todayMinutes.toFloat() / budget else 0f
                        }
                        LimitType.TIME_WINDOW -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { progressValue.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = if (s.limitExceeded) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    // Footer row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val remainingLabel = when (challenge.limitType) {
                            LimitType.SESSIONS ->
                                stringResource(
                                    R.string.detail_remaining_opens,
                                    maxOf(0, (challenge.limitValueSessions ?: 0) - s.todayOpens)
                                )
                            LimitType.TIME ->
                                stringResource(
                                    R.string.detail_remaining_min,
                                    maxOf(0, challenge.limitValueMinutes - s.todayMinutes)
                                )
                            LimitType.TIME_BUDGET ->
                                stringResource(
                                    R.string.detail_remaining_min,
                                    maxOf(0, (challenge.dailyBudgetMinutes ?: 0) - s.todayMinutes)
                                )
                            LimitType.TIME_WINDOW -> "–"
                        }
                        Text(text = remainingLabel, fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = stringResource(R.string.detail_reset_midnight),
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // ── Card 3: Info list ─────────────────────────────────────────────────
        DetoxCard {
            Column {
                // Limit
                val limitVal = when (challenge.limitType) {
                    LimitType.SESSIONS ->
                        stringResource(
                            R.string.detail_limit_sessions_val,
                            challenge.limitValueSessions ?: 0
                        )
                    LimitType.TIME ->
                        stringResource(R.string.detail_limit_time_val, challenge.limitValueMinutes)
                    LimitType.TIME_BUDGET ->
                        stringResource(
                            R.string.detail_limit_budget_val,
                            challenge.dailyBudgetMinutes ?: 0
                        )
                    LimitType.TIME_WINDOW -> "Zeitfenster"
                }
                InfoRow(label = stringResource(R.string.detail_info_limit), value = limitVal)

                // Session-Dauer (SESSION_LIMIT only)
                if (challenge.limitType == LimitType.SESSIONS) {
                    InfoDivider()
                    InfoRow(
                        label = stringResource(R.string.detail_info_session_dur),
                        value = stringResource(
                            R.string.detail_info_session_dur_val,
                            challenge.sessionDurationMinutes
                        )
                    )
                }

                // Hard Mode: Einsatz + Bei Erfolg
                if (isHardMode && challenge.amountCents != null) {
                    InfoDivider()
                    InfoRow(
                        label = stringResource(R.string.detail_info_einsatz_row),
                        value = stringResource(
                            R.string.detail_info_einsatz_val,
                            "%.2f".format(challenge.amountCents / 100f)
                        )
                    )
                    InfoDivider()
                    val refundAmount = (challenge.amountCents * 0.8f)
                    InfoRow(
                        label = stringResource(R.string.detail_info_bei_erfolg),
                        value = stringResource(
                            R.string.detail_info_bei_erfolg_val,
                            "%.2f".format(refundAmount / 100f)
                        ),
                        valueColor = AccentGreen
                    )
                }

                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.detail_info_started),
                    value = startDateStr
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.detail_info_ends),
                    value = endDateStr ?: stringResource(R.string.active_challenge_no_end_date)
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.detail_info_success_rate),
                    value = stringResource(R.string.detail_info_success_rate_val, successRatePct),
                    valueColor = AccentGreen
                )
            }
        }

        // ── Stripe note (Hard Mode only) ──────────────────────────────────────
        if (isHardMode) {
            Text(
                text = stringResource(R.string.detail_stripe_note),
                fontSize = 11.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Motivational quote ────────────────────────────────────────────────
        Text(
            text = "\"$quote\"",
            fontSize = 12.sp,
            color = Color(0xFFC7C7CC),
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // ── Abrechnung (Hard Mode only, when challenge is over) ──────────────
        AbrechnungSoloCard(challenge = challenge)

        // ── Challenge aufgeben (text only, no button background) ─────────────
        if (challenge.status == ChallengeStatus.ACTIVE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAbandonDialog = true }
                    .padding(top = 16.dp, bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.detail_abandon_text),
                    fontSize = 14.sp,
                    color = AbandonRed
                )
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── Abrechnung card (Hard Mode, completed or failed) ─────────────────────────

@Composable
private fun AbrechnungSoloCard(
    challenge: Challenge,
    modifier: Modifier = Modifier
) {
    val isHardMode = challenge.mode == ChallengeMode.HARD
    val isOver = challenge.status == ChallengeStatus.COMPLETED ||
        challenge.status == ChallengeStatus.FAILED
    if (!isHardMode || !isOver) return

    val amountCents = challenge.amountCents ?: 0
    if (amountCents <= 0) return

    val isCompleted = challenge.status == ChallengeStatus.COMPLETED
    val stakeRefund = (amountCents * 0.80).toInt()
    val appFee = amountCents - stakeRefund

    val formatCents: (Int) -> String = { cents ->
        "€%,.2f".format(cents / 100.0)
            .replace(",", "X").replace(".", ",").replace("X", ".")
    }

    Spacer(modifier = Modifier.height(12.dp))
    DetoxCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = stringResource(R.string.abrechnung_title),
                fontSize = 13.sp,
                fontWeight = FontWeight(600),
                color = TextSecondary,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = DividerColor)
            Spacer(modifier = Modifier.height(10.dp))

            if (isCompleted) {
                // ── WIN ─────────────────────────────────────────────────────
                SoloAbrechnungRow(
                    label = stringResource(R.string.abrechnung_stake_back, formatCents(stakeRefund)),
                    trailing = "✅"
                )
                Spacer(modifier = Modifier.height(6.dp))
                SoloAbrechnungRow(
                    label = stringResource(R.string.abrechnung_app_fee_20, formatCents(appFee)),
                    trailing = null
                )
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.5.dp, color = DividerColor)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.abrechnung_total, formatCents(stakeRefund)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.abrechnung_refunded),
                    fontSize = 13.sp,
                    color = AccentGreen
                )
            } else {
                // ── FAIL ────────────────────────────────────────────────────
                SoloAbrechnungRow(
                    label = stringResource(R.string.abrechnung_stake_captured, formatCents(amountCents)),
                    trailing = "❌"
                )
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.5.dp, color = DividerColor)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.abrechnung_not_passed),
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SoloAbrechnungRow(label: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = trailing, fontSize = 13.sp)
        }
    }
}

// ── Shared card composable ────────────────────────────────────────────────────

@Composable
fun DetoxCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(0.5.dp, CardBorder),
        content = content
    )
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ModeBadge(isHardMode: Boolean) {
    val bgColor = if (isHardMode) Color(0xFFFFF0E8) else Color(0xFFE8F5E9)
    val textColor = if (isHardMode) Color(0xFFC05A00) else Color(0xFF2E7D32)
    val label = if (isHardMode) stringResource(R.string.active_challenge_mode_hard)
                else stringResource(R.string.active_challenge_mode_soft)
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

@Composable
private fun DetailStatColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Black
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.Black) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.W500, color = valueColor)
    }
}

@Composable
private fun InfoDivider() {
    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
}
