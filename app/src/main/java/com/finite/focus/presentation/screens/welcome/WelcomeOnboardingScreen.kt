package com.finite.focus.presentation.screens.welcome

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.finite.focus.R
import com.finite.focus.ui.theme.detoxColors
import androidx.compose.material3.MaterialTheme
import com.finite.focus.presentation.components.AccessibilityDisclosureDialog
import com.finite.focus.presentation.components.PermissionHelpSheet
import com.finite.focus.presentation.components.PermissionHelpTopics
import com.finite.focus.ui.theme.PoppinsFamily
import com.finite.focus.util.FeatureFlags
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Design tokens ─────────────────────────────────────────────────────────────

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

private val CardShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(14.dp)

// ── Rotating-fact block (see RotatingStatCard) ────────────────────────────────
// Every fact is capped at one value line + at most two description lines, and the block
// reserves exactly that. Since the reservation is derived from the styles' lineHeight (sp),
// it grows with the user's font scale instead of clipping the way a magic dp would.

private val StatValueStyle = TextStyle(
    fontFamily = PoppinsFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 48.sp,
    lineHeight = 52.sp
)

private val StatDescStyle = TextStyle(
    fontFamily = PoppinsFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

private const val StatValueMaxLines = 1
private const val StatDescMaxLines = 2
private val StatBlockSpacing = 8.dp

/** Small buffer so font metrics that overshoot the declared lineHeight cannot clip. */
private val StatBlockHeadroom = 4.dp

/**
 * Height reserved for the rotating fact block: identical for all three facts (so nothing
 * shifts on rotation) and expressed in sp-derived dp (so nothing clips at a large font scale).
 */
@Composable
private fun statBlockHeight(): Dp = with(LocalDensity.current) {
    StatValueStyle.lineHeight.toDp() * StatValueMaxLines +
        StatBlockSpacing +
        StatDescStyle.lineHeight.toDp() * StatDescMaxLines +
        StatBlockHeadroom
}

// ── Root composable ────────────────────────────────────────────────────────────

@Composable
fun WelcomeOnboardingScreen(
    onNavigateToRegister: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 5 })

    // Page 3 permission states — refreshed on every RESUME
    var overlayGranted by remember { mutableStateOf(false) }
    var accessibilityGranted by remember { mutableStateOf(false) }
    var usageStatsGranted by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlayGranted = Settings.canDrawOverlays(context)
            accessibilityGranted = isAccessibilityServiceEnabled(context)
            usageStatsGranted = hasUsageStatsPermission(context)
        }
    }

    // Back handling: page 0 → exit app; others → previous page
    BackHandler {
        if (pagerState.currentPage == 0) {
            (context as Activity).finishAffinity()
        } else {
            coroutineScope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(detoxColors.screenBackground)
    ) {
        // The Box above stays full-bleed so the background paints under the bars; the pager
        // itself is inset so no page content hides behind the status or gesture bar.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(detoxColors.screenBackground)
            ) {
                when (page) {
                    0 -> WelcomePage(
                        onStart = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                        onSkip = { coroutineScope.launch { pagerState.animateScrollToPage(4) } },
                        currentPage = 0
                    )
                    1 -> ConceptPage(
                        onNext = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                        currentPage = 1
                    )
                    2 -> ModesPage(
                        onNext = { coroutineScope.launch { pagerState.animateScrollToPage(3) } },
                        currentPage = 2
                    )
                    3 -> PermissionsPage(
                        overlayGranted = overlayGranted,
                        accessibilityGranted = accessibilityGranted,
                        usageStatsGranted = usageStatsGranted,
                        onNext = { coroutineScope.launch { pagerState.animateScrollToPage(4) } },
                        currentPage = 3
                    )
                    4 -> StartPage(
                        onRegister = {
                            markOnboardingCompleted(context)
                            onNavigateToRegister()
                        },
                        onLogin = {
                            markOnboardingCompleted(context)
                            onNavigateToLogin()
                        },
                        currentPage = 4
                    )
                }
            }
        }
    }

    // The Huawei battery reminder that used to fire here was removed: OnboardingScreen's
    // dedicated HuaweiBatteryStep already covers battery optimisation and protected apps.
}

