package com.detox.app.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.detox.app.BuildConfig
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Refresh permission status whenever the screen is resumed
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is SettingsEvent.NavigateToLogin -> onNavigateToLogin()
            }
        }
    }

    // Time picker dialog state
    var showTimePicker by remember { mutableStateOf(false) }
    val timePickerState = rememberTimePickerState(
        initialHour = state.dailyReminderHour,
        initialMinute = state.dailyReminderMinute,
        is24Hour = true
    )

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.settings_reminder_time_picker_title)) },
            text = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminderTime(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // Delete account confirmation dialog
    if (state.showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmDialog() },
            title = { Text(stringResource(R.string.settings_delete_account_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_account_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteAccount() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.settings_delete_account_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmDialog() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = androidx.compose.ui.graphics.Color.White
    ) { innerPadding ->

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── 1. ACCOUNT ─────────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_account))

            if (state.email.isNotBlank()) {
                SettingsRow(
                    icon = Icons.Filled.AccountCircle,
                    title = state.email,
                    subtitle = state.displayName.ifBlank { null },
                    onClick = null
                )
            }

            SettingsRow(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.settings_change_password),
                subtitle = stringResource(R.string.settings_change_password_subtitle),
                onClick = { viewModel.sendPasswordReset() }
            )

            SettingsRow(
                icon = Icons.Filled.ExitToApp,
                title = stringResource(R.string.settings_logout),
                subtitle = null,
                onClick = { viewModel.logOut() },
                destructive = true
            )

            SettingsRow(
                icon = Icons.Filled.Delete,
                title = stringResource(R.string.settings_delete_account),
                subtitle = stringResource(R.string.settings_delete_account_subtitle),
                onClick = { viewModel.showDeleteConfirmDialog() },
                destructive = true
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 2. APPEARANCE ──────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_appearance))

            SwitchRow(
                icon = Icons.Filled.DarkMode,
                title = stringResource(R.string.settings_dark_mode),
                subtitle = stringResource(R.string.settings_dark_mode_subtitle),
                checked = state.darkModeEnabled,
                onCheckedChange = { viewModel.setDarkModeEnabled(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 3. NOTIFICATIONS ───────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_notifications))

            SwitchRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.settings_daily_reminder),
                subtitle = stringResource(R.string.settings_daily_reminder_subtitle),
                checked = state.dailyReminderEnabled,
                onCheckedChange = { viewModel.setDailyReminderEnabled(it) }
            )

            if (state.dailyReminderEnabled) {
                SettingsRow(
                    icon = Icons.Filled.AccessTime,
                    title = stringResource(R.string.settings_reminder_time),
                    subtitle = "%02d:%02d".format(state.dailyReminderHour, state.dailyReminderMinute),
                    onClick = { showTimePicker = true }
                )
            }

            SwitchRow(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.settings_challenge_updates),
                subtitle = stringResource(R.string.settings_challenge_updates_subtitle),
                checked = state.challengeUpdatesEnabled,
                onCheckedChange = { viewModel.setChallengeUpdatesEnabled(it) }
            )

            SwitchRow(
                icon = Icons.Filled.People,
                title = stringResource(R.string.settings_friend_alerts),
                subtitle = stringResource(R.string.settings_friend_alerts_subtitle),
                checked = state.friendAlertsEnabled,
                onCheckedChange = { viewModel.setFriendAlertsEnabled(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 4. PERMISSIONS ─────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_permissions))

            val allGranted = state.accessibilityGranted && state.overlayGranted && state.usageStatsGranted
            if (allGranted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_all_permissions_active),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            PermissionRow(
                title = stringResource(R.string.settings_permission_accessibility),
                subtitle = stringResource(R.string.settings_permission_accessibility_subtitle),
                granted = state.accessibilityGranted,
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            PermissionRow(
                title = stringResource(R.string.settings_permission_overlay),
                subtitle = stringResource(R.string.settings_permission_overlay_subtitle),
                granted = state.overlayGranted,
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            PermissionRow(
                title = stringResource(R.string.settings_permission_usage),
                subtitle = stringResource(R.string.settings_permission_usage_subtitle),
                granted = state.usageStatsGranted,
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 5. PRIVACY ─────────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_privacy))

            SettingsRow(
                icon = Icons.Filled.Policy,
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = null,
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://detox-app.com/privacy"))
                    )
                }
            )

            SettingsRow(
                icon = Icons.Filled.Policy,
                title = stringResource(R.string.settings_terms_of_service),
                subtitle = null,
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://detox-app.com/terms"))
                    )
                }
            )

            SettingsRow(
                icon = Icons.Filled.Share,
                title = stringResource(R.string.settings_export_data),
                subtitle = stringResource(R.string.settings_export_data_subtitle),
                onClick = {
                    scope.launch {
                        val json = viewModel.buildExportJson()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_SUBJECT, "Detox Data Export")
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Export Detox Data")
                        )
                    }
                }
            )

            SettingsRow(
                icon = Icons.Filled.Delete,
                title = stringResource(R.string.settings_delete_all_data),
                subtitle = stringResource(R.string.settings_delete_all_data_subtitle),
                onClick = { viewModel.showDeleteConfirmDialog() },
                destructive = true
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 6. APP INFO ────────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_app_info))

            SettingsRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_version),
                subtitle = state.appVersion,
                onClick = null
            )

            SettingsRow(
                icon = Icons.Filled.Star,
                title = stringResource(R.string.settings_rate_app),
                subtitle = null,
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=${context.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                            )
                        )
                    }
                }
            )

            SettingsRow(
                icon = Icons.Filled.Email,
                title = stringResource(R.string.settings_send_feedback),
                subtitle = null,
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:support@detox-app.com")
                            putExtra(Intent.EXTRA_SUBJECT, "Detox App Feedback")
                        }
                    )
                }
            )

            SettingsRow(
                icon = Icons.Filled.Email,
                title = stringResource(R.string.settings_contact_support),
                subtitle = "support@detox-app.com",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:support@detox-app.com")
                            putExtra(Intent.EXTRA_SUBJECT, "Detox App Support Request")
                        }
                    )
                }
            )

            // ── 7. DEBUG (visible only in debug builds) ────────────────────────
            if (BuildConfig.DEBUG) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(R.string.settings_section_debug))
                val evalQueuedMsg = stringResource(R.string.profile_evaluation_queued)
                SettingsRow(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.profile_run_evaluation),
                    subtitle = null,
                    onClick = {
                        viewModel.runEvaluationNow()
                        scope.launch { snackbarHostState.showSnackbar(evalQueuedMsg) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Reusable composables ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    destructive: Boolean = false
) {
    val titleColor = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    val iconTint = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = androidx.compose.ui.graphics.Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onOpenSettings: () -> Unit
) {
    val indicatorColor = if (granted) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = indicatorColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!granted) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_open_settings),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
