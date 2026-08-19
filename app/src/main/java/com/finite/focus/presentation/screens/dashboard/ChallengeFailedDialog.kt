package com.finite.focus.presentation.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.finite.focus.R
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.ui.theme.PoppinsFamily
import com.finite.focus.ui.theme.detoxColors
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
 * The same classification as [failReasonStringRes], in row-sized words: "Limit überschritten"
 * rather than "Du hast dein Tageslimit überschritten."
 *
 * Kept immediately beside its sentence-length twin and matching it key-for-key ON PURPOSE. The two
 * are one classification in two registers, not two classifications — a `failReason` added to one
 * `when` and forgotten in the other is a bug, and putting them adjacent is what makes that
 * omission obvious. Do not move this elsewhere or let the key sets diverge.
 *
 * Used by the History list, where the full sentence would not fit and where every loss previously
 * collapsed to a single "Aufgegeben" regardless of what actually happened.
 */
@StringRes
fun failReasonShortStringRes(failReason: String?): Int = when (failReason) {
    "limit_exceeded" -> R.string.fail_reason_short_limit_exceeded
    "abandon" -> R.string.fail_reason_short_abandon
    "permission_violation" -> R.string.fail_reason_short_permission
    "usage_violation" -> R.string.fail_reason_short_usage
    "reconciliation" -> R.string.fail_reason_short_reconciliation
    else -> R.string.fail_reason_short_unknown
}

/**
 * RED loss result dialog — the unified screen shown on every Hard Mode loss path (worker capture,
 * abandon, permission violation). Mirrors [ChallengeSuccessDialog]'s layout via the shared
 * [ResultDialogScaffold] / [ResultCard] / [ResultStatLine], but with a red ✗ icon, "Challenge
 * verloren.", an "EINSATZ EINGEZOGEN" card, no confetti, and an optional comeback hint.
 *
 * Surfaced from the Dashboard whenever a Hard Mode challenge has `status='failed'` and
 * `completionShown=0`, via the result queue ([PendingResult.HardLoss]).
 *
 * Content rules this surface keeps (see [ResultCopy.kt]):
 *  - the stake is stated ONCE — it used to appear as both the card hero and a "€8 verloren" stat,
 *    which read as two separate charges;
 *  - the reason is prominent and as concrete as the data honestly allows ([lossReasonLine]);
 *  - days held are CALENDAR days ([daysHeldCalendar]), not a count of DailyLog rows.
 */
@Composable
fun ChallengeFailedDialog(
    challenge: Challenge,
    allLogs: List<DailyLog>,
    onDismiss: () -> Unit,
    onStartNewChallenge: () -> Unit
) {
    val lostCents = challenge.amountCents ?: 0
    val lostEurosCard = "€%.2f".format(lostCents / 100.0).replace('.', ',')
    val totalDays = challenge.calendarDurationDays
    val daysHeld = daysHeldCalendar(challenge, allLogs).coerceAtMost(totalDays)
    val reasonLine = lossReasonLine(challenge, allLogs)

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
                Spacer(modifier = Modifier.height(8.dp))
                // WHY it was lost — the concrete first breach when the logs support one, else the
                // honest generic cause. Carried at label weight, not as a whispered subtitle: this
                // is the single thing the user came to this dialog to find out.
                Text(
                    text = reasonLine,
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = detoxColors.danger,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = goalLine(challenge),
                    fontFamily = PoppinsFamily,
                    fontSize = 13.sp,
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
                // The stake, stated exactly once.
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

                // Phase 3: one centered line, no 3-column row (its 80dp captions clipped
                // "Tage durchgehalten" to "Tage durchgehalt" and its third column restated the
                // stake the card already shows).
                AnimatedVisibility(
                    visible = phase3Visible,
                    enter = fadeIn(tween(300))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ResultStatLine(
                            value = stringResource(
                                R.string.result_days_held_of_total, daysHeld, totalDays
                            ),
                            valueColor = detoxColors.label,
                            valueSize = 18.sp
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
                // Tone follows the outcome: the calm way out is the primary, and staking again is
                // demoted to the outline. A green "start a new challenge" moments after a capture
                // reads as the app pushing the user back to the table.
                ResultPrimaryButton(
                    text = stringResource(R.string.success_dialog_cta_back),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.height(12.dp))
                ResultOutlineButton(
                    text = stringResource(R.string.success_dialog_cta_new),
                    onClick = onStartNewChallenge
                )
            }
        }
    }
}
