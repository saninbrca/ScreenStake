package com.detox.app.presentation.screens.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.presentation.components.activeDaysSummary
import com.detox.app.presentation.components.timeWindowSummary
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    onBack: () -> Unit,
    onStartAgain: (packageNames: String) -> Unit,
    viewModel: HistoryDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_screen_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = detoxColors.label,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = detoxColors.screenBackground),
            )
        },
        containerColor = detoxColors.screenBackground,
    ) { innerPadding ->
        when (val state = uiState) {
            is HistoryDetailViewModel.UiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            is HistoryDetailViewModel.UiState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_detail_not_found),
                        color = detoxColors.subtext,
                    )
                }
            }

            is HistoryDetailViewModel.UiState.Success -> {
                DetailContent(
                    entity = state.entity,
                    stats = state.stats,
                    durationDays = state.durationDays,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

// ── Main content ──────────────────────────────────────────────────────────────

@Composable
private fun DetailContent(
    entity: ChallengeEntity,
    stats: HistoryStats?,
    durationDays: Int,
    modifier: Modifier = Modifier,
) {
    val context     = LocalContext.current
    val isCompleted = entity.status == "completed"
    val isHard      = entity.mode == "hard"
    val isGroup     = !entity.groupChallengeId.isNullOrBlank()
    val dateFormat  = remember { SimpleDateFormat("d. MMM yyyy", Locale("de")) }
    val startStr    = remember(entity.startDate) { dateFormat.format(Date(entity.startDate)) }
    // Open-ended sentinel endDate must never render as a far-future date.
    val isOpenEnded = remember(entity.startDate, entity.endDate) {
        DateUtils.isOpenEnded(entity.startDate, entity.endDate)
    }
    val endStr      = if (isOpenEnded) stringResource(R.string.verlauf_no_end_date)
                      else remember(entity.endDate) { dateFormat.format(Date(entity.endDate)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Card 1 — Challenge info
        DetailCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeBadge(isHard = isHard, isGroup = isGroup)
                StatusLabel(isCompleted = isCompleted)
            }
            Spacer(Modifier.height(12.dp))

            // Show ALL apps of the challenge — not just the first.
            val packageNames = entity.appPackageNames
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: listOfNotNull(entity.appPackageName.takeIf { it.isNotBlank() })
            val displayNames = entity.appDisplayName
                .split(",").map { it.trim() }.filter { it.isNotBlank() }

            if (packageNames.isEmpty()) {
                // Website challenge — single display name, no package.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DetailAppIcon(
                        packageName = null,
                        appName = entity.appDisplayName,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = entity.appDisplayName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = detoxColors.label,
                    )
                }
            } else {
                packageNames.forEachIndexed { index, packageName ->
                    val appName = resolveAppName(context, packageName, displayNames.getOrNull(index))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        DetailAppIcon(
                            packageName = packageName,
                            appName = appName,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = appName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = detoxColors.label,
                        )
                    }
                    if (index < packageNames.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.verlauf_date_range,
                    startStr,
                    endStr,
                    durationDays,
                ),
                fontSize = 12.sp,
                color = detoxColors.subtext,
            )
        }

        // Card 2 — Stats (COMPLETED only)
        if (isCompleted && stats != null) {
            DetailCard {
                StatsInsetSection(stats = stats)
            }
        }

        // Card 3 — Hard Mode money (HARD mode only)
        val amountCents = entity.amountCents
        if (isHard && amountCents != null && amountCents > 0) {
            DetailCard {
                HardMoneySection(isCompleted = isCompleted, amountCents = amountCents)
            }
        }

        // Card 4 — Info list
        DetailCard {
            InfoList(
                entity = entity,
                durationDays = durationDays,
                startStr = startStr,
                endStr = endStr,
            )
        }

        // Card 5 — Motivation (user's own words, only when set)
        val motivation = entity.customMotivation?.trim().orEmpty()
        if (motivation.isNotEmpty()) {
            Text(
                text = stringResource(R.string.detail_motivation_section),
                fontSize = 13.sp,
                fontWeight = FontWeight(600),
                color = detoxColors.subtext,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            DetailCard {
                Text(
                    text = motivation,
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    color = detoxColors.label,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

// ── Card wrapper ──────────────────────────────────────────────────────────────

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground),
        border = BorderStroke(0.5.dp, detoxColors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

// ── Mode badge ────────────────────────────────────────────────────────────────

@Composable
private fun ModeBadge(isHard: Boolean, isGroup: Boolean) {
    val bg: Color
    val textColor: Color
    val label: String
    when {
        isGroup -> {
            bg = detoxColors.softPurpleBg
            textColor = detoxColors.softPurpleText
            label = stringResource(R.string.history_detail_mode_group)
        }
        isHard -> {
            bg = detoxColors.softOrangeBg
            textColor = detoxColors.softOrangeText
            label = stringResource(R.string.verlauf_mode_hard)
        }
        else -> {
            bg = detoxColors.softGreenBg
            textColor = detoxColors.softGreenText
            label = stringResource(R.string.verlauf_mode_soft)
        }
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
    }
}

// ── Status label ──────────────────────────────────────────────────────────────

@Composable
private fun StatusLabel(isCompleted: Boolean) {
    val color = if (isCompleted) detoxColors.success else detoxColors.danger
    val label = if (isCompleted)
        stringResource(R.string.verlauf_status_completed)
    else
        stringResource(R.string.verlauf_status_failed)
    Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
}

// ── Stats inset section ───────────────────────────────────────────────────────

@Composable
private fun StatsInsetSection(stats: HistoryStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(detoxColors.insetSurface, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(
                value = "${stats.bestStreak}",
                label = stringResource(R.string.verlauf_stats_streak_label),
                valueColor = detoxColors.label,
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = "${stats.totalConsciousOpens}",
                label = stringResource(R.string.verlauf_stats_opens_label),
                valueColor = detoxColors.label,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(
            text = label,
            fontSize = 12.sp,
            color = detoxColors.subtext,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Hard Mode money section ───────────────────────────────────────────────────

@Composable
private fun HardMoneySection(isCompleted: Boolean, amountCents: Int) {
    if (isCompleted) {
        val refund = floor(amountCents * 0.80) / 100.0
        Text(
            text = stringResource(R.string.verlauf_hard_refund, refund),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = detoxColors.success,
        )
        Text(
            text = stringResource(R.string.verlauf_hard_refund_fee),
            fontSize = 12.sp,
            color = detoxColors.subtext,
        )
    } else {
        val captured = amountCents / 100.0
        Text(
            text = stringResource(R.string.verlauf_hard_captured, captured),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = detoxColors.danger,
        )
        Text(
            text = stringResource(R.string.verlauf_hard_captured_reason),
            fontSize = 12.sp,
            color = detoxColors.subtext,
        )
    }
}

// ── Info list card ────────────────────────────────────────────────────────────

@Composable
private fun InfoList(
    entity: ChallengeEntity,
    durationDays: Int,
    startStr: String,
    endStr: String,
) {
    // Same path rule as ActiveChallengeScreen: block-path challenges (Website/Adult)
    // carry "time_window" only as a 24/7 sentinel with a null schedule — their limit
    // reads "always blocked" and the window/weekday rows are suppressed.
    val isBlockPath = entity.blockingType == "website"
    val limitText = if (isBlockPath) {
        stringResource(R.string.wizard_review_always_blocked)
    } else when (entity.limitType) {
        "sessions"    -> stringResource(R.string.history_detail_limit_sessions, entity.limitValueSessions ?: 0)
        "time"        -> stringResource(R.string.history_detail_limit_time, entity.limitValueMinutes)
        "time_budget" -> stringResource(R.string.history_detail_limit_budget, entity.dailyBudgetMinutes ?: 0)
        "time_window" -> stringResource(R.string.detail_limit_window_val)
        else          -> entity.limitType
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        InfoRow(label = stringResource(R.string.history_detail_info_limit), value = limitText)
        if (!isBlockPath && entity.limitType == "sessions") {
            HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
            InfoRow(
                label = stringResource(R.string.detail_info_session_dur),
                value = stringResource(R.string.detail_info_session_dur_val, entity.sessionDurationMinutes),
            )
        }
        if (!isBlockPath) {
            val activeDays = entity.activeDays
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
            InfoRow(
                label = stringResource(R.string.detail_info_time_window),
                value = timeWindowSummary(entity.scheduleStartTime, entity.scheduleEndTime),
            )
            HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
            InfoRow(
                label = stringResource(R.string.detail_info_active_days),
                value = activeDaysSummary(activeDays),
            )
        }
        if (entity.blockAdultContent == 1) {
            HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
            InfoRow(
                label = stringResource(R.string.adult_block_display_name),
                value = stringResource(R.string.wizard_review_adult_active),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
        InfoRow(label = stringResource(R.string.history_detail_info_started), value = startStr)
        HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
        InfoRow(label = stringResource(R.string.history_detail_info_ended), value = endStr)
        HorizontalDivider(thickness = 0.5.dp, color = detoxColors.divider)
        InfoRow(
            label = stringResource(R.string.history_detail_info_duration),
            value = stringResource(R.string.history_duration_days, durationDays),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = detoxColors.subtext)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = detoxColors.label)
    }
}

// ── App name resolution (PackageManager, fallback to stored name) ─────────────

private fun resolveAppName(context: Context, packageName: String, fallback: String?): String {
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        fallback?.takeIf { it.isNotBlank() } ?: packageName
    }
}

// ── App icon (PackageManager-based, Huawei-compatible) ────────────────────────

@Composable
private fun DetailAppIcon(packageName: String?, appName: String, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        if (packageName == null) return@remember null
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = appName,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Fit,
        )
    } else {
        val initial = appName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Surface(modifier = modifier, shape = CircleShape, color = detoxColors.avatarFallbackBg) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initial,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.onSolid,
                )
            }
        }
    }
}
