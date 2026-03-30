package com.detox.app.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.detox.app.R
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.model.LimitType
import com.detox.app.ui.theme.DetoxTrackableGreen
import com.detox.app.ui.theme.DetoxTertiary

@Composable
fun ChallengeCard(
    dailyStats: DailyStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = when (dailyStats.limitType) {
        LimitType.TIME -> {
            if (dailyStats.limitValueMinutes > 0) {
                dailyStats.todayMinutes.toFloat() / dailyStats.limitValueMinutes
            } else 0f
        }
        LimitType.SESSIONS -> {
            val maxSessions = dailyStats.limitValueSessions ?: 1
            if (maxSessions > 0) {
                dailyStats.todayOpens.toFloat() / maxSessions
            } else 0f
        }
    }.coerceIn(0f, 1f)

    val progressColor = if (dailyStats.limitExceeded) DetoxTertiary else DetoxTrackableGreen

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dailyStats.appDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (dailyStats.limitType) {
                            LimitType.TIME -> stringResource(
                                R.string.challenge_card_time_limit,
                                dailyStats.limitValueMinutes
                            )
                            LimitType.SESSIONS -> stringResource(
                                R.string.challenge_card_session_limit,
                                dailyStats.limitValueSessions ?: 0,
                                dailyStats.limitValueMinutes
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.challenge_card_days_remaining, dailyStats.daysRemaining),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Usage progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (dailyStats.limitType) {
                        LimitType.TIME -> stringResource(
                            R.string.challenge_card_time_progress,
                            dailyStats.todayMinutes,
                            dailyStats.limitValueMinutes
                        )
                        LimitType.SESSIONS -> stringResource(
                            R.string.challenge_card_session_progress,
                            dailyStats.todayOpens,
                            dailyStats.limitValueSessions ?: 0
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = stringResource(R.string.challenge_card_points_today, dailyStats.pointsEarnedToday),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
