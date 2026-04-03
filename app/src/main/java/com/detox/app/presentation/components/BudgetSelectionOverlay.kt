package com.detox.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.detox.app.R

/**
 * Full-screen opaque overlay that lets the user choose how many minutes of their
 * remaining daily budget they want to spend in this session.
 *
 * @param appName          Name of the tracked app.
 * @param remainingMinutes How many budget minutes are left for today.
 * @param onStart          Called with the number of minutes the user selected.
 * @param onGoBack         Called when the user taps "No, go back" or the hardware back button.
 */
@Composable
fun BudgetSelectionOverlay(
    appName: String,
    remainingMinutes: Int,
    onStart: (Int) -> Unit,
    onGoBack: () -> Unit
) {
    var selectedMinutes by remember { mutableIntStateOf(minOf(10, remainingMinutes).coerceAtLeast(1)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.budget_overlay_title, appName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.budget_overlay_remaining, remainingMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF4CAF50),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.budget_overlay_pick_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )

                // +/- picker
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Minus button
                    Surface(
                        shape = CircleShape,
                        color = if (selectedMinutes > 1) Color.White.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        IconButton(
                            onClick = { if (selectedMinutes > 1) selectedMinutes-- },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text(
                                text = "−",
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (selectedMinutes > 1) Color.White
                                else Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.budget_overlay_minutes_value, selectedMinutes),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White
                    )

                    // Plus button
                    Surface(
                        shape = CircleShape,
                        color = if (selectedMinutes < remainingMinutes) Color.White.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        IconButton(
                            onClick = { if (selectedMinutes < remainingMinutes) selectedMinutes++ },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (selectedMinutes < remainingMinutes) Color.White
                                else Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onStart(selectedMinutes) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.budget_overlay_start))
                }

                OutlinedButton(
                    onClick = onGoBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.budget_overlay_go_back),
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}
