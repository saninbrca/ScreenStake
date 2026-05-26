package com.detox.app.presentation.screens.welcome

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.R
import com.detox.app.ui.theme.PoppinsFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Design tokens ─────────────────────────────────────────────────────────────

private val IosBg = Color(0xFFF2F2F7)
private val CardBg = Color(0xFFFFFFFF)
private val CardBorder = Color(0xFFE0E0E5)
private val GreenPrimary = Color(0xFF00C853)
private val GreenLight = Color(0xFFE8F8EF)
private val TextPrimary = Color(0xFF000000)
private val TextSecondary = Color(0xFF8E8E93)
private val OrangeAccent = Color(0xFFFF6B35)
private val OrangeLight = Color(0xFFFFF0E8)
private val PurpleAccent = Color(0xFF7B61FF)
private val PurpleLight = Color(0xFFEEF0FF)
private val GreenBadgeText = Color(0xFF1E7A3C)
private val OrangeBadgeText = Color(0xFFC05A00)
private val DotInactive = Color(0xFFD1D1D6)

private val CardShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(14.dp)

// ── Root composable ────────────────────────────────────────────────────────────

@Composable
fun WelcomeOnboardingScreen(
    onNavigateToRegister: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 5 })

    // Light status bar (dark icons) throughout onboarding
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
    }

    // Page 3 permission states — refreshed on every RESUME
    var overlayGranted by remember { mutableStateOf(false) }
    var accessibilityGranted by remember { mutableStateOf(false) }
    var usageStatsGranted by remember { mutableStateOf(false) }
    var showHuaweiDialog by remember { mutableStateOf(false) }

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
            .background(IosBg)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(IosBg)
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
                        onActivate = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            if (Build.MANUFACTURER.lowercase() == "huawei") {
                                showHuaweiDialog = true
                            }
                        },
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

    if (showHuaweiDialog) {
        AlertDialog(
            onDismissRequest = { showHuaweiDialog = false },
            title = { Text(stringResource(R.string.welcome_p3_huawei_title)) },
            text = { Text(stringResource(R.string.welcome_p3_huawei_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showHuaweiDialog = false
                    try {
                        val intent = Intent("com.huawei.systemmanager.optimize.process.ProtectActivity")
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) {
                    Text(stringResource(R.string.welcome_p3_huawei_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHuaweiDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
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

        // "De" + "tox" in green
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append("De") }
                withStyle(SpanStyle(color = GreenPrimary)) { append("tox") }
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
                        .background(GreenLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = GreenPrimary,
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
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.welcome_p0_card2_sub),
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = TextSecondary
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
                withStyle(SpanStyle(color = TextPrimary)) {
                    append(stringResource(R.string.welcome_p1_title_pre))
                }
                withStyle(SpanStyle(color = GreenPrimary)) {
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
                color = TextSecondary
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
                    color = GreenPrimary,
                    trackColor = GreenLight
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
                            color = TextSecondary
                        )
                    )
                    Text(
                        text = stringResource(R.string.welcome_p1_preview_streak),
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = TextPrimary
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
                .background(GreenPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
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
                    color = TextPrimary
                )
            )
            Text(
                text = desc,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = TextSecondary
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
                withStyle(SpanStyle(color = TextPrimary)) {
                    append(stringResource(R.string.welcome_p2_title_pre))
                }
                withStyle(SpanStyle(color = GreenPrimary)) {
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
            text = stringResource(R.string.welcome_p2_subtitle),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TextSecondary
            ),
            textAlign = TextAlign.Center
        )

        ModeCard(
            iconBg = GreenLight,
            icon = Icons.Default.Star,
            iconTint = GreenPrimary,
            title = stringResource(R.string.welcome_p2_soft_title),
            subtitle = stringResource(R.string.welcome_p2_soft_sub),
            badge = stringResource(R.string.welcome_p2_soft_badge),
            badgeBg = GreenLight,
            badgeColor = GreenBadgeText
        )
        ModeCard(
            iconBg = OrangeLight,
            icon = Icons.Default.Whatshot,
            iconTint = OrangeAccent,
            title = stringResource(R.string.welcome_p2_hard_title),
            subtitle = stringResource(R.string.welcome_p2_hard_sub),
            badge = stringResource(R.string.welcome_p2_hard_badge),
            badgeBg = OrangeLight,
            badgeColor = OrangeBadgeText
        )
        ModeCard(
            iconBg = PurpleLight,
            icon = Icons.Default.Group,
            iconTint = PurpleAccent,
            title = stringResource(R.string.welcome_p2_group_title),
            subtitle = stringResource(R.string.welcome_p2_group_sub),
            badge = stringResource(R.string.welcome_p2_group_badge),
            badgeBg = PurpleLight,
            badgeColor = PurpleAccent
        )

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
            .background(CardBg)
            .border(0.5.dp, Color(0x0F000000), CardShape)
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
                        color = TextPrimary
                    )
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = TextSecondary
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
    onActivate: () -> Unit,
    currentPage: Int,
) {
    val context = LocalContext.current

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
                color = TextPrimary
            )
        )

        Text(
            text = stringResource(R.string.welcome_p3_subtitle),
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TextSecondary
            )
        )

        // Permissions card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardBg)
                .border(1.dp, CardBorder, CardShape)
        ) {
            Column {
                PermissionRow(
                    iconBg = GreenLight,
                    icon = Icons.Default.Check,
                    iconTint = GreenPrimary,
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
                HorizontalDivider(color = CardBorder, thickness = 1.dp)
                PermissionRow(
                    iconBg = PurpleLight,
                    icon = Icons.Default.AccessTime,
                    iconTint = PurpleAccent,
                    title = stringResource(R.string.welcome_p3_accessibility_title),
                    desc = stringResource(R.string.welcome_p3_accessibility_desc),
                    isGranted = accessibilityGranted,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
                HorizontalDivider(color = CardBorder, thickness = 1.dp)
                PermissionRow(
                    iconBg = OrangeLight,
                    icon = Icons.Default.Shield,
                    iconTint = OrangeAccent,
                    title = stringResource(R.string.welcome_p3_usage_title),
                    desc = stringResource(R.string.welcome_p3_usage_desc),
                    isGranted = usageStatsGranted,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
            }
        }

        // Privacy note card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(GreenLight)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.welcome_p3_privacy_text),
                    style = TextStyle(
                        fontFamily = PoppinsFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = TextPrimary
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
                text = stringResource(R.string.welcome_p3_btn_activate),
                onClick = onActivate
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
                    color = TextPrimary
                )
            )
            Text(
                text = desc,
                style = TextStyle(
                    fontFamily = PoppinsFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            )
        }
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
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
                withStyle(SpanStyle(color = TextPrimary)) {
                    append(stringResource(R.string.welcome_p4_title_pre))
                }
                withStyle(SpanStyle(color = GreenPrimary)) {
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
                color = TextSecondary
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
                        color = TextSecondary,
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
                .background(GreenLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TextPrimary
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
            delay(3000)
            currentStatIndex = (currentStatIndex + 1) % 3
        }
    }

    OnboardingCard {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = currentStatIndex,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                },
                label = "stat_rotation"
            ) { index ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stats[index].first,
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 48.sp,
                            color = GreenPrimary
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stats[index].second,
                        style = TextStyle(
                            fontFamily = PoppinsFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            color = TextSecondary
                        ),
                        textAlign = TextAlign.Center
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
                            .background(if (i == currentStatIndex) GreenPrimary else Color(0xFFC7C7CC))
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
            .background(GreenPrimary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color.White,
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
            .background(CardBg)
            .border(0.5.dp, Color(0x0F000000), CardShape)
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
                    .background(if (i == currentPage) GreenPrimary else DotInactive)
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
            .background(GreenPrimary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color.White
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
        border = BorderStroke(1.5.dp, CardBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CardBg,
            contentColor = GreenPrimary
        )
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = GreenPrimary
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
