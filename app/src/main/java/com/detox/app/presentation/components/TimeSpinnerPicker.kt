package com.detox.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter

private val ITEM_HEIGHT: Dp = 56.dp
private val COLUMN_WIDTH: Dp = 76.dp
private const val VISIBLE_ITEMS = 3

/**
 * A drum-roll style time picker with scrollable hour and minute columns.
 * No keyboard — user scrolls with their finger. Items snap to center.
 *
 * @param hour       Currently selected hour (0–23)
 * @param minute     Currently selected minute (0–59)
 * @param label      Optional label displayed above the picker
 * @param onTimeChange Called whenever the selected time changes due to scroll
 */
@Composable
fun TimeSpinnerPicker(
    hour: Int,
    minute: Int,
    label: String = "",
    onTimeChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SpinnerColumn(
                values = hours,
                selectedIndex = hour.coerceIn(0, 23),
                onSelectedChange = { onTimeChange(it, minute) }
            )

            Text(
                text = ":",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            SpinnerColumn(
                values = minutes,
                selectedIndex = minute.coerceIn(0, 59),
                onSelectedChange = { onTimeChange(hour, it) }
            )
        }
    }
}

/**
 * A single scrollable drum-roll column.
 *
 * Layout: 1 blank padding item + n value items + 1 blank padding item.
 * This lets the first and last value be centered when scrolled all the way to the edge.
 * After snap: firstVisibleItemIndex == selectedValueIndex (0-based into the real values list).
 */
@Composable
private fun SpinnerColumn(
    values: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit
) {
    // Padded list: blank at top and bottom so edge values can be centered
    val displayItems = remember(values) { listOf("") + values + listOf("") }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, values.size - 1)
    )
    val flingBehavior = rememberSnapFlingBehavior(listState)

    // Derive selected display index from scroll position
    val centerDisplayIndex by remember {
        derivedStateOf {
            // firstVisibleItemIndex maps to values[firstVisibleItemIndex] being in center
            // center item in displayItems = displayItems[firstVisibleItemIndex + 1]
            listState.firstVisibleItemIndex + 1
        }
    }

    // When scroll settles, report the newly centered value to the caller
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val idx = listState.firstVisibleItemIndex.coerceIn(0, values.size - 1)
                onSelectedChange(idx)
            }
    }

    Box(
        modifier = Modifier
            .width(COLUMN_WIDTH)
            .height(ITEM_HEIGHT * VISIBLE_ITEMS)
            .clip(MaterialTheme.shapes.medium)
    ) {
        // Highlight strip for the center (selected) row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ITEM_HEIGHT)
                .align(Alignment.Center)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                )
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            userScrollEnabled = true
        ) {
            itemsIndexed(displayItems) { index, value ->
                val isSelected = index == centerDisplayIndex
                Box(
                    modifier = Modifier
                        .width(COLUMN_WIDTH)
                        .height(ITEM_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    if (value.isNotBlank()) {
                        Text(
                            text = value,
                            style = if (isSelected)
                                MaterialTheme.typography.headlineMedium
                            else
                                MaterialTheme.typography.titleLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }
    }
}
