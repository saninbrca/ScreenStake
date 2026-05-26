package com.detox.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import kotlinx.coroutines.delay

private val BlockingBgColor      = Color(0xFF0A0A0A)
private val BlockingAccentGreen  = Color(0xFF00C853)
private val BlockingAccentOrange = Color(0xFFFF9500)
private val BlockingSurfaceDark  = Color(0xFF111111)

@Composable
fun BlockingScreenOverlay(
    appName: String,
    contextHeader: String,
    valueUsed: Int,
    maxValue: Int,
    labelText: String,
    amountCents: Int?,
    showStakeWarning: Boolean,
    onStayStrong: () -> Unit,
    onOpenAnyway: () -> Unit
) {
    val remaining = (maxValue - valueUsed).coerceAtLeast(0)
    val progress  = if (maxValue > 0) valueUsed.toFloat() / maxValue else 0f

    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Count-up animation for large number
    var displayedValue by remember { mutableIntStateOf(if (valueUsed > 0) 0 else valueUsed) }
    LaunchedEffect(Unit) {
        if (valueUsed > 0) {
            delay(100L)
            val steps = valueUsed.coerceAtMost(20)
            val stepDelay = 300L / steps
            for (i in 1..steps) {
                displayedValue = valueUsed * i / steps
                delay(stepDelay)
            }
            displayedValue = valueUsed
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
                .background(BlockingBgColor)
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

                // Context header
                Text(
                    text = contextHeader,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BlockingAccentGreen,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                // Large number (animated count-up)
                Text(
                    text = displayedValue.toString(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-3).sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // Label below number
                Text(
                    text = labelText,
                    fontSize = 13.sp,
                    color = Color(0xFF444444),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(28.dp))

                // Progress indicator: dots if limit ≤ 10, bar otherwise
                if (maxValue <= 10) {
                    OverlayDotsIndicator(used = valueUsed, total = maxValue)
                } else {
                    OverlayProgressBar(
                        progress = progress.coerceIn(0f, 1f),
                        trackColor = Color(0xFF333333),
                        fillColor = BlockingAccentGreen
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_v2_progress_time_remaining, remaining),
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

                // Stake warning card — Group Hard Mode only
                if (showStakeWarning && (amountCents ?: 0) > 0) {
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(BlockingSurfaceDark)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                R.string.overlay_v2_group_stake_warning,
                                (amountCents ?: 0) / 100f
                            ),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = BlockingAccentOrange,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Primary button
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_primary_not_open),
                    onClick = onStayStrong
                )

                Spacer(Modifier.height(12.dp))

                // Ghost button — white text, 10sp, barely noticeable
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        onClick = onOpenAnyway,
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
        }
    }
}
