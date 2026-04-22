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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.detox.app.R

/**
 * Stage 1 — Intention Check Overlay.
 *
 * Shown on every app open for session-limit challenges while the conscious-open
 * limit has NOT yet been reached.  Asks the user for explicit confirmation so
 * that only deliberate opens count against their daily allowance.
 *
 * Full-screen, fully opaque — the user sees nothing of the app beneath.
 */
@Composable
fun SessionIntentionOverlay(
    packageName: String,
    appName: String,
    opensUsed: Int,
    maxOpens: Int,
    /** Epoch-ms of when the previous session ended (timer expired / app left foreground).
     *  Null when this is the first open of the day. */
    lastSessionEndedAt: Long?,
    /** The challenge's custom motivation text, or the default "Stay strong!" string. */
    motivationText: String,
    /** Current consecutive-day streak (before today). Hidden when 0. */
    streak: Int = 0,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    val context = LocalContext.current

    val appIcon: ImageBitmap? = remember(packageName) {
        try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap().asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    var showCountdown by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: icon, app name, question ─────────────────────────────────
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
                    text = stringResource(R.string.session_intention_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = motivationText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            // ── Middle: progress counter ───────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.session_intention_opens_used, opensUsed, maxOpens),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                val progress = if (maxOpens > 0) opensUsed.toFloat() / maxOpens else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )

                lastSessionEndedAt?.let { endedAt ->
                    val elapsedMs = System.currentTimeMillis() - endedAt
                    val mins = (elapsedMs / 60_000L).toInt()
                    val secs = ((elapsedMs % 60_000L) / 1_000L).toInt()
                    Text(
                        text = stringResource(R.string.session_intention_last_session, mins, secs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── Bottom: streak badge + action buttons ──────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
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
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Button(
                    onClick = onNo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.stay_strong_button),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.open_anyway_hint),
                    fontSize = 10.sp,
                    color = Color(0xFF9E9E9E),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        onClick = { showCountdown = true },
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF9E9E9E))
                    ) {
                        Text(
                            text = stringResource(R.string.open_anyway_button),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        if (showCountdown) {
            CountdownScreen(
                packageName = packageName,
                onComplete = onYes,
                onCancel = onNo
            )
        }
    }
}
