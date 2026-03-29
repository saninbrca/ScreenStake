package com.detox.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DetoxPrimaryDark,
    onPrimary = DetoxPrimaryContainer,
    primaryContainer = DetoxPrimaryContainer,
    onPrimaryContainer = DetoxOnPrimaryContainer,
    secondary = DetoxSecondaryDark,
    onSecondary = DetoxSecondaryContainer,
    secondaryContainer = DetoxSecondaryContainer,
    onSecondaryContainer = DetoxOnSecondaryContainer,
    tertiary = DetoxTertiaryDark,
    error = DetoxErrorDark,
    background = DetoxBackgroundDark,
    onBackground = DetoxOnBackgroundDark,
    surface = DetoxSurfaceDark,
    onSurface = DetoxOnSurfaceDark
)

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
    error = DetoxError,
    background = DetoxBackgroundLight,
    onBackground = DetoxOnBackgroundLight,
    surface = DetoxSurfaceLight,
    onSurface = DetoxOnSurfaceLight
)

@Composable
fun DetoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
