package com.finite.focus.presentation.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.finite.focus.ui.theme.PoppinsFamily
import com.finite.focus.ui.theme.detoxColors

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals in the
// result dialogs (docs/08 design system).

/**
 * Shared scaffold for the result dialogs ([ChallengeSuccessDialog] win / [ChallengeFailedDialog]
 * loss): a centered [Dialog] with the rounded gray frame, an optional [background] layer (the win
 * dialog draws confetti there; the loss dialog leaves it empty), the centered content column, and
 * the top-right circular **X** close button. Layout is identical to the original win dialog so the
 * win screen renders unchanged after extraction.
 */
@Composable
internal fun ResultDialogScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Press ripples derive from LocalContentColor, whose static default is Black —
        // invisible on the dark frame/cards. Resolve it to the frame's content color so
        // every clickable inside gets visible feedback in both modes.
        CompositionLocalProvider(LocalContentColor provides detoxColors.label) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(detoxColors.dialogSurface)
            ) {
                background()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content
                )

                // X close button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(detoxColors.cardBackground)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = detoxColors.subtext,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** White rounded result card (the amount + stats container) shared by both result dialogs. */
@Composable
internal fun ResultCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(detoxColors.cardBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * The result surfaces' primary action — a full-width filled button.
 *
 * Its FILL carries the verdict, which is why the color is a parameter rather than always
 * `colorScheme.primary`: green belongs to the win alone. A loss or an unverified end offers the calm
 * "back to Dashboard" in a neutral fill, so the app never nudges a user who just lost straight back
 * into staking again — see [ResultOutlineButton] for the demoted "start a new challenge".
 */
@Composable
internal fun ResultPrimaryButton(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ripple derives from LocalContentColor — resolve it to the button's own content
    // color, not the surrounding frame's.
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(containerColor)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = contentColor
            )
        }
    }
}

/** Full-width outlined secondary action, same metrics as [ResultPrimaryButton]. */
@Composable
internal fun ResultOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            // colorScheme.outline, not the near-invisible cardBorder: this button sits directly on
            // the dialog frame with no fill of its own, so the border is the whole affordance.
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = PoppinsFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = detoxColors.label
        )
    }
}

/** Tertiary text action (["Im Verlauf ansehen"], ["Zurück zum Dashboard"]) under the buttons. */
@Composable
internal fun ResultTextLink(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontFamily = PoppinsFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = color,
        modifier = modifier.clickable { onClick() }
    )
}

/**
 * One centered figure with its caption, stacked — the result cards' single stat.
 *
 * Deliberately NOT a column in a 3-up row: that row gave each caption ~80dp, which clipped the
 * German labels ("Tage durchgehalten" rendered as "Tage durchgehalt") and invited padding the card
 * with restated or invented figures. One full-width line per card, one fact per line.
 */
@Composable
internal fun ResultStatLine(
    value: String,
    caption: String? = null,
    valueColor: Color,
    valueSize: TextUnit = 36.sp
) {
    Text(
        text = value,
        fontFamily = PoppinsFamily,
        fontWeight = FontWeight.Bold,
        fontSize = valueSize,
        color = valueColor,
        textAlign = TextAlign.Center
    )
    if (caption != null) {
        Text(
            text = caption,
            fontFamily = PoppinsFamily,
            fontSize = 12.sp,
            color = detoxColors.subtext,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
