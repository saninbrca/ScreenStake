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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Full-screen daily budget overlay (v2 redesign).
 *
 * Context header = "⏱ X min übrig heute" computed from remainingMinutes.
 * Dark chip picker for session duration (CHANGE 5).
 * No ghost button (CHANGE 4 — ghost only on SessionIntentionOverlay).
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
    val pickerValues = (1..safeMax).toList()
    val defaultSelection = minOf(5, safeMax)

    var selectedMinutes by remember { mutableIntStateOf(defaultSelection) }
    var showCountdown by remember { mutableStateOf(false) }

    val AccentOrange = Color(0xFFFF9500)
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
            color = Color(0xFF333333),
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
            // ── Context header (CHANGE 1) ──────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_v2_header_budget, remainingMinutes),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF00C853),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // ── Large remaining number (CHANGE 2) ──────────────────────────────
            Text(
                text = remainingMinutes.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // ── Label below number ─────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_v2_label_budget, budgetTotalMinutes),
                fontSize = 13.sp,
                color = Color(0xFF444444),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // ── Progress bar (used %, orange — keep existing component) ────────
            OverlayProgressBar(
                progress = usedProgress.coerceIn(0f, 1f),
                trackColor = BorderDark,
                fillColor = AccentOrange
            )

            // ── Labels below bar (CHANGE 3) ────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.overlay_v2_progress_budget_used, usedMinutes),
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )
                Text(
                    text = "${(usedProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Horizontal scroll picker (dark mode) ──────────────────────────
            DetoxHorizontalPicker(
                values = pickerValues,
                selectedValue = selectedMinutes,
                onValueChange = { selectedMinutes = it },
                unit = "",
                darkMode = true
            )

            Spacer(Modifier.height(6.dp))

            // ── Label below picker ─────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_v2_chips_label),
                fontSize = 11.sp,
                color = Color(0xFF333333),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            // ── Primary button: start session ──────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.overlay_budget_start_session, selectedMinutes),
                onClick = { showCountdown = true }
            )

            // No ghost button (CHANGE 4)
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
