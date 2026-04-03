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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.detox.app.R
import com.detox.app.domain.model.ChallengeMode

@Composable
fun LimitExceededOverlay(
    appName: String,
    challengeMode: ChallengeMode = ChallengeMode.SOFT,
    amountCents: Int? = null,
    onContinue: () -> Unit,
    onStop: () -> Unit
) {
    @Suppress("KotlinConstantConditions")
    val isHardMode = challengeMode == ChallengeMode.HARD && amountCents != null
    // Solid opaque background — nothing bleeds through from the app underneath.
    val bgColor = if (isHardMode) Color(0xFF1A0000) else Color(0xFF0D0D0D)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1C1C1C)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val title = if (isHardMode && amountCents != null) {
                    stringResource(R.string.limit_exceeded_hard_title, amountCents / 100f)
                } else {
                    stringResource(R.string.limit_exceeded_title)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isHardMode) Color(0xFFFF6B6B) else Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                val message = if (isHardMode) {
                    stringResource(R.string.limit_exceeded_hard_message)
                } else {
                    stringResource(R.string.limit_exceeded_message)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.limit_exceeded_stop))
                }

                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isHardMode) {
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(
                        text = if (isHardMode) {
                            stringResource(R.string.limit_exceeded_hard_continue)
                        } else {
                            stringResource(R.string.limit_exceeded_continue)
                        }
                    )
                }

            }
        }
    }
}
