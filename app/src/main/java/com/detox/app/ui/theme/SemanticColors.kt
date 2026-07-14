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
 * the accent/success/warning/danger family, the soft tinted containers (one soft*Bg
 * per hue with soft*Text badge text and soft*Icon vivid glyphs), the icon-tile
 * system (tile* backgrounds + tileGlyph), the group accent, selected-card surface,
 * and the iOS-gray family (grouped screen background, card background/border,
 * divider, label, subtext, chevron/hint).
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
    // Soft tinted containers — ONE background per hue with TWO foreground roles:
    // soft*Text (readable badge/label text) and soft*Icon (vivid decorative glyph,
    // the icon-circle treatment). Never split the background into per-consumer
    // families: identical-value twins drift the first time someone tunes one.
    // Blue has no Text slot because no blue badge text exists yet.
    val softGreenBg: Color,
    val softGreenText: Color,
    val softGreenIcon: Color,
    val softOrangeBg: Color,
    val softOrangeText: Color,
    val softOrangeIcon: Color,
    val softPurpleBg: Color,
    val softPurpleText: Color,
    val softPurpleIcon: Color,
    val softBlueBg: Color,
    val softBlueIcon: Color,
    // Icon-tile system (Settings/Auth/Maintenance/FAQ/Support rows): a saturated
    // tile background with [tileGlyph] drawn on top. Tiles whose color CARRIES
    // meaning keep their semantic slot instead (accent, success, danger,
    // groupAccent); the tile* family is for hue-keyed decorative tiles.
    val tileGreen: Color,
    val tileOrange: Color,
    val tilePurple: Color,
    val tileBlue: Color,
    val tileRed: Color,
    val tileNeutral: Color,
    /**
     * Glyph on ANY tile* background. Dark tiles brighten to pastels, so the glyph
     * darkens to keep contrast — that inversion lives here, not at call sites.
     */
    val tileGlyph: Color,
    /** Surface tint of a selected card/option (wizard mode cards, app picker rows). */
    val selectedSurface: Color,
    /**
     * Caution container — a card warning the user about a consequential setting
     * (adult-content blocking). Deliberately NOT errorContainer: this is caution,
     * not an error state.
     */
    val attentionSurface: Color,
    val attentionBorder: Color,
    val screenBackground: Color,
    val cardBackground: Color,
    /** Recessed fill inside a card: text-field containers, inset panels. */
    val insetSurface: Color,
    /**
     * Frame of the custom result dialogs: the grouped backdrop the result cards sit
     * on. A miniature of the app shell, so dark keeps the shell's card hierarchy
     * (#0F0F0F frame under #1A1A1A cards); the dialog scrim separates it from the
     * screen behind. NOT an M3 dialog surface — the result dialogs are hand-built.
     */
    val dialogSurface: Color,
    /**
     * Fallback avatar/monogram circle (app icon or favicon unavailable). Unlike
     * tile* backgrounds it stays MUTED in dark mode — the opposite behavior of
     * tiles, which brighten. Known consumers: HistoryDetail's DetailAppIcon,
     * ChallengeCard's favicon fallback.
     */
    val avatarFallbackBg: Color,
    /**
     * Monogram letter on [avatarFallbackBg]; stays light in BOTH modes because the
     * background stays muted (reusing tileGlyph here would be ~2:1 contrast in dark).
     */
    val avatarFallbackFg: Color,
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
    softGreenBg = Color(0xFFE8F8EF),
    softGreenText = Color(0xFF1E7A3C),
    softGreenIcon = Color(0xFF00C853),
    softOrangeBg = Color(0xFFFFF0E8),
    softOrangeText = Color(0xFFC05A00),
    softOrangeIcon = Color(0xFFFF6B35),
    softPurpleBg = Color(0xFFEEF0FF),
    softPurpleText = Color(0xFF5856D6),
    softPurpleIcon = Color(0xFF7B61FF),
    softBlueBg = Color(0xFFE8F0FF),
    softBlueIcon = Color(0xFF2979FF),
    tileGreen = Color(0xFF00C853),
    tileOrange = Color(0xFFFF9500),
    tilePurple = Color(0xFF5856D6),
    tileBlue = Color(0xFF2979FF),
    tileRed = Color(0xFFFF3B30),
    tileNeutral = Color(0xFF8E8E93),
    tileGlyph = Color(0xFFFFFFFF),
    selectedSurface = Color(0xFFF0FDF4),
    attentionSurface = Color(0xFFFFF5F5),
    attentionBorder = Color(0xFFFFD0D0),
    screenBackground = Color(0xFFF2F2F7),
    cardBackground = Color(0xFFFFFFFF),
    insetSurface = Color(0xFFF2F2F7),
    dialogSurface = Color(0xFFF2F2F7),
    avatarFallbackBg = Color(0xFFAEAEB2),
    avatarFallbackFg = Color(0xFFFFFFFF),
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
    softGreenBg = Color(0xFF003318),
    softGreenText = Color(0xFFD8FFE8),
    softGreenIcon = Color(0xFF00E676),
    softOrangeBg = Color(0xFF3A2114),
    softOrangeText = Color(0xFFFFAB8A),
    softOrangeIcon = Color(0xFFFFAB8A),
    softPurpleBg = Color(0xFF272348),
    softPurpleText = Color(0xFFB8B5FF),
    softPurpleIcon = Color(0xFF9F8BFF),
    softBlueBg = Color(0xFF152A45),
    softBlueIcon = Color(0xFF8AB4FF),
    tileGreen = Color(0xFF00E676),
    tileOrange = Color(0xFFFF9F0A),
    tilePurple = Color(0xFFB8B5FF),
    tileBlue = Color(0xFF8AB4FF),
    tileRed = Color(0xFFFF6B6B),
    tileNeutral = Color(0xFF9E9E9E),
    tileGlyph = Color(0xFF1A1A1A),
    selectedSurface = Color(0xFF12291B),
    attentionSurface = Color(0xFF2A1414),
    attentionBorder = Color(0xFF4D2321),
    screenBackground = Color(0xFF0F0F0F),
    cardBackground = Color(0xFF1A1A1A),
    insetSurface = Color(0xFF111111),
    dialogSurface = Color(0xFF0F0F0F),
    avatarFallbackBg = Color(0xFF48484A),
    avatarFallbackFg = Color(0xFFFFFFFF),
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