// ── Page 0: Willkommen ─────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(
    onStart: () -> Unit,
    onSkip: () -> Unit,
    currentPage: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        DetoxLogoIcon()

        // "Finite" + accent "." — the dot marks where the scrolling ends
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = detoxColors.label)) { append(stringResource(R.string.app_name)) }
                withStyle(SpanStyle(color = detoxColors.accent)) { append(".") }
            },
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            )
        )

        RotatingStatCard()

        // Feature card
        OnboardingCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(detoxColors.softGreenBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = detoxColors.softGreenIcon,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.welcome_p0_card2_title),
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = detoxColors.label
                        )
                    )
                    Text(
                        text = stringResource(R.string.welcome_p0_card2_sub),
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = detoxColors.subtext
                        )
                    )
                }
            }
        }

        PageDots(currentPage = currentPage)

        PrimaryButton(text = stringResource(R.string.welcome_p0_btn_start), onClick = onStart)
        SecondaryButton(text = stringResource(R.string.welcome_p0_btn_skip), onClick = onSkip)
    }
}

// ── Page 1: Konzept ────────────────────────────────────────────────────────────

@Composable
private fun ConceptPage(
    onNext: () -> Unit,
    currentPage: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = detoxColors.label)) {
                    append(stringResource(R.string.welcome_p1_title_pre))
                }
                withStyle(SpanStyle(color = detoxColors.accent)) {
                    append(stringResource(R.string.welcome_p1_title_highlight))
                }
            },
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp
            ),
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.welcome_p1_subtitle),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = detoxColors.subtext
            ),
            textAlign = TextAlign.Center
        )

        // Steps card
        OnboardingCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ConceptStep(
                    number = "1",
                    title = stringResource(R.string.welcome_p1_step1_title),
                    desc = stringResource(R.string.welcome_p1_step1_desc)
                )
                ConceptStep(
                    number = "2",
                    title = stringResource(R.string.welcome_p1_step2_title),
                    desc = stringResource(R.string.welcome_p1_step2_desc)
                )
                ConceptStep(
                    number = "3",
                    title = stringResource(R.string.welcome_p1_step3_title),
                    desc = stringResource(R.string.welcome_p1_step3_desc)
                )
            }
        }

        // Mini preview card
        OnboardingCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { 2f / 5f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = detoxColors.softGreenBg
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.welcome_p1_preview_opens),
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = detoxColors.subtext
                        )
                    )
                    Text(
                        text = stringResource(R.string.welcome_p1_preview_streak),
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = detoxColors.label
                        )
                    )
                }
            }
        }

        PageDots(currentPage = currentPage)
        PrimaryButton(text = stringResource(R.string.welcome_p1_btn_next), onClick = onNext)
    }
}

@Composable
private fun ConceptStep(number: String, title: String, desc: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = detoxColors.label
                )
            )
            Text(
                text = desc,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = detoxColors.subtext
                )
            )
        }
    }
}

// ── Page 2: Modi ───────────────────────────────────────────────────────────────

@Composable
private fun ModesPage(
    onNext: () -> Unit,
    currentPage: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = detoxColors.label)) {
                    append(stringResource(R.string.welcome_p2_title_pre))
                }
                withStyle(SpanStyle(color = detoxColors.accent)) {
                    append(stringResource(R.string.welcome_p2_title_highlight))
                }
            },
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp
            ),
            textAlign = TextAlign.Center
        )

        Text(
            text = if (FeatureFlags.moneyEnabled) {
                stringResource(R.string.welcome_p2_subtitle)
            } else {
                stringResource(R.string.welcome_p2_subtitle_soft)
            },
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = detoxColors.subtext
            ),
            textAlign = TextAlign.Center
        )

        ModeCard(
            iconBg = detoxColors.softGreenBg,
            icon = Icons.Default.Star,
            iconTint = detoxColors.softGreenIcon,
            title = stringResource(R.string.welcome_p2_soft_title),
            subtitle = stringResource(R.string.welcome_p2_soft_sub),
            badge = stringResource(R.string.welcome_p2_soft_badge),
            badgeBg = detoxColors.softGreenBg,
            badgeColor = detoxColors.softGreenText
        )
        // Money surfaces (Hard Mode + Group) gated behind the build-level money floor.
        // Gate only — flipping BuildConfig.MONEY_FEATURES_ENABLED restores this page exactly.
        if (FeatureFlags.moneyEnabled) {
            ModeCard(
                iconBg = detoxColors.softOrangeBg,
                icon = Icons.Default.Whatshot,
                iconTint = detoxColors.softOrangeIcon,
                title = stringResource(R.string.welcome_p2_hard_title),
                subtitle = stringResource(R.string.welcome_p2_hard_sub),
                badge = stringResource(R.string.welcome_p2_hard_badge),
                badgeBg = detoxColors.softOrangeBg,
                badgeColor = detoxColors.softOrangeText
            )
            ModeCard(
                iconBg = detoxColors.softPurpleBg,
                icon = Icons.Default.Group,
                iconTint = detoxColors.softPurpleIcon,
                title = stringResource(R.string.welcome_p2_group_title),
                subtitle = stringResource(R.string.welcome_p2_group_sub),
                badge = stringResource(R.string.welcome_p2_group_badge),
                badgeBg = detoxColors.softPurpleBg,
                badgeColor = detoxColors.softPurpleIcon
            )
        }

        PageDots(currentPage = currentPage)
        PrimaryButton(text = stringResource(R.string.welcome_p2_btn_next), onClick = onNext)
    }
}

