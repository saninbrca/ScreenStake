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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.presentation.screens.profile.IbanSaveState
import com.detox.app.ui.theme.ThemeMode
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.FeatureFlags
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals in
// this file (see ui/theme). Structural surfaces use M3 roles; meaning-carrying
// colors use the semantic holder.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onNavigateToFaq: () -> Unit = {},
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
    // Money-floor gated: the payout account (IBAN) is a real-money surface, so the sheet can
    // never open in the soft-only release even if showIbanSheet were somehow set.
    if (showIbanSheet && FeatureFlags.moneyEnabled) {
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
                    color = detoxColors.label
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
                    placeholder = { Text(stringResource(R.string.iban_placeholder)) }
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
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    enabled = ibanValid && sheetName.isNotBlank() &&
                            ibanSaveState !is IbanSaveState.Loading
                ) {
                    if (ibanSaveState is IbanSaveState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
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

    // ── Logout Confirmation Dialog ─────────────────────────────────────────────
    if (state.showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLogoutConfirmDialog() },
            title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.logOut() },
                    colors = ButtonDefaults.textButtonColors(contentColor = detoxColors.danger)
                ) { Text(stringResource(R.string.settings_logout_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLogoutConfirmDialog() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // ── Delete Account Confirmation Dialog (with re-auth) ──────────────────────
    if (state.showDeleteConfirmDialog) {
        var deletePassword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmDialog() },
            title = { Text(stringResource(R.string.settings_delete_account_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_delete_account_confirm_message))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.settings_delete_reauth_message),
                        fontSize = 13.sp,
                        color = detoxColors.subtext
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text(stringResource(R.string.settings_delete_reauth_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = state.deleteReauthError != null,
                        enabled = !state.deleteReauthLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.deleteReauthError?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = it, fontSize = 12.sp, color = detoxColors.danger)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteAccount(deletePassword) },
                    enabled = deletePassword.isNotBlank() && !state.deleteReauthLoading,
                    colors = ButtonDefaults.textButtonColors(contentColor = detoxColors.danger)
                ) { Text(stringResource(R.string.settings_delete_reauth_continue)) }
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
                        color = detoxColors.label
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = detoxColors.screenBackground)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = detoxColors.screenBackground
    ) { innerPadding ->

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
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
            IosSection(stringResource(R.string.settings_section_account), entranceDelayMs = 0) {
                // Email (non-tappable)
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Person, detoxColors.tileNeutral) },
                    label = state.email.ifBlank { "—" },
                    labelColor = detoxColors.subtext,
                    labelSize = 14
                )
                IosRowDivider()
                // Passwort ändern (inline confirmation + 60s cooldown)
                val pwCooldown = state.passwordResetCooldownSeconds
                val pwSubtitle = when {
                    pwCooldown > 0 ->
                        stringResource(R.string.settings_password_reset_cooldown, pwCooldown)
                    state.passwordResetMessage != null -> state.passwordResetMessage!!
                    else -> stringResource(R.string.settings_change_password_subtitle)
                }
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Lock, detoxColors.tileNeutral) },
                    label = stringResource(R.string.settings_change_password),
                    subtitle = pwSubtitle,
                    showChevron = pwCooldown == 0,
                    onClick = { if (pwCooldown == 0) viewModel.sendPasswordReset() }
                )
                IosRowDivider()
                // Abmelden
                IosRow(
                    iconContent = { IosIconBox(Icons.AutoMirrored.Filled.ExitToApp, detoxColors.danger) },
                    label = stringResource(R.string.settings_logout),
                    labelColor = detoxColors.danger,
                    onClick = { viewModel.showLogoutConfirmDialog() }
                )
                IosRowDivider()
                // Konto löschen
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Delete, detoxColors.danger) },
                    label = stringResource(R.string.settings_delete_account),
                    labelColor = detoxColors.danger,
                    subtitle = stringResource(R.string.settings_delete_account_subtitle),
                    onClick = { viewModel.showDeleteConfirmDialog() }
                )
            }

            // ── 2. AKTIVITÄT ───────────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_activity), entranceDelayMs = 60) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.History, detoxColors.tileGreen) },
                    label = stringResource(R.string.settings_history_row_title),
                    showChevron = true,
                    onClick = onNavigateToHistory
                )
            }

            // ── 3. AUSZAHLUNGSKONTO ────────────────────────────────────────────
            // Money-floor gated: the payout account section is hidden entirely for the soft-only
            // release. Sections are independent Cards, so omitting one leaves the rest intact.
            if (FeatureFlags.moneyEnabled) {
                IosSection(stringResource(R.string.settings_section_payout_account), entranceDelayMs = 120) {
                    if (ibanData == null) {
                        IosRow(
                            iconContent = { IosIconBox(Icons.Filled.AccountBalance, detoxColors.tileGreen) },
                            label = stringResource(R.string.settings_payout_add_iban),
                            subtitle = stringResource(R.string.settings_payout_add_iban_subtitle),
                            showChevron = true,
                            onClick = { showIbanSheet = true }
                        )
                    } else {
                        IosRow(
                            iconContent = { IosIconBox(Icons.Filled.AccountBalance, detoxColors.tileGreen) },
                            label = "AT•••• ${ibanData!!.iban.takeLast(4)}",
                            trailingContent = {
                                Text(
                                    text = stringResource(R.string.settings_payout_edit_label),
                                    fontSize = 14.sp,
                                    color = detoxColors.accent
                                )
                            },
                            onClick = { showIbanSheet = true }
                        )
                    }
                }
            }

            // ── 4. ERSCHEINUNGSBILD ────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_appearance), entranceDelayMs = 180) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.DarkMode, detoxColors.tilePurple) },
                    label = stringResource(R.string.settings_dark_mode)
                )
                IosThemeModeSelector(
                    selected = state.themeMode,
                    onSelect = { viewModel.setThemeMode(it) }
                )
            }

            // ── 5. BENACHRICHTIGUNGEN ──────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_notifications), entranceDelayMs = 240) {
                IosSwitchRow(
                    iconContent = { IosIconBox(Icons.Filled.Check, detoxColors.tileGreen) },
                    label = stringResource(R.string.settings_challenge_updates),
                    subtitle = stringResource(R.string.settings_challenge_updates_subtitle),
                    checked = state.challengeUpdatesEnabled,
                    onCheckedChange = { viewModel.setChallengeUpdatesEnabled(it) }
                )
                // Money-floor gated: the group participant-failed notification is a Group-only
                // (buy-in) surface — hidden in the soft-only release. The general challenge-updates
                // row above stays, so the section is never empty.
                if (FeatureFlags.moneyEnabled) {
                    IosRowDivider()
                    IosSwitchRow(
                        iconContent = { IosIconBox(Icons.Filled.Group, detoxColors.groupAccent) },
                        label = stringResource(R.string.settings_group_participant_failed),
                        subtitle = stringResource(R.string.settings_group_participant_failed_subtitle),
                        checked = state.groupParticipantFailedEnabled,
                        onCheckedChange = { viewModel.setGroupParticipantFailedEnabled(it) }
                    )
                }
            }

            // ── 6. BERECHTIGUNGEN ──────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_permissions), entranceDelayMs = 300) {
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
            IosSection(stringResource(R.string.settings_section_privacy), entranceDelayMs = 360) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Policy, detoxColors.tilePurple) },
                    label = stringResource(R.string.settings_privacy_policy),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.url_datenschutz)))
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Policy, detoxColors.tilePurple) },
                    label = stringResource(R.string.settings_terms_of_service),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.url_agb)))
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Policy, detoxColors.tilePurple) },
                    label = stringResource(R.string.settings_impressum),
                    showChevron = true,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.url_impressum)))
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Share, detoxColors.tileGreen) },
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
                    iconContent = { IosIconBox(Icons.Filled.Delete, detoxColors.danger) },
                    label = stringResource(R.string.settings_delete_all_data),
                    labelColor = detoxColors.danger,
                    subtitle = stringResource(R.string.settings_delete_all_data_subtitle),
                    onClick = { viewModel.showDeleteConfirmDialog() }
                )
            }

            // ── 8. HILFE & SUPPORT ─────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_help), entranceDelayMs = 420) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.SupportAgent, detoxColors.tileGreen) },
                    label = stringResource(R.string.settings_contact_support),
                    subtitle = stringResource(R.string.settings_contact_support_subtitle),
                    showChevron = true,
                    onClick = onNavigateToSupport
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.AutoMirrored.Filled.HelpOutline, detoxColors.tilePurple) },
                    label = stringResource(R.string.settings_faq),
                    showChevron = true,
                    onClick = onNavigateToFaq
                )
            }

            // ── 9. APP INFO ────────────────────────────────────────────────────
            IosSection(stringResource(R.string.settings_section_app_info), entranceDelayMs = 480) {
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Info, detoxColors.tileNeutral) },
                    label = stringResource(R.string.settings_version),
                    trailingContent = {
                        Text(
                            text = state.appVersion,
                            fontSize = 14.sp,
                            color = detoxColors.subtext
                        )
                    }
                )
                IosRowDivider()
                IosRow(
                    iconContent = { IosIconBox(Icons.Filled.Star, detoxColors.tileOrange) },
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
            }

            // ── 9. ENTWICKLER (debug only) ─────────────────────────────────────
            if (BuildConfig.DEBUG) {
                IosSection(stringResource(R.string.settings_section_debug)) {
                    val evalQueuedMsg = stringResource(R.string.profile_evaluation_queued)
                    IosRow(
                        iconContent = { IosIconBox(Icons.Filled.PlayArrow, detoxColors.tileNeutral) },
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
    entranceDelayMs: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(entranceDelayMs.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
            slideInHorizontally(
                initialOffsetX = { 40 },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            )
    ) {
        Column {
            Text(
                text = header,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = detoxColors.subtext,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, detoxColors.cardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(content = content)
            }
        }
    }
}

/**
 * iOS-style segmented control for the theme mode. Only WRITES the selection via
 * [onSelect]; applying it to the UI is MainActivity's prefs listener + DetoxTheme.
 */
@Composable
private fun IosThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp, bottom = 12.dp)
            .background(detoxColors.screenBackground, RoundedCornerShape(8.dp))
            .padding(2.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isSelected) {
                            Modifier.background(detoxColors.cardBackground, RoundedCornerShape(6.dp))
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        }
                    ),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) detoxColors.label else detoxColors.subtext
                )
            }
        }
    }
}

@Composable
private fun IosRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        thickness = 0.5.dp,
        color = detoxColors.divider
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
            tint = detoxColors.tileGlyph,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun IosRow(
    iconContent: @Composable () -> Unit,
    label: String,
    labelColor: Color = detoxColors.label,
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
                    color = detoxColors.subtext
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
                tint = detoxColors.hint,
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
                color = detoxColors.label
            )
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, fontSize = 14.sp, color = detoxColors.subtext)
            }
            if (extraLabel != null) {
                extraLabel()
            }
        }
        // M3 defaults (checkedTrack = primary, checkedThumb = onPrimary) match the old
        // explicit green/white in light mode and adapt correctly in dark.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
    val iconBg = if (granted) detoxColors.success else detoxColors.danger
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
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = detoxColors.label)
            Text(text = subtitle, fontSize = 14.sp, color = detoxColors.subtext)
        }
        if (!granted) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onActivate,
                colors = ButtonDefaults.textButtonColors(contentColor = detoxColors.accent)
            ) {
                Text(text = activateLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
