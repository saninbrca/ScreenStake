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
 * the accent/success/warning/danger family, the four badge/icon-tile
 * background/foreground pairs, the group accent, selected-card surface, and the
 * iOS-gray family (grouped screen background, card background/border, divider,
 * label, subtext, chevron/hint).
 *
 * Design-fixed alarm colors that are deliberately identical in both modes live in
 * [DetoxAlertColors] instead.
 */
@Immutable
data class DetoxSemanticColors(
    val accent: Color,
    val success: Color,
    /** Brand warning accent (icons, badges) — the DetoxWarning orange. */
    val warning: Color,
    /** Status warning — "pending / expiring / caution" text and warning icon tiles. */
    val warningStrong: Color,
    val danger: Color,
    /** The one color of the "group" concept (icon tiles, group badges/labels). */
    val groupAccent: Color,
    val badgeGreenBg: Color,
    val badgeGreenFg: Color,
    val badgeOrangeBg: Color,
    val badgeOrangeFg: Color,
    val badgePurpleBg: Color,
    val badgePurpleFg: Color,
    val badgeBlueBg: Color,
    val badgeBlueFg: Color,
    /** Surface tint of a selected card/option (wizard mode cards, app picker rows). */
    val selectedSurface: Color,
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
    warningStrong = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
    groupAccent = Color(0xFF7B61FF),
    badgeGreenBg = Color(0xFFE8F8EF),
    badgeGreenFg = Color(0xFF1E7A3C),
    badgeOrangeBg = Color(0xFFFFF0E8),
    badgeOrangeFg = Color(0xFFC05A00),
    badgePurpleBg = Color(0xFFEEF0FF),
    badgePurpleFg = Color(0xFF5856D6),
    badgeBlueBg = Color(0xFFE8F0FF),
    badgeBlueFg = Color(0xFF2979FF),
    selectedSurface = Color(0xFFF0FDF4),
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
    warningStrong = Color(0xFFFF9F0A),
    danger = Color(0xFFFF6B6B),
    groupAccent = Color(0xFF9F8BFF),
    badgeGreenBg = Color(0xFF003318),
    badgeGreenFg = Color(0xFFD8FFE8),
    badgeOrangeBg = Color(0xFF3A2114),
    badgeOrangeFg = Color(0xFFFFAB8A),
    badgePurpleBg = Color(0xFF272348),
    badgePurpleFg = Color(0xFFB8B5FF),
    badgeBlueBg = Color(0xFF152A45),
    badgeBlueFg = Color(0xFF8AB4FF),
    selectedSurface = Color(0xFF12291B),
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
