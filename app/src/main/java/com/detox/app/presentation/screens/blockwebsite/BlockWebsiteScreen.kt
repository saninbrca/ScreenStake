package com.detox.app.presentation.screens.blockwebsite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.detox.app.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockWebsiteScreen(
    onContinue: (blockedDomains: List<String>, blockAdultContent: Boolean) -> Unit
) {
    var domains by remember { mutableStateOf<List<String>>(emptyList()) }
    var domainInput by remember { mutableStateOf("") }
    var blockAdultContent by remember { mutableStateOf(false) }

    fun addDomain() {
        val trimmed = domainInput.trim().lowercase()
        if (trimmed.isNotBlank() && !domains.contains(trimmed)) {
            domains = domains + trimmed
        }
        domainInput = ""
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.block_website_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.block_website_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Domain input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.challenge_setup_custom_domain_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { addDomain() })
                )
                Button(onClick = { addDomain() }) {
                    Text(stringResource(R.string.challenge_setup_custom_domain_add))
                }
            }

            // Domain chips
            if (domains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    domains.forEach { domain ->
                        InputChip(
                            selected = false,
                            onClick = { domains = domains - domain },
                            label = { Text(domain) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // "Block adult content" toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.challenge_setup_block_adult),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = blockAdultContent,
                    onCheckedChange = { blockAdultContent = it }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onContinue(domains, blockAdultContent) },
                modifier = Modifier.fillMaxWidth(),
                enabled = domains.isNotEmpty() || blockAdultContent
            ) {
                Text(stringResource(R.string.block_website_continue))
            }
        }
    }
}
