package com.detox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Every M3 role is set explicitly in BOTH schemes so nothing falls back to
// Material's purple-tinted baseline (dialogs, menus, sheets and nav surfaces
// draw from the surfaceContainer* family).
private val LightColorScheme = lightColorScheme(
    primary = DetoxPrimary,
    onPrimary = DetoxBackgroundLight,
    primaryContainer = DetoxGreenMint,
    onPrimaryContainer = DetoxGreenDeep,
    inversePrimary = DetoxPrimaryDark,
    secondary = DetoxSecondary,
    onSecondary = DetoxBackgroundLight,
    secondaryContainer = DetoxOnSecondaryContainer,
    onSecondaryContainer = DetoxSecondaryContainer,
    tertiary = DetoxTertiary,
    onTertiary = DetoxBackgroundLight,
    tertiaryContainer = DetoxTertiaryContainerLight,
    onTertiaryContainer = DetoxOnTertiaryContainerLight,
    error = DetoxError,
    onError = DetoxBackgroundLight,
    errorContainer = DetoxErrorContainerLight,
    onErrorContainer = DetoxOnErrorContainerLight,
    background = DetoxBackgroundLight,
    onBackground = DetoxOnBackgroundLight,
    surface = DetoxBackgroundLight,
    onSurface = DetoxOnSurfaceLight,
    surfaceVariant = DetoxSurfaceLight,
    onSurfaceVariant = DetoxTextSecondaryLight,
    // No tonal-elevation overlays in either mode.
    surfaceTint = Color.Transparent,
    inverseSurface = DetoxInverseSurfaceLight,
    inverseOnSurface = DetoxInverseOnSurfaceLight,
    outline = DetoxTextSecondaryLight,
    outlineVariant = DetoxOutlineVariantLight,
    scrim = Color.Black,
    surfaceBright = DetoxSurfaceBrightLight,
    surfaceDim = DetoxSurfaceDimLight,
    surfaceContainer = DetoxSurfaceContainerLight,
    surfaceContainerHigh = DetoxSurfaceContainerHighLight,
    surfaceContainerHighest = DetoxSurfaceContainerHighestLight,
    surfaceContainerLow = DetoxSurfaceContainerLowLight,
    surfaceContainerLowest = DetoxSurfaceContainerLowestLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = DetoxPrimaryDark,
    onPrimary = DetoxGreenDeep,
    primaryContainer = DetoxGreenDeep,
    onPrimaryContainer = DetoxGreenMint,
    inversePrimary = DetoxPrimary,
    secondary = DetoxSecondaryDark,
    onSecondary = DetoxBackgroundDark,
    secondaryContainer = DetoxSecondaryContainer,
    onSecondaryContainer = DetoxOnSecondaryContainer,
    tertiary = DetoxTertiaryDark,
    onTertiary = DetoxBackgroundDark,
    tertiaryContainer = DetoxTertiaryContainerDark,
    onTertiaryContainer = DetoxOnTertiaryContainerDark,
    error = DetoxErrorDark,
    onError = DetoxBackgroundDark,
    errorContainer = DetoxErrorContainerDark,
    onErrorContainer = DetoxOnErrorContainerDark,
    background = DetoxBackgroundDark,
    onBackground = DetoxOnBackgroundDark,
    surface = DetoxSurfaceDark,
    onSurface = DetoxOnSurfaceDark,
    surfaceVariant = DetoxSurfaceVariantDark,
    onSurfaceVariant = DetoxTextSecondaryDark,
    // No tonal-elevation overlays in either mode (the default dark surfaceTint is
    // primary, which green-tinted every elevated surface).
    surfaceTint = Color.Transparent,
    inverseSurface = DetoxInverseSurfaceDark,
    inverseOnSurface = DetoxInverseOnSurfaceDark,
    outline = DetoxTextSecondaryDark,
    outlineVariant = DetoxOutlineVariantDark,
    scrim = Color.Black,
    surfaceBright = DetoxSurfaceBrightDark,
    surfaceDim = DetoxSurfaceDimDark,
    surfaceContainer = DetoxSurfaceContainerDark,
    surfaceContainerHigh = DetoxSurfaceContainerHighDark,
    surfaceContainerHighest = DetoxSurfaceContainerHighestDark,
    surfaceContainerLow = DetoxSurfaceContainerLowDark,
    surfaceContainerLowest = DetoxSurfaceContainerLowestDark,
)

/**
 * The resolved dark/light decision of the enclosing [DetoxTheme]. Read this instead
 * of `isSystemInDarkTheme()` anywhere the active mode is needed (e.g. system-bar
 * icon styling) — the system value is NOT the app theme once the user picks
 * LIGHT/DARK.
 */
val LocalDetoxDarkTheme = staticCompositionLocalOf { false }

/**
 * Tri-state entry point — the ONLY place [ThemeMode] is resolved to a boolean and
 * the ONLY permitted call site of `isSystemInDarkTheme()` in the app.
 */
@Composable
fun DetoxTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    DetoxTheme(darkTheme = darkTheme, content = content)
}

/**
 * Boolean entry point for surfaces whose appearance is fixed by design (the
 * always-dark blocking overlays pass `darkTheme = true`). Deliberately has NO
 * default value: every call site must state its mode explicitly.
 */
@Composable
fun DetoxTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DetoxSemanticDark else DetoxSemanticLight

    CompositionLocalProvider(
        LocalDetoxColors provides semanticColors,
        LocalDetoxDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = DetoxShapes,
            content = content
        )
    }
}
