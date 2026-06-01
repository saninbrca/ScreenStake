package com.detox.app.presentation.screens.friends

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.BuildConfig
import com.detox.app.R
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsHubScreen(
    onCreateGroupChallenge: () -> Unit,
    onJoinGroupChallenge: () -> Unit,
    onGroupChallengeClick: (String) -> Unit,
    viewModel: FriendsHubViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val groupChallengeEnabled by viewModel.groupChallengeEnabled.collectAsStateWithLifecycle()
    val onForceStart: (String) -> Unit = { groupId -> viewModel.forceStartChallenge(groupId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onCreateGroupChallenge,
                                enabled = groupChallengeEnabled,
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

                    // Remote kill-switch note when Group Challenge creation is disabled.
                    if (!groupChallengeEnabled) {
                        item {
                            Text(
                                text = stringResource(R.string.feature_temporarily_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9500),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    if (state.data.active.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.friends_hub_active)) }
                        items(state.data.active) { gc ->
                            GroupChallengeCard(
                                groupChallenge = gc,
                                currentUserId = state.data.currentUserId,
                                onClick = { onGroupChallengeClick(gc.groupId) },
                                onForceStart = null
                            )
                        }
                    }

                    if (state.data.waiting.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.friends_hub_waiting)) }
                        items(state.data.waiting) { gc ->
                            GroupChallengeCard(
                                groupChallenge = gc,
                                currentUserId = state.data.currentUserId,
                                onClick = { onGroupChallengeClick(gc.groupId) },
                                onForceStart = if (BuildConfig.DEBUG) onForceStart else null
                            )
                        }
                    }

                    if (state.data.active.isEmpty() && state.data.waiting.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.friends_hub_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.friends_hub_empty_subtitle),
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
    currentUserId: String?,
    onClick: () -> Unit,
    onForceStart: ((String) -> Unit)?
) {
    val context = LocalContext.current
    val appIcon: ImageBitmap? = remember(groupChallenge.appPackageNames) {
        try {
            val pkg = groupChallenge.appPackageNames.firstOrNull() ?: return@remember null
            context.packageManager.getApplicationIcon(pkg).toBitmap(48, 48).asImageBitmap()
        } catch (_: Exception) { null }
    }

    when (groupChallenge.status) {
        GroupChallengeStatus.ACTIVE ->
            ActiveChallengeCard(groupChallenge, currentUserId, appIcon, onClick)
        else ->
            WaitingChallengeCard(groupChallenge, appIcon, onClick, onForceStart)
    }
}

@Composable
private fun ActiveChallengeCard(
    gc: GroupChallenge,
    currentUserId: String?,
    appIcon: ImageBitmap?,
    onClick: () -> Unit
) {
    val now = System.currentTimeMillis()
    val remainingMs = gc.endDate - now
    val remainingDays = remainingMs / (24L * 60 * 60 * 1000)
    val remainingHours = (remainingMs % (24L * 60 * 60 * 1000)) / (60 * 60 * 1000)

    LaunchedEffect(gc.endDate) {
        Timber.d("endDate=${gc.endDate} remaining=${remainingDays}d ${remainingHours}h")
    }

    val endTimeStr = remember(gc.endDate) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(gc.endDate))
    }
    val remainingLabel = when {
        remainingMs <= 0 -> "Abgelaufen"
        remainingDays > 1 -> "Noch $remainingDays Tage"
        remainingDays == 1L -> "Endet morgen"
        remainingHours > 0 -> "Endet heute um $endTimeStr Uhr"
        else -> "Abgelaufen"
    }
    val endDateStr = remember(gc.endDate) {
        if (gc.endDate > 0L) "Endet ${SimpleDateFormat("d. MMM", Locale.GERMAN).format(Date(gc.endDate))}"
        else "—"
    }
    val startDate = gc.startDate
    val endDate = gc.endDate
    val totalDuration = endDate - startDate
    val elapsed = now - startDate
    val progress = if (totalDuration > 0) {
        (elapsed.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Timber.d("Progress: start=$startDate end=$endDate now=$now progress=$progress")

    val potEuros = gc.participants.size * gc.buyInCents / 100

    val userRank: Int? = remember(gc.participants, currentUserId) {
        if (currentUserId == null) return@remember null
        val sorted = gc.participants.sortedBy { it.timeUsedMinutes }
        val idx = sorted.indexOfFirst { it.userId == currentUserId }
        if (idx >= 0) idx + 1 else null
    }
    val rankText = when (userRank) {
        1 -> "Du: #1 🏆"
        2 -> "Du: #2 🥈"
        3 -> "Du: #3 🥉"
        null -> null
        else -> "Du: #$userRank"
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: icon + app name + LIVE badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appIcon != null) {
                    Image(
                        painter = BitmapPainter(appIcon),
                        contentDescription = gc.appDisplayName,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = gc.appDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                LiveBadge()
            }

            Spacer(Modifier.height(12.dp))

            // Days progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = endDateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = remainingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            // Stats row: pot · rank · players
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (potEuros > 0) {
                    Text(
                        text = "€$potEuros Topf",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (rankText != null) {
                    Text(
                        text = rankText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "👥 ${gc.participants.size}/${gc.maxParticipants} Spieler",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WaitingChallengeCard(
    gc: GroupChallenge,
    appIcon: ImageBitmap?,
    onClick: () -> Unit,
    onForceStart: ((String) -> Unit)?
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val startInfo = when {
        gc.startDate == 0L -> "Wartet auf Start"
        gc.startDate > now -> "Startet am ${SimpleDateFormat("d. MMM", Locale.GERMAN).format(Date(gc.startDate))}"
        else -> "Startet bald"
    }
    val joined = gc.participants.size
    val max = gc.maxParticipants

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.medium
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: icon + app name + Warten badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appIcon != null) {
                    Image(
                        painter = BitmapPainter(appIcon),
                        contentDescription = gc.appDisplayName,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = gc.appDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                WaitingBadge()
            }

            Spacer(Modifier.height(12.dp))

            // Code block + share button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Beitritts-Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = gc.code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 4.sp
                    )
                }
                IconButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Tritt meiner Detox Group Challenge bei! Code: ${gc.code}"
                            )
                        }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Code teilen",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Players count
            Text(
                text = "👥 $joined/$max Spieler beigetreten${if (joined < 2) " — mind. 2 zum Starten" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Start date
            Text(
                text = startInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Buy-in
            if (gc.buyInCents > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Buy-in: €${gc.buyInCents / 100} · ${gc.durationDays} Tage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // DEBUG-only force-start
            if (onForceStart != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { onForceStart(gc.groupId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Force Start (DEBUG)")
                }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Box(
        modifier = Modifier
            .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "🟢 LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF2E7D32),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun WaitingBadge() {
    Box(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "⏳ Warten",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}
