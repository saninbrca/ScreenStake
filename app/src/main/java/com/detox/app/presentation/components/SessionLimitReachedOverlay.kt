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
 * Stage 2 — Limit Reached Overlay (v2 redesign).
 *
 * Context header + large number + label are all computed in OverlayManager and passed in,
 * allowing this composable to handle SESSION_LIMIT, TIME_LIMIT, and DAILY_BUDGET exhausted.
 *
 * No bypass available — single "Stark bleiben 💪" button only.
 */
@Composable
fun SessionLimitReachedOverlay(
    appName: String = "",
    contextHeader: String = "",
    largeNumber: Int = 0,
    largeNumberLabel: String = "",
    onNo: () -> Unit
) {
    val AccentOrange = Color(0xFFFF9500)
    val BorderDark   = Color(0xFF222222)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // App name top-right
        if (appName.isNotEmpty()) {
            Text(
                text = appName,
                fontSize = 11.sp,
                color = Color(0xFF333333),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 72.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Context header (CHANGE 1) ──────────────────────────────────────
            if (contextHeader.isNotEmpty()) {
                Text(
                    text = contextHeader,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00C853),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }

            // ── Large number (CHANGE 2) ────────────────────────────────────────
            Text(
                text = largeNumber.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // ── Label below number ─────────────────────────────────────────────
            if (largeNumberLabel.isNotEmpty()) {
                Text(
                    text = largeNumberLabel,
                    fontSize = 13.sp,
                    color = Color(0xFF444444),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Progress bar (100%, orange — keep existing component) ──────────
            OverlayProgressBar(progress = 1f, trackColor = BorderDark, fillColor = AccentOrange)

            // ── Labels below bar (CHANGE 3) ────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.overlay_v2_progress_limit_reached),
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )
                Text(
                    text = "100%",
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── CHANGE 6 — limit reached clarification ─────────────────────────
            Text(
                text = stringResource(R.string.overlay_v2_limit_reached_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.overlay_v2_limit_reached_sub),
                fontSize = 13.sp,
                color = Color(0xFF444444),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            // ── Single primary button — no ghost (CHANGE 6) ────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.stay_strong_button),
                onClick = onNo
            )
        }
    }
}
