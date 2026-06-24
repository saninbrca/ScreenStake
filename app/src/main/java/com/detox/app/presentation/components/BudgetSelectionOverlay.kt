package com.detox.app.presentation.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

private val BgColor      = Color(0xFF0A0A0A)
private val AccentGreen  = Color(0xFF00C853)
private val TextSecond   = Color(0xFF666666)   // sub / unit / consequence base
private val TextEmphasis = Color(0xFF888888)   // the "Y min" portion (slight emphasis, same hue family)
private val MotivationClr = Color(0xFFAAAAAA)
private val AppNameColor  = Color(0xFF444444)   // blocked app name, top-right only
private val GhostColor    = Color(0xFF999999)   // legible, but never louder than the primary

/**
 * Full-screen daily budget overlay ("Calm Authority" redesign, stance "visible friction").
 *
 * The user is choosing time to SPEND — the opposite of the other A-family overlays — so the
 * friction here is VISIBILITY, not punishment. The remaining daily budget is the scarcity
 * anchor in the green ALL-CAPS eyebrow ("NOCH X MIN HEUTE"), and a live consequence line under
 * the picker ("danach noch Y min übrig") shows what's left after this session, recomputed as
 * the picker turns. Single green accent (#00C853) only — no red/orange "low budget" alarm.
 *
 * The picker defaults LOW (min(5, remaining)) — the quiet lever toward shorter sessions — and
 * is bounded by the remaining budget so the user can never pick more than they have left.
 *
 * The ghost button ("stark bleiben") stays visible and legible (#999): restraint is the good
 * choice here and is honestly offered, but never louder than the primary "X min starten".
 *
 * Visual-only: no flow/logic/money changes. FLAG_SECURE, the opaque background, no haptics
 * (the picker is passed `enableHaptics = false`) and per-show construction are preserved by the
 * host (OverlayManager.createSessionComposeView — built fresh, never pre-cached).
 */
@Composable
fun BudgetSelectionOverlay(
    packageName: String,
    appName: String,
    remainingMinutes: Int,
    /** The user's own custom motivation ("why"). Null/blank = not shown. */
    motivationText: String? = null,
    onStart: (Int) -> Unit,
    onGoBack: () -> Unit
) {
    // Picker is bounded by what's left — the user can never pick more than the remaining budget.
    val safeMax = remainingMinutes.coerceAtLeast(1)
    val pickerValues = remember(safeMax) { (1..safeMax).toList() }
    // Default LOW: the strongest quiet lever toward shorter sessions.
    val defaultSelection = minOf(5, safeMax)

    var selectedMinutes by remember { mutableIntStateOf(defaultSelection) }
    var showCountdown by remember { mutableStateOf(false) }

    // Live consequence: minutes left after this session. Recomputed on every picker step
    // because selectedMinutes is updated by the picker's onValueChange below.
    val leftoverMinutes = (remainingMinutes - selectedMinutes).coerceAtLeast(0)

    // Consequence line strings hoisted out of buildAnnotatedString (stringResource is @Composable).
    val consequenceBefore = stringResource(R.string.overlay_budget_consequence_before)
    val consequenceAmount = stringResource(R.string.overlay_budget_consequence_amount, leftoverMinutes)
    val consequenceAfter  = stringResource(R.string.overlay_budget_consequence_after)

    // Entrance: fade + slight upward translate of the centre block (~260ms ease-out),
    // matching SessionIntentionOverlay / SessionLimitReachedOverlay.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(260, easing = LinearOutSlowInEasing),
        label = "budgetContentAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)   // opaque immediately — never reveal the app behind
    ) {
        // App name top-right (#444, 11sp/400)
        Text(
            text = appName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            color = AppNameColor,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .graphicsLayer { alpha = contentAlpha }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 60.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Centre block floats between two weights → vertically centred, generous space.
            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = contentAlpha
                    translationY = (1f - contentAlpha) * 12.dp.toPx()
                }
            ) {
                // ── Eyebrow — scarcity anchor, spaced ALL-CAPS, the single green accent ──
                Text(
                    text = stringResource(R.string.overlay_budget_eyebrow, remainingMinutes),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccentGreen,
                    letterSpacing = 2.5.sp,
                    textAlign = TextAlign.Center
                )

                // ── User's own motivation ("why") — mirrors the decision overlays ──────
                val motivation = motivationText?.takeIf { it.isNotBlank() }
                if (motivation != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.overlay_motivation_quote, motivation),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = MotivationClr,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(28.dp))

                // ── Picker (dark A variant): the selected value is the 50sp hero, with a
                //    green dot above it and dimmed neighbours. No haptics in the overlay. ──
                DetoxHorizontalPicker(
                    values = pickerValues,
                    selectedValue = selectedMinutes,
                    onValueChange = { selectedMinutes = it },
                    unit = "",
                    darkMode = true,
                    enableHaptics = false
                )

                Spacer(Modifier.height(10.dp))

                // ── Unit label under the picker ───────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_budget_unit_minutes),
                    fontSize = 12.sp,
                    color = TextSecond,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(18.dp))

                // ── Live consequence line — updates as the picker turns ───────────────
                Text(
                    text = buildAnnotatedString {
                        append(consequenceBefore)
                        withStyle(SpanStyle(color = TextEmphasis)) { append(consequenceAmount) }
                        append(consequenceAfter)
                    },
                    fontSize = 13.sp,
                    color = TextSecond,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Actions pinned bottom ──────────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
            ) {
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_budget_start_session, selectedMinutes),
                    onClick = { showCountdown = true }
                )

                Spacer(Modifier.height(12.dp))

                // Ghost — visible & legible (restraint is the good choice here), never louder
                // than the primary.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        onClick = onGoBack,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.stay_strong_button),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            color = GhostColor
                        )
                    }
                }
            }
        }

        if (showCountdown) {
            CountdownScreen(
                packageName = packageName,
                appName = appName,
                onComplete = { onStart(selectedMinutes) },
                onCancel = onGoBack
            )
        }
    }
}
