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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.HapticManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import kotlin.math.abs

private val IndicatorClr = Color(0xFF00C853)
private val ArrowClr     = Color(0xFFCCCCCC)

private val ItemWidthDp = 68.dp
private val FadeWidthDp = 40.dp

// ── In-app smooth interpolation (Wave 2 restyle) ─────────────────────────────────
// The in-app picker scales size + colour by FRACTIONAL distance from centre (not integer
// buckets) so items grow/shrink smoothly as they pass the centre. The overlay (darkMode)
// path is untouched and keeps its integer `when(dist)` buckets.
//
// Colours are theme-resolved and passed in by the composable: the selected value is
// detoxColors.label, and the neighbour-dimming ramp fades label → cardBackground. On a
// white surface that lerp reproduces the original #000/#AAA/#CCC/#E0E0E0 grays exactly;
// in dark mode it fades white → #1A1A1A, so neighbours dim toward the dark card instead
// of rendering as unreadable black-on-dark.

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun lightFontSize(f: Float): Float = when {
    f <= 1f -> lerpF(40f, 22f, f)
    f <= 2f -> lerpF(22f, 16f, f - 1f)
    else    -> lerpF(16f, 14f, (f - 2f).coerceAtMost(1f))
}

private fun lightColor(f: Float, sel: Color, adj1: Color, adj2: Color, far: Color): Color = when {
    f <= 1f -> lerp(sel, adj1, f.coerceIn(0f, 1f))
    f <= 2f -> lerp(adj1, adj2, (f - 1f).coerceIn(0f, 1f))
    else    -> lerp(adj2, far, (f - 2f).coerceIn(0f, 1f))
}

/**
 * Horizontal scrollable number picker with snap behavior.
 *
 * Selected item is always centered, shown bold in black with a green underline indicator.
 * Adjacent items fade out in size and color. White gradient edges hide items softly.
 *
 * @param values    Ordered list of integer values to display (contiguous, step 1).
 * @param selectedValue  Currently selected value — must be in [values].
 * @param onValueChange  Called on each step change during scroll.
 * @param unit      Unit label shown below the picker row (e.g. "Minuten pro Tag"). Pass empty string to hide.
 * @param darkMode  When true, uses the "Calm Authority" overlay treatment: #0A0A0A background,
 *                  a large white selected value (50sp, tabular), dimmed neighbours, and a green
 *                  indicator dot ABOVE the value (instead of the light variant's underline).
 *                  This variant is used only by [BudgetSelectionOverlay].
 * @param enableHaptics  When false, the step-change haptic is suppressed. Overlays pass false
 *                  (overlays never add haptic feedback); in-app pickers keep the default true.
 * @param surfaceColor  LIGHT-mode background the picker band + edge fades blend into. Defaults to
 *                  white; callers on a non-white surface (e.g. the wizard's #F2F2F7) pass that colour
 *                  so the picker has no visible box. Ignored in dark mode (always #0A0A0A).
 */
