package com.finite.focus.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.finite.focus.R
import com.finite.focus.domain.model.ChallengeMode

@Composable
fun LimitExceededOverlay(
    appName: String,
    challengeMode: ChallengeMode = ChallengeMode.SOFT,
    amountCents: Int? = null,
    /** How many minutes the user has used today (shown in the stats row). */
    todayMinutes: Int = 0,
    /** The daily time limit in minutes (shown in the stats row). */
    limitMinutes: Int = 0,
    /** Current consecutive-day streak (before today). Hidden when 0. */
    streak: Int = 0,
    /**
     * True for the local mirror row of a group challenge. A group NEVER auto-fails on a limit
     * breach (`DailyEvaluationWorker.evaluateGroupChallenge` records the day for stats only) —
     * the buy-in is forfeited to the pot only by giving up in the group detail screen.
     */
    isGroupChallenge: Boolean = false,
    /** True when the stake already left the card — see [com.finite.focus.domain.model.StakeCapture]. */
    isStakeAlreadyCharged: Boolean = false,
    onStop: () -> Unit
) {
    @Suppress("KotlinConstantConditions")
    val isHardMode = challengeMode == ChallengeMode.HARD && amountCents != null

    // This overlay engages at `>=` the limit (CheckDailyLimitUseCase) while settlement only loses
    // at `>` (DailyEvaluationWorker.computeLimitExceeded, TIME) — the two predicates are MEANT to
    // diverge. So REACHING the limit is still a win and must never be told as a loss: only the
    // strictly-over case may talk about losing money, and it uses settlement's own comparison.
    val limitExceeded = todayMinutes > limitMinutes
    val bgColor = Color(0xFF0A0A0A)
    val accentColor = if (isHardMode) Color(0xFFFF4444) else Color(0xFF00C853)

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
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: title, app name, stats, message ──────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // "You're losing €X" is only true for a solo Hard Mode challenge that is actually
                // OVER its limit; at the limit, and for a group (which never auto-fails), the
                // neutral title is the honest one.
                val title = if (isHardMode && amountCents != null && limitExceeded && !isGroupChallenge) {
                    stringResource(R.string.limit_exceeded_hard_title, formatEuroCents(amountCents))
                } else {
                    stringResource(R.string.limit_exceeded_title)
                }
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHardMode) Color(0xFFFF6B6B) else Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = appName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                if (limitMinutes > 0) {
                    Text(
                        text = stringResource(R.string.limit_exceeded_time_used, todayMinutes, limitMinutes),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        textAlign = TextAlign.Center
                    )
                }

                val message = when {
                    !isHardMode || amountCents == null ->
                        stringResource(R.string.limit_exceeded_message)
                    // Still a win: blocked for today, stake untouched.
                    !limitExceeded ->
                        stringResource(R.string.limit_exceeded_hard_reached_message, formatEuroCents(amountCents))
                    // Group: the day is recorded for the leaderboard, nothing is charged.
                    isGroupChallenge ->
                        stringResource(R.string.limit_exceeded_group_message, formatEuroCents(amountCents))
                    // Solo loss — the money either already left the card at creation, or gets
                    // captured on the loss path at the daily evaluation.
                    isStakeAlreadyCharged ->
                        stringResource(R.string.limit_exceeded_hard_lost_charged_message, formatEuroCents(amountCents))
                    else ->
                        stringResource(R.string.limit_exceeded_hard_lost_capture_message, formatEuroCents(amountCents))
                }
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            // ── Middle: streak badge ───────────────────────────────────────────────
            Text(
                text = if (streak > 0) {
                    stringResource(R.string.streak_overlay_format, streak)
                } else {
                    stringResource(R.string.streak_overlay_zero)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            // ── Bottom: single action button ──────────────────────────────────────
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C853),
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = stringResource(R.string.overlay_primary_not_open),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    } // AnimatedVisibility
}
