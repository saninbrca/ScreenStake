package com.detox.app.presentation.screens.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsHubScreen(
    onCreateGroupChallenge: () -> Unit,
    onJoinGroupChallenge: () -> Unit,
    onGroupChallengeClick: (String) -> Unit,
    viewModel: FriendsHubViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.friends_hub_title)) })
        }
    ) { innerPadding ->
        when (val state = uiState) {
            FriendsHubUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            is FriendsHubUiState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── Action buttons ──────────────────────────────────────────
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onCreateGroupChallenge,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text(
                                    stringResource(R.string.friends_hub_create),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            OutlinedButton(
                                onClick = onJoinGroupChallenge,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.GroupAdd, contentDescription = null)
                                Text(
                                    stringResource(R.string.friends_hub_join),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }

                    // ── Active ──────────────────────────────────────────────────
                    if (state.data.active.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.friends_hub_active))
                        }
                        items(state.data.active) { gc ->
                            GroupChallengeCard(gc, onClick = { onGroupChallengeClick(gc.groupId) })
                        }
                    }

                    // ── Waiting (not started yet) ───────────────────────────────
                    if (state.data.waiting.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.friends_hub_waiting))
                        }
                        items(state.data.waiting) { gc ->
                            GroupChallengeCard(gc, onClick = { onGroupChallengeClick(gc.groupId) })
                        }
                    }

                    // ── History ─────────────────────────────────────────────────
                    if (state.data.history.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.friends_hub_history))
                        }
                        items(state.data.history) { gc ->
                            GroupChallengeCard(gc, onClick = { onGroupChallengeClick(gc.groupId) })
                        }
                    }

                    // ── Empty state ─────────────────────────────────────────────
                    if (state.data.active.isEmpty() && state.data.waiting.isEmpty() && state.data.history.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.friends_hub_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun GroupChallengeCard(
    groupChallenge: GroupChallenge,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = groupChallenge.appDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusChip(groupChallenge.status)
            }

            Spacer(Modifier.height(4.dp))

            // Code
            Text(
                text = "Code: ${groupChallenge.code}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(4.dp))

            // Participants
            val joined = groupChallenge.participants.size
            val max = groupChallenge.maxParticipants
            Text(
                text = "$joined/$max joined${if (groupChallenge.status == GroupChallengeStatus.WAITING && joined < 2) " — needs at least 2 to start" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Start date countdown or formatted date
            val now = System.currentTimeMillis()
            val diffMs = groupChallenge.startDate - now
            val startInfo = if (groupChallenge.status == GroupChallengeStatus.WAITING && diffMs > 0) {
                val days = TimeUnit.MILLISECONDS.toDays(diffMs)
                val hours = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
                "Starts in ${days}d ${hours}h"
            } else {
                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                "Started ${sdf.format(Date(groupChallenge.startDate))}"
            }
            Text(
                text = startInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Buy-in
            Text(
                text = "Buy-in: €${groupChallenge.buyInCents / 100} · ${groupChallenge.durationDays} days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusChip(status: GroupChallengeStatus) {
    val (label, color) = when (status) {
        GroupChallengeStatus.WAITING -> "Waiting" to MaterialTheme.colorScheme.secondary
        GroupChallengeStatus.ACTIVE -> "Active" to MaterialTheme.colorScheme.primary
        GroupChallengeStatus.COMPLETED -> "Done" to MaterialTheme.colorScheme.tertiary
        GroupChallengeStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
