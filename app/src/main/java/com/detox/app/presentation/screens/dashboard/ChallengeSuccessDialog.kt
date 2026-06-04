package com.detox.app.presentation.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import com.detox.app.ui.theme.PoppinsFamily
import com.detox.app.util.DateUtils
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.random.Random

// Shared colors (DialogBg, AccentGreen, TextSecondary, CardBg, BadgeBorder) now live in
// ResultDialogComponents.kt and are reused by both the win and loss dialogs.
private val ConfettiColors = listOf(
    Color(0xFFFF9500),
    Color(0xFF00C853),
    Color(0xFF7B61FF),
    Color(0xFFFF3B30)
)

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
    onStartNewChallenge: () -> Unit
) {
    val totalConsciousOpens = allLogs.sumOf { it.consciousOpens }

    val totalUsedMinutes: Double = when (challenge.limitType) {
        LimitType.TIME -> allLogs.sumOf { it.totalMinutes }.toDouble()
        LimitType.TIME_BUDGET -> allLogs.sumOf { it.budgetUsedMs }.toDouble() / 60_000.0
        LimitType.SESSIONS -> (totalConsciousOpens * challenge.sessionDurationMinutes).toDouble()
        else -> 0.0
    }

    val budgetMinutesPerDay: Double = when (challenge.limitType) {
        LimitType.TIME -> challenge.limitValueMinutes.toDouble()
        LimitType.TIME_BUDGET -> (challenge.dailyBudgetMinutes ?: challenge.limitValueMinutes).toDouble()
        LimitType.SESSIONS -> ((challenge.limitValueSessions ?: 1) * challenge.sessionDurationMinutes).toDouble()
        else -> challenge.limitValueMinutes.toDouble()
    }

    val totalBudgetMinutes = challenge.durationDays * budgetMinutesPerDay
    val savedMinutes = (totalBudgetMinutes - totalUsedMinutes).coerceAtLeast(0.0)
    val savedHours = savedMinutes / 60.0

    val reductionPercent = if (totalBudgetMinutes > 0) {
        ((1.0 - totalUsedMinutes / totalBudgetMinutes) * 100).toInt().coerceIn(0, 99)
    } else 0

    val refundEuros = floor((challenge.amountCents ?: 0) * 0.80) / 100.0
    val feeEuros = ((challenge.amountCents ?: 0) / 100.0) - refundEuros

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
    val mainStatAnim = remember { Animatable((savedHours * 0.5).toFloat()) }
    val refundAnim = remember { Animatable((refundEuros * 0.5).toFloat()) }
    val opensAnim = remember { Animatable(0f) }
    val reductionAnim = remember { Animatable(0f) }
    val daysAnim = remember { Animatable(0f) }

    LaunchedEffect(phase2Visible) {
        if (phase2Visible) {
            mainStatAnim.animateTo(savedHours.toFloat(), tween(800, easing = FastOutSlowInEasing))
            refundAnim.animateTo(refundEuros.toFloat(), tween(800, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(phase3Visible) {
        if (phase3Visible) {
            daysAnim.animateTo(challenge.durationDays.toFloat(), tween(600, easing = FastOutSlowInEasing))
            opensAnim.animateTo(totalConsciousOpens.toFloat(), tween(600, easing = FastOutSlowInEasing))
            reductionAnim.animateTo(reductionPercent.toFloat(), tween(600, easing = FastOutSlowInEasing))
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
                color = ConfettiColors.random(),
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
                                .background(AccentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
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
                                color = Color.Black
                            )
                            Text(
                                text = stringResource(R.string.success_dialog_title_dot),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = AccentGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // Subtitle
                        Text(
                            text = stringResource(R.string.success_dialog_subtitle, challenge.durationDays),
                            fontFamily = PoppinsFamily,
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // Streak badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(CardBg)
                                .border(1.dp, BadgeBorder, RoundedCornerShape(50.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.success_dialog_streak_badge, streak),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AccentGreen
                            )
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
                            // Hard Mode: money card
                            Text(
                                text = stringResource(R.string.success_dialog_money_label),
                                fontFamily = PoppinsFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "€%.2f".format(refundAnim.value).replace('.', ','),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 36.sp,
                                color = AccentGreen
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.success_dialog_money_fee, "€%.2f".format(feeEuros).replace('.', ',')),
                                fontFamily = PoppinsFamily,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        } else {
                            // Soft Mode: time saved card
                            Text(
                                text = stringResource(R.string.success_dialog_time_label),
                                fontFamily = PoppinsFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "%.1f Std.".format(mainStatAnim.value).replace('.', ','),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 36.sp,
                                color = AccentGreen
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.success_dialog_time_subtext),
                                fontFamily = PoppinsFamily,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp),
                            thickness = 0.5.dp,
                            color = DialogBg
                        )

                        // Phase 3: 3-column stats
                        AnimatedVisibility(
                            visible = phase3Visible,
                            enter = fadeIn(tween(300))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatColumn(
                                    value = daysAnim.value.toInt().toString(),
                                    label = stringResource(R.string.success_dialog_stat_days),
                                    valueColor = Color.Black
                                )
                                StatColumn(
                                    value = opensAnim.value.toInt().toString(),
                                    label = stringResource(R.string.success_dialog_stat_opens),
                                    valueColor = Color.Black
                                )
                                StatColumn(
                                    value = "${reductionAnim.value.toInt()}%",
                                    label = stringResource(R.string.success_dialog_stat_reduction),
                                    valueColor = AccentGreen
                                )
                            }
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AccentGreen)
                                .clickable { onStartNewChallenge() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.success_dialog_cta_new),
                                fontFamily = PoppinsFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.success_dialog_cta_back),
                            fontFamily = PoppinsFamily,
                            fontSize = 14.sp,
                            color = TextSecondary,
                            modifier = Modifier.clickable { onDismiss() }
                        )
                    }
                }
            }
}

/** Computes `durationDays` from a Challenge, handling both timestamp and day-offset endDate formats. */
private val Challenge.durationDays: Int
    get() {
        val endDateMs = if (endDate > 1_700_000_000_000L) endDate
        else startDate + (endDate * DateUtils.MILLIS_PER_DAY)
        return ((endDateMs - startDate) / DateUtils.MILLIS_PER_DAY).toInt().coerceAtLeast(1)
    }
