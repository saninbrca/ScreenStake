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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
 * Shown when user opens an app outside its configured TIME_WINDOW_ONLY schedule.
 *
 * Displays a live countdown to when the window opens, plus open/close times.
 * Single "Verstanden 👍" button — no bypass.
 *
 * @param openTime  Scheduled open time, e.g. "09:00".
 * @param closeTime Scheduled close time, e.g. "22:00".
 * @param minutesUntilOpen Minutes remaining until the window opens. Updated externally.
 */
@Composable
fun TimeWindowOverlay(
    appName: String,
    openTime: String,
    closeTime: String,
    minutesUntilOpen: Int,
    onDismiss: () -> Unit
) {
    val TextHint    = Color(0xFF555555)
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
            // ── Emoji ──────────────────────────────────────────────────────────
            Text(text = "⏰", fontSize = 48.sp, textAlign = TextAlign.Center)

            Spacer(Modifier.height(16.dp))

            // ── Title ──────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_time_window_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // ── Subtitle ───────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_time_window_subtitle, openTime),
                fontSize = 14.sp,
                color = Color(0xFF666666),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            // ── Countdown inset ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.overlay_time_window_available_in),
                        fontSize = 11.sp,
                        color = TextHint
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = countdownText,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = (-1).sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.overlay_time_window_hours_unit),
                        fontSize = 11.sp,
                        color = TextHint
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Open / close time row ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = openTime,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.overlay_time_window_opens_label),
                        fontSize = 10.sp,
                        color = TextHint
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(BorderDark)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = closeTime,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.overlay_time_window_closes_label),
                        fontSize = 10.sp,
                        color = TextHint
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Single primary button ──────────────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.overlay_time_window_ok),
                onClick = onDismiss
            )
        }
    }
}
