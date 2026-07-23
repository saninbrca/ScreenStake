package com.detox.app.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
