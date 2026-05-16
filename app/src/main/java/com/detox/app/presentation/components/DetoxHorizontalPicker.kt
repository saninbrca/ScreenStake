package com.detox.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import kotlin.math.abs

private val PickerBg     = Color.White
private val SelectedClr  = Color(0xFF000000)
private val Adjacent1Clr = Color(0xFFAAAAAA)
private val Adjacent2Clr = Color(0xFFCCCCCC)
private val FartherClr   = Color(0xFFE0E0E0)
private val IndicatorClr = Color(0xFF00C853)
private val ArrowClr     = Color(0xFFCCCCCC)
private val UnitClr      = Color(0xFF8E8E93)

private val ItemWidthDp = 44.dp
private val FadeWidthDp = 40.dp

/**
 * Horizontal scrollable number picker with snap behavior.
 *
 * Selected item is always centered, shown bold in black with a green underline indicator.
 * Adjacent items fade out in size and color. White gradient edges hide items softly.
 *
 * @param values    Ordered list of integer values to display (contiguous, step 1).
 * @param selectedValue  Currently selected value — must be in [values].
 * @param onValueChange  Called on each step change during scroll.
 * @param unit      Unit label shown below the picker row (e.g. "Minuten pro Tag").
 */
@Composable
fun DetoxHorizontalPicker(
    values: List<Int>,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    unit: String,
) {
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(listState)

    val selectedIndex = remember(values, selectedValue) {
        values.indexOf(selectedValue).takeIf { it >= 0 } ?: 0
    }

    // Initial scroll — fires once, then sets initialized = true so haptic is gated.
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex)
        initialized = true
    }

    // Item whose center is closest to the viewport center.
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - viewCenter) }
                ?.index ?: selectedIndex
        }
    }

    // Notify parent and fire haptic on each step change.
    LaunchedEffect(centeredIndex) {
        if (!initialized) return@LaunchedEffect
        val newValue = values.getOrNull(centeredIndex) ?: return@LaunchedEffect
        if (newValue != selectedValue) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onValueChange(newValue)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(PickerBg),
        ) {
            val sidePadding = (maxWidth - ItemWidthDp) / 2

            LazyRow(
                state = listState,
                flingBehavior = snapFling,
                contentPadding = PaddingValues(horizontal = sidePadding),
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(values.size) { idx ->
                    val dist = abs(idx - centeredIndex)
                    val fontSize = when (dist) {
                        0    -> 28.sp
                        1    -> 20.sp
                        2    -> 16.sp
                        else -> 14.sp
                    }
                    val fontWeight = if (dist == 0) FontWeight.Bold else FontWeight.Normal
                    val textColor = when (dist) {
                        0    -> SelectedClr
                        1    -> Adjacent1Clr
                        2    -> Adjacent2Clr
                        else -> FartherClr
                    }

                    Column(
                        modifier = Modifier
                            .width(ItemWidthDp)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = values[idx].toString(),
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            color = textColor,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(3.dp))
                        if (dist == 0) {
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(2.dp)
                                    .background(IndicatorClr, RoundedCornerShape(1.dp)),
                            )
                        } else {
                            // Reserve space so text doesn't shift on selection change.
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            // Left gradient fade
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(FadeWidthDp)
                    .background(
                        Brush.horizontalGradient(listOf(PickerBg, Color.Transparent)),
                    ),
            )

            // Right gradient fade
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(FadeWidthDp)
                    .background(
                        Brush.horizontalGradient(listOf(Color.Transparent, PickerBg)),
                    ),
            )

            // Left arrow hint
            Text(
                text = "←",
                color = ArrowClr,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp),
            )

            // Right arrow hint
            Text(
                text = "→",
                color = ArrowClr,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = unit,
            fontSize = 14.sp,
            color = UnitClr,
            textAlign = TextAlign.Center,
        )
    }
}
