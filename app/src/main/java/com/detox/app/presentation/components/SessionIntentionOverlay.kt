package com.detox.app.presentation.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import com.detox.app.presentation.util.pressScaleFeedback
import kotlinx.coroutines.delay

private val BgColor      = Color(0xFF0A0A0A)
private val AccentGreen  = Color(0xFF00C853)
private val TextSecond   = Color(0xFF666666)
private val TextHint     = Color(0xFF555555)
private val SurfaceDark  = Color(0xFF111111)
private val BorderDark   = Color(0xFF222222)
private val TrackDark    = Color(0xFF333333)

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

    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Count-up animation for large number
    var displayedOpens by remember { mutableIntStateOf(if (opensUsed > 0) 0 else opensUsed) }
    LaunchedEffect(Unit) {
        if (opensUsed > 0) {
            delay(100L)
            val steps = opensUsed.coerceAtMost(20)
            val stepDelay = 300L / steps
            for (i in 1..steps) {
                displayedOpens = opensUsed * i / steps
                delay(stepDelay)
            }
            displayedOpens = opensUsed
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
                .background(BgColor)
        ) {
            // App name top-right
            Text(
                text = appName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp)
                    .padding(top = 60.dp, bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))

                // ── Context header ─────────────────────────────────────────────────
                Text(
                    text = contextHeader,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentGreen,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                // ── Large number (animated count-up) ───────────────────────────────
                Text(
                    text = displayedOpens.toString(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-3).sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // ── Label below number ─────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_v2_label_sessions, maxOpens),
                    fontSize = 13.sp,
                    color = Color(0xFF444444),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(28.dp))

                // ── Progress indicator: dots if limit ≤ 10, bar otherwise ──────────
                if (maxOpens <= 10) {
                    OverlayDotsIndicator(used = opensUsed, total = maxOpens)
                } else {
                    OverlayProgressBar(progress = progress.coerceIn(0f, 1f), trackColor = TrackDark, fillColor = AccentGreen)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_v2_progress_sessions_remaining, remaining),
                            fontSize = 11.sp,
                            color = Color(0xFFAAAAAA)
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Primary button ─────────────────────────────────────────────────
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_primary_not_open),
                    onClick = onNo
                )

                Spacer(Modifier.height(12.dp))

                // ── Ghost button — barely visible, 10sp, 32dp ─────────────────────
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        onClick = { showCountdown = true },
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
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
}

@Composable
internal fun OverlayProgressBar(
    progress: Float,
    trackColor: Color = Color(0xFF333333),
    fillColor: Color = Color(0xFF00C853),
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 400, delayMillis = 150),
        label = "progressBar"
    )
    Box(modifier = modifier.height(8.dp)) {
        // Track
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor)
        )
        // Fill with glow — Canvas is not clipped by parent so glow bleeds naturally
        if (animatedProgress > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
            ) {
                val radius = 4.dp.toPx()
                val blurRadius = 8.dp.toPx()
                drawIntoCanvas { canvas ->
                    val glowPaint = Paint().apply {
                        asFrameworkPaint().apply {
                            isAntiAlias = true
                            color = fillColor.copy(alpha = 0.4f).toArgb()
                            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                    canvas.drawRoundRect(
                        left = 0f, top = 0f,
                        right = size.width, bottom = size.height,
                        radiusX = radius, radiusY = radius,
                        paint = glowPaint
                    )
                }
                drawRoundRect(
                    color = fillColor,
                    cornerRadius = CornerRadius(radius)
                )
            }
        }
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
        modifier = modifier.height(52.dp).pressScaleFeedback(),
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

/**
 * Row of circular dot indicators replacing the progress bar when limit ≤ 10.
 * Filled dots (#00C853) represent used slots; empty dots (#333333) represent remaining.
 */
@Composable
internal fun OverlayDotsIndicator(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (index < used) Color(0xFF00C853) else Color(0xFF333333))
            )
        }
    }
}
