package com.detox.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design-fixed celebration colors — intentionally IDENTICAL in light and dark mode.
 *
 * Decorative confetti in the win dialog. Theming decoration per-mode is meaningless,
 * and these hues pop equally on the light (#F2F2F7) and dark (#0F0F0F) dialog frame.
 * Same treatment as [DetoxAlertColors]: consciously theme-independent colors live in
 * a named constant set — screens reference it by name, never raw literals.
 */
object DetoxCelebrationColors {
    val Confetti: List<Color> = listOf(
        Color(0xFFFF9500),
        Color(0xFF00C853),
        Color(0xFF7B61FF),
        Color(0xFFFF3B30),
    )
}
