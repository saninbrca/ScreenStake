package com.detox.app.presentation.screens.groupchallenge.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.LimitType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeJoinScreen(
    onBack: () -> Unit,
    onJoined: () -> Unit,
    viewModel: GroupChallengeJoinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val codeInput by viewModel.codeInput.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState) {
        if (uiState is GroupJoinUiState.JoinedSuccessfully) onJoined()
        if (uiState is GroupJoinUiState.Error) {
            snackbarHostState.showSnackbar((uiState as GroupJoinUiState.Error).message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_group_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.join_group_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = codeInput,
                onValueChange = viewModel::onCodeChanged,
                label = { Text(stringResource(R.string.join_group_code_label)) },
                placeholder = { Text("ABCD12") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { viewModel.lookupCode() }),
                isError = uiState is GroupJoinUiState.Error,
                enabled = uiState !is GroupJoinUiState.LookingUp
            )

            Button(
                onClick = viewModel::lookupCode,
                modifier = Modifier.fillMaxWidth(),
                enabled = codeInput.length == 6 && uiState !is GroupJoinUiState.LookingUp
            ) {
                if (uiState is GroupJoinUiState.LookingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(stringResource(R.string.join_group_lookup))
            }

            // ── Preview card ────────────────────────────────────────────────────
            val previewGc = when (val s = uiState) {
                is GroupJoinUiState.Preview -> s.groupChallenge
                is GroupJoinUiState.AwaitingPayment -> s.groupChallenge
                is GroupJoinUiState.ProcessingPayment ->
                    (uiState as? GroupJoinUiState.AwaitingPayment)?.groupChallenge
                else -> null
            }

            previewGc?.let { gc ->
                GroupPreviewCard(gc)

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.initiatePayment(gc) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState is GroupJoinUiState.Preview
                ) {
                    if (uiState is GroupJoinUiState.ProcessingPayment) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        stringResource(
                            R.string.join_group_pay_button,
                            gc.buyInCents / 100
                        )
                    )
                }
            }

            // ── Payment sheet trigger ───────────────────────────────────────────
            val awaitingPayment = uiState as? GroupJoinUiState.AwaitingPayment
            if (awaitingPayment != null) {
                // In a real integration this triggers Stripe PaymentSheet.
                // For now we surface the clientSecret and simulate success.
                LaunchedEffect(awaitingPayment.paymentData.clientSecret) {
                    // TODO: launch Stripe PaymentSheet with awaitingPayment.paymentData.clientSecret
                    // On success: viewModel.onPaymentSuccess()
                    // On cancel: viewModel.onPaymentCancelled()
                }
            }
        }
    }
}

@Composable
private fun GroupPreviewCard(gc: GroupChallenge) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = gc.appDisplayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val limitSummary = when (gc.limitType) {
                LimitType.TIME -> "Max ${gc.limitValueMinutes} min/day"
                LimitType.SESSIONS -> "Max ${gc.limitValueSessions} opens/day"
                LimitType.TIME_BUDGET -> "Budget: ${gc.limitValueMinutes} min/day"
            }
            Text(text = limitSummary, style = MaterialTheme.typography.bodyMedium)
            Text(text = "${gc.durationDays} days", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.join_group_entry_fee, gc.buyInCents / 100),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${gc.participants.size}/${gc.maxParticipants} joined",
                style = MaterialTheme.typography.bodyMedium
            )
            val diffMs = gc.startDate - System.currentTimeMillis()
            val countdown = if (diffMs > 0) {
                val d = TimeUnit.MILLISECONDS.toDays(diffMs)
                val h = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
                "Starts in ${d}d ${h}h"
            } else {
                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                "Start: ${sdf.format(Date(gc.startDate))}"
            }
            Text(text = countdown, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (gc.bonusEnabled) {
                Text(
                    text = "🏆 Bonus for best performer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
