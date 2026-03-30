package com.detox.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.detox.app.R
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.DailyLimitStatus

@Composable
fun BlockingScreenOverlay(
    status: DailyLimitStatus,
    totalPoints: Int,
    onOpenAnyway: () -> Unit,
    onSkip: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.blocking_overlay_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                // Custom motivation
                status.challenge.customMotivation?.let { motivation ->
                    Text(
                        text = motivation,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Usage progress
                val progressText = when (status.challenge.limitType) {
                    LimitType.TIME -> stringResource(
                        R.string.blocking_overlay_usage_time,
                        status.todayMinutes,
                        status.challenge.limitValueMinutes
                    )
                    LimitType.SESSIONS -> stringResource(
                        R.string.blocking_overlay_usage_sessions,
                        status.todayOpens,
                        status.challenge.limitValueSessions ?: 0
                    )
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val progress = when (status.challenge.limitType) {
                    LimitType.TIME -> {
                        if (status.challenge.limitValueMinutes > 0)
                            status.todayMinutes.toFloat() / status.challenge.limitValueMinutes
                        else 0f
                    }
                    LimitType.SESSIONS -> {
                        val max = status.challenge.limitValueSessions ?: 1
                        if (max > 0) status.todayOpens.toFloat() / max else 0f
                    }
                }.coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                // Points
                Text(
                    text = stringResource(R.string.blocking_overlay_points_today, totalPoints),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons
                Button(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.blocking_overlay_skip))
                }

                OutlinedButton(
                    onClick = onOpenAnyway,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.blocking_overlay_open_anyway))
                }
            }
        }
    }
}
