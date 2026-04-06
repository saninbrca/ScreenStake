package com.detox.app.presentation.screens.appselection

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.R
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.presentation.components.AppUsageCard

@Composable
fun AppSelectionScreen(
    onAppsSelected: (packages: List<String>, primaryDisplayName: String) -> Unit,
    viewModel: AppSelectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedPackages by viewModel.selectedPackages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadApps()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp)) {
                Text(
                    text = stringResource(R.string.app_selection_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.app_selection_subtitle_multiselect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Box(modifier = Modifier.weight(1f)) {
                when (val state = uiState) {
                    is AppSelectionUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.app_selection_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is AppSelectionUiState.Error -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.app_selection_error),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }

                    is AppSelectionUiState.NoPermission -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.app_selection_no_permission),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }) {
                                    Text(text = stringResource(R.string.app_selection_grant_permission))
                                }
                            }
                        }
                    }

                    is AppSelectionUiState.Success -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Trackable apps — selectable with checkbox
                            if (state.trackableApps.isNotEmpty()) {
                                items(state.trackableApps, key = { it.packageName }) { app ->
                                    SelectableAppRow(
                                        app = app,
                                        isSelected = selectedPackages.contains(app.packageName),
                                        onToggle = { viewModel.toggleSelection(app.packageName) }
                                    )
                                }
                            }

                            // Non-trackable apps — shown dimmed, not selectable
                            if (state.nonTrackableApps.isNotEmpty()) {
                                item {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text(
                                        text = stringResource(R.string.app_selection_not_enough_usage),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                items(state.nonTrackableApps, key = { it.packageName }) { app ->
                                    AppUsageCard(appUsageInfo = app, onClick = {})
                                }
                            }
                        }
                    }
                }
            }

            // Bottom "Next" bar
            val successState = uiState as? AppSelectionUiState.Success
            if (successState != null) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            val packages = selectedPackages.toList()
                            if (packages.isEmpty()) return@Button
                            val primaryName = successState.trackableApps
                                .firstOrNull { it.packageName == packages.first() }
                                ?.appName ?: packages.first()
                            onAppsSelected(packages, primaryName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedPackages.isNotEmpty()
                    ) {
                        Text(
                            if (selectedPackages.isEmpty())
                                stringResource(R.string.app_selection_select_hint)
                            else
                                stringResource(R.string.app_selection_next, selectedPackages.size)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableAppRow(
    app: AppUsageInfo,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
        AppUsageCard(
            appUsageInfo = app,
            onClick = onToggle,
            modifier = Modifier.weight(1f)
        )
    }
}
