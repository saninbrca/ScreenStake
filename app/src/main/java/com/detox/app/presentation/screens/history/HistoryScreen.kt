package com.detox.app.presentation.screens.history

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.presentation.screens.profile.HistoryItem
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HistoryFilter {
    ALL, WON, ELIMINATED, ABANDONED
}

private fun historyItemStatus(item: HistoryItem): String = when (item) {
    is HistoryItem.Solo -> when (item.entity.status) {
        "completed" -> "won"
        "failed" -> "eliminated"
        "abandoned" -> "abandoned"
        else -> "unknown"
    }
    is HistoryItem.Group -> item.myResult
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onGroupChallengeClick: (String) -> Unit = {},
    onSoloChallengeClick: (String) -> Unit = {},
    onRedemptionStarted: () -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val allItems by viewModel.historyItems.collectAsStateWithLifecycle()
    var activeFilter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }

    LaunchedEffect(Unit) {
        viewModel.redemptionStarted.collectLatest { onRedemptionStarted() }
    }

    val filtered = remember(allItems, activeFilter) {
        when (activeFilter) {
            HistoryFilter.ALL -> allItems
            HistoryFilter.WON -> allItems.filter { historyItemStatus(it) == "won" }
            HistoryFilter.ELIMINATED -> allItems.filter { historyItemStatus(it) == "eliminated" }
            HistoryFilter.ABANDONED -> allItems.filter {
                historyItemStatus(it) in listOf("abandoned", "cancelled")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_screen_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
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
                    containerColor = Color(0xFFF2F2F7)
                )
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Filter tabs ────────────────────────────────────────────────────
            val filters = listOf(
                HistoryFilter.ALL to stringResource(R.string.verlauf_filter_all),
                HistoryFilter.WON to stringResource(R.string.verlauf_filter_won),
                HistoryFilter.ELIMINATED to stringResource(R.string.verlauf_filter_eliminated),
                HistoryFilter.ABANDONED to stringResource(R.string.verlauf_filter_abandoned),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filters) { (filter, label) ->
                    VerlaufFilterTab(
                        label = label,
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter }
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
                        color = Color(0xFF8E8E93),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { item ->
                        when (item) {
                            is HistoryItem.Solo -> "solo_${item.entity.id}"
                            is HistoryItem.Group -> "group_${item.entity.groupId}"
                        }
                    }) { item ->
                        VerlaufChallengeCard(
                            item = item,
                            onCardClick = {
                                when (item) {
                                    is HistoryItem.Solo -> onSoloChallengeClick(item.entity.id)
                                    is HistoryItem.Group -> onGroupChallengeClick(item.entity.groupId)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Filter tab — underline style ──────────────────────────────────────────────

@Composable
private fun VerlaufFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.Black else Color(0xFF8E8E93)
        )
        Spacer(modifier = Modifier.height(4.dp))
        val indicatorWidth by animateDpAsState(
            targetValue = if (selected) 24.dp else 0.dp,
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            label = "filterIndicator"
        )
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(indicatorWidth)
                .background(Color(0xFF00C853), RoundedCornerShape(1.dp))
        )
    }
}

// ── Challenge card — minimal, tappable ────────────────────────────────────────

@Composable
private fun VerlaufChallengeCard(
    item: HistoryItem,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("d. MMM yyyy", Locale("de")) }

    val challengeName: String
    val subrowText: String
    val isGroup: Boolean
    val isRedemption: Boolean

    when (item) {
        is HistoryItem.Solo -> {
            val entity = item.entity
            val date = dateFormat.format(Date(entity.endDate))
            challengeName = entity.appDisplayName ?: entity.appPackageName ?: "App"
            isGroup = false
            isRedemption = entity.isRedemption != 0
            subrowText = when {
                entity.isRedemption != 0 -> stringResource(R.string.verlauf_subrow_comeback, date)
                entity.mode == "hard" -> stringResource(R.string.verlauf_subrow_hard, date)
                else -> stringResource(R.string.verlauf_subrow_soft, date)
            }
        }
        is HistoryItem.Group -> {
            val entity = item.entity
            val date = dateFormat.format(Date(entity.endDate))
            challengeName = entity.appDisplayName ?: "App"
            isGroup = true
            isRedemption = false
            subrowText = stringResource(
                R.string.verlauf_subrow_group,
                item.successCount,
                item.totalCount,
                date
            )
        }
    }

    val status = historyItemStatus(item)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(0.5.dp, Color(0x0F000000)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── Header row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    AppIconPlaceholder(
                        label = when {
                            isGroup -> "👥"
                            isRedemption -> "🔥"
                            else -> "📱"
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = challengeName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight(600),
                        color = Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(status = status)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Subrow ─────────────────────────────────────────────────────────
            Text(
                text = subrowText,
                fontSize = 13.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}

// ── Status badge ──────────────────────────────────────────────────────────────

@Composable
private fun StatusBadge(status: String) {
    val (bgColor, textColor, text) = when (status) {
        "won" -> Triple(Color(0xFFE8F8EF), Color(0xFF1E7A3C), stringResource(R.string.verlauf_badge_won))
        "eliminated" -> Triple(Color(0xFFFFF0E8), Color(0xFFC05A00), stringResource(R.string.verlauf_badge_eliminated))
        "abandoned", "cancelled" -> Triple(Color(0xFFF2F2F7), Color(0xFF8E8E93), stringResource(R.string.verlauf_badge_abandoned))
        else -> Triple(Color(0xFFF2F2F7), Color(0xFF8E8E93), status)
    }
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

// ── App icon placeholder ──────────────────────────────────────────────────────

@Composable
private fun AppIconPlaceholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFFF2F2F7), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 16.sp)
    }
}
