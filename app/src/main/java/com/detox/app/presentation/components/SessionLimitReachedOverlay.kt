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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Stage 2 — Limit Reached Overlay (dark, minimal redesign).
 *
 * No bypass available — single "Stark bleiben 💪" button only.
 * Challenge can only be quit via Dashboard → Detail → "Aufgeben".
 */
@Composable
fun SessionLimitReachedOverlay(
    opensUsed: Int = 0,
    maxOpens: Int = 0,
    streak: Int = 0,
    onNo: () -> Unit
) {
    val AccentOrange = Color(0xFFFF9500)
    val TextHint     = Color(0xFF555555)
    val SurfaceDark  = Color(0xFF111111)
    val BorderDark   = Color(0xFF222222)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 72.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Emoji ──────────────────────────────────────────────────────────
            Text(text = "🔒", fontSize = 48.sp, textAlign = TextAlign.Center)

            Spacer(Modifier.height(16.dp))

            // ── Title ──────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_limit_reached_new_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // ── Subtitle ───────────────────────────────────────────────────────
            if (maxOpens > 0) {
                Text(
                    text = stringResource(R.string.overlay_limit_reached_new_subtitle, maxOpens),
                    fontSize = 14.sp,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Stats row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = opensUsed.toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange
                    )
                    Text(
                        text = stringResource(R.string.overlay_limit_reached_opens_label),
                        fontSize = 10.sp,
                        color = TextHint
                    )
                }
                if (streak > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = streak.toString(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.overlay_limit_reached_streak_label),
                            fontSize = 10.sp,
                            color = TextHint
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Progress bar (100%, orange) ────────────────────────────────────
            OverlayProgressBar(progress = 1f, trackColor = BorderDark, fillColor = AccentOrange)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (maxOpens > 0) {
                    Text(
                        text = stringResource(R.string.overlay_limit_reached_progress_label, opensUsed, maxOpens),
                        fontSize = 11.sp,
                        color = TextHint
                    )
                }
                Text(
                    text = stringResource(R.string.overlay_limit_reached_progress_full),
                    fontSize = 11.sp,
                    color = AccentOrange
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Hint inset ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.overlay_limit_reached_hint),
                    fontSize = 11.sp,
                    color = TextHint,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Single primary button ──────────────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.stay_strong_button),
                onClick = onNo
            )
        }
    }
}
