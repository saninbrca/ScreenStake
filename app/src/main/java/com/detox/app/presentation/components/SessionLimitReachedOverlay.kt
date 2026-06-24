package com.detox.app.presentation.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

// Tokens mirror SessionIntentionOverlay's "Calm Authority" palette (same hex values).
private val Stage2Bg      = Color(0xFF0A0A0A)
private val Stage2Accent  = Color(0xFF00C853)
private val Stage2Sub     = Color(0xFF666666)
private val Stage2AppName = Color(0xFF444444)

/**
 * Stage 2 — Limit Reached Overlay ("Calm Authority", direction "Done").
 *
 * Reframes the exhausted state as completion, not punishment: a calm closing statement
 * instead of a lock icon and a dead limit-number hero, and NO ghost button (the decision
 * is already made). Monochrome on #0A0A0A with a single green accent (#00C853) carried by
 * the eyebrow and the fully-filled completion line.
 *
 * [eyebrowText] is the per-type "Done" headline computed in OverlayManager (Soft → streak
 * held, Hard → stake secured, Group → rank held, else generic); rendered spaced ALL-CAPS.
 *
 * No bypass — single "Verstanden" button. FLAG_SECURE + no-haptics + opaque background are
 * preserved here and by the host; the view is built fresh per-show (no pre-cache).
 */
@Composable
fun SessionLimitReachedOverlay(
    appName: String = "",
    eyebrowText: String = "",
    onNo: () -> Unit
) {
    // Entrance: fade + slight upward translate of the centre block (~260ms ease-out),
    // matching Stage 1. No count-up — the hero is a statement, not a number.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = LinearOutSlowInEasing),
        label = "stage2ContentAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Stage2Bg)   // opaque immediately — never reveal the app behind
    ) {
        // App name top-right (#444, 11sp/400)
        if (appName.isNotEmpty()) {
            Text(
                text = appName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = Stage2AppName,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
                    .graphicsLayer { alpha = contentAlpha }
            )
        }

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
                // ── Eyebrow — spaced ALL-CAPS, the single green accent ─────────────
                if (eyebrowText.isNotEmpty()) {
                    Text(
                        text = eyebrowText.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Stage2Accent,
                        letterSpacing = 2.5.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // ── Hero — calm closing statement (text, not a number) ─────────────
                Text(
                    text = stringResource(R.string.overlay_v2_limit_hero),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-1).sp,
                    lineHeight = 35.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // ── Sub-label (#666) ───────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_v2_limit_sub),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Stage2Sub,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(30.dp))

                // ── Completion line — fully filled green (signal, not a progress bar) ──
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Stage2Accent)
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Single primary button — no ghost ───────────────────────────────────
            OverlayPrimaryButton(
                text = stringResource(R.string.overlay_primary_understood),
                onClick = onNo,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
            )
        }
    }
}
