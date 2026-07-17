package com.detox.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design-fixed IDENTITY colors — intentionally IDENTICAL in light and dark mode.
 *
 * Unlike [DetoxSemanticColors], these carry no semantic meaning that a theme should
 * reinterpret: they distinguish PEOPLE (avatar circles) and RANKS (podium medals).
 * Re-tinting them per mode would defeat their only job — being consistently
 * recognizable. Same treatment as [DetoxAlertColors] / [DetoxCelebrationColors]:
 * consciously theme-independent colors live in a named constant set, referenced by
 * name so that after the migration a raw `Color(0x…)` literal in presentation/ still
 * means "bug".
 */

/**
 * The avatar-circle hash palette. A participant's circle color is chosen by hashing
 * their name into this list, so the SAME person keeps the SAME color everywhere. The
 * monogram letter on top uses [DetoxSemanticColors.onSolid] (white in both modes; the
 * saturated bg keeps it legible). Order and values are load-bearing — never reorder or
 * recolor, or existing users' avatar colors would shuffle.
 */
object DetoxAvatarPalette {
    val Colors: List<Color> = listOf(
        Color(0xFF5C6BC0), // indigo
        Color(0xFF42A5F5), // blue
        Color(0xFF26A69A), // teal
        Color(0xFFEC407A), // pink
        Color(0xFFAB47BC), // purple
        Color(0xFF26C6DA), // cyan
    )
}

/**
 * Podium / leaderboard rank medals. Gold / Silver / Bronze are the universally-read
 * medal hues; they are design-fixed for the same reason as the avatar palette. Values
 * match the frozen `GroupChallengeResultsScreen` podium exactly (that always-dark
 * overlay keeps its own private copies under the overlay freeze; this set is the home
 * for every non-overlay caller, currently the group-detail leaderboard ranks).
 */
object DetoxPodiumColors {
    val Gold = Color(0xFFFFD700)
    val Silver = Color(0xFFC0C0C0)
    val Bronze = Color(0xFFCD7F32)
}
