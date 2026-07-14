package com.detox.app.presentation.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.detox.app.util.HapticManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.presentation.screens.challengecreation.APP_DOMAIN_MAP
import com.detox.app.presentation.screens.challengecreation.AppListState
import com.detox.app.ui.theme.detoxColors

// ── Shared App/Website selection step ─────────────────────────────────────────
// Used identically in Solo Wizard (Step 2) and Group Challenge Wizard (Step 1).
// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

@Composable
internal fun AppWebsiteSelectionStep(
    appListState: AppListState,
    selectedApps: Set<String>,
    searchQuery: String,
    activeTab: Int,
    domainToggles: Map<String, Boolean>,
    manualDomains: List<String>,
    manualDomainInput: String,
    manualDomainError: String?,
    blockAdultContent: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onReloadApps: () -> Unit,
    onTabChange: (Int) -> Unit,
    onToggleDomain: (String) -> Unit,
    onManualDomainInputChange: (String) -> Unit,
    onAddManualDomain: () -> Unit,
    onRemoveManualDomain: (String) -> Unit,
    onBlockAdultContentChange: (Boolean) -> Unit,
) {
    // LocalContentColor's static Black default would make every ripple and every
    // default-colored Text invisible in dark mode — resolve it once for the step.
    CompositionLocalProvider(LocalContentColor provides detoxColors.label) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Pill tab switcher with a sliding white indicator (no elevation — replaces the old
        // per-tab drop shadow). Labels use clean line icons instead of emoji.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(detoxColors.insetSurface)
                .padding(4.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                val tabWidth = maxWidth / 2
                val indicatorOffset by animateDpAsState(
                    targetValue = if (activeTab == 0) 0.dp else tabWidth,
                    animationSpec = tween(250),
                    label = "tab_indicator_offset",
                )
                // Sliding indicator
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50.dp))
                        .background(detoxColors.cardBackground),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    val tabIcons = listOf(Icons.Outlined.Apps, Icons.Outlined.Language)
                    val tabLabels = listOf(
                        stringResource(R.string.app_selection_tab_apps),
                        stringResource(R.string.app_selection_tab_websites),
                    )
                    tabIcons.forEachIndexed { index, icon ->
                        val isActive = activeTab == index
                        val tabTint by animateColorAsState(
                            targetValue = if (isActive) detoxColors.accent else detoxColors.subtext,
                            animationSpec = tween(200),
                            label = "tab_tint",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50.dp))
                                .clickable { onTabChange(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = tabTint,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = tabLabels[index],
                                    fontSize = 14.sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = tabTint,
                                )
                            }
                        }
                    }
                }
            }
        }

        when (activeTab) {
            0 -> AppsTabContent(
                appListState = appListState,
                selectedApps = selectedApps,
                searchQuery = searchQuery,
                domainToggles = domainToggles,
                onSearchQueryChange = onSearchQueryChange,
                onToggleApp = onToggleApp,
                onReloadApps = onReloadApps,
                onToggleDomain = onToggleDomain,
            )
            1 -> WebsitesTabContent(
                manualDomains = manualDomains,
                manualDomainInput = manualDomainInput,
                manualDomainError = manualDomainError,
                blockAdultContent = blockAdultContent,
                onManualDomainInputChange = onManualDomainInputChange,
                onAddManualDomain = onAddManualDomain,
                onRemoveManualDomain = onRemoveManualDomain,
                onBlockAdultContentChange = onBlockAdultContentChange,
            )
        }
    }
    }
}

// ── Apps tab ──────────────────────────────────────────────────────────────────

