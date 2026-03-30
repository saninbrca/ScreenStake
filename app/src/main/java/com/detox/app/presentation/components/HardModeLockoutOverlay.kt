package com.detox.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.detox.app.R

/**
 * Full-screen permanent lockout overlay shown after a Hard Mode limit is exceeded
 * and the payment has been captured. Cannot be dismissed without an emergency code.
 *
 * @param appName       Name of the locked app.
 * @param amountCents   Amount already charged (to display to the user).
 * @param correctCode   The challenge's 6-digit emergency code.
 * @param onEmergencyUnlock Called when the correct code is entered. The overlay dismisses
 *                          and 50 points are deducted by the caller.
 * @param onExitHome    Sends the user to the home screen (doesn't unlock the app).
 */
@Composable
fun HardModeLockoutOverlay(
    appName: String,
    amountCents: Int,
    correctCode: String,
    onEmergencyUnlock: () -> Unit,
    onExitHome: () -> Unit
) {
    var showCodeInput by remember { mutableStateOf(false) }
    var enteredCode by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC1A0000)), // Very deep dark-red
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

            TextButton(onClick = { showCodeInput = !showCodeInput }) {
                Text(
                    text = stringResource(R.string.lockout_emergency_button),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            AnimatedVisibility(visible = showCodeInput) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(
                            color = Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = enteredCode,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                enteredCode = input
                                codeError = false
                            }
                        },
                        label = { Text(stringResource(R.string.lockout_emergency_hint), color = Color.White) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        isError = codeError,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    )

                    if (codeError) {
                        Text(
                            text = stringResource(R.string.lockout_emergency_wrong),
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            if (enteredCode == correctCode) {
                                onEmergencyUnlock()
                            } else {
                                codeError = true
                                enteredCode = ""
                            }
                        },
                        enabled = enteredCode.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B6B)
                        )
                    ) {
                        Text(stringResource(R.string.lockout_emergency_unlock))
                    }
                }
            }
        }
    }
}
