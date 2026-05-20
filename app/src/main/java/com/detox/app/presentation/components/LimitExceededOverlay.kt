package com.detox.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.detox.app.R
import com.detox.app.domain.model.ChallengeMode

@Composable
fun LimitExceededOverlay(
    appName: String,
    challengeMode: ChallengeMode = ChallengeMode.SOFT,
    amountCents: Int? = null,
    /** How many minutes the user has used today (shown in the stats row). */
    todayMinutes: Int = 0,
    /** The daily time limit in minutes (shown in the stats row). */
    limitMinutes: Int = 0,
    /** Current consecutive-day streak (before today). Hidden when 0. */
    streak: Int = 0,
    onStop: () -> Unit
) {
    @Suppress("KotlinConstantConditions")
    val isHardMode = challengeMode == ChallengeMode.HARD && amountCents != null
    val bgColor = if (isHardMode) Color(0xFF1A0000) else Color(0xFF0D0D0D)
    val accentColor = if (isHardMode) Color(0xFFFF4444) else MaterialTheme.colorScheme.primary

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            initialScale = 0.95f
        )
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: title, app name, stats, message ──────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val title = if (isHardMode && amountCents != null) {
                    stringResource(R.string.limit_exceeded_hard_title, amountCents / 100f)
                } else {
                    stringResource(R.string.limit_exceeded_title)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isHardMode) Color(0xFFFF6B6B) else Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                if (limitMinutes > 0) {
                    Text(
                        text = stringResource(R.string.limit_exceeded_time_used, todayMinutes, limitMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        textAlign = TextAlign.Center
                    )
                }

                val message = if (isHardMode) {
                    stringResource(R.string.limit_exceeded_hard_message)
                } else {
                    stringResource(R.string.limit_exceeded_message)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            // ── Middle: streak badge ───────────────────────────────────────────────
            Text(
                text = if (streak > 0) {
                    stringResource(R.string.streak_overlay_format, streak)
                } else {
                    stringResource(R.string.streak_overlay_zero)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            // ── Bottom: single action button ──────────────────────────────────────
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32)
                )
            ) {
                Text(
                    text = stringResource(R.string.stay_strong_button),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
    } // AnimatedVisibility
}