@Composable
private fun ModeCard(
    iconBg: Color,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badge: String,
    badgeBg: Color,
    badgeColor: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(detoxColors.cardBackground)
            .border(0.5.dp, detoxColors.cardBorder, CardShape)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = detoxColors.label
                    )
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = detoxColors.subtext
                    )
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badge,
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = badgeColor
                    )
                )
            }
        }
    }
}

// ── Page 3: Berechtigungen ─────────────────────────────────────────────────────

@Composable
private fun PermissionsPage(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    usageStatsGranted: Boolean,
    onNext: () -> Unit,
    currentPage: Int,
) {
    val context = LocalContext.current

    // Prominent disclosure gate for the AccessibilityService (Play policy): the settings intent
    // fires ONLY after the affirmative tap. Shown every time the enable flow is initiated.
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showAccessibilityDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDismiss = { showAccessibilityDisclosure = false },
        )
    }

    // Purely explanatory help sheet. Never requests anything and never launches a settings
    // intent, so it sits completely outside the permission gate.
    var showPermissionHelp by remember { mutableStateOf(false) }
    if (showPermissionHelp) {
        PermissionHelpSheet(
            steps = PermissionHelpTopics.AllPermissions,
            onDismiss = { showPermissionHelp = false },
        )
    }

    val allPermissionsGranted = overlayGranted && accessibilityGranted && usageStatsGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = stringResource(R.string.welcome_p3_title),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = detoxColors.label
            )
        )

        Text(
            text = stringResource(R.string.welcome_p3_subtitle),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = detoxColors.subtext
            )
        )

        // Permissions card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(detoxColors.cardBackground)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
        ) {
            Column {
                PermissionRow(
                    iconBg = detoxColors.softGreenBg,
                    icon = Icons.Default.Check,
                    iconTint = detoxColors.softGreenIcon,
                    title = stringResource(R.string.welcome_p3_overlay_title),
                    desc = stringResource(R.string.welcome_p3_overlay_desc),
                    isGranted = overlayGranted,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                PermissionRow(
                    iconBg = detoxColors.softPurpleBg,
                    icon = Icons.Default.AccessTime,
                    iconTint = detoxColors.softPurpleIcon,
                    title = stringResource(R.string.welcome_p3_accessibility_title),
                    desc = stringResource(R.string.welcome_p3_accessibility_desc),
                    isGranted = accessibilityGranted,
                    onClick = { showAccessibilityDisclosure = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                PermissionRow(
                    iconBg = detoxColors.softOrangeBg,
                    icon = Icons.Default.Shield,
                    iconTint = detoxColors.softOrangeIcon,
                    title = stringResource(R.string.welcome_p3_usage_title),
                    desc = stringResource(R.string.welcome_p3_usage_desc),
                    isGranted = usageStatsGranted,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
            }
        }

        // Subtle grey help link — opens the step-by-step sheet, changes no permission state.
        Text(
            text = stringResource(R.string.permission_help_link),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                color = detoxColors.subtext,
                textDecoration = TextDecoration.Underline
            ),
            modifier = Modifier.clickable { showPermissionHelp = true }
        )

        // Privacy note card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(detoxColors.softGreenBg)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = detoxColors.accent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.welcome_p3_privacy_text),
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = detoxColors.label
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PageDots(currentPage = currentPage)
            PrimaryButton(
                text = if (allPermissionsGranted) {
                    stringResource(R.string.welcome_p1_btn_next)
                } else {
                    stringResource(R.string.welcome_p3_btn_activate)
                },
                onClick = {
                    if (allPermissionsGranted) {
                        onNext()
                    } else {
                        // Open only the FIRST missing permission's flow. Accessibility ALWAYS
                        // routes through the disclosure dialog — never a direct settings intent.
                        when {
                            !overlayGranted -> context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                            !accessibilityGranted -> showAccessibilityDisclosure = true
                            !usageStatsGranted -> context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PermissionRow(
    iconBg: Color,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    desc: String,
    isGranted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = detoxColors.label
                )
            )
            Text(
                text = desc,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = detoxColors.subtext
                )
            )
        }
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = detoxColors.success,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = detoxColors.subtext,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Page 4: Los geht's ────────────────────────────────────────────────────────

@Composable
private fun StartPage(
    onRegister: () -> Unit,
    onLogin: () -> Unit,
    currentPage: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        DetoxLogoIcon()

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = detoxColors.label)) {
                    append(stringResource(R.string.welcome_p4_title_pre))
                }
                withStyle(SpanStyle(color = detoxColors.accent)) {
                    append(stringResource(R.string.welcome_p4_title_dot))
                }
            },
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            )
        )

        Text(
            text = stringResource(R.string.welcome_p4_subtitle),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = detoxColors.subtext
            ),
            textAlign = TextAlign.Center
        )

        // Recommendation card
        OnboardingCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.welcome_p4_rec_label),
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = detoxColors.subtext,
                        letterSpacing = 0.8.sp
                    )
                )
                RecommendationRow(stringResource(R.string.welcome_p4_rec_1))
                RecommendationRow(stringResource(R.string.welcome_p4_rec_2))
                RecommendationRow(stringResource(R.string.welcome_p4_rec_3))
            }
        }

        PageDots(currentPage = currentPage)
        PrimaryButton(text = stringResource(R.string.welcome_p4_btn_register), onClick = onRegister)
        SecondaryButton(text = stringResource(R.string.welcome_p4_btn_login), onClick = onLogin)
    }
}

