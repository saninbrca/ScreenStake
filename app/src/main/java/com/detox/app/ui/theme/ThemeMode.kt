package com.detox.app.ui.theme

import android.content.SharedPreferences

/**
 * User-selected theme mode, persisted in the "detox_settings" SharedPreferences
 * (that file name is fixed — never introduce a second prefs file for this).
 *
 * Resolution to an actual dark/light boolean happens in exactly ONE place:
 * the [DetoxTheme] `themeMode` overload. No other code may call
 * `isSystemInDarkTheme()`.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun saveTo(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_THEME_MODE, name).apply()
    }

    companion object {
        const val KEY_THEME_MODE = "theme_mode"

        /** Legacy Boolean toggle. Read only for migration; never written again. */
        const val KEY_LEGACY_DARK_MODE = "dark_mode_enabled"

        /**
         * Reads the persisted mode with legacy migration: an explicit Boolean toggle
         * maps true → DARK / false → LIGHT (existing users keep their look); users
         * who never touched the toggle (no key at all) follow the system.
         */
        fun fromPrefs(prefs: SharedPreferences): ThemeMode {
            prefs.getString(KEY_THEME_MODE, null)?.let { stored ->
                return entries.firstOrNull { it.name == stored } ?: SYSTEM
            }
            return when {
                !prefs.contains(KEY_LEGACY_DARK_MODE) -> SYSTEM
                prefs.getBoolean(KEY_LEGACY_DARK_MODE, false) -> DARK
                else -> LIGHT
            }
        }
    }
}
