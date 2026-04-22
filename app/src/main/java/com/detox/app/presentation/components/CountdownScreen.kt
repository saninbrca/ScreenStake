package com.detox.app.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Full-screen countdown overlay shown when the user taps "Trotzdem öffnen".
 * Counts 5 → 0 with a scale-pop animation on each digit.
 * Tapping "Abbrechen" at any point cancels the countdown and navigates home.
 */
@Composable
fun CountdownScreen(
    packageName: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(5) }
    var active by remember { mutableStateOf(true) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        Timber.d("Countdown started for $packageName")
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        Timber.d("Countdown completed — opening $packageName")
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = stringResource(R.string.countdown_top_text),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            key(countdown) {
                var targetScale by remember { mutableStateOf(1.4f) }
                val animatedScale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = tween(durationMillis = 400),
                    label = "countdown_scale"
                )
                LaunchedEffect(Unit) { targetScale = 1f }

                Text(
                    text = "$countdown",
                    modifier = Modifier.scale(animatedScale),
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = stringResource(R.string.countdown_bottom_text),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = {
                active = false
                Timber.d("Countdown cancelled — user backed out")
                onCancel()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = stringResource(R.string.countdown_cancel_button),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
