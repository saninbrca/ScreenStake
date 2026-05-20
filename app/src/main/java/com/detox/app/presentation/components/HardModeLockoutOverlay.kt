package com.detox.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

/**
 * Full-screen permanent lockout overlay for Hard Mode challenges.
 *
 * Shown after the payment has been captured (money taken). App stays locked until midnight.
 * Single "Zurück" button — no bypass.
 *
 * @param amountCents   Amount already charged in euro-cents.
 * @param daysRemaining Days left in the challenge (shown for context).
 */
@Composable
fun HardModeLockoutOverlay(
    appName: String,
    amountCents: Int,
    daysRemaining: Int = 0,
    onExitHome: () -> Unit
) {
    val TextHint    = Color(0xFF555555)
    val SurfaceDark = Color(0xFF111111)

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
                Text(text = "🔐", fontSize = 48.sp, textAlign = TextAlign.Center)

                Spacer(Modifier.height(16.dp))

                // ── Title ──────────────────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_lockout_new_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.3).sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                // ── Subtitle ───────────────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_lockout_new_subtitle),
                    fontSize = 14.sp,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(28.dp))

                // ── Dark inset: stake + days ───────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.overlay_lockout_amount_stake, amountCents / 100),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (daysRemaining > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.overlay_lockout_days_remaining, daysRemaining),
                                fontSize = 12.sp,
                                color = TextHint
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Single back button ─────────────────────────────────────────────
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_lockout_back_button),
                    onClick = onExitHome
                )
            }
        }
    }
}
