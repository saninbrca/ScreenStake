package com.finite.focus.presentation.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finite.focus.R
import com.finite.focus.domain.model.Challenge
import com.finite.focus.ui.theme.PoppinsFamily
import com.finite.focus.ui.theme.detoxColors

/**
 * NEUTRAL result acknowledgement for a challenge that ended without an observable outcome
 * ([com.finite.focus.domain.model.ChallengeStatus.ENDED_UNVERIFIED]) — typically a challenge that
 * finished while the app was uninstalled and was restored afterwards.
 *
 * Deliberately neither the green win nor the red loss surface: no confetti, no "Geschafft", no ✗,
 * no stats, and no numeric card — every figure this dialog could show would be invented, since the
 * whole reason it exists is that nothing was observed. Claiming a win the app never saw is the bug
 * it was built to fix, and claiming a loss would be an equally unfounded accusation. The honest
 * statement is "it's over, and we can't say how it went" — one line, then the way out.
 *
 * The primary action is the calm "back to Dashboard" in a NEUTRAL fill; starting another challenge
 * is offered as an outline secondary. Green here would celebrate an outcome nobody can vouch for.
 *
 * Colors are theme tokens only ([detoxColors] / `MaterialTheme.colorScheme`) — no literals, so it
 * renders correctly in light and dark.
 */
@Composable
fun ChallengeUnverifiedDialog(
    challenge: Challenge,
    onDismiss: () -> Unit,
    onStartNewChallenge: () -> Unit,
) {
    ResultDialogScaffold(onDismiss = onDismiss) {
        // Neutral icon on the muted card surface — not success, not danger.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(detoxColors.cardBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = detoxColors.subtext,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.unverified_dialog_title),
            fontFamily = PoppinsFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = detoxColors.label,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Which challenge this is about.
        Text(
            text = stringResource(R.string.failed_dialog_challenge_label, challenge.appDisplayName),
            fontFamily = PoppinsFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = detoxColors.label,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.unverified_dialog_body),
            fontFamily = PoppinsFamily,
            fontSize = 14.sp,
            color = detoxColors.subtext,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        ResultPrimaryButton(
            text = stringResource(R.string.success_dialog_cta_back),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onDismiss,
        )

        Spacer(modifier = Modifier.height(12.dp))

        ResultOutlineButton(
            text = stringResource(R.string.success_dialog_cta_new),
            onClick = onStartNewChallenge,
        )
    }
}
