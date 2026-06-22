package com.detox.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R

/**
 * Full-screen opaque overlay shown when a blocked domain is detected in a browser.
 *
 * No bypass — single "Zurück" button only.
 * Back button is also intercepted by OverlayManager via createSessionComposeView.
 */
@Composable
fun WebsiteBlockedOverlay(
    domain: String,
    challengeName: String?,
    streak: Int,
    motivationText: String?,
    onGoBack: () -> Unit,
) {
    val TextHint    = Color(0xFF555555)
    val SurfaceDark = Color(0xFF111111)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            initialScale = 0.95f
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp)
                    .padding(top = 72.dp, bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Emoji ──────────────────────────────────────────────────────────
                Text(text = "🌐", fontSize = 48.sp, textAlign = TextAlign.Center)

                Spacer(Modifier.height(16.dp))

                // ── Title ──────────────────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_website_new_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.3).sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                // ── Subtitle ───────────────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.overlay_website_new_subtitle),
                    fontSize = 14.sp,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                // ── Domain in dark inset ───────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = domain,
                        fontSize = 13.sp,
                        color = Color(0xFF333333),
                        textAlign = TextAlign.Center
                    )
                }

                // ── User's own motivation ("why") ──────────────────────────────────
                val motivation = motivationText?.takeIf { it.isNotBlank() }
                if (motivation != null) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.overlay_motivation_quote, motivation),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFFAAAAAA),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.weight(1f))

                // ── Single back button ─────────────────────────────────────────────
                OverlayPrimaryButton(
                    text = stringResource(R.string.overlay_website_back_button),
                    onClick = onGoBack
                )
            }
        }
    }
}
