package com.detox.app.presentation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.detox.app.R
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.model.LimitType
import com.detox.app.ui.theme.DetoxWarning

@Composable
fun ChallengeCard(
    dailyStats: DailyStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = when (dailyStats.limitType) {
        LimitType.TIME -> {
            if (dailyStats.limitValueMinutes > 0)
                dailyStats.todayMinutes.toFloat() / dailyStats.limitValueMinutes
            else 0f
        }
        LimitType.SESSIONS -> {
            val max = dailyStats.limitValueSessions ?: 1
            if (max > 0) dailyStats.todayOpens.toFloat() / max else 0f
        }
        LimitType.TIME_BUDGET -> {
            val budget = dailyStats.dailyBudgetMinutes ?: dailyStats.limitValueMinutes
            if (budget > 0) dailyStats.todayMinutes.toFloat() / budget else 0f
        }
        LimitType.TIME_WINDOW -> 0f
    }.coerceIn(0f, 1f)

    val progressColor = if (dailyStats.limitExceeded)
        DetoxWarning
    else
        MaterialTheme.colorScheme.primary

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconImage(
                    packageName = dailyStats.appPackageName,
                    appName = dailyStats.appDisplayName,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dailyStats.appDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        DaysLeftBadge(daysRemaining = dailyStats.daysRemaining)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = buildLimitLabel(dailyStats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = buildProgressLabel(dailyStats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            if (dailyStats.blockAdultContent) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.challenge_card_adult_blocked),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (dailyStats.moneyLostCents > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.challenge_card_payment_captured,
                        dailyStats.moneyLostCents / 100f
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = DetoxWarning
                )
            }
        }
    }
}

@Composable
private fun DaysLeftBadge(daysRemaining: Int) {
    val label = if (daysRemaining > 3650)
        stringResource(R.string.challenge_card_no_end_date)
    else
        stringResource(R.string.challenge_card_days_remaining, daysRemaining)

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun AppIconImage(packageName: String?, appName: String, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        if (packageName == null) return@remember null
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
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
            contentScale = ContentScale.Fit
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = appName,
                modifier = Modifier
                    .padding(10.dp)
                    .size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun buildLimitLabel(stats: DailyStats): String = when (stats.limitType) {
    LimitType.TIME -> stringResource(R.string.challenge_card_time_limit, stats.limitValueMinutes)
    LimitType.SESSIONS -> stringResource(
        R.string.challenge_card_session_limit,
        stats.limitValueSessions ?: 0,
        stats.limitValueMinutes
    )
    LimitType.TIME_BUDGET -> stringResource(
        R.string.challenge_card_budget_limit,
        stats.dailyBudgetMinutes ?: stats.limitValueMinutes
    )
    LimitType.TIME_WINDOW -> stringResource(R.string.challenge_card_time_window_limit)
}

@Composable
private fun buildProgressLabel(stats: DailyStats): String = when (stats.limitType) {
    LimitType.TIME -> stringResource(
        R.string.challenge_card_time_progress,
        stats.todayMinutes,
        stats.limitValueMinutes
    )
    LimitType.SESSIONS -> stringResource(
        R.string.challenge_card_session_progress,
        stats.todayOpens,
        stats.limitValueSessions ?: 0
    )
    LimitType.TIME_BUDGET -> stringResource(
        R.string.challenge_card_budget_progress,
        stats.todayMinutes,
        stats.dailyBudgetMinutes ?: stats.limitValueMinutes,
        stats.budgetRemainingMinutes
            ?: ((stats.dailyBudgetMinutes ?: 0) - stats.todayMinutes)
    )
    LimitType.TIME_WINDOW -> stringResource(
        R.string.challenge_card_time_window_progress,
        stats.todayMinutes
    )
}