@Composable
fun DetoxHorizontalPicker(
    values: List<Int>,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    unit: String,
    darkMode: Boolean = false,
    enableHaptics: Boolean = true,
    surfaceColor: Color = detoxColors.cardBackground,
) {
    val pickerBg     = if (darkMode) Color(0xFF0A0A0A) else surfaceColor
    // Dark-style (overlay + in-app dark) value/neighbour palette — read ONLY by the
    // `if (darkMode)` render branch. The in-app light-style branch resolves its own
    // theme colours below (indicatorClr / unitClr / the label→cardBackground ramp).
    val selectedClr  = Color(0xFFFFFFFF)
    val adjacent1Clr = Color(0xFF555555)
    val adjacent2Clr = Color(0xFF2E2E2E)
    val fartherClr   = Color(0xFF2E2E2E)
    val unitClr      = if (darkMode) Color(0xFF666666) else detoxColors.subtext
    val arrowClr     = if (darkMode) Color(0xFF3A3A3A) else ArrowClr
    // In-app theme colours (light-style branch): green centre-dot = brand accent;
    // neighbour-dimming ramp fades the label toward the card surface (see lightColor).
    val indicatorClr = if (darkMode) IndicatorClr else detoxColors.accent
    val rampSel  = detoxColors.label
    val rampAdj1 = lerp(detoxColors.label, detoxColors.cardBackground, 170f / 255f)
    val rampAdj2 = lerp(detoxColors.label, detoxColors.cardBackground, 204f / 255f)
    val rampFar  = lerp(detoxColors.label, detoxColors.cardBackground, 224f / 255f)

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(listState)
    val density = LocalDensity.current

    // Fractional-distance inputs for the smooth light-mode scaling (B4). Dark mode ignores these.
    val viewportCenterPx by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.viewportStartOffset + info.viewportEndOffset) / 2f
        }
    }
    val itemCentersPx by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.associate { it.index to (it.offset + it.size / 2f) }
        }
    }

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
            if (enableHaptics) HapticManager.light(context)
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
                .background(pickerBg),
        ) {
            // Dark (overlay) items are wider to seat the 50sp hero value + multi-digit budgets.
            val itemWidth = if (darkMode) 88.dp else ItemWidthDp
            val itemWidthPx = with(density) { itemWidth.toPx() }
            val sidePadding = (maxWidth - itemWidth) / 2

            LazyRow(
                state = listState,
                flingBehavior = snapFling,
                contentPadding = PaddingValues(horizontal = sidePadding),
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(values.size) { idx ->
                    val dist = abs(idx - centeredIndex)
                    // Smooth fractional distance from centre for the light branch (B4); dark uses `dist`.
                    val itemCenterPx = itemCentersPx[idx]
                    val fdist = if (itemCenterPx != null) abs(itemCenterPx - viewportCenterPx) / itemWidthPx
                                else dist.toFloat()
                    val fontSize = if (darkMode) {
                        when (dist) {
                            0    -> 48.sp   // hero selected value
                            1    -> 24.sp
                            2    -> 18.sp
                            else -> 15.sp
                        }
                    } else {
                        when (dist) {
                            0    -> 28.sp
                            1    -> 20.sp
                            2    -> 16.sp
                            else -> 14.sp
                        }
                    }
                    val fontWeight = if (dist == 0) FontWeight.Bold else FontWeight.Normal
                    val textColor = when (dist) {
                        0    -> selectedClr
                        1    -> adjacent1Clr
                        2    -> adjacent2Clr
                        else -> fartherClr
                    }

                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (darkMode) {
                            // "Calm Authority": green indicator dot ABOVE the selected value.
                            if (dist == 0) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(indicatorClr, CircleShape),
                                )
                            } else {
                                // Reserve dot space so the value baseline never shifts.
                                Spacer(Modifier.height(6.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = values[idx].toString(),
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                letterSpacing = if (dist == 0) (-2).sp else 0.sp,
                                style = TextStyle(fontFeatureSettings = "tnum"),  // tabular figures
                            )
                        } else {
                            // Light restyle: green indicator dot ABOVE the selected value (mirrors the
                            // dark overlay), with size + colour interpolated smoothly by fractional offset.
                            val near = fdist < 0.5f
                            if (dist == 0) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(indicatorClr, CircleShape),
                                )
                            } else {
                                // Reserve dot space so the value baseline never shifts.
                                Spacer(Modifier.height(6.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = values[idx].toString(),
                                fontSize = lightFontSize(fdist).sp,
                                fontWeight = if (near) FontWeight.Bold else FontWeight.Normal,
                                color = lightColor(fdist, rampSel, rampAdj1, rampAdj2, rampFar),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                letterSpacing = if (near) (-1).sp else 0.sp,
                                style = TextStyle(fontFeatureSettings = "tnum"),  // tabular figures
                            )
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
                        Brush.horizontalGradient(listOf(pickerBg, Color.Transparent)),
                    ),
            )

            // Right gradient fade
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(FadeWidthDp)
                    .background(
                        Brush.horizontalGradient(listOf(Color.Transparent, pickerBg)),
                    ),
            )

            // Arrow hints — DARK overlay only. The light picker drops the ←/→ glyphs entirely
            // (B1); its affordance is the green centre dot + smooth scaling instead.
            if (darkMode) {
                // Left arrow hint
                Text(
                    text = "←",
                    color = arrowClr,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp),
                )

                // Right arrow hint
                Text(
                    text = "→",
                    color = arrowClr,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                )
            }
        }

        if (unit.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = unit,
                fontSize = 14.sp,
                color = unitClr,
                textAlign = TextAlign.Center,
            )
        }
    }
}
