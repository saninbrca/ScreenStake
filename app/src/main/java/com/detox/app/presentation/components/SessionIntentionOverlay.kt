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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

private val BgColor      = Color(0xFF0A0A0A)
private val AccentGreen  = Color(0xFF00C853)
private val TextSecond   = Color(0xFF666666)
private val TextHint     = Color(0xFF555555)
private val SurfaceDark  = Color(0xFF111111)
private val BorderDark   = Color(0xFF222222)

/**
 * Stage 1 — Intention Check Overlay (dark, minimal redesign).
 *
 * consciousOpens ONLY increments after the ghost button tap + 5s countdown.
 * "Stark bleiben 💪" and back button go home without incrementing.
 *
 * @param challengeDaysLeft Days remaining in the challenge; Int.MAX_VALUE = no end date.
 */
@Composable
fun SessionIntentionOverlay(
    packageName: String,
    appName: String,
    opensUsed: Int,
    maxOpens: Int,
    lastSessionEndedAt: Long?,
    motivationText: String,
    streak: Int = 0,
    challengeDaysLeft: Int = Int.MAX_VALUE,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    var showCountdown by remember { mutableStateOf(false) }
    val remaining = (maxOpens - opensUsed).coerceAtLeast(0)
    val progress  = if (maxOpens > 0) opensUsed.toFloat() / maxOpens else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
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
            Text(text = "📱", fontSize = 48.sp, textAlign = TextAlign.Center)

            Spacer(Modifier.height(16.dp))

            // ── Title ──────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_intention_question),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // ── Streak line ────────────────────────────────────────────────────
            if (streak > 0) {
                Text(
                    text = stringResource(R.string.overlay_intention_streak_line, streak),
                    fontSize = 13.sp,
                    color = AccentGreen,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
            } else {
                Spacer(Modifier.height(20.dp))
            }

            // ── Stats row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCell(value = opensUsed.toString(), label = stringResource(R.string.overlay_intention_opens_today_label))
                StatCell(
                    value = remaining.toString(),
                    label = stringResource(R.string.overlay_intention_remaining_label),
                    valueColor = AccentGreen
                )
                if (challengeDaysLeft != Int.MAX_VALUE) {
                    StatCell(value = challengeDaysLeft.toString(), label = stringResource(R.string.overlay_intention_days_label))
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Progress bar ──────────────────────────────────────────────────
            OverlayProgressBar(progress = progress.coerceIn(0f, 1f), trackColor = BorderDark, fillColor = AccentGreen)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.overlay_intention_progress_label, opensUsed, maxOpens),
                    fontSize = 11.sp,
                    color = TextHint
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = AccentGreen
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Primary button ─────────────────────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.stay_strong_button),
                onClick = onNo
            )

            Spacer(Modifier.height(12.dp))

            // ── Ghost button ───────────────────────────────────────────────────
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                TextButton(
                    onClick = { showCountdown = true },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF333333))
                ) {
                    Text(
                        text = stringResource(R.string.overlay_ghost_open),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }

        if (showCountdown) {
            CountdownScreen(
                packageName = packageName,
                onComplete = onYes,
                onCancel = onNo
            )
        }
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    valueColor: Color = Color.White
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextHint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun OverlayProgressBar(
    progress: Float,
    trackColor: Color = Color(0xFF222222),
    fillColor: Color = Color(0xFF00C853),
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Box(
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .background(fillColor)
        )
    }
}

@Composable
internal fun OverlayPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00C853),
            contentColor = Color.Black
        )
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
