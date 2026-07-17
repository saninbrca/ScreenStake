package com.detox.app.presentation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.detox.app.R
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.util.pressScaleFeedback
import com.detox.app.ui.theme.DetoxAlertColors
import com.detox.app.ui.theme.detoxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

// Process-level caches keyed by package name. App icon/label lookups are otherwise re-run every
// time a card is recomposed after scrolling back into view (LazyColumn disposes off-screen items,
// dropping their `remember` caches). Presentation-only — no effect on DailyStats or challenge data.
private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
private val labelCache = ConcurrentHashMap<String, String>()

// ── Website challenge helpers ─────────────────────────────────────────────────

internal fun websiteDisplayName(blockedDomains: List<String>, partialBlockDomains: List<String>): String {
    if (blockedDomains.isNotEmpty()) {
        return when (blockedDomains.size) {
            1 -> blockedDomains[0]
            2 -> "${blockedDomains[0]} & ${blockedDomains[1]}"
            else -> "${blockedDomains[0]} +${blockedDomains.size - 1} weitere"
        }
    }
    return "Website"
}

internal fun websitePrimaryDomain(blockedDomains: List<String>, partialBlockDomains: List<String>): String? =
    partialBlockDomains.firstOrNull()?.substringBefore('/') ?: blockedDomains.firstOrNull()

/** Loads a website favicon via Google's Favicon Service. Fallback: grey rounded square with first letter. */
@Composable
internal fun FaviconImage(domain: String, modifier: Modifier) {
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data("https://www.google.com/s2/favicons?domain=$domain&sz=64")
            .crossfade(true)
            .build(),
        contentDescription = domain,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Fit,
        loading = {
            FaviconFallbackContent(domain = domain)
        },
        error = {
            FaviconFallbackContent(domain = domain)
        },
        success = {
            SubcomposeAsyncImageContent()
        }
    )
}

