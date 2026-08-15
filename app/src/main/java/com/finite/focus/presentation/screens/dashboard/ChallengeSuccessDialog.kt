package com.finite.focus.presentation.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finite.focus.R
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.ui.theme.DetoxCelebrationColors
import com.finite.focus.ui.theme.PoppinsFamily
import com.finite.focus.ui.theme.detoxColors
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.random.Random

private data class Particle(
    val xFraction: Float,
    val y0Fraction: Float,
    val speedFraction: Float,
    val rotation0: Float,
    val rotationSpeed: Float,
    val color: Color,
    val widthPx: Float,
    val heightPx: Float
)

@Composable
fun ChallengeSuccessDialog(
    challenge: Challenge,
    allLogs: List<DailyLog>,
    streak: Int,
    onDismiss: () -> Unit,
    onStartNewChallenge: () -> Unit,
    onViewHistory: () -> Unit
) {
    val resources = LocalContext.current.resources

    val refundEuros = floor((challenge.amountCents ?: 0) * 0.80) / 100.0
    val feeEuros = ((challenge.amountCents ?: 0) / 100.0) - refundEuros

    // The ONE metric line, derived from the challenge's own limit — see [winMetricLine]. It replaces
    // the old pair of usage-aggregated figures (a per-day average that rendered "Ø 0,0" whenever no
    // usage rows survived, plus a conscious-opens total restating the same fact).
    val metricLine = winMetricLine(challenge)

    // Animation phase flags
    var phase1Visible by remember { mutableStateOf(false) }
    var phase2Visible by remember { mutableStateOf(false) }
    var phase3Visible by remember { mutableStateOf(false) }
    var phase4Visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        phase1Visible = true
        delay(300)
        phase2Visible = true
        delay(300)
        phase3Visible = true
        delay(300)
        phase4Visible = true
    }

    // Count-up animations
    val refundAnim = remember { Animatable((refundEuros * 0.5).toFloat()) }
    val daysAnim = remember { Animatable(0f) }

    LaunchedEffect(phase2Visible) {
        if (phase2Visible) {
            refundAnim.animateTo(refundEuros.toFloat(), tween(800, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(phase2Visible) {
        // Soft branch: the days hero sits in the phase-2 card, so its count-up starts with the card.
        if (phase2Visible && challenge.mode != ChallengeMode.HARD) {
            daysAnim.animateTo(challenge.calendarDurationDays.toFloat(), tween(600, easing = FastOutSlowInEasing))
        }
    }

    // Confetti particles
    val particles = remember {
        List(35) {
            Particle(
                xFraction = Random.nextFloat(),
                y0Fraction = -Random.nextFloat() * 0.4f,
                speedFraction = 0.08f + Random.nextFloat() * 0.12f,
                rotation0 = Random.nextFloat() * 360f,
                rotationSpeed = (Random.nextFloat() - 0.5f) * 200f,
                color = DetoxCelebrationColors.Confetti.random(),
                widthPx = 8f + Random.nextFloat() * 10f,
                heightPx = 4f + Random.nextFloat() * 6f
            )
        }
    }
    val startTimeMs = remember { System.currentTimeMillis() }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { elapsedMs = System.currentTimeMillis() - startTimeMs }
        }
    }

    ResultDialogScaffold(
        onDismiss = onDismiss,
        background = {
            // Confetti canvas behind content
            Canvas(modifier = Modifier.matchParentSize()) {
                val t = (elapsedMs % 4000L) / 4000f
                particles.forEach { p ->
                    val y = ((p.y0Fraction + p.speedFraction * t * 4f) % 1.3f)
                    if (y > 0f) {
                        val rotation = p.rotation0 + p.rotationSpeed * t * 4f
                        withTransform({
                            translate(p.xFraction * size.width, y * size.height)
                            rotate(rotation)
                        }) {
                            drawRect(
                                color = p.color.copy(alpha = 0.85f),
                                topLeft = Offset(-p.widthPx / 2, -p.heightPx / 2),
                                size = Size(p.widthPx, p.heightPx)
                            )
                        }
                    }
                }
            }
        }
    ) {
                // Phase 1: Icon + title + subtitle + streak badge
                AnimatedVisibility(
                    visible = phase1Visible,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -20 }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Green icon
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(detoxColors.success),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = detoxColors.tileGlyph,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Title
                        Row {
                            Text(
                                text = stringResource(R.string.success_dialog_title_main),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = detoxColors.label
                            )
                            Text(
                                text = stringResource(R.string.success_dialog_title_dot),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = detoxColors.success
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Which challenge was won (blocked app names / domain / "Adult-Block")
                        Text(
                            text = challenge.appDisplayName,
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = detoxColors.label,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Subtitle
                        Text(
                            text = stringResource(R.string.success_dialog_subtitle, challenge.calendarDurationDays),
                            fontFamily = PoppinsFamily,
                            fontSize = 14.sp,
                            color = detoxColors.subtext,
                            textAlign = TextAlign.Center
                        )
                        // Streak badge — OPEN-ENDED challenges only. On a fixed-end win the streak
                        // is redundant (a win means every day was clean → streak == duration).
                        if (challenge.isOpenEndedChallenge && streak > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50.dp))
                                    .background(detoxColors.cardBackground)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50.dp))
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.success_dialog_streak_badge, streak),
                                    fontFamily = PoppinsFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = detoxColors.success
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Phase 2: Stats card
                AnimatedVisibility(
                    visible = phase2Visible,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 30 }
                ) {
                    ResultCard {
                        if (challenge.mode == ChallengeMode.HARD) {
                            // Hard Mode: money hero, then the days line + the one metric line.
                            Text(
                                text = stringResource(R.string.success_dialog_money_label),
                                fontFamily = PoppinsFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = detoxColors.subtext,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            ResultStatLine(
                                value = "€%.2f".format(refundAnim.value).replace('.', ','),
                                caption = stringResource(
                                    R.string.success_dialog_money_fee,
                                    "€%.2f".format(feeEuros).replace('.', ',')
                                ),
                                valueColor = detoxColors.success
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 12.dp),
                                thickness = 0.5.dp,
                                color = detoxColors.divider
                            )

                            // Phase 3: the one metric line, centered. (Was a 3-column row whose
                            // German captions clipped at 80dp, whose "% weniger Bildschirmzeit" was
                            // a capped unused-allowance share — ~99% for every winner, so it
                            // measured nothing — and whose day count the subtitle already states.)
                            AnimatedVisibility(
                                visible = phase3Visible,
                                enter = fadeIn(tween(300))
                            ) {
                                Text(
                                    text = metricLine,
                                    fontFamily = PoppinsFamily,
                                    fontSize = 13.sp,
                                    color = detoxColors.subtext,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                )
                            }
                        } else {
                            // Soft Mode: calendar-duration hero + the one metric line.
                            Text(
                                text = stringResource(R.string.success_dialog_days_label),
                                fontFamily = PoppinsFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = detoxColors.subtext,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val animatedDays = daysAnim.value.toInt()
                            ResultStatLine(
                                value = resources.getQuantityString(
                                    R.plurals.success_dialog_days_value, animatedDays, animatedDays
                                ),
                                caption = metricLine,
                                valueColor = detoxColors.success
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Phase 4: Buttons
                AnimatedVisibility(
                    visible = phase4Visible,
                    enter = fadeIn(tween(300))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Win keeps the green primary: this is the one outcome where "go again" is
                        // the honest next step.
                        ResultPrimaryButton(
                            text = stringResource(R.string.success_dialog_cta_new),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            onClick = onStartNewChallenge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ResultTextLink(
                            text = stringResource(R.string.success_dialog_cta_history),
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onViewHistory
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ResultTextLink(
                            text = stringResource(R.string.success_dialog_cta_back),
                            color = detoxColors.subtext,
                            onClick = onDismiss
                        )
                    }
                }
            }
}
