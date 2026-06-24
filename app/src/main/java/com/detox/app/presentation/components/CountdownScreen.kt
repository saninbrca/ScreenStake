package com.detox.app.presentation.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import timber.log.Timber
import kotlin.math.ceil

private val BgColor      = Color(0xFF0A0A0A)
private val AccentGreen  = Color(0xFF00C853)
private val TextSecond   = Color(0xFF666666)
private val TrackRing    = Color(0xFF1E1E1E)
private val AppNameColor  = Color(0xFF444444)
private val CancelColor   = Color(0xFF999999)

private const val COUNTDOWN_TOTAL_MS = 5000L

/**
 * CountdownScreen — "Calm Authority" redesign (ring).
 *
 * The short cooldown beat shown AFTER the user commits to opening ("Ja, öffnen" / "X min
 * starten"), before the app is released. The 5 seconds are framed as a felt pause in which
 * the impulse can cool and the user can still back out — a draining green ring makes the
 * time visibly run down while the whole-second number stays the calm hero.
 *
 * Single continuous timer source: [withFrameNanos] tracks elapsed millis; the number is the
 * ceil of the remaining whole seconds (5→1) and the ring sweep is the continuous remaining
 * fraction, so the ring drains smoothly (sub-second) rather than stuttering once per second.
 * Total duration and completion are unchanged (5s → [onComplete]).
 *
 * Cancel routing is unchanged: the bottom "Abbrechen" stops the timer and calls [onCancel]
 * (caller decides home vs. back-to-prior-overlay); the app is NOT opened.
 */
@Composable
fun CountdownScreen(
    packageName: String,
    appName: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    var active by remember { mutableStateOf(true) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    // Single continuous timer source — drives BOTH the number and the ring sweep.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        Timber.d("Countdown started for $packageName")
        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            elapsedMs = (nowNanos - startNanos) / 1_000_000
            if (elapsedMs >= COUNTDOWN_TOTAL_MS) break
        }
        Timber.d("Countdown completed — opening $packageName")
        onComplete()
    }

    val remainingMs = (COUNTDOWN_TOTAL_MS - elapsedMs).coerceIn(0L, COUNTDOWN_TOTAL_MS)
    val seconds = ceil(remainingMs / 1000.0).toInt().coerceIn(0, 5)
    val ringFraction = remainingMs.toFloat() / COUNTDOWN_TOTAL_MS

    // Entrance: fade + slight upward translate of the centre block (~260ms ease-out),
    // matching the sibling overlays.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = LinearOutSlowInEasing),
        label = "countdownContentAlpha"
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
                    text = stringResource(R.string.countdown_calm_eyebrow),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccentGreen,
                    letterSpacing = 2.5.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(28.dp))

                // ── Draining ring with the whole-second number centred inside ───────
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(150.dp)) {
                        val strokeWidth = 5.dp.toPx()
                        val inset = strokeWidth / 2f
                        val arcSize = androidx.compose.ui.geometry.Size(
                            size.width - strokeWidth,
                            size.height - strokeWidth
                        )
                        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                        // Track — full circle
                        drawArc(
                            color = TrackRing,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        // Progress — drains from full to empty, starting at top
                        drawArc(
                            color = AccentGreen,
                            startAngle = -90f,
                            sweepAngle = 360f * ringFraction.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    Text(
                        text = seconds.toString(),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFeatureSettings = "tnum")  // tabular figures
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── Sub-label (#666) ────────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.countdown_calm_sub),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecond,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Cancel — honestly offered (legible #999), but quieter than the ring ─
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        onClick = {
                            active = false
                            Timber.d("Countdown cancelled — user backed out")
                            onCancel()
                        },
                        modifier = Modifier.height(44.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = CancelColor)
                    ) {
                        Text(
                            text = stringResource(R.string.countdown_calm_cancel),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
