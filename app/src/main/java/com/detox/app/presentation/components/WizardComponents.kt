package com.detox.app.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.detox.app.R
import com.detox.app.presentation.util.pressScaleFeedback
import com.detox.app.ui.theme.detoxColors

/**
 * Shared chrome for the challenge-creation wizards (Solo/Hard and Group).
 *
 * Both wizards render the same header, limit-type cards, review-summary rows, fee breakdown
 * and consent checkbox, so they live here once instead of being duplicated per screen — the
 * Group wizard previously carried near-copies that had visibly drifted (emoji instead of
 * icons, no animations, different paddings).
 */

/** Card corner radius shared by every wizard card. */
val WizardCardShape = RoundedCornerShape(16.dp)

/** Primary-button corner radius shared by every wizard CTA. */
val WizardBtnShape = RoundedCornerShape(14.dp)

/** Dialog corner radius — M3's own dialog radius, so the picker reads as a dialog. */
private val WizardDialogShape = RoundedCornerShape(28.dp)

/**
 * The width Material3's [DatePicker] refuses to go below (`DatePickerModalTokens
 * .ContainerWidth`, applied internally as a `sizeIn(minWidth = …)`). Not exposed by
 * the library, so it is mirrored here — see [WizardDatePickerDialog].
 */
private val DatePickerMinWidth = 360.dp

/** Step-transition duration/easing — the header progress bar and the step slide stay in sync. */
const val WIZARD_TRANSITION_MS = 300
val WizardTransitionEasing = LinearOutSlowInEasing

// ── Wizard header ─────────────────────────────────────────────────────────────

@Composable
fun WizardHeader(
    currentStep: Int,
    totalSteps: Int,
    onBack: () -> Unit,
) {
    // Progress fraction is unchanged (currentStep/totalSteps); only the RENDERED value is animated
    // so the bar fills smoothly between steps instead of jumping (~300ms ease-out).
    val progress = currentStep.toFloat() / totalSteps.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = WIZARD_TRANSITION_MS, easing = WizardTransitionEasing),
        label = "wizard_progress",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.wizard_back),
                    tint = detoxColors.label,
                )
            }
            Text(
                text = stringResource(R.string.wizard_step_progress, currentStep, totalSteps),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = detoxColors.subtext,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

// ── Limit-type selection card ─────────────────────────────────────────────────

@Composable
fun WizardLimitTypeCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) detoxColors.accent else detoxColors.cardBorder,
        animationSpec = tween(150), label = "limit_border_color",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.5.dp,
        animationSpec = tween(150), label = "limit_border_width",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) detoxColors.selectedSurface else detoxColors.cardBackground,
        animationSpec = tween(150), label = "limit_bg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScaleFeedback()
            .clip(WizardCardShape)
            .background(bgColor)
            .border(borderWidth, borderColor, WizardCardShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label,
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = detoxColors.subtext,
                    maxLines = 2,
                )
            }
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                val checkScale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(150), label = "limit_check",
                )
                if (!isSelected) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(detoxColors.cardBackground)
                            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = detoxColors.accent,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale },
                )
            }
        }
    }
}

// ── Derived-result row ────────────────────────────────────────────────────────

/**
 * "What you just chose actually means THIS" — a computed consequence of the inputs above it.
 *
 * Third member of the wizard's card family, and deliberately built on the exact chassis of
 * [WizardInfoBulletRow] / [WizardLimitTypeCard] (40dp icon circle, 22dp glyph, 14dp padding,
 * [WizardCardShape], `cardBackground` over a 0.5dp `cardBorder`) so a step reads as one stack of
 * cards rather than as inputs with loose grey text underneath. It differs from the info row in what
 * the type does: the info row is a title over a paragraph, this is a small [caption] over a [value]
 * the eye is meant to land on FIRST.
 *
 * [prominent] is the rank inside the family, not a separate style: `true` is for the result the
 * user is actively steering (its inputs are right above it and it changes as they scroll), `false`
 * for a result that merely restates a setting. Rank is carried by the value's size alone — both
 * keep the same chassis, so they still read as siblings. For context that is NOT a result of the
 * inputs, use [WizardHintLine] instead; it sits a rank below this and has no card.
 *
 * The value cross-fades on change so a live recomputation is visible rather than an instant
 * substitution, and uses tabular figures so digits do not jitter while a picker is being scrolled.
 */
