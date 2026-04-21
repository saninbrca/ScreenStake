package com.detox.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A numeric input field with [–] and [+] stepper buttons on each side.
 * Allows both direct text entry (numeric keyboard) and step-wise increment/decrement.
 * Value is always clamped to [min]..[max].
 */
@Composable
fun StepperField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 999,
    step: Int = 1,
    suffix: String = "",
    error: String? = null,
    enabled: Boolean = true,
) {
    // Local display text — sync from external value when it changes
    var textValue by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        val parsed = textValue.toIntOrNull()
        if (parsed != value) textValue = value.toString()
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Decrement button ──────────────────────────────────────────────
            FilledIconButton(
                onClick = {
                    val newVal = (value - step).coerceAtLeast(min)
                    onValueChange(newVal)
                    textValue = newVal.toString()
                },
                enabled = enabled && value > min,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Decrease",
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Text field ────────────────────────────────────────────────────
            OutlinedTextField(
                value = textValue,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(5)
                    textValue = digits
                    val parsed = digits.toIntOrNull()
                    if (parsed != null) onValueChange(parsed.coerceIn(min, max))
                },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = error != null,
                enabled = enabled,
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Center,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize
                ),
                modifier = Modifier.weight(1f)
            )

            // ── Increment button ──────────────────────────────────────────────
            FilledIconButton(
                onClick = {
                    val newVal = (value + step).coerceAtMost(max)
                    onValueChange(newVal)
                    textValue = newVal.toString()
                },
                enabled = enabled && value < max,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Increase",
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Suffix label (outside field so it always renders horizontally) ─
            if (suffix.isNotBlank()) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp)
                )
            }
        }

        // Error text
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}
