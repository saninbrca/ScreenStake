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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.detox.app.presentation.components.DetoxHorizontalPicker
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.presentation.components.AppIconImage
import com.detox.app.presentation.components.FaviconImage
import com.detox.app.presentation.components.websiteDisplayName
import com.detox.app.domain.model.BlockingType
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
private val AccentOrange = Color(0xFFFF9500)
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
    val abandonStatus by viewModel.abandonStatus.collectAsStateWithLifecycle()
    val reduceLimitState by viewModel.reduceLimitState.collectAsStateWithLifecycle()

    LaunchedEffect(abandonSuccess) {
        if (abandonSuccess) onBack()
    }

    LaunchedEffect(reduceLimitState) {
        if (reduceLimitState is ReduceLimitState.Success) viewModel.resetReduceLimitState()
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
                        onAbandon = { viewModel.abandonChallenge() },
                        onReduceLimit = { viewModel.reducePendingLimit(it) },
                        isReducing = reduceLimitState is ReduceLimitState.Loading
                    )
                }
            }

            // Capturing the solo Hard Mode stake on abandon → block interaction until the CF returns.
            if (abandonStatus is AbandonState.Loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Capture (or post-capture status write) failed → challenge stays ACTIVE; let the user retry.
            if (abandonStatus is AbandonState.Error) {
                AlertDialog(
                    onDismissRequest = { viewModel.resetAbandonStatus() },
                    title = { Text(stringResource(R.string.active_challenge_abandon_error_title)) },
                    text = { Text(stringResource(R.string.active_challenge_abandon_error_message)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.resetAbandonStatus() }) {
                            Text(stringResource(R.string.dialog_ok))
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveChallengeContent(
    challenge: Challenge,
    status: DailyLimitStatus?,
    streak: Int,
    bestStreak: Int,
    successRatePct: Int,
    onAbandon: () -> Unit,
    onReduceLimit: (Int) -> Unit,
    isReducing: Boolean,
) {
    val isHardMode = challenge.mode == ChallengeMode.HARD
    val darkGreen = Color(0xFF2E7D32)
    var showAbandonDialog by remember { mutableStateOf(false) }

    // ── Limit reduction state ────────────────────────────────────────────────
    val currentLimitValue = remember(challenge) {
        when (challenge.limitType) {
            LimitType.SESSIONS    -> challenge.limitValueSessions ?: 0
            LimitType.TIME        -> challenge.limitValueMinutes
            LimitType.TIME_BUDGET -> challenge.dailyBudgetMinutes ?: 0
            LimitType.TIME_WINDOW -> 0
        }
    }
    val limitUnit = when (challenge.limitType) {
        LimitType.SESSIONS    -> stringResource(R.string.limit_unit_opens)
        LimitType.TIME        -> stringResource(R.string.limit_unit_minutes)
        LimitType.TIME_BUDGET -> stringResource(R.string.limit_unit_minutes_budget)
        LimitType.TIME_WINDOW -> ""
    }
    val showLimitSection = challenge.groupChallengeId == null &&
        challenge.limitType != LimitType.TIME_WINDOW &&
        currentLimitValue > 1 &&
        challenge.pendingLimitValue == null &&
        challenge.status == ChallengeStatus.ACTIVE
    var showReduceSheet by remember { mutableStateOf(false) }
    var showReduceConfirm by remember { mutableStateOf(false) }
    var pickerValue by remember(currentLimitValue) { mutableIntStateOf(maxOf(1, currentLimitValue - 1)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    // ── Reduce limit confirmation dialog ─────────────────────────────────────
    if (showReduceConfirm) {
        AlertDialog(
            onDismissRequest = { showReduceConfirm = false },
            title = { Text(stringResource(R.string.limit_reduce_confirm_title)) },
            text = {
                Text(stringResource(R.string.limit_reduce_confirm_body, pickerValue, limitUnit))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReduceConfirm = false
                        showReduceSheet = false
                        onReduceLimit(pickerValue)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text(stringResource(R.string.limit_reduce_confirm_ok), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReduceConfirm = false }) {
                    Text(stringResource(R.string.limit_reduce_confirm_cancel))
                }
            }
        )
    }

    // ── Reduce limit bottom sheet ─────────────────────────────────────────────
    if (showReduceSheet && currentLimitValue > 1) {
        ModalBottomSheet(
            onDismissRequest = { showReduceSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.limit_reduce_sheet_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black
                )
                Text(
                    text = "Aktuell: $currentLimitValue $limitUnit",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                DetoxHorizontalPicker(
                    values = (1 until currentLimitValue).toList(),
                    selectedValue = pickerValue,
                    onValueChange = { pickerValue = it },
                    unit = limitUnit,
                    darkMode = false
                )
                Text(
                    text = stringResource(R.string.limit_reduce_warning),
                    fontSize = 12.sp,
                    color = AccentOrange
                )
                Button(
                    onClick = { showReduceConfirm = true },
                    enabled = !isReducing && pickerValue < currentLimitValue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    if (isReducing) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.height(20.dp).width(20.dp))
                    } else {
                        Text(
                            text = stringResource(R.string.limit_reduce_button, pickerValue, limitUnit),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
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
    // Open-ended ("Kein Enddatum") sentinel → null out the end-date + day-count so the existing
    // "∞" (days-left) and active_challenge_no_end_date (ends row) fallbacks render instead of a
    // far-future date / ~36500-day count. Display-only; endDate itself is unchanged.
    val isOpenEnded = remember(challenge.startDate, endDateMs) {
        DateUtils.isOpenEnded(challenge.startDate, endDateMs)
    }
    val endDateStr = remember(endDateMs, isOpenEnded) {
        if (endDateMs > 0L && !isOpenEnded) dateFmt.format(Date(endDateMs)) else null
    }
    val daysLeft = remember(endDateMs, now, isOpenEnded) {
        if (endDateMs > 0L && !isOpenEnded) maxOf(0, ((endDateMs - now) / DateUtils.MILLIS_PER_DAY).toInt()) else null
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

                // App / website name
                val displayTitle = remember(challenge) {
                    if (challenge.blockingType == BlockingType.WEBSITE) {
                        websiteDisplayName(challenge.blockedDomains, emptyList())
                    } else {
                        challenge.appDisplayName
                    }
                }
                Text(
                    text = displayTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    letterSpacing = (-0.3).sp
                )

                // Stats row (3 columns)
                val animatedStreak by animateIntAsState(
                    targetValue = streak,
                    animationSpec = tween(600, delayMillis = 200, easing = FastOutSlowInEasing),
                    label = "streakAnim"
                )
                val animatedDaysLeft by animateIntAsState(
                    targetValue = daysLeft ?: 0,
                    animationSpec = tween(600, delayMillis = 200, easing = FastOutSlowInEasing),
                    label = "daysLeftAnim"
                )
                val streakScale = remember { Animatable(1f) }
                var streakGreenPulse by remember { mutableStateOf(false) }
                val streakColor by animateColorAsState(
                    targetValue = if (streakGreenPulse) AccentGreen else Color.Black,
                    animationSpec = tween(200),
                    label = "streakColor"
                )
                var previousStreak by remember { mutableIntStateOf(streak) }
                var isFirstLoad by remember { mutableStateOf(true) }
                LaunchedEffect(streak) {
                    if (isFirstLoad) {
                        isFirstLoad = false
                        previousStreak = streak
                        return@LaunchedEffect
                    }
                    if (streak > previousStreak) {
                        launch {
                            streakScale.animateTo(1.3f, tween(200))
                            streakScale.animateTo(1f, tween(200))
                        }
                        launch {
                            streakGreenPulse = true
                            delay(300L)
                            streakGreenPulse = false
                        }
                    }
                    previousStreak = streak
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    DetailStatColumn(
                        label = stringResource(R.string.detail_streak_current_label),
                        value = "$animatedStreak",
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer(scaleX = streakScale.value, scaleY = streakScale.value),
                        valueColor = streakColor
                    )
                    DetailStatColumn(
                        label = stringResource(R.string.detail_days_left_label),
                        value = if (daysLeft != null) animatedDaysLeft.toString() else "∞",
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

                    // EXISTING PROGRESS BAR — animated fill on open
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
                    val animatedProgress by animateFloatAsState(
                        targetValue = progressValue.coerceIn(0f, 1f),
                        animationSpec = tween(600, delayMillis = 300, easing = FastOutSlowInEasing),
                        label = "progressAnim"
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (s.limitExceeded) AbandonRed else AccentGreen,
                        trackColor = Color(0xFFE0E0E5)
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

        // ── BLOCKIERTE APPS section ───────────────────────────────────────────
        if (challenge.appPackageNames.isNotEmpty()) {
            val ctx = LocalContext.current
            Text(
                text = stringResource(R.string.detail_blocked_apps_section),
                fontSize = 13.sp,
                fontWeight = FontWeight(600),
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
            DetoxCard {
                Column {
                    challenge.appPackageNames.forEachIndexed { index, pkg ->
                        val appLabel = remember(pkg) { resolveAppLabel(ctx, pkg) ?: pkg }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIconImage(
                                packageName = pkg,
                                appName = appLabel,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = appLabel,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight(600),
                                    color = Color.Black
                                )
                                Text(
                                    text = pkg,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        if (index < challenge.appPackageNames.lastIndex) InfoDivider()
                    }
                }
            }
        }

        // ── BLOCKIERTE WEBSITES section ───────────────────────────────────────
        if (challenge.blockingType == BlockingType.WEBSITE && challenge.blockedDomains.isNotEmpty()) {

            Text(
                text = stringResource(R.string.detail_blocked_websites_section),
                fontSize = 13.sp,
                fontWeight = FontWeight(600),
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
            DetoxCard {
                Column {
                    challenge.blockedDomains.forEachIndexed { index, domain ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaviconImage(
                                domain = domain,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = domain,
                                fontSize = 15.sp,
                                fontWeight = FontWeight(600),
                                color = Color.Black
                            )
                        }
                        if (index < challenge.blockedDomains.lastIndex) InfoDivider()
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
            }
        }

        // ── LIMIT ANPASSEN section ────────────────────────────────────────────
        if (showLimitSection) {
            Text(
                text = stringResource(R.string.limit_reduce_section_title),
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
            DetoxCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showReduceSheet = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.limit_reduce_row_label),
                            fontSize = 17.sp,
                            color = Color.Black
                        )
                        Text(
                            text = stringResource(R.string.limit_reduce_row_sub),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color(0xFFC7C7CC)
                    )
                }
            }
        } else if (challenge.pendingLimitValue != null && challenge.status == ChallengeStatus.ACTIVE &&
                   challenge.limitType != LimitType.TIME_WINDOW && challenge.groupChallengeId == null) {
            Text(
                text = stringResource(R.string.limit_reduce_pending, challenge.pendingLimitValue!!, limitUnit),
                fontSize = 13.sp,
                color = AccentOrange,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
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
                    .padding(top = 40.dp, bottom = 24.dp),
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
    val bgColor = if (isHardMode) Color(0xFFFFF0E8) else Color(0xFFE8F8EF)
    val textColor = if (isHardMode) Color(0xFFC05A00) else Color(0xFF1E7A3C)
    val label = if (isHardMode) stringResource(R.string.active_challenge_mode_hard)
                else stringResource(R.string.active_challenge_mode_soft)
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
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

private fun resolveAppLabel(context: Context, packageName: String): String? = try {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
} catch (e: Exception) { null }
