package com.detox.app.presentation.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.detox.app.R
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.ui.theme.PoppinsFamily
import com.detox.app.ui.theme.detoxColors
import kotlinx.coroutines.delay

/**
 * Maps a stored [Challenge.failReason] to the German user-facing loss reason. `null` and any legacy/
 * unknown value (e.g. "client_loss") fall back to the generic text — never crashes. Shared by the
 * Hard loss dialog and the Soft fail screen.
 */
@StringRes
fun failReasonStringRes(failReason: String?): Int = when (failReason) {
    "limit_exceeded" -> R.string.fail_reason_limit_exceeded
    "abandon" -> R.string.fail_reason_abandon
    "permission_violation" -> R.string.fail_reason_permission
    "usage_violation" -> R.string.fail_reason_usage
    "reconciliation" -> R.string.fail_reason_reconciliation
    else -> R.string.fail_reason_unknown
}

/**
 * RED loss result dialog — the unified screen shown on every Hard Mode loss path (worker capture,
 * abandon, permission violation). Mirrors [ChallengeSuccessDialog]'s layout via the shared
 * [ResultDialogScaffold] / [ResultCard] / [StatColumn], but with a red ✗ icon, "Challenge verloren.",
 * an "EINSATZ EINGEZOGEN" card, no confetti, and an optional comeback hint.
 *
 * Surfaced from the Dashboard whenever a Hard Mode challenge has `status='failed'` and
 * `completionShown=0` (see `DashboardViewModel.getUnshownFailedHardChallenge`).
 */
@Composable
fun ChallengeFailedDialog(
    challenge: Challenge,
    allLogs: List<DailyLog>,
    onDismiss: () -> Unit,
    onStartNewChallenge: () -> Unit
) {
    val daysHeld = allLogs.count { !it.limitExceeded }
    val totalConsciousOpens = allLogs.sumOf { it.consciousOpens }
    val lostCents = challenge.amountCents ?: 0
    val lostEurosCard = "€%.2f".format(lostCents / 100.0).replace('.', ',')
    val lostEurosStat = "€${lostCents / 100}"

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

    ResultDialogScaffold(onDismiss = onDismiss) {
        // Phase 1: Icon + title + subtitle
        AnimatedVisibility(
            visible = phase1Visible,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -20 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Red icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(detoxColors.danger),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = detoxColors.tileGlyph,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Title
                Row {
                    Text(
                        text = stringResource(R.string.hard_fail_title),
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
                        color = detoxColors.danger
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Challenge identity (which challenge was lost)
                Text(
                    text = stringResource(R.string.failed_dialog_challenge_label, challenge.appDisplayName),
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = detoxColors.label,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Subtitle — the reason this challenge failed
                Text(
                    text = stringResource(failReasonStringRes(challenge.failReason)),
                    fontFamily = PoppinsFamily,
                    fontSize = 14.sp,
                    color = detoxColors.subtext,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Phase 2: Money card (captured stake)
        AnimatedVisibility(
            visible = phase2Visible,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 30 }
        ) {
            ResultCard {
                Text(
                    text = stringResource(R.string.failed_dialog_money_label),
                    fontFamily = PoppinsFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.subtext,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = lostEurosCard,
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    color = detoxColors.danger
                )

                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp),
                    thickness = 0.5.dp,
                    color = detoxColors.divider
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
                            value = daysHeld.toString(),
                            label = stringResource(R.string.success_dialog_stat_days),
                            valueColor = detoxColors.label
                        )
                        StatColumn(
                            value = totalConsciousOpens.toString(),
                            label = stringResource(R.string.success_dialog_stat_opens),
                            valueColor = detoxColors.label
                        )
                        StatColumn(
                            value = lostEurosStat,
                            label = stringResource(R.string.failed_dialog_stat_lost),
                            valueColor = detoxColors.danger
                        )
                    }
                }
            }
        }

        // Optional comeback hint (redemption window opens 24h after the loss)
        if (challenge.redemptionEligible) {
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedVisibility(
                visible = phase3Visible,
                enter = fadeIn(tween(300))
            ) {
                Text(
                    text = stringResource(R.string.failed_dialog_comeback),
                    fontFamily = PoppinsFamily,
                    fontSize = 12.sp,
                    color = detoxColors.subtext,
                    textAlign = TextAlign.Center
                )
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
                // Ripple derives from LocalContentColor — resolve it to the button's
                // own content color, not the frame's.
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { onStartNewChallenge() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.success_dialog_cta_new),
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.success_dialog_cta_back),
                    fontFamily = PoppinsFamily,
                    fontSize = 14.sp,
                    color = detoxColors.subtext,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }
        }
    }
}
