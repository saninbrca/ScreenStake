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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.detox.app.R

/**
 * Full-screen permanent lockout overlay shown after a Hard Mode time-limit is exceeded
 * and the payment has been captured. The app is locked for the rest of the day.
 *
 * @param appName       Name of the locked app.
 * @param amountCents   Amount already charged (to display to the user).
 * @param onExitHome    Sends the user to the home screen.
 */
@Composable
fun HardModeLockoutOverlay(
    appName: String,
    amountCents: Int,
    onExitHome: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A0000)), // Fully opaque deep dark-red
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.lockout_title),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.lockout_subtitle, appName),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.lockout_money_lost, amountCents / 100f),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF6B6B), // Coral
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onExitHome,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.15f)
                )
            ) {
                Text(
                    text = stringResource(R.string.lockout_exit_home),
                    color = Color.White
                )
            }
        }
    }
}
