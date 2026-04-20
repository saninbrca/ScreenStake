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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        LimitType.TIME_BUDGET -> {
            val budget = dailyStats.dailyBudgetMinutes ?: dailyStats.limitValueMinutes
            if (budget > 0) dailyStats.todayMinutes.toFloat() / budget else 0f
        }
        // TIME_WINDOW has no usage limit — progress bar stays empty
        LimitType.TIME_WINDOW -> 0f
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
                            LimitType.TIME_BUDGET -> stringResource(
                                R.string.challenge_card_budget_limit,
                                dailyStats.dailyBudgetMinutes ?: dailyStats.limitValueMinutes
                            )
                            LimitType.TIME_WINDOW -> stringResource(R.string.challenge_card_time_window_limit)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    // Sentinel: durationDays >= 3650 means "no end date"
                    val daysLabel = if (dailyStats.daysRemaining > 3650)
                        stringResource(R.string.challenge_card_no_end_date)
                    else
                        stringResource(R.string.challenge_card_days_remaining, dailyStats.daysRemaining)
                    Text(
                        text = daysLabel,
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
                        LimitType.TIME_BUDGET -> stringResource(
                            R.string.challenge_card_budget_progress,
                            dailyStats.todayMinutes,
                            dailyStats.dailyBudgetMinutes ?: dailyStats.limitValueMinutes,
                            dailyStats.budgetRemainingMinutes
                                ?: ((dailyStats.dailyBudgetMinutes ?: 0) - dailyStats.todayMinutes)
                        )
                        LimitType.TIME_WINDOW -> stringResource(
                            R.string.challenge_card_time_window_progress,
                            dailyStats.todayMinutes
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Adult-content shield badge
            if (dailyStats.blockAdultContent) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.challenge_card_adult_blocked),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Hard Mode: show payment-captured banner if money was charged today
            if (dailyStats.moneyLostCents > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.challenge_card_payment_captured,
                        dailyStats.moneyLostCents / 100f
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = DetoxTertiary
                )
            }
        }
    }
}