@Composable
internal fun AppsTabContent(
    appListState: AppListState,
    selectedApps: Set<String>,
    searchQuery: String,
    domainToggles: Map<String, Boolean>,
    onSearchQueryChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onReloadApps: () -> Unit,
    onToggleDomain: (String) -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        BasicTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(detoxColors.insetSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            // BasicTextField's static black text/cursor defaults are invisible on the
            // dark field — resolve both from the theme.
            textStyle = TextStyle(color = detoxColors.label),
            cursorBrush = SolidColor(detoxColors.label),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = detoxColors.subtext,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                stringResource(R.string.app_selection_search_placeholder),
                                color = detoxColors.subtext,
                                fontSize = 15.sp,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )

        when {
            appListState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.loading_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.grant_permission))
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
                        OutlinedButton(onClick = onReloadApps) { Text(stringResource(R.string.retry)) }
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
                        AppSelectionRow(
                            app = app,
                            isSelected = isSelected,
                            conflictChallengeName = conflictName,
                            onToggle = { if (conflictName == null) onToggleApp(app.packageName) },
                        )
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
                            AppSelectionRow(
                                app = app,
                                isSelected = false,
                                conflictChallengeName = null,
                                onToggle = {},
                                dimmed = true,
                            )
                        }
                    }

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
internal fun DomainSuggestionsSection(
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
internal fun WebsitesTabContent(
    manualDomains: List<String>,
    manualDomainInput: String,
    manualDomainError: String?,
    blockAdultContent: Boolean,
    onManualDomainInputChange: (String) -> Unit,
    onAddManualDomain: () -> Unit,
    onRemoveManualDomain: (String) -> Unit,
    onBlockAdultContentChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = detoxColors.attentionSurface),
            border = BorderStroke(0.5.dp, detoxColors.attentionBorder),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(detoxColors.danger),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "18+",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = detoxColors.tileGlyph,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_selection_adult_content_block),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = detoxColors.label,
                    )
                    Text(
                        text = stringResource(R.string.app_selection_adult_content_desc),
                        fontSize = 12.sp,
                        color = detoxColors.subtext,
                    )
                }
                Switch(
                    checked = blockAdultContent,
                    onCheckedChange = onBlockAdultContentChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = detoxColors.danger,
                        uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = manualDomainInput,
                onValueChange = onManualDomainInputChange,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50.dp))
                    .background(detoxColors.insetSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                // Static black text/cursor defaults are invisible on the dark field.
                textStyle = TextStyle(color = detoxColors.label),
                cursorBrush = SolidColor(detoxColors.label),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAddManualDomain() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (manualDomainInput.isEmpty()) {
                            Text(
                                stringResource(R.string.app_selection_domain_input_hint),
                                color = detoxColors.subtext,
                                fontSize = 15.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Button(
                onClick = onAddManualDomain,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.add), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (manualDomainError != null) {
            Text(
                text = manualDomainError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (manualDomains.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                manualDomains.forEach { domain ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(detoxColors.insetSurface)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(domain, fontSize = 13.sp, color = detoxColors.label)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = detoxColors.subtext,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onRemoveManualDomain(domain) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── App list row ──────────────────────────────────────────────────────────────

@Composable
internal fun AppSelectionRow(
    app: AppUsageInfo,
    isSelected: Boolean,
    conflictChallengeName: String?,
    onToggle: () -> Unit,
    dimmed: Boolean = false,
) {
    val isBusy = conflictChallengeName != null
    val context = LocalContext.current
    val bgColor = when {
        isBusy -> MaterialTheme.colorScheme.surfaceVariant
        isSelected -> detoxColors.selectedSurface
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(enabled = !dimmed && !isBusy) {
                HapticManager.light(context)
                onToggle()
            }
            .alpha(if (dimmed) 0.4f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lazy, cached, off-main-thread icon (process cache keyed by package) — survives scroll
        // disposal and avoids re-decoding when rows recycle, even with 100+ launcher apps.
        AppIconImage(
            packageName = app.packageName,
            appName = app.appName,
            modifier = Modifier
                .size(48.dp)
                .alpha(if (isBusy) 0.5f else 1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = app.appName,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isBusy) detoxColors.hint else detoxColors.label,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        when {
            isBusy -> Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = detoxColors.hint,
                modifier = Modifier.size(16.dp),
            )
            isSelected -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = detoxColors.accent,
                modifier = Modifier.size(24.dp),
            )
            else -> Box(modifier = Modifier.size(24.dp))
        }
    }
}

