package com.detox.app.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.detox.app.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.presentation.screens.profile.IbanSaveState
import kotlinx.coroutines.launch

// ── Color constants ────────────────────────────────────────────────────────────
private val BgColor = Color(0xFFF2F2F7)
private val CardColor = Color.White
private val CardBorder = Color(0x0F000000)
private val DividerColor = Color(0xFFF2F2F7)
private val LabelColor = Color(0xFF000000)
private val SubtextColor = Color(0xFF8E8E93)
private val ChevronColor = Color(0xFFC7C7CC)
private val DestructiveColor = Color(0xFFFF3B30)
private val GreenColor = Color(0xFF00C853)
private val OrangeColor = Color(0xFFFF9500)
private val PurpleColor = Color(0xFF5856D6)
private val GreyIconBg = Color(0xFF8E8E93)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ibanData by viewModel.ibanData.collectAsStateWithLifecycle()
    val ibanSaveState by viewModel.ibanSaveState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showIbanSheet by remember { mutableStateOf(false) }

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

    LaunchedEffect(ibanSaveState) {
        when (val s = ibanSaveState) {
            is IbanSaveState.Success -> {
                showIbanSheet = false
                snackbarHostState.showSnackbar(context.getString(R.string.settings_payout_iban_saved))
                viewModel.clearIbanSaveState()
            }
            is IbanSaveState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearIbanSaveState()
            }
            else -> Unit
        }
    }

    // ── IBAN Bottom Sheet ──────────────────────────────────────────────────────
    if (showIbanSheet) {
        var sheetIban by remember { mutableStateOf(ibanData?.iban ?: "") }
        var sheetName by remember { mutableStateOf(ibanData?.name ?: "") }
        val ibanValid = sheetIban.replace(" ", "").uppercase().matches(Regex("AT[0-9]{18}"))
        ModalBottomSheet(onDismissRequest = { showIbanSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_payout_iban_sheet_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LabelColor
                )
                OutlinedTextField(
                    value = sheetName,
                    onValueChange = { sheetName = it },
                    label = { Text(stringResource(R.string.payout_iban_holder_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sheetIban,
                    onValueChange = { sheetIban = it },
                    label = { Text(stringResource(R.string.payout_iban_field_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = sheetIban.isNotBlank() && !ibanValid,
                    placeholder = { Text("AT61 1904 3002 3457 3201") }
                )
                Button(
                    onClick = {
                        viewModel.saveIban(
                            iban = sheetIban.replace(" ", "").uppercase(),
                            name = sheetName
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenColor,
                        contentColor = Color.White
                    ),
                    enabled = ibanValid && sheetName.isNotBlank() &&
                            ibanSaveState !is IbanSaveState.Loading
                ) {
                    if (ibanSaveState is IbanSaveState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_payout_iban_save_button),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // ── Time Picker Dialog ─────────────────────────────────────────────────────
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
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // ── Logout Confirmation Dialog ─────────────────────────────────────────────
    if (state.showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLogoutConfirmDialog() },
            title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.logOut() },
                    colors = ButtonDefaults.textButtonColors(contentColor = DestructiveColor)
                ) { Text(stringResource(R.string.settings_logout_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLogoutConfirmDialog() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // ── Delete Account Confirmation Dialog ─────────────────────────────────────
    if (state.showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmDialog() },
            title = { Text(stringResource(R.string.settings_delete_account_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_account_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteAccount() },
                    colors = ButtonDefaults.textButtonColors(contentColor = DestructiveColor)
                ) { Text(stringResource(R.string.settings_delete_account_confirm_yes)) }
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
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LabelColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = GreenColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgColor
    ) { innerPadding ->

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = GreenColor) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {

            // ── 1. KONTO ───────────────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_account)) {
                // Email (non-tappable)
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Person, GreyIconBg) },
                    label = state.email.ifBlank { "—" },
                    labelColor = SubtextColor,
                    labelSize = 14
                )
                IosRowDivider()
                // Passwort ändern
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Lock, GreyIconBg) },
                    label = stringResource(R.string.settings_change_password),
                    subtitle = stringResource(R.string.settings_change_password_subtitle),
                    showChevron = true,
                    onClick = { viewModel.sendPasswordReset() }
                )
                IosRowDivider()
                // Abmelden
                IosRow(
                    iconContent = { IosIconBox(Icons.AutoMirrored.Filled.ExitToApp, DestructiveColor) },
                    label = stringResource(R.string.settings_logout),
                    labelColor = DestructiveColor,
                    onClick = { viewModel.showLogoutConfirmDialog() }
                )
                IosRowDivider()
                // Konto löschen
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Delete, DestructiveColor) },
                    label = stringResource(R.string.settings_delete_account),
                    labelColor = DestructiveColor,
                    subtitle = stringResource(R.string.settings_delete_account_subtitle),
                    onClick = { viewModel.showDeleteConfirmDialog() }
                )
            }

            // ── 2. AKTIVITÄT ───────────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_activity)) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.History, GreenColor) },
                    label = stringResource(R.string.settings_history_row_title),
                    showChevron = true,
                    onClick = onNavigateToHistory
                )
            }

            // ── 3. AUSZAHLUNGSKONTO ────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_payout_account)) {
                if (ibanData == null) {
                    IosRow(
                        iconContent = { IosIconBox(Icons.Filled.AccountBalance, GreenColor) },
                        label = stringResource(R.string.settings_payout_add_iban),
                        subtitle = stringResource(R.string.settings_payout_add_iban_subtitle),
                        showChevron = true,
                        onClick = { showIbanSheet = true }
                    )
                } else {
                    IosRow(
                        iconContent = { IosIconBox(Icons.Filled.AccountBalance, GreenColor) },
                        label = "AT•••• ${ibanData!!.iban.takeLast(4)}",
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.settings_payout_edit_label),
                                fontSize = 14.sp,
                                color = GreenColor
                            )
                        },
                        onClick = { showIbanSheet = true }
                    )
                }
            }

            // ── 4. ERSCHEINUNGSBILD ────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_appearance)) {
                IosSwitchRow(
                    iconContent = { IosIconBox(Icons.Filled.DarkMode, PurpleColor) },
                    label = stringResource(R.string.settings_dark_mode),
                    extraLabel = {
                        Text(
                            text = stringResource(R.string.settings_dark_mode_experimental),
                            fontSize = 12.sp,
                            color = OrangeColor
                        )
                    },
                    checked = state.darkModeEnabled,
                    onCheckedChange = { viewModel.setDarkModeEnabled(it) }
                )
            }

            // ── 5. BENACHRICHTIGUNGEN ──────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_notifications)) {
                IosSwitchRow(
                    iconContent = { IosIconBox(Icons.Filled.Notifications, OrangeColor) },
                    label = stringResource(R.string.settings_daily_reminder),
                    checked = state.dailyReminderEnabled,
                    onCheckedChange = { viewModel.setDailyReminderEnabled(it) }
                )
                if (state.dailyReminderEnabled) {
                    IosRowDivider()
                    IosRow(
                        iconContent = { IosIconBox(Icons.Filled.AccessTime, OrangeColor) },
                        label = stringResource(R.string.settings_reminder_time),
                        trailingContent = {
                            Text(
                                text = "%02d:%02d".format(
                                    state.dailyReminderHour,
                                    state.dailyReminderMinute
                                ),
                                fontSize = 14.sp,
                                color = SubtextColor
                            )
                        },
                        showChevron = true,
                        onClick = { showTimePicker = true }
                    )
                }
                IosRowDivider()
                IosSwitchRow(
                    iconContent = { IosIconBox(Icons.Filled.Check, GreenColor) },
                    label = stringResource(R.string.settings_challenge_updates),
                    subtitle = stringResource(R.string.settings_challenge_updates_subtitle),
                    checked = state.challengeUpdatesEnabled,
                    onCheckedChange = { viewModel.setChallengeUpdatesEnabled(it) }
                )
            }

            // ── 6. BERECHTIGUNGEN ──────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_permissions)) {
                IosPermissionRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.settings_permission_accessibility),
                    subtitle = stringResource(R.string.settings_permission_accessibility_subtitle),
                    granted = state.accessibilityGranted,
                    activateLabel = stringResource(R.string.settings_permission_activate),
                    onActivate = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
                IosRowDivider()
                IosPermissionRow(
                    icon = Icons.Filled.PhoneAndroid,
                    title = stringResource(R.string.settings_permission_overlay),
                    subtitle = stringResource(R.string.settings_permission_overlay_subtitle),
                    granted = state.overlayGranted,
                    activateLabel = stringResource(R.string.settings_permission_activate),
                    onActivate = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
                IosRowDivider()
                IosPermissionRow(
                    icon = Icons.Filled.BarChart,
                    title = stringResource(R.string.settings_permission_usage),
                    subtitle = stringResource(R.string.settings_permission_usage_subtitle),
                    granted = state.usageStatsGranted,
                    activateLabel = stringResource(R.string.settings_permission_activate),
                    onActivate = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
            }

            // ── 7. DATENSCHUTZ ─────────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_privacy)) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Policy, PurpleColor) },
                    label = stringResource(R.string.settings_privacy_policy),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://detox-app.com/privacy"))
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Policy, PurpleColor) },
                    label = stringResource(R.string.settings_terms_of_service),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://detox-app.com/terms"))
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Share, GreenColor) },
                    label = stringResource(R.string.settings_export_data),
                    subtitle = stringResource(R.string.settings_export_data_subtitle),
                    showChevron = true,
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
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Delete, DestructiveColor) },
                    label = stringResource(R.string.settings_delete_all_data),
                    labelColor = DestructiveColor,
                    subtitle = stringResource(R.string.settings_delete_all_data_subtitle),
                    onClick = { viewModel.showDeleteConfirmDialog() }
                )
            }

            // ── 8. APP INFO ────────────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_app_info)) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Info, GreyIconBg) },
                    label = stringResource(R.string.settings_version),
                    trailingContent = {
                        Text(
                            text = state.appVersion,
                            fontSize = 14.sp,
                            color = SubtextColor
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Star, OrangeColor) },
                    label = stringResource(R.string.settings_rate_app),
                    showChevron = true,
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
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Email, GreenColor) },
                    label = stringResource(R.string.settings_send_feedback),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:support@detox-app.com")
                                putExtra(Intent.EXTRA_SUBJECT, "Detox App Feedback")
                            }
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Email, GreyIconBg) },
                    label = stringResource(R.string.settings_contact_support),
                    subtitle = stringResource(R.string.settings_contact_support_subtitle),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:support@detox-app.com")
                                putExtra(Intent.EXTRA_SUBJECT, "Detox App Support Request")
                            }
                        )
                    }
                )
            }

            // ── 9. ENTWICKLER (debug only) ─────────────────────────────────────
            if (BuildConfig.DEBUG) {
                IosSection(stringResource(R.string.settings_section_debug)) {
                    val evalQueuedMsg = stringResource(R.string.profile_evaluation_queued)
                    IosRow(
                        iconContent = { IosIconBox(Icons.Filled.PlayArrow, GreyIconBg) },
                        label = stringResource(R.string.profile_run_evaluation),
                        onClick = {
                            viewModel.runEvaluationNow()
                            scope.launch { snackbarHostState.showSnackbar(evalQueuedMsg) }
                        }
                    )
                }
            }
        }
    }
}

