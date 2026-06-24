package com.detox.app.presentation.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import com.detox.app.presentation.util.pressScaleFeedback

private val BgColor      = Color(0xFF0A0A0A)
private val AccentGreen  = Color(0xFF00C853)
private val TextSecond   = Color(0xFF666666)
private val TextHint     = Color(0xFF555555)
private val SurfaceDark  = Color(0xFF111111)
private val BorderDark   = Color(0xFF222222)
private val TrackDark    = Color(0xFF333333)
private val ProgressTrack = Color(0xFF1E1E1E)  // "Calm Authority" thin-line track
private val AppNameColor  = Color(0xFF444444)  // blocked app name, top-right only

/**
 * Stage 1 — Intention Check Overlay ("Calm Authority" redesign).
 *
 * The hero is now the REMAINING opens (maxOpens − opensUsed), not the used count, and
 * counts up on show. The screen is monochrome on #0A0A0A with a single green accent
 * (#00C853) carried by the spaced ALL-CAPS context header, the hero progress fill, and
 * the primary button. ~40% of the screen stays empty; actions are pinned to the bottom.
 *
 * Context header is computed in OverlayManager and passed as a pre-formatted string
 * (Streak / €Amount / Group rank depending on challenge type); the old emoji prefix is
 * stripped here (see [cleanHeader]).
 *
 * consciousOpens ONLY increments after the ghost button tap + countdown. The primary
 * button and back button go home without incrementing.
 */
@Composable
fun SessionIntentionOverlay(
    packageName: String,
    appName: String,
    contextHeader: String,
    opensUsed: Int,
    maxOpens: Int,
    /** The user's own custom motivation ("why"). Null/blank = not shown. */
    motivationText: String? = null,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    var showCountdown by remember { mutableStateOf(false) }
    val remaining = (maxOpens - opensUsed).coerceAtLeast(0)

    // Strip the old 🔥/💰/👥 emoji prefix and uppercase, in-place. Done here (not in
    // strings.xml) because the redesign is scoped to this overlay — the shared header
    // strings keep their emoji for the other overlays that still read them.
    val header = remember(contextHeader) { cleanHeader(contextHeader) }

    // Entrance: fade + slight upward translate of the centre block (~260ms ease-out),
    // and an integer count-up of the hero 0 → remaining (~600ms ease-out).
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val contentAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = LinearOutSlowInEasing),
        label = "overlayContentAlpha"
    )
    val displayedRemaining by animateIntAsState(
        targetValue = if (shown) remaining else 0,
        animationSpec = tween(600, easing = LinearOutSlowInEasing),
        label = "overlayHeroCountUp"
    )
    // Progress fill = REMAINING fraction, tracked to the count-up so the green line grows
    // in sync with the hero on show (and is shorter the more opens have been spent).
    val progressFraction = if (maxOpens > 0) displayedRemaining.toFloat() / maxOpens else 0f

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
                // ── Context header — spaced ALL-CAPS, the single green accent ───────
                Text(
                    text = header,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccentGreen,
                    letterSpacing = 2.5.sp,
                    textAlign = TextAlign.Center
                )

                // ── User's own motivation ("why") — primary placement at decision ──
                val motivation = motivationText?.takeIf { it.isNotBlank() }
                if (motivation != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.overlay_motivation_quote, motivation),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFFAAAAAA),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(32.dp))

                // ── Hero: REMAINING count, 64sp tabular, animated count-up ─────────
                Text(
                    text = displayedRemaining.toString(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-2).sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFeatureSettings = "tnum")  // tabular figures
                )

                Spacer(Modifier.height(12.dp))

                // ── Sub-label — "übrig" framing (#666) ─────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_v2_label_sessions_remaining),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecond,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(30.dp))

                // ── Progress line — fill = REMAINING, 3dp × 120dp centred ──────────
                RemainingProgressLine(fraction = progressFraction)
            }

            Spacer(Modifier.weight(1f))

            // ── Actions pinned bottom ──────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
            ) {
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_primary_not_open),
                    onClick = onNo
                )

                Spacer(Modifier.height(12.dp))

                // Ghost button — barely visible (white @ 28%), 10sp, 32dp
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        onClick = { showCountdown = true },
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White.copy(alpha = 0.28f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_ghost_open),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        if (showCountdown) {
            CountdownScreen(
                packageName = packageName,
                appName = appName,
                onComplete = onYes,
                onCancel = onNo
            )
        }
    }
}

/**
 * Strips a leading decorative glyph (the old 🔥 / 💰 / 👥 emoji prefix) and uppercases
 * the header for the spaced-caps "Calm Authority" treatment. Currency (€) and the rank
 * "#" are preserved as header content; only the leading emoji + whitespace is dropped.
 */
private fun cleanHeader(raw: String): String =
    raw.dropWhile { !it.isLetterOrDigit() && it != '€' && it != '#' }
        .trim()
        .uppercase()

/** Thin progress line — green fill = the fraction of the allowance still REMAINING. */
@Composable
private fun RemainingProgressLine(fraction: Float) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(ProgressTrack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(AccentGreen)
        )
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
            fontWeight = FontWeight.SemiBold
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
