package com.detox.app.presentation.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

private val BgColor      = Color(0xFF0A0A0A)
private val AccentGreen  = Color(0xFF00C853)
private val TextSecond   = Color(0xFF666666)
private val SurfaceDark  = Color(0xFF111111)
private val InsetBorder  = Color(0xFF1E1E1E)
private val AppNameColor  = Color(0xFF444444)

/**
 * TimeWindowOverlay — "Calm Authority" redesign (countdown).
 *
 * Shown when the user opens an app OUTSIDE its allowed TIME_WINDOW_ONLY schedule. It is
 * info-only: nothing to decide, the app simply isn't available yet. Rather than a wall,
 * the screen gives the forward-looking countdown ("VERFÜGBAR IN 2:14") AND the durable
 * fact ("ab HH:MM wieder frei") so the message still reads after the relative time goes
 * stale. Monochrome on #0A0A0A with the single green accent on the eyebrow; no emoji,
 * no ghost button.
 *
 * The hero is a TIME, not a count — it fades/translates in like its siblings but does NOT
 * count up digit-by-digit. [minutesUntilOpen] is computed once at show-time in
 * OverlayManager; no live per-second tick (the overlay is dismissed in seconds and the
 * absolute "ab HH:MM" carries the durable info).
 *
 * Format mirrors the wait: ≥60 min → "H:MM" + "STUNDEN"; <60 min → bare minutes +
 * "MINUTEN". The unit label always agrees with the format shown.
 */
@Composable
fun TimeWindowOverlay(
    appName: String,
    openTime: String,
    minutesUntilOpen: Int,
    onDismiss: () -> Unit
) {
    val remaining = minutesUntilOpen.coerceAtLeast(0)
    val showAsHours = remaining >= 60
    val heroText = if (showAsHours) {
        "%d:%02d".format(remaining / 60, remaining % 60)
    } else {
        remaining.toString()
    }
    val unitText = stringResource(
        if (showAsHours) R.string.overlay_tw_calm_unit_hours
        else R.string.overlay_tw_calm_unit_minutes
    )

    // Entrance: fade + slight upward translate of the centre block (~260ms ease-out),
    // matching the sibling overlays. No count-up — the hero is a time, not a count.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = LinearOutSlowInEasing),
        label = "timeWindowContentAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)   // opaque immediately — never reveal the app behind
    ) {
        // App name top-right (#444, 11sp/400)
        Text(
            text = appName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            color = AppNameColor,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .graphicsLayer { alpha = contentAlpha }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 60.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Centre block floats between two weights → vertically centred, ~40% empty.
            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = contentAlpha
                    translationY = (1f - contentAlpha) * 12.dp.toPx()
                }
            ) {
                // ── Eyebrow — spaced ALL-CAPS, the single green accent ──────────────
                Text(
                    text = stringResource(R.string.overlay_tw_calm_eyebrow),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccentGreen,
                    letterSpacing = 2.5.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                // ── Countdown hero in a subtle inset card ───────────────────────────
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(SurfaceDark, RoundedCornerShape(16.dp))
                        .border(1.dp, InsetBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 26.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = heroText,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = (-2).sp,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFeatureSettings = "tnum")  // tabular figures
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = unitText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecond,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Absolute reopen time — the durable fact (#666) ──────────────────
                Text(
                    text = stringResource(R.string.overlay_tw_calm_reopen, openTime),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecond,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Primary button only — no ghost, no bypass ──────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
            ) {
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_tw_calm_button),
                    onClick = onDismiss
                )
            }
        }
    }
}