// ── Layout helpers ─────────────────────────────────────────────────────────────

@Composable
private fun IosSection(
    header: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = header,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        color = SubtextColor,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun IosRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        thickness = 0.5.dp,
        color = DividerColor
    )
}

@Composable
private fun IosIconBox(icon: ImageVector, backgroundColor: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun IosRow(
    iconContent: @Composable () -> Unit,
    label: String,
    labelColor: Color = LabelColor,
    labelSize: Int = 16,
    subtitle: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        iconContent()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = labelSize.sp,
                fontWeight = FontWeight.Normal,
                color = labelColor
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = SubtextColor
                )
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = ChevronColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun IosSwitchRow(
    iconContent: @Composable () -> Unit,
    label: String,
    subtitle: String? = null,
    extraLabel: (@Composable () -> Unit)? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        iconContent()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = LabelColor
            )
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, fontSize = 14.sp, color = SubtextColor)
            }
            if (extraLabel != null) {
                extraLabel()
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = GreenColor,
                checkedThumbColor = Color.White
            )
        )
    }
}

@Composable
private fun IosPermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    activateLabel: String,
    onActivate: () -> Unit
) {
    val iconBg = if (granted) GreenColor else DestructiveColor
    val statusIcon = if (granted) Icons.Filled.Check else Icons.Filled.Close

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IosIconBox(icon = statusIcon, backgroundColor = iconBg)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = LabelColor)
            Text(text = subtitle, fontSize = 14.sp, color = SubtextColor)
        }
        if (!granted) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onActivate,
                colors = ButtonDefaults.textButtonColors(contentColor = GreenColor)
            ) {
                Text(text = activateLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
