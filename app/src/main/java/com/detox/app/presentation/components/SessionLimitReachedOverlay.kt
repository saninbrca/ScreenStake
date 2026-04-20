package com.detox.app.presentation.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.detox.app.R
import com.detox.app.domain.model.ChallengeMode

/**
 * Stage 2 — Limit Reached Overlay.
 *
 * Shown when the user's conscious-open count has reached or exceeded the
 * session limit.  Makes the consequence of continuing crystal-clear:
 * points are lost (Soft Mode) or money is captured (Hard Mode).
 *
 * Full-screen, fully opaque — identical visual language to [SessionIntentionOverlay].
 */
@Composable
fun SessionLimitReachedOverlay(
    packageName: String,
    appName: String,
    challengeMode: ChallengeMode,
    amountCents: Int?,
    /** Current consecutive-day streak (before today). Hidden when 0. */
    streak: Int = 0,
    onYesLose: () -> Unit,
    onNo: () -> Unit
) {
    val isHard = challengeMode == ChallengeMode.HARD && amountCents != null
    val context = LocalContext.current

    val appIcon: ImageBitmap? = remember(packageName) {
        try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap().asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    // Hard Mode uses a deep-red tint; Soft Mode stays with the dark neutral background
    val bgColor = if (isHard) Color(0xFF1A0000) else Color(0xFF0D0D0D)
    val accentColor = if (isHard) Color(0xFFFF4444) else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: icon, app name, title ─────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                appIcon?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = appName,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                    )
                }

                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.session_limit_reached_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = accentColor,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.session_limit_reached_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            // ── Middle: consequence banner ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = accentColor.copy(alpha = 0.15f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                val consequence = if (isHard && amountCents != null) {
                    stringResource(R.string.session_limit_reached_consequence_hard, amountCents / 100f)
                } else {
                    stringResource(R.string.session_limit_reached_consequence_soft)
                }
                Text(
                    text = consequence,
                    style = MaterialTheme.typography.titleLarge,
                    color = accentColor,
                    textAlign = TextAlign.Center
                )
            }

            // ── Bottom: streak badge + action buttons ──────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (streak > 0) {
                    Text(
                        text = stringResource(R.string.streak_display, streak),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = onNo,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.session_limit_reached_no),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = onYesLose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = accentColor.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        text = if (isHard) {
                            stringResource(R.string.session_limit_reached_yes_hard)
                        } else {
                            stringResource(R.string.session_limit_reached_yes_soft)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
