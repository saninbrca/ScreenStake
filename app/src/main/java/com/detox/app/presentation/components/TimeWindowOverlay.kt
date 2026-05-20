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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.detox.app.R
import kotlinx.coroutines.delay

/**
 * Shown when user opens an app outside its configured TIME_WINDOW_ONLY schedule (v2 redesign).
 *
 * Context header: "📅 Verfügbar ab HH:MM" computed from openTime.
 * Status text replaces the large number (no usage count for TIME_WINDOW).
 * Dark inset countdown card (CHANGE 7).
 * Single "Stark bleiben 💪" button — no bypass.
 */
@Composable
fun TimeWindowOverlay(
    appName: String,
    openTime: String,
    closeTime: String,
    minutesUntilOpen: Int,
    onDismiss: () -> Unit
) {
    val SurfaceDark = Color(0xFF111111)
    val BorderDark  = Color(0xFF222222)

    // Live countdown ticking down every minute
    var remaining by remember { mutableStateOf(minutesUntilOpen) }
    LaunchedEffect(minutesUntilOpen) {
        remaining = minutesUntilOpen
        while (remaining > 0) {
            delay(60_000L)
            remaining = (remaining - 1).coerceAtLeast(0)
        }
    }

    val hours   = remaining / 60
    val minutes = remaining % 60
    val countdownText = "%02d:%02d".format(hours, minutes)

    // Entrance animation
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
                text = stringResource(R.string.overlay_v2_header_time_window, openTime),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF00C853),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // ── Status text (CHANGE 7) — replaces large number for TIME_WINDOW ─
            Text(
                text = stringResource(R.string.overlay_v2_time_window_status),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // ── Sub text ───────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_v2_time_window_sub, openTime),
                fontSize = 13.sp,
                color = Color(0xFF444444),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // ── Dark inset countdown card (CHANGE 7) ───────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.overlay_time_window_available_in),
                        fontSize = 11.sp,
                        color = Color(0xFF444444)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = countdownText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = (-1).sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.overlay_time_window_hours_unit),
                        fontSize = 11.sp,
                        color = Color(0xFF444444)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Open / close time row (CHANGE 7) ──────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = openTime,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.overlay_time_window_opens_label),
                        fontSize = 10.sp,
                        color = Color(0xFF444444)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(Color(0xFF222222))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = closeTime,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.overlay_time_window_closes_label),
                        fontSize = 10.sp,
                        color = Color(0xFF444444)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Primary button: "Stark bleiben 💪" ────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.stay_strong_button),
                onClick = onDismiss
            )
        }
    }
    } // AnimatedVisibility
}