@Composable
private fun RecommendationRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(detoxColors.softGreenBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = detoxColors.softGreenIcon,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = detoxColors.label
            )
        )
    }
}

// ── Shared composables ─────────────────────────────────────────────────────────

@Composable
private fun RotatingStatCard() {
    val stats = listOf(
        Pair(stringResource(R.string.welcome_p0_stat1_value), stringResource(R.string.welcome_p0_stat1_desc)),
        Pair(stringResource(R.string.welcome_p0_stat2_value), stringResource(R.string.welcome_p0_stat2_desc)),
        Pair(stringResource(R.string.welcome_p0_stat3_value), stringResource(R.string.welcome_p0_stat3_desc)),
    )

    var currentStatIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            currentStatIndex = (currentStatIndex + 1) % 3
        }
    }

    OnboardingCard {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // One reserved height for all three facts, so the card, its dots and everything
            // below stay put across rotations. Every fact value is short enough to hold a
            // single line at 48sp on a 360dp-wide screen, so only one value line is reserved.
            AnimatedContent(
                targetState = currentStatIndex,
                transitionSpec = {
                    // Pure cross-fade: nothing here affects layout, so no horizontal travel.
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statBlockHeight()),
                contentAlignment = Alignment.Center,
                label = "stat_rotation"
            ) { index ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(
                        StatBlockSpacing,
                        Alignment.CenterVertically
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Identical typography for all three facts — explicit lineHeight so the
                    // measured height is deterministic rather than font-metric dependent. The
                    // maxLines caps are what make the reserved height above exact: no fact can
                    // grow past what is reserved, so nothing is ever clipped or shifted.
                    Text(
                        text = stats[index].first,
                        style = StatValueStyle.copy(color = detoxColors.accent),
                        textAlign = TextAlign.Center,
                        maxLines = StatValueMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stats[index].second,
                        style = StatDescStyle.copy(color = detoxColors.subtext),
                        textAlign = TextAlign.Center,
                        maxLines = StatDescMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Stat dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { i ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (i == currentStatIndex) MaterialTheme.colorScheme.primary else detoxColors.hint)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetoxLogoIcon() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun OnboardingCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(detoxColors.cardBackground)
            .border(0.5.dp, detoxColors.cardBorder, CardShape)
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
private fun PageDots(currentPage: Int, pageCount: Int = 5) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(pageCount) { i ->
            val width by animateDpAsState(
                targetValue = if (i == currentPage) 22.dp else 6.dp,
                label = "dot_width_$i"
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (i == currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(ButtonShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = ButtonShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = detoxColors.cardBackground,
            contentColor = detoxColors.accent
        )
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = detoxColors.accent
            )
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains(context.packageName)
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun markOnboardingCompleted(context: Context) {
    context.getSharedPreferences("detox_settings", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("onboarding_completed", true)
        .apply()
}
