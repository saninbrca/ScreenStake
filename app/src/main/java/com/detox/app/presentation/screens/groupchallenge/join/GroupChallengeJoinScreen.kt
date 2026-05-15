package com.detox.app.presentation.screens.groupchallenge.join

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import coil.compose.rememberAsyncImagePainter
import com.detox.app.domain.model.LimitType
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

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
    val coroutineScope = rememberCoroutineScope()
    val joinedSuccessMessage = stringResource(R.string.join_group_joined_success)
    val paymentCancelledMessage = stringResource(R.string.join_group_payment_cancelled)
    val paymentFailedMessage = stringResource(R.string.join_group_payment_failed)

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentSuccess()
            is PaymentSheetResult.Canceled -> {
                viewModel.onPaymentCancelled()
                coroutineScope.launch { snackbarHostState.showSnackbar(paymentCancelledMessage) }
            }
            is PaymentSheetResult.Failed -> {
                viewModel.onPaymentCancelled()
                coroutineScope.launch { snackbarHostState.showSnackbar(paymentFailedMessage) }
            }
        }
    }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GroupJoinUiState.JoinedSuccessfully -> {
                snackbarHostState.showSnackbar(joinedSuccessMessage)
                onJoined()
            }
            is GroupJoinUiState.AwaitingPayment -> {
                paymentSheet.presentWithPaymentIntent(
                    paymentIntentClientSecret = s.paymentData.clientSecret,
                    configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App")
                )
            }
            is GroupJoinUiState.Error -> {
                // Post-payment errors show inline retry button — no snackbar needed.
                if (s.retryGroupChallenge == null) {
                    snackbarHostState.showSnackbar(s.message)
                }
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_group_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
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
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(stringResource(R.string.join_group_lookup))
            }

            // ── Challenge details card ──────────────────────────────────────────
            val previewGc: GroupChallenge? = when (val s = uiState) {
                is GroupJoinUiState.Preview -> s.groupChallenge
                is GroupJoinUiState.AwaitingPayment -> s.groupChallenge
                is GroupJoinUiState.ConfirmingJoin -> s.groupChallenge
                is GroupJoinUiState.Error -> s.retryGroupChallenge  // keep card visible for post-payment errors
                else -> null
            }

            previewGc?.let { gc ->
                GroupDetailsCard(gc)

                Spacer(Modifier.height(4.dp))

                val isLoading = uiState is GroupJoinUiState.ProcessingPayment ||
                    uiState is GroupJoinUiState.AwaitingPayment ||
                    uiState is GroupJoinUiState.ConfirmingJoin

                when {
                    isLoading -> {
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.join_group_confirming),
                                fontSize = 16.sp
                            )
                        }
                    }
                    uiState is GroupJoinUiState.Error -> {
                        OutlinedButton(
                            onClick = viewModel::clearError,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.join_group_retry),
                                fontSize = 16.sp
                            )
                        }
                    }
                    else -> {
                        Button(
                            onClick = { viewModel.initiatePayment(gc) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = uiState is GroupJoinUiState.Preview,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.join_group_join_and_pay,
                                    gc.buyInCents / 100
                                ),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDetailsCard(gc: GroupChallenge) {
    val context = LocalContext.current
    val appIcon: Drawable? = remember(gc.appPackageNames) {
        gc.appPackageNames.firstOrNull()?.let { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {

            // ── App row ──────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    androidx.compose.foundation.Image(
                        painter = rememberAsyncImagePainter(model = appIcon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = gc.appDisplayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Buy-in (prominent) ───────────────────────────────────────────────
            Text(
                text = stringResource(R.string.join_group_buy_in, gc.buyInCents / 100),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            // ── Limit ────────────────────────────────────────────────────────────
            val limitText = when (gc.limitType) {
                LimitType.SESSIONS -> stringResource(
                    R.string.join_group_limit_sessions,
                    gc.limitValueSessions ?: gc.limitValueMinutes
                )
                LimitType.TIME -> stringResource(R.string.join_group_limit_time, gc.limitValueMinutes)
                LimitType.TIME_BUDGET -> stringResource(R.string.join_group_limit_budget, gc.limitValueMinutes)
                LimitType.TIME_WINDOW -> stringResource(R.string.join_group_limit_time_window)
            }
            DetailRow(label = "📋", value = limitText)

            // ── Duration ─────────────────────────────────────────────────────────
            DetailRow(
                label = "⏱",
                value = stringResource(R.string.join_group_duration, gc.durationDays)
            )

            // ── Participants ─────────────────────────────────────────────────────
            DetailRow(
                label = "👥",
                value = stringResource(R.string.join_group_participants, gc.participants.size, gc.maxParticipants)
            )

            // ── Creator ──────────────────────────────────────────────────────────
            if (gc.creatorDisplayName.isNotBlank()) {
                DetailRow(
                    label = "👤",
                    value = stringResource(R.string.join_group_creator, gc.creatorDisplayName)
                )
            }

            // ── Start ────────────────────────────────────────────────────────────
            DetailRow(
                label = "🚀",
                value = stringResource(R.string.join_group_start_manual)
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 16.sp, modifier = Modifier.width(28.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
