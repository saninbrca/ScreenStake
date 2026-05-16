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
 * Stage 1 — Intention Check Overlay (v2 redesign).
 *
 * Context header is computed in OverlayManager and passed as a pre-formatted string
 * (Streak / €Amount / Group rank depending on challenge type).
 *
 * consciousOpens ONLY increments after the ghost button tap + 5s countdown.
 * "Stark bleiben 💪" and back button go home without incrementing.
 */
@Composable
fun SessionIntentionOverlay(
    packageName: String,
    appName: String,
    contextHeader: String,
    opensUsed: Int,
    maxOpens: Int,
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
                text = contextHeader,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentGreen,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // ── Large number (CHANGE 2) ────────────────────────────────────────
            Text(
                text = opensUsed.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // ── Label below number ─────────────────────────────────────────────
            Text(
                text = stringResource(R.string.overlay_v2_label_sessions, maxOpens),
                fontSize = 13.sp,
                color = Color(0xFF444444),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // ── Progress bar (unchanged component) ────────────────────────────
            OverlayProgressBar(progress = progress.coerceIn(0f, 1f), trackColor = BorderDark, fillColor = AccentGreen)

            // ── Labels below bar (CHANGE 3) ────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.overlay_v2_progress_sessions_remaining, remaining),
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Primary button ─────────────────────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.stay_strong_button),
                onClick = onNo
            )

            Spacer(Modifier.height(12.dp))

            // ── Ghost button (CHANGE 4) — barely visible, 10sp #222, 32dp ─────
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                TextButton(
                    onClick = { showCountdown = true },
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF222222))
                ) {
                    Text(
                        text = stringResource(R.string.overlay_ghost_open),
                        fontSize = 10.sp,
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
