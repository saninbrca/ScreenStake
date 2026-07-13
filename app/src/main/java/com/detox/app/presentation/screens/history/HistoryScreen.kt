package com.detox.app.presentation.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.ui.theme.detoxColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onChallengeClick: (String) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_screen_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.verlauf_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.entity.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = { onChallengeClick(entry.entity.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: SoloChallengeHistory, onClick: () -> Unit) {
    val entity = entry.entity
    val isCompleted = entity.status == "completed"
    val isGroup = !entity.groupChallengeId.isNullOrBlank()
    val isHard = entity.mode == "hard"
    val dateFormat = remember { SimpleDateFormat("d. MMM yyyy", Locale("de")) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.appDisplayName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = detoxColors.label
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateFormat.format(Date(entity.endDate)),
                    fontSize = 12.sp,
                    color = detoxColors.subtext
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TypeBadge(isGroup = isGroup, isHard = isHard)
                StatusText(isCompleted = isCompleted)
            }
        }
    }
}

@Composable
private fun TypeBadge(isGroup: Boolean, isHard: Boolean) {
    val bg: Color
    val textColor: Color
    val label: String
    when {
        isGroup -> {
            bg = detoxColors.badgePurpleBg
            textColor = detoxColors.badgePurpleFg
            label = stringResource(R.string.history_detail_mode_group)
        }
        isHard -> {
            bg = detoxColors.badgeOrangeBg
            textColor = detoxColors.badgeOrangeFg
            label = stringResource(R.string.verlauf_mode_hard)
        }
        else -> {
            bg = detoxColors.badgeGreenBg
            textColor = detoxColors.badgeGreenFg
            label = stringResource(R.string.verlauf_mode_soft)
        }
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
    }
}

@Composable
private fun StatusText(isCompleted: Boolean) {
    val color = if (isCompleted) detoxColors.success else detoxColors.danger
    val label = if (isCompleted)
        stringResource(R.string.verlauf_status_completed)
    else
        stringResource(R.string.verlauf_status_failed)
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color
    )
}
