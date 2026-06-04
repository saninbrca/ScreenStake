package com.detox.app.presentation.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.detox.app.ui.theme.PoppinsFamily

// Shared palette for the win/loss result dialogs (docs/08 design system).
internal val DialogBg = Color(0xFFF2F2F7)
internal val CardBg = Color.White
internal val AccentGreen = Color(0xFF00C853)
internal val AccentRed = Color(0xFFFF3B30)
internal val TextSecondary = Color(0xFF8E8E93)
internal val BadgeBorder = Color(0xFFE0E0E5)

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
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DialogBg)
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
                    .background(CardBg)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
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
            .background(CardBg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/** A single labelled stat in the 3-column result row (Tage durchgehalten / Bewusst geöffnet / …). */
@Composable
internal fun StatColumn(
    value: String,
    label: String,
    valueColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontFamily = PoppinsFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = valueColor
        )
        Text(
            text = label,
            fontFamily = PoppinsFamily,
            fontSize = 11.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.width(80.dp)
        )
    }
}
