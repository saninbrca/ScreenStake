package com.finite.focus.presentation.screens.softfail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finite.focus.R
import com.finite.focus.presentation.screens.dashboard.failReasonStringRes
import com.finite.focus.presentation.screens.dashboard.goalLine
import com.finite.focus.presentation.screens.dashboard.lossReasonLine
import com.finite.focus.ui.theme.detoxColors

/**
 * Full-screen Soft Mode loss result.
 *
 * NO money card, ever: a Soft challenge has no stake, so any "€" on this screen would be a fiction.
 * What it owes the user instead is WHY it ended — the concrete first breached day when the logs
 * support one, the honest generic cause otherwise — and what the goal had been.
 *
 * Button tone follows the outcome (see [com.finite.focus.presentation.screens.dashboard.ResultPrimaryButton]):
 * the calm way home is the primary, starting another challenge is the outline secondary.
 */
@Composable
fun SoftFailResultScreen(
    onNewChallenge: () -> Unit,
    onHome: () -> Unit,
    viewModel: SoftFailResultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val challenge = uiState.challenge

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Calendar days survived (never a log-row count). A day-1 fail gets its own copy —
            // a flexed "0 days! 💪" would be absurd.
            uiState.daysSurvived?.let { days ->
                Text(
                    text = if (days > 0) {
                        resources.getQuantityString(R.plurals.soft_fail_result_title_days, days, days)
                    } else {
                        stringResource(R.string.soft_fail_result_title_zero)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            uiState.appDisplayName?.let { name ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.failed_dialog_challenge_label, name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // WHY it ended: the first breached day where it is cleanly derivable, else the mapped
            // failReason. Until the challenge resolves, the reason alone is all we can state.
            Text(
                text = if (challenge != null) {
                    lossReasonLine(challenge, uiState.logs)
                } else {
                    stringResource(failReasonStringRes(uiState.failReason))
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = detoxColors.danger,
                textAlign = TextAlign.Center,
            )

            if (challenge != null) {
                Spacer(modifier = Modifier.height(6.dp))
                // The goal that was missed, in the challenge's own limit terms.
                Text(
                    text = goalLine(challenge),
                    style = MaterialTheme.typography.bodyMedium,
                    color = detoxColors.subtext,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.success_dialog_cta_back),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onNewChallenge,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.success_dialog_cta_new),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
