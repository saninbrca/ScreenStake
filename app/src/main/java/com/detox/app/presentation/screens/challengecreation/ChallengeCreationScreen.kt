package com.detox.app.presentation.screens.challengecreation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.presentation.components.StepperField
import com.detox.app.presentation.components.TimeSpinnerPicker
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

// ── Screen entry point ────────────────────────────────────────────────────────

@Composable
fun ChallengeCreationScreen(
    onFinished: () -> Unit,
    onDiscarded: () -> Unit,
    viewModel: ChallengeCreationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appListState by viewModel.appListState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadApps()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeCreationUiState.Success) onFinished()
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentConfirmed()
            is PaymentSheetResult.Canceled  -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed    -> viewModel.onPaymentCancelled()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChallengeCreationUiState.AwaitingPayment) {
            val s = uiState as ChallengeCreationUiState.AwaitingPayment
            paymentSheet.presentWithPaymentIntent(
                paymentIntentClientSecret = s.clientSecret,
                configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App"),
            )
        }
    }

    var showDiscardDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (state.currentStep == 1) showDiscardDialog = true
        else viewModel.goBack()
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard challenge?") },
            text = { Text("Your progress will be lost.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onDiscarded() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing")
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            WizardHeader(
                currentStep = state.currentStep,
                totalSteps = TOTAL_STEPS,
                onBack = {
                    if (state.currentStep == 1) showDiscardDialog = true
                    else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * direction } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * direction } + fadeOut())
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "wizard_step",
            ) { step ->
                when (step) {
                    1 -> Step1ModeSelection(
                        selectedMode = state.selectedMode,
                        onSelectMode = viewModel::selectMode,
                    )
                    2 -> Step2AppOrWebsite(
                        appListState = appListState,
                        selectedApps = state.selectedApps,
                        searchQuery = state.searchQuery,
                        activeTab = state.activeTab,
                        domainToggles = state.domainToggles,
                        manualDomains = state.manualDomains,
                        manualDomainInput = state.manualDomainInput,
                        manualDomainError = state.manualDomainError,
                        blockAdultContent = state.blockAdultContent,
                        partialBlockDomains = state.partialBlockDomains,
                        partialBlockSections = state.partialBlockSections,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onToggleApp = viewModel::toggleApp,
                        onReloadApps = viewModel::loadApps,
                        onTabChange = viewModel::updateActiveTab,
                        onToggleDomain = viewModel::toggleDomain,
                        onManualDomainInputChange = viewModel::updateManualDomainInput,
                        onAddManualDomain = viewModel::addManualDomain,
                        onRemoveManualDomain = viewModel::removeManualDomain,
                        onBlockAdultContentChange = viewModel::updateBlockAdultContent,
                        onTogglePartialBlock = viewModel::togglePartialBlockDomain,
                        onTogglePartialSection = viewModel::togglePartialSection,
                    )
                    3 -> Step3LimitType(
                        selected = state.limitType,
                        onSelect = viewModel::selectLimitType,
                    )
                    4 -> Step4LimitValues(
                        state = state,
                        onUpdateLimitMinutes = viewModel::updateLimitMinutes,
                        onUpdateLimitSessions = viewModel::updateLimitSessions,
                        onUpdateSessionDuration = viewModel::updateSessionDurationMinutes,
                        onUpdateDailyBudget = viewModel::updateDailyBudgetMinutes,
                    )
                    5 -> Step5Schedule(
                        scheduleStart = state.scheduleStart,
                        scheduleEnd = state.scheduleEnd,
                        activeDays = state.activeDays,
                        isRequired = state.limitType == LimitType.TIME_WINDOW,
                        onStartChange = viewModel::updateScheduleStart,
                        onEndChange = viewModel::updateScheduleEnd,
                        onToggleDay = viewModel::toggleActiveDay,
                        onClearSchedule = viewModel::clearSchedule,
                    )
                    6 -> Step6Duration(
                        state = state,
                        onUpdateDuration = viewModel::updateDurationDays,
                        onToggleNoEndDate = viewModel::updateNoEndDate,
                        onUpdateAmount = viewModel::updateAmountEuros,
                    )
                    7 -> Step7Confirm(
                        state = state,
                        uiState = uiState,
                        onUpdateMotivation = viewModel::updateMotivationText,
                        onCreateChallenge = viewModel::createChallenge,
                    )
                }
            }

            if (state.currentStep < TOTAL_STEPS) {
                HorizontalDivider()
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = viewModel::goNext,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.canGoNext(),
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

// ── Wizard header ─────────────────────────────────────────────────────────────

@Composable
private fun WizardHeader(
    currentStep: Int,
    totalSteps: Int,
    onBack: () -> Unit,
) {
    val progress = currentStep.toFloat() / totalSteps.toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                text = "Step $currentStep of $totalSteps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ── Step 1: Mode selection ────────────────────────────────────────────────────

@Composable
private fun Step1ModeSelection(
    selectedMode: ChallengeMode?,
    onSelectMode: (ChallengeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Choose your mode",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "How serious do you want to be?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModeCard(
            emoji = "🎯",
            title = "Soft Mode",
            subtitle = "Build habits with streaks. No money involved.",
            isSelected = selectedMode == ChallengeMode.SOFT,
            showWarning = false,
            onClick = { onSelectMode(ChallengeMode.SOFT) },
        )

        ModeCard(
            emoji = "💰",
            title = "Hard Mode",
            subtitle = "Real money on the line. Serious commitment.",
            isSelected = selectedMode == ChallengeMode.HARD,
            showWarning = true,
            onClick = { onSelectMode(ChallengeMode.HARD) },
        )
    }
}

@Composable
private fun ModeCard(
    emoji: String,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    showWarning: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp)
            .border(width = 2.dp, color = borderColor, shape = MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = emoji, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showWarning) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Step 2: Apps / Websites tabs ──────────────────────────────────────────────

@Composable
private fun Step2AppOrWebsite(
    appListState: AppListState,
    selectedApps: Set<String>,
    searchQuery: String,
    activeTab: Int,
    domainToggles: Map<String, Boolean>,
    manualDomains: List<String>,
    manualDomainInput: String,
    manualDomainError: String?,
    blockAdultContent: Boolean,
    partialBlockDomains: Set<String>,
    partialBlockSections: Set<String>,
    onSearchQueryChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onReloadApps: () -> Unit,
    onTabChange: (Int) -> Unit,
    onToggleDomain: (String) -> Unit,
    onManualDomainInputChange: (String) -> Unit,
    onAddManualDomain: () -> Unit,
    onRemoveManualDomain: (String) -> Unit,
    onBlockAdultContentChange: (Boolean) -> Unit,
    onTogglePartialBlock: (String) -> Unit,
    onTogglePartialSection: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = activeTab) {
            Tab(
                selected = activeTab == 0,
                onClick = { onTabChange(0) },
                text = { Text("📱 Apps") },
            )
            Tab(
                selected = activeTab == 1,
                onClick = { onTabChange(1) },
                text = { Text("🌐 Websites") },
            )
        }

        when (activeTab) {
            0 -> AppsTabContent(
                appListState = appListState,
                selectedApps = selectedApps,
                searchQuery = searchQuery,
                domainToggles = domainToggles,
                partialBlockSections = partialBlockSections,
                onSearchQueryChange = onSearchQueryChange,
                onToggleApp = onToggleApp,
                onReloadApps = onReloadApps,
                onToggleDomain = onToggleDomain,
                onTogglePartialSection = onTogglePartialSection,
            )
            1 -> WebsitesTabContent(
                manualDomains = manualDomains,
                manualDomainInput = manualDomainInput,
                manualDomainError = manualDomainError,
                blockAdultContent = blockAdultContent,
                partialBlockDomains = partialBlockDomains,
                onManualDomainInputChange = onManualDomainInputChange,
                onAddManualDomain = onAddManualDomain,
                onRemoveManualDomain = onRemoveManualDomain,
                onBlockAdultContentChange = onBlockAdultContentChange,
                onTogglePartialBlock = onTogglePartialBlock,
            )
        }
    }
}

