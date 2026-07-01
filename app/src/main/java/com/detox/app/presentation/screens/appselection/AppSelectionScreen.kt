package com.detox.app.presentation.screens.appselection

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.detox.app.presentation.components.AppIconImage
import com.detox.app.presentation.components.AppUsageCard

@Composable
fun AppSelectionScreen(
    onAppsSelected: (packages: List<String>, primaryDisplayName: String) -> Unit,
    viewModel: AppSelectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedPackages by viewModel.selectedPackages.collectAsStateWithLifecycle()
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
                        val context = LocalContext.current
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
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            if (state.trackableApps.isNotEmpty()) {
                                items(state.trackableApps, key = { it.packageName }) { app ->
                                    val conflictName = state.conflictingPackages[app.packageName]
                                    SelectableAppRow(
                                        app = app,
                                        isSelected = selectedPackages.contains(app.packageName),
                                        conflictChallengeName = conflictName,
                                        onToggle = { viewModel.toggleSelection(app.packageName) }
                                    )
                                }
                            }

                            if (state.nonTrackableApps.isNotEmpty()) {
                                item {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text(
                                        text = stringResource(R.string.app_selection_not_enough_usage),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                                items(state.nonTrackableApps, key = { it.packageName }) { app ->
                                    AppUsageCard(
                                        appUsageInfo = app,
                                        onClick = {},
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val successState = uiState as? AppSelectionUiState.Success
            if (successState != null) {
                val hasConflictSelected = selectedPackages.any {
                    successState.conflictingPackages.containsKey(it)
                }
                val hasSelection = selectedPackages.isNotEmpty()
                HorizontalDivider()
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            val packages = selectedPackages.toList()
                            val primaryName = successState.trackableApps
                                .firstOrNull { it.packageName == packages.firstOrNull() }
                                ?.appName ?: packages.firstOrNull() ?: ""
                            onAppsSelected(packages, primaryName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasSelection && !hasConflictSelected
                    ) {
                        Text(
                            if (!hasSelection)
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
    conflictChallengeName: String?,
    onToggle: () -> Unit
) {
    val enabled = conflictChallengeName == null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconImage(
                packageName = app.packageName,
                appName = app.appName,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.app_selection_usage_summary,
                        app.avgDailyMinutes,
                        app.avgDailyOpens
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Box(modifier = Modifier.size(24.dp))
            }
        }

        if (conflictChallengeName != null) {
            Text(
                text = stringResource(R.string.app_selection_already_in_challenge, conflictChallengeName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 76.dp, bottom = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