@Composable
fun WizardResultRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    caption: String?,
    value: String,
    prominent: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WizardCardShape)
            .background(detoxColors.cardBackground)
            .border(0.5.dp, detoxColors.cardBorder, WizardCardShape)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (caption != null) {
                    Text(
                        text = caption,
                        fontSize = 13.sp,
                        color = detoxColors.subtext,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        // Short, non-bouncy: this fires on every picker detent, so anything longer
                        // would lag behind a fast scroll. clip = false keeps the row from being
                        // visibly re-measured when the digit count changes (9 → 10 min).
                        (fadeIn(tween(120)) togetherWith fadeOut(tween(90)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "wizard_result_value",
                ) { shown ->
                    Text(
                        text = shown,
                        fontSize = if (prominent) 20.sp else 17.sp,
                        fontWeight = if (prominent) FontWeight.Bold else FontWeight.SemiBold,
                        color = detoxColors.label,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

/**
 * Quiet one-liner of context that is NOT a result of the inputs — today's reality the user is
 * choosing against, e.g. what they currently average.
 *
 * The lowest rank of the [WizardResultRow] family: same icon-then-text reading order, but no card,
 * no circle and body-sized subtext, so it can sit under an input without ever competing with it.
 * A card here would out-weigh the picker it belongs to and read as a second limit.
 */
@Composable
fun WizardHintLine(
    icon: ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        // Top, not Center: the sentence wraps to two lines on narrow screens, and a centred icon
        // would float off on its own beside the block instead of marking where the note starts.
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = detoxColors.subtext,
            // 2dp nudge puts the glyph on the cap-height rather than the line box top.
            modifier = Modifier.padding(top = 2.dp).size(14.dp),
        )
        Text(
            // 12sp is the wizard's existing hint size (the weekday card's "no selection = every
            // day"), so this matches an established rank instead of inventing one — and it earns
            // back the width the glyph costs, which at 13sp broke a one-line sentence into a
            // two-line block with an orphan.
            text = text,
            fontSize = 12.sp,
            color = detoxColors.subtext,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Info bullet row ───────────────────────────────────────────────────────────

/**
 * One explanatory row: tinted icon circle + a scannable [title] over its supporting [text].
 *
 * Same geometry and tokens as [WizardLimitTypeCard] (40dp circle, 22dp glyph, 14dp padding,
 * [WizardCardShape], `cardBackground`/`cardBorder`) and the same 15sp-SemiBold-label over
 * subtext type pair, minus the selection affordances — this row is read-only, so it has no press
 * feedback, no border animation and no check mark.
 *
 * [borderColor]/[borderWidth]/[titleColor] default to the neutral card. Overriding them is how a
 * caller marks ONE row as the one that matters, reusing the wizard's own "this card is special"
 * idiom (the accent border [WizardLimitTypeCard] uses for selection) instead of a fill tint — a
 * tinted fill would swallow the icon circle, which is drawn from the same soft* family.
 */
@Composable
fun WizardInfoBulletRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    text: String,
    titleColor: Color = detoxColors.label,
    borderColor: Color = detoxColors.cardBorder,
    borderWidth: Dp = 0.5.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WizardCardShape)
            .background(detoxColors.cardBackground)
            .border(borderWidth, borderColor, WizardCardShape)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    lineHeight = 20.sp,
                )
                Text(
                    text = text,
                    fontSize = 14.sp,
                    color = detoxColors.subtext,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

// ── Review-summary row ────────────────────────────────────────────────────────

@Composable
fun WizardSummaryDividerRow(
    label: String,
    value: String,
    valueColor: Color = detoxColors.label,
    isFirst: Boolean = false,
) {
    if (!isFirst) {
        HorizontalDivider(color = detoxColors.divider, thickness = 0.5.dp)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // BOTH columns are weighted. With only the label weighted, the value was
        // measured at its full intrinsic width first and a long one ("Aktiviert
        // (10% des Pots)") starved the label column until a single German word no
        // longer fit — at which point Compose falls back to breaking mid-word
        // ("Gewinner-B / onus"). Capping the value at half the row keeps every
        // label wide enough to wrap at word boundaries instead, in both languages.
        // fill = false so a short value still renders at its natural width and the
        // slack goes between the columns rather than padding the value out.
        Text(
            text = label,
            fontSize = 14.sp,
            color = detoxColors.subtext,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── Fee breakdown card ────────────────────────────────────────────────────────

/** Formats integer cents as a German money string, e.g. 800 → "€8,00". */
fun formatEuroCents(cents: Int): String =
    "€%d,%02d".format(cents / 100, cents % 100)

/**
 * Stake/buy-in breakdown shown on the review step.
 *
 * [notes] renders as italic footnote lines under the rows — that is where asterisk footnotes
 * belong; never append them to a row's value, which would push prose into the bold money column.
 */
@Composable
fun WizardFeeBreakdownCard(
    stakeLabel: String,
    stakeValue: String,
    refundValue: String,
    feeValue: String,
    notes: List<String> = emptyList(),
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WizardCardShape)
            .background(detoxColors.cardBackground)
            .border(0.5.dp, detoxColors.cardBorder, WizardCardShape),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.fee_overview_title).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = detoxColors.subtext,
            )
            Spacer(modifier = Modifier.height(12.dp))
            WizardFeeRow(stakeLabel, stakeValue, detoxColors.label)
            HorizontalDivider(
                color = detoxColors.divider,
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            WizardFeeRow(stringResource(R.string.fee_return_on_success), refundValue, detoxColors.success)
            HorizontalDivider(
                color = detoxColors.divider,
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            WizardFeeRow(stringResource(R.string.fee_service_fee), feeValue, detoxColors.subtext)
            notes.forEach { note ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = note,
                    fontSize = 12.sp,
                    color = detoxColors.subtext,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun WizardFeeRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = detoxColors.label)
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}

// ── Consent checkbox row (FAGG § 18 waiver, uninstall-forfeit consent) ────────

@Composable
fun WizardWaiverCheckboxRow(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String = stringResource(R.string.withdrawal_waiver_text),
) {
    val boxBg by animateColorAsState(
        targetValue = if (checked) detoxColors.accent else detoxColors.cardBackground,
        animationSpec = tween(150), label = "waiver_bg",
    )
    val boxBorder by animateColorAsState(
        targetValue = if (checked) detoxColors.accent else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(150), label = "waiver_border",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(150), label = "waiver_check",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(boxBg)
                .border(
                    width = 1.5.dp,
                    color = boxBorder,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale },
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = detoxColors.label,
        )
    }
}

// ── Missing-permission dialog row ─────────────────────────────────────────────

@Composable
fun WizardMissingPermissionRow(name: String, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "• $name",
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onGrant) {
            Text(stringResource(R.string.challenge_permission_grant))
        }
    }
}

// ── Date picker dialog ────────────────────────────────────────────────────────

/**
 * The wizards' date picker. Currently only the Group wizard (step 5, start date) has
 * one; it lives here so a Solo/Hard picker is identical the day one is added, instead
 * of a second raw `DatePickerDialog` drifting away from this one.
 *
 * Deliberately NOT Material3's [androidx.compose.material3.DatePickerDialog]:
 *
 *  - **Theme.** M3's defaults draw the container from `surfaceContainerHigh` and the
 *    day/nav foregrounds from the M3 roles, so the sheet read as a foreign grey panel
 *    next to the app's cards. Every slot below is a [detoxColors] token, and
 *    `tonalElevation = 0.dp` matches the theme's "no tonal overlays in either mode".
 *  - **Height.** The default title + headline + mode toggle is ~140dp of chrome that
 *    pushed the dialog past its own max height, clipping the top. All three are off;
 *    [title] renders inside the surface instead, where nothing can overlap it.
 *  - **Width.** [DatePicker] will not measure below [DatePickerMinWidth], and M3's
 *    dialog wraps it in a `requiredWidth` of exactly that — which overflows (and
 *    clips the confirm button off the right edge) on any viewport narrower than
 *    360dp, e.g. a device set to a larger display size. Scaling the density down by
 *    the shortfall shrinks the picker as real layout — hit targets included — and is
 *    a no-op at 360dp and above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardDatePickerDialog(
    state: DatePickerState,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            val outer = LocalDensity.current
            val shrink = (maxWidth / DatePickerMinWidth).coerceAtMost(1f)
            CompositionLocalProvider(
                LocalDensity provides Density(outer.density * shrink, outer.fontScale),
            ) {
                Surface(
                    shape = WizardDialogShape,
                    color = detoxColors.cardBackground,
                    tonalElevation = 0.dp,
                ) {
                    Column {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = detoxColors.label,
                            modifier = Modifier.padding(
                                start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp,
                            ),
                        )
                        DatePicker(
                            state = state,
                            title = null,
                            headline = null,
                            showModeToggle = false,
                            colors = DatePickerDefaults.colors(
                                containerColor = detoxColors.cardBackground,
                                weekdayContentColor = detoxColors.subtext,
                                subheadContentColor = detoxColors.subtext,
                                navigationContentColor = detoxColors.label,
                                yearContentColor = detoxColors.label,
                                currentYearContentColor = detoxColors.accent,
                                selectedYearContentColor = MaterialTheme.colorScheme.onPrimary,
                                selectedYearContainerColor = detoxColors.accent,
                                dayContentColor = detoxColors.label,
                                disabledDayContentColor = detoxColors.hint,
                                selectedDayContentColor = MaterialTheme.colorScheme.onPrimary,
                                selectedDayContainerColor = detoxColors.accent,
                                todayContentColor = detoxColors.accent,
                                todayDateBorderColor = detoxColors.accent,
                                dividerColor = detoxColors.divider,
                            ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            androidx.compose.material3.TextButton(onClick = onDismiss) {
                                Text(
                                    text = stringResource(R.string.dialog_cancel),
                                    color = detoxColors.subtext,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            androidx.compose.material3.TextButton(
                                onClick = onConfirm,
                                enabled = state.selectedDateMillis != null,
                            ) {
                                Text(
                                    text = stringResource(R.string.ok),
                                    color = if (state.selectedDateMillis != null) detoxColors.accent
                                    else detoxColors.hint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
