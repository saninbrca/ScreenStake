package com.detox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors that are not Material3 roles, with an explicit light AND dark value
 * for each. Provided by [DetoxTheme]; screens read them via [detoxColors] instead of
 * file-private `Color(0x…)` literals.
 *
 * The set mirrors what actually recurs across the screens' private palette headers:
 * the accent/success/warning/danger family, the three badge background/foreground
 * pairs, and the iOS-gray family (grouped screen background, card background/border,
 * divider, label, subtext, chevron/hint).
 */
@Immutable
data class DetoxSemanticColors(
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val badgeGreenBg: Color,
    val badgeGreenFg: Color,
    val badgeOrangeBg: Color,
    val badgeOrangeFg: Color,
    val badgePurpleBg: Color,
    val badgePurpleFg: Color,
    val screenBackground: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val divider: Color,
    val label: Color,
    val subtext: Color,
    val hint: Color,
)

val DetoxSemanticLight = DetoxSemanticColors(
    accent = Color(0xFF00C853),
    success = Color(0xFF00C853),
    warning = Color(0xFFFF6B35),
    danger = Color(0xFFFF3B30),
    badgeGreenBg = Color(0xFFE8F8EF),
    badgeGreenFg = Color(0xFF1E7A3C),
    badgeOrangeBg = Color(0xFFFFF0E8),
    badgeOrangeFg = Color(0xFFC05A00),
    badgePurpleBg = Color(0xFFEEF0FF),
    badgePurpleFg = Color(0xFF5856D6),
    screenBackground = Color(0xFFF2F2F7),
    cardBackground = Color(0xFFFFFFFF),
    cardBorder = Color(0x0F000000),
    divider = Color(0xFFF2F2F7),
    label = Color(0xFF000000),
    subtext = Color(0xFF8E8E93),
    hint = Color(0xFFC7C7CC),
)

val DetoxSemanticDark = DetoxSemanticColors(
    accent = Color(0xFF00E676),
    success = Color(0xFF00E676),
    warning = Color(0xFFFFAB8A),
    danger = Color(0xFFFF6B6B),
    badgeGreenBg = Color(0xFF003318),
    badgeGreenFg = Color(0xFFD8FFE8),
    badgeOrangeBg = Color(0xFF3A2114),
    badgeOrangeFg = Color(0xFFFFAB8A),
    badgePurpleBg = Color(0xFF272348),
    badgePurpleFg = Color(0xFFB8B5FF),
    screenBackground = Color(0xFF0F0F0F),
    cardBackground = Color(0xFF1A1A1A),
    cardBorder = Color(0x14FFFFFF),
    divider = Color(0xFF222222),
    label = Color(0xFFFFFFFF),
    subtext = Color(0xFF9E9E9E),
    hint = Color(0xFF48484A),
)

val LocalDetoxColors = staticCompositionLocalOf { DetoxSemanticLight }

/** Theme-resolved semantic colors, analogous to `MaterialTheme.colorScheme`. */
val detoxColors: DetoxSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalDetoxColors.current
