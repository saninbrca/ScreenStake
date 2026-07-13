package com.detox.app.ui.theme

import androidx.compose.ui.graphics.Color

// Primary — Electric Green
val DetoxPrimary = Color(0xFF00C853)
val DetoxPrimaryVariant = Color(0xFF00A846)
val DetoxPrimaryDark = Color(0xFF00E676)

// Value-descriptive names: light scheme uses mint as container / deep as content,
// the dark scheme the reverse (previously named DetoxPrimaryContainer /
// DetoxOnPrimaryContainer, which read crossed in the light scheme).
val DetoxGreenDeep = Color(0xFF003318)
val DetoxGreenMint = Color(0xFFD8FFE8)

// Secondary — Dark accents
val DetoxSecondary = Color(0xFF1A1A1A)
val DetoxSecondaryDark = Color(0xFF424242)
val DetoxSecondaryContainer = Color(0xFF212121)
val DetoxOnSecondaryContainer = Color(0xFFF5F5F5)

// Semantic
val DetoxSuccess = Color(0xFF00E676)
val DetoxWarning = Color(0xFFFF6B35)
val DetoxDanger = Color(0xFFFF3B30)

// Tertiary (maps to Warning in Material3)
val DetoxTertiary = Color(0xFFFF6B35)
val DetoxTertiaryDark = Color(0xFFFFAB8A)

// Error
val DetoxError = Color(0xFFFF3B30)
val DetoxErrorDark = Color(0xFFFF6B6B)

// Light theme backgrounds
val DetoxBackgroundLight = Color(0xFFFFFFFF)
val DetoxSurfaceLight = Color(0xFFF5F5F5)
val DetoxOnBackgroundLight = Color(0xFF1A1A1A)
val DetoxOnSurfaceLight = Color(0xFF1A1A1A)
val DetoxTextSecondaryLight = Color(0xFF757575)

// Dark theme backgrounds
val DetoxBackgroundDark = Color(0xFF0F0F0F)
val DetoxSurfaceDark = Color(0xFF1A1A1A)
val DetoxOnBackgroundDark = Color(0xFFFFFFFF)
val DetoxOnSurfaceDark = Color(0xFFFFFFFF)
val DetoxTextSecondaryDark = Color(0xFF9E9E9E)

// ── M3 role completion ─────────────────────────────────────────────────────────
// Every role below exists so no scheme slot falls back to Material's purple-tinted
// baseline. Neutral ramps are derived from the existing palette family
// (#0A0A0A / #0F0F0F / #111111 / #1A1A1A / #1E1E1E and the iOS grays).

// Containers & inverse — light
val DetoxTertiaryContainerLight = Color(0xFFFFF0E8)
val DetoxOnTertiaryContainerLight = Color(0xFFC05A00)
val DetoxErrorContainerLight = Color(0xFFFFE5E3)
val DetoxOnErrorContainerLight = Color(0xFF6E1B16)
val DetoxOutlineVariantLight = Color(0xFFE0E0E5)
val DetoxInverseSurfaceLight = Color(0xFF1A1A1A)
val DetoxInverseOnSurfaceLight = Color(0xFFF5F5F5)

// Containers & inverse — dark
val DetoxTertiaryContainerDark = Color(0xFF3A2114)
val DetoxOnTertiaryContainerDark = Color(0xFFFFAB8A)
val DetoxErrorContainerDark = Color(0xFF421715)
val DetoxOnErrorContainerDark = Color(0xFFFFDAD6)
val DetoxSurfaceVariantDark = Color(0xFF2E2E2E)
val DetoxOutlineVariantDark = Color(0xFF2E2E2E)
val DetoxInverseSurfaceDark = Color(0xFFF5F5F5)
val DetoxInverseOnSurfaceDark = Color(0xFF1A1A1A)

// Neutral surface ramp — light (iOS gray family; drives dialogs, menus, sheets)
val DetoxSurfaceDimLight = Color(0xFFE0E0E5)
val DetoxSurfaceBrightLight = Color(0xFFFFFFFF)
val DetoxSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val DetoxSurfaceContainerLowLight = Color(0xFFF5F5F5)
val DetoxSurfaceContainerLight = Color(0xFFF2F2F7)
val DetoxSurfaceContainerHighLight = Color(0xFFE9E9EE)
val DetoxSurfaceContainerHighestLight = Color(0xFFE0E0E5)

// Neutral surface ramp — dark (no purple: stays in the #0A0A0A–#333333 family)
val DetoxSurfaceDimDark = Color(0xFF0F0F0F)
val DetoxSurfaceBrightDark = Color(0xFF333333)
val DetoxSurfaceContainerLowestDark = Color(0xFF0A0A0A)
val DetoxSurfaceContainerLowDark = Color(0xFF111111)
val DetoxSurfaceContainerDark = Color(0xFF1A1A1A)
val DetoxSurfaceContainerHighDark = Color(0xFF1E1E1E)
val DetoxSurfaceContainerHighestDark = Color(0xFF222222)

// Status colors
val DetoxTrackableGreen = Color(0xFF00C853)
val DetoxGrayedOut = Color(0xFF757575)
