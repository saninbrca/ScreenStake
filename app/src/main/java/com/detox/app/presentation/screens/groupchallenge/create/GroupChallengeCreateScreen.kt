package com.detox.app.presentation.screens.groupchallenge.create

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.components.StepperField
import com.detox.app.presentation.screens.challengecreation.APP_DOMAIN_MAP
import com.detox.app.presentation.screens.challengecreation.AppListState
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChallengeCreateScreen(
    onBack: () -> Unit,
    onCreated: (groupId: String) -> Unit,
    viewModel: GroupChallengeCreateViewModel = hiltViewModel(),
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appListState by viewModel.appListState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadApps()
        }
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onPaymentSuccess()
            is PaymentSheetResult.Canceled -> viewModel.onPaymentCancelled()
            is PaymentSheetResult.Failed -> viewModel.onPaymentCancelled()
        }
    }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GroupCreateUiState.AwaitingPayment -> {
                paymentSheet.presentWithPaymentIntent(
                    paymentIntentClientSecret = s.clientSecret,
                    configuration = PaymentSheet.Configuration(merchantDisplayName = "Detox App"),
                )
            }
            is GroupCreateUiState.Created -> {
                onCreated(s.groupId)
            }
            is GroupCreateUiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    BackHandler {
        if (formState.currentStep == 1) onBack()
        else viewModel.goBack()
    }

    val isLoading = uiState is GroupCreateUiState.Loading || uiState is GroupCreateUiState.AwaitingPayment

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(snackbarHostState)

            WizardHeader(
                currentStep = formState.currentStep,
                totalSteps = GROUP_WIZARD_TOTAL_STEPS,
                onBack = {
                    if (formState.currentStep == 1) onBack()
                    else viewModel.goBack()
                },
            )

            AnimatedContent(
                targetState = formState.currentStep,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * direction } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * direction } + fadeOut())
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "group_wizard_step",
            ) { step ->
                when (step) {
                    1 -> Step1AppSelection(
                        appListState = appListState,
                        selectedApps = formState.packageNames.toSet(),
                        searchQuery = formState.searchQuery,
                        domainToggles = formState.domainToggles,
                        packageNamesError = formState.packageNamesError,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onToggleApp = viewModel::toggleApp,
                        onReloadApps = viewModel::loadApps,
                        onToggleDomain = viewModel::toggleDomain,
                    )
                    2 -> Step2LimitType(
                        selected = formState.limitType,
                        onSelect = viewModel::setLimitType,
                    )
                    3 -> Step3LimitAndDuration(
                        formState = formState,
                        onUpdateLimitMinutes = viewModel::setLimitValueMinutes,
                        onUpdateLimitSessions = viewModel::setLimitValueSessions,
                        onUpdateSessionDuration = viewModel::setSessionMinutes,
                        onUpdateDailyBudget = viewModel::setDailyBudgetMinutes,
                        onUpdateDuration = viewModel::setDurationDays,
                    )
                    4 -> Step4BuyIn(
                        buyIn = formState.buyInEuros,
                        onBuyInChange = viewModel::setBuyInEuros,
                    )
                    5 -> Step5StartDateAndBonus(
                        startDateMs = formState.startDateMs,
                        startDateError = formState.startDateError,
                        bonusEnabled = formState.bonusEnabled,
                        onStartDateChange = viewModel::setStartDate,
                        onBonusToggle = viewModel::setBonusEnabled,
                    )
                    6 -> Step6Review(
                        formState = formState,
                        isLoading = isLoading,
                        onCreateChallenge = viewModel::createChallenge,
                    )
                }
            }

            if (formState.currentStep < GROUP_WIZARD_TOTAL_STEPS) {
                HorizontalDivider()
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = viewModel::goNext,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.canGoNext() && !isLoading,
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            progress = { currentStep.toFloat() / totalSteps.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

// ── Step 1: App selection ─────────────────────────────────────────────────────

@Composable
private fun Step1AppSelection(
    appListState: AppListState,
    selectedApps: Set<String>,
    searchQuery: String,
    domainToggles: Map<String, Boolean>,
    packageNamesError: String?,
    onSearchQueryChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onReloadApps: () -> Unit,
    onToggleDomain: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Select apps to block",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Pick one or more apps for this group challenge.",
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
            )
            if (packageNamesError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = packageNamesError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                    Text(
                        text = "Usage access permission required to see your app stats.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            appListState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "Failed to load apps.",
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

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(trackable, key = { it.packageName }) { app ->
                        val isSelected = selectedApps.contains(app.packageName)
                        val conflictName = appListState.conflictingPackages[app.packageName]
                        GroupAppGridItem(
                            app = app,
                            isSelected = isSelected,
                            conflictChallengeName = conflictName,
                            onToggle = { if (conflictName == null) onToggleApp(app.packageName) },
                        )
                    }

                    if (nonTrackable.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
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
                            GroupAppGridItem(
                                app = app,
                                isSelected = false,
                                conflictChallengeName = null,
                                onToggle = {},
                                dimmed = true,
                            )
                        }
                    }

                    if (domainToggles.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            GroupDomainSuggestionsSection(
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
private fun GroupAppGridItem(
    app: AppUsageInfo,
    isSelected: Boolean,
    conflictChallengeName: String?,
    onToggle: () -> Unit,
    dimmed: Boolean = false,
) {
    val borderColor = when {
        conflictChallengeName != null -> MaterialTheme.colorScheme.error
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected || conflictChallengeName != null) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.small,
            )
            .alpha(if (dimmed) 0.45f else 1f)
            .clickable(enabled = !dimmed && conflictChallengeName == null, onClick = onToggle),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                app.icon?.let { drawable ->
                    val painter = remember(drawable) {
                        BitmapPainter(drawable.toBitmap(48, 48).asImageBitmap())
                    }
                    Image(
                        painter = painter,
                        contentDescription = app.appName,
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center),
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.TopEnd),
                    )
                }
            }
            Text(
                text = app.appName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (app.avgDailyMinutes > 0) {
                Text(
                    text = "${app.avgDailyMinutes}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (conflictChallengeName != null) {
                Text(
                    text = "busy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GroupDomainSuggestionsSection(
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

// ── Step 2: Limit type ────────────────────────────────────────────────────────

@Composable
private fun Step2LimitType(
    selected: LimitType,
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "How do you want to restrict the group?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        GroupLimitTypeCard(
            emoji = "⏱",
            title = "Time Limit",
            description = "Block after X minutes per day",
            isSelected = selected == LimitType.TIME,
            onClick = { onSelect(LimitType.TIME) },
        )
        GroupLimitTypeCard(
            emoji = "🔢",
            title = "Session Limit",
            description = "Block after X conscious opens per day",
            isSelected = selected == LimitType.SESSIONS,
            onClick = { onSelect(LimitType.SESSIONS) },
        )
        GroupLimitTypeCard(
            emoji = "💰",
            title = "Daily Budget",
            description = "Split your time across the day",
            isSelected = selected == LimitType.TIME_BUDGET,
            onClick = { onSelect(LimitType.TIME_BUDGET) },
        )
    }
}

@Composable
private fun GroupLimitTypeCard(
    emoji: String,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 2.dp, color = borderColor, shape = MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = emoji, fontSize = 20.sp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
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

// ── Step 3: Limit value + duration ────────────────────────────────────────────

@Composable
private fun Step3LimitAndDuration(
    formState: GroupCreateFormState,
    onUpdateLimitMinutes: (Int) -> Unit,
    onUpdateLimitSessions: (Int) -> Unit,
    onUpdateSessionDuration: (Int) -> Unit,
    onUpdateDailyBudget: (Int) -> Unit,
    onUpdateDuration: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Set your limit",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        when (formState.limitType) {
            LimitType.TIME -> {
                Text(
                    text = "How many minutes per day?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepperField(
                    value = formState.limitValueMinutes,
                    onValueChange = onUpdateLimitMinutes,
                    label = "Daily limit",
                    suffix = "min",
                    min = 5,
                    max = 600,
                    step = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "App will be blocked after ${formState.limitValueMinutes} min/day",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LimitType.SESSIONS -> {
                Text(
                    text = "How many opens per day, and how long per session?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepperField(
                    value = formState.limitValueSessions,
                    onValueChange = onUpdateLimitSessions,
                    label = "Max opens",
                    suffix = "opens",
                    min = 1,
                    max = 50,
                    step = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                StepperField(
                    value = formState.sessionMinutes,
                    onValueChange = onUpdateSessionDuration,
                    label = "Per session",
                    suffix = "min",
                    min = 5,
                    max = 120,
                    step = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${formState.limitValueSessions}× opens · ${formState.sessionMinutes} min each",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LimitType.TIME_BUDGET -> {
                Text(
                    text = "What's the daily time budget?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepperField(
                    value = formState.dailyBudgetMinutes,
                    onValueChange = onUpdateDailyBudget,
                    label = "Daily budget",
                    suffix = "min",
                    min = 5,
                    max = 600,
                    step = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${formState.dailyBudgetMinutes} min total per day",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            else -> Unit
        }

        HorizontalDivider()

        Text(
            text = "Duration",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        StepperField(
            value = formState.durationDays,
            onValueChange = onUpdateDuration,
            label = "Duration",
            suffix = "days",
            min = 3,
            max = 365,
            step = 1,
            error = formState.durationError,
            modifier = Modifier.fillMaxWidth(),
        )
        if (formState.durationError != null) {
            Text(
                text = formState.durationError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ── Step 4: Buy-in ────────────────────────────────────────────────────────────

@Composable
private fun Step4BuyIn(
    buyIn: Int,
    onBuyInChange: (Int) -> Unit,
) {
    val estimatedPot = buyIn * 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Buy-in per player",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "All players must pay to join. The pot is split between successful completers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StepperField(
            value = buyIn,
            onValueChange = onBuyInChange,
            label = "Buy-in per player",
            suffix = "€",
            min = 10,
            max = 50,
            step = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Total pot with 20 players:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "€$estimatedPot",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ── Step 5: Start date + bonus ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step5StartDateAndBonus(
    startDateMs: Long,
    startDateError: String?,
    bonusEnabled: Boolean,
    onStartDateChange: (Long) -> Unit,
    onBonusToggle: (Boolean) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val tomorrowUtcMidnight = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = tomorrowUtcMidnight,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= tomorrowUtcMidnight
        },
    )
    val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { onStartDateChange(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Start date & bonus",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Start Date (tomorrow or later)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(
                    if (startDateMs > 0L) sdf.format(Date(startDateMs))
                    else "Pick a date…"
                )
            }
            if (startDateError != null) {
                Text(
                    text = startDateError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()

        val tooltipState = rememberTooltipState()
        val tooltipScope = rememberCoroutineScope()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Bonus for winner 🏆",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = {
                        PlainTooltip {
                            Text("Best performer gets 10% extra from the pot. App takes 5% fee.")
                        }
                    },
                    state = tooltipState,
                ) {
                    IconButton(
                        onClick = { tooltipScope.launch { tooltipState.show() } },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Bonus info",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Switch(checked = bonusEnabled, onCheckedChange = onBonusToggle)
        }

        if (bonusEnabled) {
            Text(
                text = "The participant with the best performance receives an extra 10% of the total pot. The app takes a 5% fee.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Step 6: Review & create ───────────────────────────────────────────────────

@Composable
private fun Step6Review(
    formState: GroupCreateFormState,
    isLoading: Boolean,
    onCreateChallenge: () -> Unit,
) {
    val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val estimatedPot = formState.buyInEuros * 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Review & Create",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val appSummary = if (formState.packageNames.size == 1) formState.displayName
                    else "${formState.displayName} +${formState.packageNames.size - 1} more"
                ReviewRow("Apps", appSummary)

                val checkedDomains = formState.domainToggles.entries
                    .filter { it.value }
                    .flatMap { APP_DOMAIN_MAP[it.key] ?: emptyList() }
                if (checkedDomains.isNotEmpty()) {
                    ReviewRow("+ Websites", checkedDomains.joinToString(", "))
                }

                val limitSummary = when (formState.limitType) {
                    LimitType.TIME -> "⏱ ${formState.limitValueMinutes} min/day"
                    LimitType.SESSIONS -> "🔢 ${formState.limitValueSessions}× · ${formState.sessionMinutes} min each"
                    LimitType.TIME_BUDGET -> "💰 ${formState.dailyBudgetMinutes} min budget/day"
                    else -> "—"
                }
                ReviewRow("Limit", limitSummary)
                ReviewRow("Duration", "${formState.durationDays} days")
                ReviewRow("Buy-in", "€${formState.buyInEuros} per player")
                ReviewRow("Start date", if (formState.startDateMs > 0L) sdf.format(Date(formState.startDateMs)) else "—")
                ReviewRow("Winner bonus", if (formState.bonusEnabled) "Enabled (10% of pot)" else "Disabled")
                ReviewRow("Max players", "20")

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Estimated pot:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Up to €$estimatedPot with 20 players",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onCreateChallenge,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Create Group Challenge 🚀")
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
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
