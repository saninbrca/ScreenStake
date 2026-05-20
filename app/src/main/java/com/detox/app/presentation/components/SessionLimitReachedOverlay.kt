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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

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

    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Count-up animation for large number
    var displayedNumber by remember { mutableIntStateOf(if (largeNumber > 0) 0 else largeNumber) }
    LaunchedEffect(Unit) {
        if (largeNumber > 0) {
            delay(100L)
            val steps = largeNumber.coerceAtMost(20)
            val stepDelay = 300L / steps
            for (i in 1..steps) {
                displayedNumber = largeNumber * i / steps
                delay(stepDelay)
            }
            displayedNumber = largeNumber
        }
    }

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
                // ── Context header ─────────────────────────────────────────────────
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

                // ── Large number (animated count-up) ───────────────────────────────
                Text(
                    text = displayedNumber.toString(),
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

                // ── Progress bar (100%, orange) ────────────────────────────────────
                OverlayProgressBar(progress = 1f, trackColor = BorderDark, fillColor = AccentOrange)

                // ── Labels below bar ───────────────────────────────────────────────
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

                // ── Limit reached clarification ────────────────────────────────────
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

                // ── Single primary button — no ghost ───────────────────────────────
                OverlayPrimaryButton(
                    text = stringResource(R.string.stay_strong_button),
                    onClick = onNo
                )
            }
        }
    }
}