@Composable
private fun FaviconFallbackContent(domain: String) {
    val letter = domain.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(10.dp),
        color = detoxColors.avatarFallbackBg
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = detoxColors.onSolid,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ChallengeCard(
    dailyStats: DailyStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Adult-only challenge (no app, no custom domains): dedicated card — the generic
    // limit/progress layout has nothing meaningful to show for a pure 24/7 block.
    if (dailyStats.blockAdultContent &&
        dailyStats.appPackageNames.isEmpty() && dailyStats.appPackageName == null &&
        dailyStats.blockedDomains.isEmpty()
    ) {
        AdultBlockCard(dailyStats = dailyStats, onClick = onClick, modifier = modifier)
        return
    }

    val progress = when (dailyStats.limitType) {
        LimitType.TIME -> {
            if (dailyStats.limitValueMinutes > 0)
                dailyStats.todayMinutes.toFloat() / dailyStats.limitValueMinutes
            else 0f
        }
        LimitType.SESSIONS -> {
            val max = dailyStats.limitValueSessions ?: 1
            if (max > 0) dailyStats.todayOpens.toFloat() / max else 0f
        }
        LimitType.TIME_BUDGET -> {
            val budget = dailyStats.dailyBudgetMinutes ?: dailyStats.limitValueMinutes
            if (budget > 0) dailyStats.todayMinutes.toFloat() / budget else 0f
        }
        LimitType.TIME_WINDOW -> 0f
    }.coerceIn(0f, 1f)

    val progressColor = if (dailyStats.limitExceeded)
        detoxColors.warning
    else
        MaterialTheme.colorScheme.primary

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().pressScaleFeedback(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        val packageNames = remember(dailyStats.appPackageNames, dailyStats.appPackageName) {
            dailyStats.appPackageNames.ifEmpty { listOfNotNull(dailyStats.appPackageName) }
        }
        val websiteDomains = remember(dailyStats.blockedDomains, dailyStats.partialBlockDomains) {
            if (packageNames.isEmpty()) {
                val featureDomains = dailyStats.partialBlockDomains.map { it.substringBefore('/') }.distinct()
                (featureDomains + dailyStats.blockedDomains).distinct()
            } else emptyList()
        }
        val websiteDisplayText = remember(dailyStats.blockedDomains, dailyStats.partialBlockDomains) {
            if (packageNames.isEmpty() && websiteDomains.isNotEmpty())
                websiteDisplayName(dailyStats.blockedDomains, dailyStats.partialBlockDomains)
            else null
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconStack(
                    packageNames = packageNames,
                    appDisplayName = dailyStats.appDisplayName,
                    isGroup = dailyStats.isGroup,
                    websiteDomains = websiteDomains
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppNameLabel(
                            packageNames = packageNames,
                            appDisplayName = dailyStats.appDisplayName,
                            modifier = Modifier.weight(1f),
                            websiteDisplayText = websiteDisplayText
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        ModeBadge(dailyStats = dailyStats)
                        Spacer(modifier = Modifier.width(4.dp))
                        DaysLeftBadge(
                            daysRemaining = dailyStats.daysRemaining,
                            isOpenEnded = dailyStats.isOpenEnded,
                            streak = dailyStats.streak
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = buildLimitLabel(dailyStats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = buildProgressLabel(dailyStats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (dailyStats.isGroup && dailyStats.maxParticipants > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                R.string.challenge_card_participants,
                                dailyStats.participantCount,
                                dailyStats.maxParticipants
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = detoxColors.groupAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (dailyStats.isGroup && dailyStats.userRank != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.challenge_card_rank, dailyStats.userRank),
                            style = MaterialTheme.typography.bodySmall,
                            color = detoxColors.groupAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            if (dailyStats.blockAdultContent) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.challenge_card_adult_blocked),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (dailyStats.moneyLostCents > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.challenge_card_payment_captured,
                        dailyStats.moneyLostCents / 100f
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = detoxColors.warning
                )
            }
        }
    }
}

/**
 * Card for adult-only challenges ("Adult-Block" — no app, no domains, no minute limit).
 * There is no progress to fill on an always-on block, so the streak is the hero element;
 * the footer shows the end date or "Kein Enddatum". Reuses the generic card tokens
 * (surface, medium shape, 4dp elevation) and the wizard's red "18+" circle motif.
 */
@Composable
private fun AdultBlockCard(
    dailyStats: DailyStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().pressScaleFeedback(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "18+" circle — same motif as the wizard's adult toggle card.
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = detoxColors.danger
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "18+",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = detoxColors.onSolid
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.adult_block_display_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.challenge_card_adult_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))
                ModeBadge(dailyStats = dailyStats)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Streak hero — replaces the progress bar.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (dailyStats.streak <= 0)
                        stringResource(R.string.challenge_card_streak_day_one)
                    else
                        stringResource(R.string.challenge_card_streak_format, dailyStats.streak),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = when {
                    dailyStats.isOpenEnded -> stringResource(R.string.challenge_card_no_end_date)
                    dailyStats.daysRemaining <= 0 -> stringResource(R.string.challenge_card_ends_today)
                    else -> stringResource(R.string.challenge_card_days_left, dailyStats.daysRemaining)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Overlapping icon stack with solo/group badge. Handles 1 / 2-3 / 4+ apps and website favicons. */
@Composable
private fun AppIconStack(
    packageNames: List<String>,
    appDisplayName: String,
    isGroup: Boolean,
    websiteDomains: List<String> = emptyList()
) {
    val isWebsite = websiteDomains.isNotEmpty()
    val items = if (isWebsite) websiteDomains else packageNames
    val n = items.size.coerceAtLeast(1)
    val iconSize = if (n == 1) 32.dp else 28.dp
    val overlap = 18 // dp each additional icon is offset

    // Total Box width: for 1 app → 32dp; for n apps → 28 + (n-1)*18; for 4+ → 3 icons + 1 "+X"
    val displayCount = if (n >= 4) 4 else n  // 4 slots when 4+ (3 icons + "+X")
    val stackSlots = if (n == 1) 1 else displayCount
    val boxWidth = if (n == 1) iconSize else (28 + (stackSlots - 1) * overlap).dp

    Box(modifier = Modifier.size(width = boxWidth, height = iconSize)) {
        val slotsToShow = if (n >= 4) 3 else n
        for (i in 0 until slotsToShow) {
            if (isWebsite) {
                val domain = items.getOrNull(i) ?: continue
                FaviconImage(
                    domain = domain,
                    modifier = Modifier
                        .size(iconSize)
                        .offset(x = (i * overlap).dp)
                        // Ring that separates overlapping icons = the card surface behind them.
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                )
            } else {
                val pkg = packageNames.getOrNull(i)
                val label = pkg?.let { appDisplayName } ?: appDisplayName
                AppIconImage(
                    packageName = pkg,
                    appName = label,
                    modifier = Modifier
                        .size(iconSize)
                        .offset(x = (i * overlap).dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }

        // "+X" overflow circle for 4+ apps
        if (n >= 4) {
            val remaining = n - 3
            Surface(
                modifier = Modifier
                    .size(iconSize)
                    .offset(x = (3 * overlap).dp),
                shape = CircleShape,
                color = detoxColors.avatarFallbackBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "+$remaining",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = detoxColors.onSolid,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Solo/group badge at bottom-start
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(14.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Icon(
                imageVector = if (isGroup) Icons.Filled.Group else Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.padding(2.dp),
                tint = if (isGroup) detoxColors.groupAccent
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** App name label that adapts to number of apps. For website challenges pass websiteDisplayText. */
@Composable
private fun AppNameLabel(
    packageNames: List<String>,
    appDisplayName: String,
    modifier: Modifier = Modifier,
    websiteDisplayText: String? = null
) {
    val context = LocalContext.current
    val n = packageNames.size

    // Website challenge: always show computed name
    if (websiteDisplayText != null) {
        Text(
            text = websiteDisplayText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    when {
        n <= 1 -> Text(
            text = appDisplayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        n <= 3 -> {
            val labels = remember(packageNames) {
                packageNames.joinToString(", ") { pkg ->
                    labelCache.getOrPut(pkg) {
                        try {
                            val info = context.packageManager.getApplicationInfo(pkg, 0)
                            context.packageManager.getApplicationLabel(info).toString()
                        } catch (e: Exception) {
                            pkg.substringAfterLast('.')
                        }
                    }
                }
            }
            Text(
                text = labels,
                fontSize = 13.sp,
                color = detoxColors.subtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier
            )
        }
        else -> Text(
            text = stringResource(R.string.challenge_apps_count, n),
            fontSize = 13.sp,
            color = detoxColors.subtext,
            maxLines = 1,
            modifier = modifier
        )
    }
}

@Composable
private fun ModeBadge(dailyStats: DailyStats) {
    // Solid saturated mode badges (white text). Group uses solidPurpleBg (not
    // groupAccent, which brightens to #9F8BFF where white text is ~2.2:1 in dark);
    // HARD uses the design-fixed alarm red.
    val (label, color) = when {
        dailyStats.isGroup -> stringResource(R.string.challenge_card_badge_group) to detoxColors.solidPurpleBg
        dailyStats.mode == ChallengeMode.HARD -> stringResource(R.string.challenge_card_badge_hard) to DetoxAlertColors.RedDeep
        else -> stringResource(R.string.challenge_card_badge_soft) to detoxColors.solidGreenBg
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = detoxColors.onSolid
        )
    }
}

@Composable
private fun DaysLeftBadge(daysRemaining: Int, isOpenEnded: Boolean = false, streak: Int = 0) {
    if (daysRemaining == Int.MAX_VALUE && !isOpenEnded) return

    // Each badge carries its own foreground: primary badges use onPrimary (deep green
    // in dark), the solid-orange "ends today" badge uses onSolid (white). Pairing the
    // foreground with the background is what keeps white off the bright dark primary.
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val (label, badgeColor, textColor) = when {
        // Open-ended: "days remaining" is meaningless → show the consecutive-success streak instead.
        // Compact card format ("🔥 N Tage"); the flame signals "streak", full wording is on the detail
        // screen. streak == 0 means day 1 of the (possibly just-restarted) streak → "🔥 Tag 1".
        isOpenEnded && streak <= 0 -> Triple(stringResource(R.string.challenge_card_streak_day_one), primary, onPrimary)
        isOpenEnded -> Triple(stringResource(R.string.challenge_card_streak_format, streak), primary, onPrimary)
        daysRemaining <= 0 -> Triple(stringResource(R.string.challenge_card_ends_today), detoxColors.solidOrangeBg, detoxColors.onSolid)
        daysRemaining == 1 -> Triple(stringResource(R.string.challenge_card_tomorrow), primary, onPrimary)
        else -> Triple(stringResource(R.string.challenge_card_days_left, daysRemaining), primary, onPrimary)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = badgeColor
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1
        )
    }
}

/** Loads app icon via PackageManager. Fallback: grey circle with first letter. */
@Composable
internal fun AppIconImage(packageName: String?, appName: String, modifier: Modifier) {
    val context = LocalContext.current
    // Seed synchronously from the process cache so a card scrolling back into view is an O(1) hit
    // (no re-decode, no flicker). On a miss the grey-letter placeholder shows until the off-thread
    // decode below publishes the bitmap.
    var bitmap by remember(packageName) {
        mutableStateOf(packageName?.let { iconCache[it] })
    }
    LaunchedEffect(packageName) {
        if (packageName == null || bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        if (decoded != null) {
            iconCache[packageName] = decoded
            bitmap = decoded
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = appName,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Fit
        )
    } else {
        val firstLetter = appName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = detoxColors.avatarFallbackBg
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = firstLetter,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.onSolid,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun buildLimitLabel(stats: DailyStats): String = when (stats.limitType) {
    LimitType.TIME -> stringResource(R.string.challenge_card_time_limit, stats.limitValueMinutes)
    LimitType.SESSIONS -> stringResource(
        R.string.challenge_card_session_limit,
        stats.limitValueSessions ?: 0,
        stats.limitValueMinutes
    )
    LimitType.TIME_BUDGET -> stringResource(
        R.string.challenge_card_budget_limit,
        stats.dailyBudgetMinutes ?: stats.limitValueMinutes
    )
    LimitType.TIME_WINDOW -> stringResource(R.string.challenge_card_time_window_limit)
}

@Composable
private fun buildProgressLabel(stats: DailyStats): String = when (stats.limitType) {
    LimitType.TIME -> stringResource(
        R.string.challenge_card_time_progress,
        stats.todayMinutes,
        stats.limitValueMinutes
    )
    LimitType.SESSIONS -> stringResource(
        R.string.challenge_card_session_progress,
        stats.todayOpens,
        stats.limitValueSessions ?: 0
    )
    LimitType.TIME_BUDGET -> stringResource(
        R.string.challenge_card_budget_progress,
        stats.todayMinutes,
        stats.dailyBudgetMinutes ?: stats.limitValueMinutes,
        stats.budgetRemainingMinutes
            ?: ((stats.dailyBudgetMinutes ?: 0) - stats.todayMinutes)
    )
    LimitType.TIME_WINDOW -> stringResource(
        R.string.challenge_card_time_window_progress,
        stats.todayMinutes
    )
}
