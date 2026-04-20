package com.detox.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

/**
 * Full-screen opaque overlay shown when the AccessibilityService detects a blocked domain
 * in a browser address bar.
 *
 * No bypass is possible — the only action is "Stay strong 💪" which sends the user home.
 * Back button is also intercepted by OverlayManager via createSessionComposeView.
 */
@Composable
fun WebsiteBlockedOverlay(
    domain: String,
    challengeName: String?,
    /** Current consecutive-day streak (before today). Hidden when 0. */
    streak: Int,
    motivationText: String?,
    onGoBack: () -> Unit,
) {
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
            // ── Top: icon, title, domain, challenge name, motivation ──────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "🚫",
                    fontSize = 56.sp,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.website_blocked_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = domain,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                if (challengeName != null) {
                    Text(
                        text = stringResource(R.string.website_blocked_challenge_label, challengeName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )
                }

                if (!motivationText.isNullOrBlank()) {
                    Text(
                        text = "\"$motivationText\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }

            // ── Middle: streak badge ──────────────────────────────────────────────
            if (streak > 0) {
                Text(
                    text = stringResource(R.string.streak_display, streak),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }

            // ── Bottom: single "Stay strong" button ───────────────────────────────
            Button(
                onClick = onGoBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.website_blocked_stay_strong),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
