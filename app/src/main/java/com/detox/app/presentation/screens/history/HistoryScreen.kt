package com.detox.app.presentation.screens.history

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.presentation.screens.profile.HistoryItem
import com.detox.app.presentation.screens.profile.GroupHistoryRow
import com.detox.app.presentation.screens.profile.SoloHistoryRow

enum class HistoryFilter {
    ALL, SOLO, GROUP, WON, FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onGroupChallengeClick: (String) -> Unit = {},
    onSoloChallengeClick: (String) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val allItems by viewModel.historyItems.collectAsStateWithLifecycle()
    var activeFilter by remember { mutableStateOf(HistoryFilter.ALL) }

    val filtered = remember(allItems, activeFilter) {
        when (activeFilter) {
            HistoryFilter.ALL -> allItems
            HistoryFilter.SOLO -> allItems.filterIsInstance<HistoryItem.Solo>()
            HistoryFilter.GROUP -> allItems.filterIsInstance<HistoryItem.Group>()
            HistoryFilter.WON -> allItems.filter { item ->
                when (item) {
                    is HistoryItem.Solo -> item.entity.status == "completed"
                    is HistoryItem.Group -> item.myResult == "won"
                }
            }
            HistoryFilter.FAILED -> allItems.filter { item ->
                when (item) {
                    is HistoryItem.Solo -> item.entity.status == "failed"
                    is HistoryItem.Group -> item.myResult == "eliminated"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_screen_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Filter chips ───────────────────────────────────────────────────
            val filters = listOf(
                HistoryFilter.ALL to stringResource(R.string.history_filter_all),
                HistoryFilter.SOLO to stringResource(R.string.history_filter_solo),
                HistoryFilter.GROUP to stringResource(R.string.history_filter_group),
                HistoryFilter.WON to stringResource(R.string.history_filter_won),
                HistoryFilter.FAILED to stringResource(R.string.history_filter_failed),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { (filter, label) ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(label) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.profile_no_recent_activity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { item ->
                        when (item) {
                            is HistoryItem.Solo -> "solo_${item.entity.id}"
                            is HistoryItem.Group -> "group_${item.entity.groupId}"
                        }
                    }) { item ->
                        when (item) {
                            is HistoryItem.Solo -> SoloHistoryRow(
                                challenge = item.entity,
                                onClick = { onSoloChallengeClick(item.entity.id) }
                            )
                            is HistoryItem.Group -> GroupHistoryRow(
                                item = item,
                                onClick = { onGroupChallengeClick(item.entity.groupId) }
                            )
                        }
                    }
                }
            }
        }
    }
}
