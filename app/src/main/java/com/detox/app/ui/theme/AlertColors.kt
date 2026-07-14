package com.detox.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design-fixed attention colors — intentionally IDENTICAL in light and dark mode.
 *
 * These paint alarm surfaces (the missing-permission banner, the HARD-mode badge)
 * whose whole job is to break out of the ambient theme; giving them a dark variant
 * would soften exactly the signal they exist to send. That is why they live here
 * and not in [DetoxSemanticColors].
 *
 * Screens reference these constants by name. After the screen migration, a raw
 * `Color(0x…)` literal inside presentation/ means "bug" — consciously-kept
 * theme-independent colors belong in this file.
 */
object DetoxAlertColors {
    /** Permission-banner pulse start; general alarm-red foreground. */
    val Red = Color(0xFFD32F2F)

    /** Permission-banner pulse end; the HARD-mode badge color. */
    val RedDeep = Color(0xFFB71C1C)

    /** Permission-banner background behind the pulsing content. */
    val RedBackground = Color(0xFF7F0000)

    /** Foreground (text/icons, and the CTA button fill) on the alarm reds. */
    val OnAlert = Color(0xFFFFFFFF)
}
