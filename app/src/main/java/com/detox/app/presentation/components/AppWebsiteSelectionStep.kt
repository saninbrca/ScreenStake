package com.detox.app.presentation.components

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.detox.app.util.HapticManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.detox.app.R
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.presentation.screens.challengecreation.APP_DOMAIN_MAP
import com.detox.app.presentation.screens.challengecreation.AppListState
import com.detox.app.presentation.screens.challengecreation.FEATURE_BLOCK_MAP

private val DOMAIN_TO_PACKAGE = mapOf(
    "instagram.com" to "com.instagram.android",
    "youtube.com"   to "com.google.android.youtube",
    "tiktok.com"    to "com.zhiliaoapp.musically",
    "facebook.com"  to "com.facebook.katana",
    "twitter.com"   to "com.twitter.android",
    "x.com"         to "com.twitter.android",
)

private val DOMAIN_BRAND_COLOR = mapOf(
    "instagram.com" to Color(0xFFE1306C),
    "youtube.com"   to Color(0xFFFF0000),
    "tiktok.com"    to Color(0xFF010101),
    "facebook.com"  to Color(0xFF1877F2),
    "twitter.com"   to Color(0xFF010101),
    "x.com"         to Color(0xFF010101),
)

// ── Shared App/Website selection step ─────────────────────────────────────────
// Used identically in Solo Wizard (Step 2) and Group Challenge Wizard (Step 1).

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFFF2F2F7))
                .padding(4.dp),
        ) {
            Row {
                listOf("📱 Apps", "🌐 Websites").forEachIndexed { index, label ->
                    val isActive = activeTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isActive) Modifier
                                    .shadow(2.dp, RoundedCornerShape(50.dp))
                                    .background(Color.White, RoundedCornerShape(50.dp))
                                else Modifier
                            )
                            .clip(RoundedCornerShape(50.dp))
                            .clickable { onTabChange(index) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) Color.Black else Color(0xFF8E8E93),
                        )
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
internal fun AppsTabContent(
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
        BasicTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFFF2F2F7))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                stringResource(R.string.app_selection_search_placeholder),
                                color = Color(0xFF8E8E93),
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
                        AppSelectionRow(
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

// ── Platform icon with badge ──────────────────────────────────────────────────

@Composable
private fun PlatformAppIconWithBadge(path: String) {
    val context = LocalContext.current
    val domain = path.substringBefore('/')
    val packageName = DOMAIN_TO_PACKAGE[domain]

    val iconBitmap = remember(packageName) {
        if (packageName == null) return@remember null
        try {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(120, 120)
                .asImageBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    Box(modifier = Modifier.size(40.dp)) {
        if (iconBitmap != null) {
            Image(
                painter = BitmapPainter(iconBitmap),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            val brandColor = DOMAIN_BRAND_COLOR[domain] ?: Color(0xFF8E8E93)
            val initial = domain.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(brandColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFFFF3B30)),
        )
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
            .verticalScroll(rememberScrollState()),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5F5)),
            border = BorderStroke(0.5.dp, Color(0xFFFFD0D0)),
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
                        .background(Color(0xFFFF3B30)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "18+",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_selection_adult_content_block),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                    )
                    Text(
                        text = stringResource(R.string.app_selection_adult_content_desc),
                        fontSize = 12.sp,
                        color = Color(0xFF8E8E93),
                    )
                }
                Switch(
                    checked = blockAdultContent,
                    onCheckedChange = onBlockAdultContentChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFFFF3B30),
                        uncheckedTrackColor = Color(0xFFE0E0E5),
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
                    .background(Color(0xFFF2F2F7))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
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
                                color = Color(0xFF8E8E93),
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text("Add", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                            .background(Color(0xFFF2F2F7))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(domain, fontSize = 13.sp, color = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onRemoveManualDomain(domain) },
                        )
                    }
                }
            }
        }

        val visibleFeatures = FEATURE_BLOCK_MAP.filter { (path, _) ->
            val parentDomain = path.substringBefore('/')
            manualDomains.none { it.equals(parentDomain, ignoreCase = true) }
        }
        if (visibleFeatures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.app_selection_feature_block_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF8E8E93),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            visibleFeatures.forEach { (path, featureName) ->
                val isEnabled = partialBlockDomains.contains(path)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(0.5.dp, Color(0x0F000000)),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTogglePartialBlock(path) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlatformAppIconWithBadge(path = path)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = featureName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black,
                            )
                            Text(
                                text = path,
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93),
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { onTogglePartialBlock(path) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFF00C853),
                                uncheckedTrackColor = Color(0xFFE0E0E5),
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFFF9FFF9) else Color.Transparent)
            .clickable(enabled = !dimmed && !isBusy) {
                HapticManager.light(context)
                onToggle()
            }
            .alpha(if (dimmed || isBusy) 0.4f else 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } ?: Box(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black,
                )
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
                    tint = Color(0xFF00C853),
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Box(modifier = Modifier.size(24.dp))
            }
        }
        if (conflictChallengeName != null) {
            Text(
                text = conflictChallengeName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 80.dp, bottom = 8.dp),
            )
        }
    }
}

// ── Partial section sub-row ───────────────────────────────────────────────────

@Composable
internal fun PartialSectionSubRow(
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
                color = Color(0xFF8E8E93),
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
}
