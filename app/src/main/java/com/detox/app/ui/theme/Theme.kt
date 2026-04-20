package com.detox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = DetoxPrimary,
    onPrimary = DetoxBackgroundLight,
    primaryContainer = DetoxOnPrimaryContainer,
    onPrimaryContainer = DetoxPrimaryContainer,
    secondary = DetoxSecondary,
    onSecondary = DetoxBackgroundLight,
    secondaryContainer = DetoxOnSecondaryContainer,
    onSecondaryContainer = DetoxSecondaryContainer,
    tertiary = DetoxTertiary,
    onTertiary = DetoxBackgroundLight,
    error = DetoxError,
    onError = DetoxBackgroundLight,
    background = DetoxBackgroundLight,
    onBackground = DetoxOnBackgroundLight,
    surface = DetoxSurfaceLight,
    onSurface = DetoxOnSurfaceLight,
    onSurfaceVariant = DetoxTextSecondaryLight,
    outline = DetoxTextSecondaryLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = DetoxPrimaryDark,
    onPrimary = DetoxPrimaryContainer,
    primaryContainer = DetoxPrimaryContainer,
    onPrimaryContainer = DetoxOnPrimaryContainer,
    secondary = DetoxSecondaryDark,
    onSecondary = DetoxBackgroundDark,
    secondaryContainer = DetoxSecondaryContainer,
    onSecondaryContainer = DetoxOnSecondaryContainer,
    tertiary = DetoxTertiaryDark,
    onTertiary = DetoxBackgroundDark,
    error = DetoxErrorDark,
    onError = DetoxBackgroundDark,
    background = DetoxBackgroundDark,
    onBackground = DetoxOnBackgroundDark,
    surface = DetoxSurfaceDark,
    onSurface = DetoxOnSurfaceDark,
    onSurfaceVariant = DetoxTextSecondaryDark,
    outline = DetoxTextSecondaryDark,
)

@Composable
fun DetoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = DetoxShapes,
        content = content
    )
}