// ── Apps tab ──────────────────────────────────────────────────────────────────

@Composable
private fun AppsTabContent(
    appListState: AppListState,
    selectedApps: Set<String>,
    searchQuery: String,
    domainToggles: Map<String, Boolean>,
    partialBlockSections: Set<String>,
    onSearchQueryChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onReloadApps: () -> Unit,
    onToggleDomain: (String) -> Unit,
    onTogglePartialSection: (String) -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Select apps to track",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Pick one or more apps you want to reduce.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
            )
        }

        when {
            appListState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            appListState.noPermission -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = "Usage access permission is required to see your app stats.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) {
                            Text("Grant permission")
                        }
                    }
                }
            }

            appListState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = "Failed to load apps.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = onReloadApps) { Text("Retry") }
                    }
                }
            }

            else -> {
                val query = searchQuery.trim().lowercase()
                val trackable = if (query.isEmpty()) appListState.trackableApps
                    else appListState.trackableApps.filter { it.appName.lowercase().contains(query) }
                val nonTrackable = if (query.isEmpty()) appListState.nonTrackableApps
                    else appListState.nonTrackableApps.filter { it.appName.lowercase().contains(query) }

                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(trackable, key = { it.packageName }) { app ->
                        val isSelected = selectedApps.contains(app.packageName)
                        val conflictName = appListState.conflictingPackages[app.packageName]
                        AppListRow(
                            app = app,
                            isSelected = isSelected,
                            conflictChallengeName = conflictName,
                            onToggle = { if (conflictName == null) onToggleApp(app.packageName) },
                        )
                        val sections = PartialBlockSection.BY_PACKAGE[app.packageName]
                        if (!sections.isNullOrEmpty()) {
                            sections.forEach { section ->
                                PartialSectionSubRow(
                                    section = section,
                                    app = app,
                                    isSelected = partialBlockSections.contains(section.id),
                                    onToggle = { onTogglePartialSection(section.id) },
                                )
                            }
                        }
                    }

                    if (nonTrackable.isNotEmpty()) {
                        item {
                            Column {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    text = "Not enough usage to track",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        items(nonTrackable, key = { "${it.packageName}_nt" }) { app ->
                            AppListRow(
                                app = app,
                                isSelected = false,
                                conflictChallengeName = null,
                                onToggle = {},
                                dimmed = true,
                            )
                        }
                    }

                    // Domain suggestions for selected apps that have known domains
                    if (domainToggles.isNotEmpty()) {
                        item {
                            DomainSuggestionsSection(
                                domainToggles = domainToggles,
                                appListState = appListState,
                                onToggleDomain = onToggleDomain,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainSuggestionsSection(
    domainToggles: Map<String, Boolean>,
    appListState: AppListState,
    onToggleDomain: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Also block these websites?",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        domainToggles.forEach { (pkg, enabled) ->
            val domains = APP_DOMAIN_MAP[pkg] ?: return@forEach
            val appName = appListState.trackableApps
                .firstOrNull { it.packageName == pkg }?.appName ?: pkg
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleDomain(pkg) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = { onToggleDomain(pkg) },
                    modifier = Modifier.size(36.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Also block ${domains.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Websites tab ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebsitesTabContent(
    manualDomains: List<String>,
    manualDomainInput: String,
    manualDomainError: String?,
    blockAdultContent: Boolean,
    partialBlockDomains: Set<String>,
    onManualDomainInputChange: (String) -> Unit,
    onAddManualDomain: () -> Unit,
    onRemoveManualDomain: (String) -> Unit,
    onBlockAdultContentChange: (Boolean) -> Unit,
    onTogglePartialBlock: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Block websites",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Add websites to block in your browser during this challenge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Domain input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = manualDomainInput,
                onValueChange = onManualDomainInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. instagram.com") },
                singleLine = true,
                isError = manualDomainError != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAddManualDomain() }),
                shape = RoundedCornerShape(12.dp),
            )
            Button(onClick = onAddManualDomain) {
                Text("Add")
            }
        }

        if (manualDomainError != null) {
            Text(
                text = manualDomainError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (manualDomains.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                manualDomains.forEach { domain ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveManualDomain(domain) },
                        label = { Text(domain) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        // ── Block specific features (partial-block toggles) ────────────────────
        // Only shown when the parent domain is NOT already fully blocked.
        val visibleFeatures = FEATURE_BLOCK_MAP.filter { (path, _) ->
            val parentDomain = path.substringBefore('/')
            manualDomains.none { it.equals(parentDomain, ignoreCase = true) }
        }
        if (visibleFeatures.isNotEmpty()) {
            Text(
                text = "Block specific features",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Block only certain sections of websites you haven't fully blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            visibleFeatures.forEach { (path, featureName) ->
                val isEnabled = partialBlockDomains.contains(path)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTogglePartialBlock(path) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🚫 $featureName",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onTogglePartialBlock(path) },
                    )
                }
            }
            HorizontalDivider()
        }

        // Adult content toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🔞 Block Adult Content",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (blockAdultContent) {
                    Text(
                        text = "100+ adult websites will be blocked automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(
                checked = blockAdultContent,
                onCheckedChange = onBlockAdultContentChange,
            )
        }
    }
}

// ── App list row ─────────────────────────────────────────────────────────────

@Composable
private fun AppListRow(
    app: AppUsageInfo,
    isSelected: Boolean,
    conflictChallengeName: String?,
    onToggle: () -> Unit,
    dimmed: Boolean = false,
) {
    val isBusy = conflictChallengeName != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !dimmed && !isBusy, onClick = onToggle)
            .alpha(if (dimmed || isBusy) 0.4f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let { drawable ->
            val painter = remember(drawable) {
                BitmapPainter(drawable.toBitmap(48, 48).asImageBitmap())
            }
            Image(
                painter = painter,
                contentDescription = app.appName,
                modifier = Modifier.size(48.dp),
            )
        } ?: Box(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (app.avgDailyMinutes > 0) {
                Text(
                    text = "${app.avgDailyMinutes} min/day · ${app.avgDailyOpens} opens/day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isBusy) {
                Text(
                    text = "busy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}

// ── Partial section sub-row ───────────────────────────────────────────────────

@Composable
private fun PartialSectionSubRow(
    section: PartialBlockSection,
    app: AppUsageInfo,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let { drawable ->
            val painter = remember(drawable) {
                BitmapPainter(drawable.toBitmap(36, 36).asImageBitmap())
            }
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } ?: Box(modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text(
                text = section.subRowDescription,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = androidx.compose.ui.graphics.Color(0xFF8E8E93),
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
}

// ── Step 3: Limit type ────────────────────────────────────────────────────────

@Composable
private fun Step3LimitType(
    selected: LimitType?,
    onSelect: (LimitType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Set a limit type",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "How do you want to restrict yourself?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        LimitTypeCard(
            emoji = "⏱",
            title = "Time Limit",
            description = "Block after X minutes per day",
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        LimitTypeCard(
            emoji = "🔢",
            title = "Session Limit",
            description = "Block after X conscious opens per day",
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        LimitTypeCard(
            emoji = "💰",
            title = "Daily Budget",
            description = "Split your time across the day",
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
        LimitTypeCard(
            emoji = "🕐",
            title = "Time Window Only",
            description = "Only allow during specific hours",
            isSelected = selected == LimitType.TIME_WINDOW,
            onClick = { onSelect(LimitType.TIME_WINDOW) },
        )
    }
}

@Composable
private fun LimitTypeCard(
    emoji: String,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 2.dp, color = borderColor, shape = MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Step 4: Limit values ──────────────────────────────────────────────────────

@Composable
private fun Step4LimitValues(
    state: ChallengeCreationState,
    onUpdateLimitMinutes: (Int) -> Unit,
    onUpdateLimitSessions: (Int) -> Unit,
    onUpdateSessionDuration: (Int) -> Unit,
    onUpdateDailyBudget: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Set your limit",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        when (state.limitType) {
            LimitType.TIME -> {
                Text(
                    text = "How many minutes per day?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                StepperField(
                    value = state.limitValueMinutes,
                    onValueChange = onUpdateLimitMinutes,
                    label = "Daily limit",
                    suffix = "min",
                    min = 5,
                    max = 600,
                    step = 5,
                    error = state.limitMinutesError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.limitMinutesError == null && state.limitValueMinutes >= 5) {
                    Text(
                        text = "App will be blocked after ${state.limitValueMinutes} min/day",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            LimitType.SESSIONS -> {
                Text(
                    text = "How many opens per day, and how long per session?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                StepperField(
                    value = state.limitValueSessions,
                    onValueChange = onUpdateLimitSessions,
                    label = "Max opens",
                    suffix = "opens",
                    min = 1,
                    max = 50,
                    step = 1,
                    error = state.limitSessionsError,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                StepperField(
                    value = state.sessionDurationMinutes,
                    onValueChange = onUpdateSessionDuration,
                    label = "Per session",
                    suffix = "min",
                    min = 1,
                    max = 120,
                    step = 1,
                    error = state.sessionMinutesError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.limitSessionsError == null && state.sessionMinutesError == null) {
                    Text(
                        text = "${state.limitValueSessions}× opens · ${state.sessionDurationMinutes} min each",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            LimitType.TIME_BUDGET -> {
                Text(
                    text = "What's your daily time budget?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                StepperField(
                    value = state.dailyBudgetMinutes,
                    onValueChange = onUpdateDailyBudget,
                    label = "Daily budget",
                    suffix = "min",
                    min = 1,
                    max = 600,
                    step = 5,
                    error = state.dailyBudgetError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.dailyBudgetError == null && state.dailyBudgetMinutes >= 1) {
                    Text(
                        text = "${state.dailyBudgetMinutes} min total per day",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            LimitType.TIME_WINDOW -> {
                Text(
                    text = "You'll set the allowed hours in the next step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "🕐 App will only be accessible during your scheduled time window.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            null -> {
                Text(
                    text = "Go back and select a limit type.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Step 5: Schedule ──────────────────────────────────────────────────────────

private val ALL_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val DAY_LABELS = mapOf(
    "MON" to "Mo", "TUE" to "Tu", "WED" to "We",
    "THU" to "Th", "FRI" to "Fr", "SAT" to "Sa", "SUN" to "Su",
)

private fun parseTime(time: String): Pair<Int, Int> =
    if (time.length != 5) 0 to 0
    else runCatching { time.split(":").let { it[0].toInt() to it[1].toInt() } }.getOrDefault(0 to 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step5Schedule(
    scheduleStart: String,
    scheduleEnd: String,
    activeDays: Set<String>,
    isRequired: Boolean,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onToggleDay: (String) -> Unit,
    onClearSchedule: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val (startH, startM) = parseTime(scheduleStart)
    val (endH, endM) = parseTime(scheduleEnd)

    val timeError: String? = if (scheduleStart.length == 5 && scheduleEnd.length == 5) {
        if (startH * 60 + startM >= endH * 60 + endM) "End time must be after start time" else null
    } else null

    val timeDisplay = when {
        scheduleStart.length == 5 && scheduleEnd.length == 5 -> "$scheduleStart – $scheduleEnd"
        scheduleStart.length == 5 -> "$scheduleStart – ??:??"
        scheduleEnd.length == 5   -> "??:?? – $scheduleEnd"
        else -> "Tap to set time range"
    }

    val hasSchedule = scheduleStart.isNotBlank() || scheduleEnd.isNotBlank() || activeDays.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (isRequired) "Set allowed hours" else "Usage schedule (optional)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (isRequired)
                "The app will only be accessible during this window."
            else
                "Optionally restrict tracking to specific hours and days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .border(
                    width = 1.dp,
                    color = if (timeError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.small,
                )
                .clickable { showTimePicker = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = timeDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = if (scheduleStart.length == 5 || scheduleEnd.length == 5)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (timeError != null) {
            Text(text = timeError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ALL_DAYS.forEach { day ->
                val isSelected = activeDays.contains(day)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleDay(day) },
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = DAY_LABELS[day] ?: day,
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (hasSchedule) {
            OutlinedButton(onClick = onClearSchedule) {
                Text("Clear schedule")
            }
        }
    }

    if (showTimePicker) {
        ModalBottomSheet(
            onDismissRequest = { showTimePicker = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Set time range",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    TimeSpinnerPicker(
                        hour = startH,
                        minute = startM,
                        label = "From",
                        onTimeChange = { h, m -> onStartChange("%02d:%02d".format(h, m)) },
                    )
                    TimeSpinnerPicker(
                        hour = endH,
                        minute = endM,
                        label = "To",
                        onTimeChange = { h, m -> onEndChange("%02d:%02d".format(h, m)) },
                    )
                }
                if (timeError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = timeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { showTimePicker = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ── Step 6: Duration ──────────────────────────────────────────────────────────

@Composable
private fun Step6Duration(
    state: ChallengeCreationState,
    onUpdateDuration: (Int) -> Unit,
    onToggleNoEndDate: (Boolean) -> Unit,
    onUpdateAmount: (Int) -> Unit,
) {
    val isHardMode = state.selectedMode == ChallengeMode.HARD

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Challenge duration",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        if (isHardMode) {
            Text(
                text = "Amount at stake",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            StepperField(
                value = state.amountEuros,
                onValueChange = onUpdateAmount,
                label = "Amount (€)",
                min = 5,
                max = 50,
                step = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "⚠️ If you exceed the limit, €${state.amountEuros} will be captured immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            HorizontalDivider()
        }

        if (!isHardMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "No end date",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = state.noEndDate,
                    onCheckedChange = onToggleNoEndDate,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    )
                )
            }
        }

        if (!state.noEndDate) {
            StepperField(
                value = state.durationDays,
                onValueChange = onUpdateDuration,
                label = "Duration",
                suffix = "days",
                min = if (isHardMode) 14 else 1,
                max = 365,
                step = 1,
                error = state.durationError,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presets = if (isHardMode) listOf(14, 21, 30, 60) else listOf(1, 7, 14, 30)
                presets.forEach { days ->
                    FilterChip(
                        selected = state.durationDays == days,
                        onClick = { onUpdateDuration(days) },
                        label = { Text("${days}d", maxLines = 1) },
                    )
                }
            }
        }
    }
}

// ── Step 7: Confirm ───────────────────────────────────────────────────────────

@Composable
private fun Step7Confirm(
    state: ChallengeCreationState,
    uiState: ChallengeCreationUiState,
    onUpdateMotivation: (String) -> Unit,
    onCreateChallenge: () -> Unit,
) {
    val isLoading = uiState is ChallengeCreationUiState.Loading ||
            uiState is ChallengeCreationUiState.AwaitingPayment

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Review & start",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SummaryRow("Mode", if (state.selectedMode == ChallengeMode.HARD) "💰 Hard Mode" else "🎯 Soft Mode")

                if (state.activeTab == 0) {
                    // Apps tab summary
                    SummaryRow("Apps", "${state.selectedApps.size} selected")
                    val checkedDomains = state.domainToggles.entries.filter { it.value }
                        .flatMap { APP_DOMAIN_MAP[it.key] ?: emptyList() }
                    if (checkedDomains.isNotEmpty()) {
                        SummaryRow("+ Sites", checkedDomains.joinToString(", "))
                    }
                } else {
                    // Websites tab summary
                    if (state.manualDomains.isNotEmpty()) {
                        val preview = state.manualDomains.take(3).joinToString(", ") +
                                if (state.manualDomains.size > 3) " +${state.manualDomains.size - 3}" else ""
                        SummaryRow("Websites", preview)
                    }
                    if (state.blockAdultContent) {
                        SummaryRow("Adult Content", "🔞 Blocked")
                    }
                }

                val limitSummary = when (state.limitType) {
                    LimitType.TIME        -> "⏱ ${state.limitValueMinutes} min/day"
                    LimitType.SESSIONS    -> "🔢 ${state.limitValueSessions}× · ${state.sessionDurationMinutes} min each"
                    LimitType.TIME_BUDGET -> "💰 ${state.dailyBudgetMinutes} min budget/day"
                    LimitType.TIME_WINDOW -> "🕐 Time window"
                    null                  -> "—"
                }
                SummaryRow("Limit", limitSummary)

                if (state.scheduleStart.length == 5 && state.scheduleEnd.length == 5) {
                    SummaryRow("Schedule", "${state.scheduleStart} – ${state.scheduleEnd}")
                }

                val durationSummary = if (state.noEndDate) "No end date"
                    else "${state.durationDays} days"
                SummaryRow("Duration", durationSummary)

                if (state.selectedMode == ChallengeMode.HARD) {
                    SummaryRow("At stake", "€${state.amountEuros}")
                }
            }
        }

        Text(
            text = "Your motivation (optional)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = state.motivationText,
            onValueChange = { if (it.length <= 200) onUpdateMotivation(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Why are you doing this?") },
            minLines = 2,
            maxLines = 4,
        )

        if (uiState is ChallengeCreationUiState.Error) {
            Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onCreateChallenge,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = if (state.selectedMode == ChallengeMode.HARD)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else
                ButtonDefaults.buttonColors(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                val label = if (state.selectedMode == ChallengeMode.HARD)
                    "Pay €${state.amountEuros} & Start"
                else
                    "Start Challenge"
                Text(text = label)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
