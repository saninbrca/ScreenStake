package com.detox.app.presentation.components

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import com.detox.app.R

/**
 * Full-screen daily budget overlay (dark, minimal redesign).
 *
 * Horizontal scroll picker for session duration. Primary button = "X min starten".
 * Ghost button = "Stark bleiben 💪".
 *
 * @param budgetTotalMinutes Total daily budget in minutes (used for progress bar).
 */
@Composable
fun BudgetSelectionOverlay(
    packageName: String,
    appName: String,
    remainingMinutes: Int,
    budgetTotalMinutes: Int = remainingMinutes,
    onStart: (Int) -> Unit,
    onGoBack: () -> Unit
) {
    val usedMinutes = (budgetTotalMinutes - remainingMinutes).coerceAtLeast(0)
    val usedProgress = if (budgetTotalMinutes > 0) usedMinutes.toFloat() / budgetTotalMinutes else 0f

    val safeMax = remainingMinutes.coerceAtLeast(1)
    val defaultSelection = minOf(10, safeMax)

    var selectedMinutes by remember { mutableIntStateOf(defaultSelection) }
    var showCountdown by remember { mutableStateOf(false) }

    val AccentOrange = Color(0xFFFF9500)
    val TextHint     = Color(0xFF555555)
    val TextSecond   = Color(0xFF666666)
    val BorderDark   = Color(0xFF222222)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // App name top-right
        Text(
            text = appName,
            fontSize = 11.sp,
            color = Color(0xFF444444),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 72.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Subtitle ──────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_budget_label),
                fontSize = 11.sp,
                color = TextHint,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // ── Big remaining number ───────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = remainingMinutes.toString(),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "  ${stringResource(R.string.overlay_budget_min_remaining)}",
                    fontSize = 16.sp,
                    color = TextHint,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Progress bar (used %, orange) ──────────────────────────────────
            OverlayProgressBar(
                progress = usedProgress.coerceIn(0f, 1f),
                trackColor = BorderDark,
                fillColor = AccentOrange
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$usedMinutes / $budgetTotalMinutes min",
                    fontSize = 11.sp,
                    color = TextHint
                )
                Text(
                    text = "${(usedProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = AccentOrange
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Section label ─────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_budget_how_long),
                fontSize = 12.sp,
                color = TextSecond,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            // ── Horizontal scroll picker ──────────────────────────────────────
            DetoxHorizontalPicker(
                values = (1..safeMax).toList(),
                selectedValue = selectedMinutes,
                onValueChange = { selectedMinutes = it },
                unit = "Minuten",
            )

            Spacer(Modifier.weight(1f))

            // ── Primary button: start session ──────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.overlay_budget_start_session, selectedMinutes),
                onClick = { showCountdown = true }
            )

            Spacer(Modifier.height(12.dp))

            // ── Ghost button: stay strong ──────────────────────────────────────
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                TextButton(
                    onClick = onGoBack,
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF333333))
                ) {
                    Text(
                        text = stringResource(R.string.stay_strong_button),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }

        if (showCountdown) {
            CountdownScreen(
                packageName = packageName,
                onComplete = { onStart(selectedMinutes) },
                onCancel = onGoBack
            )
        }
    }
}
