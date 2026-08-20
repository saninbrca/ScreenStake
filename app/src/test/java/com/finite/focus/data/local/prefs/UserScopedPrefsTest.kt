package com.finite.focus.data.local.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the user/device split in `detox_settings`.
 *
 * The file is mixed: clearing too little leaves the stale-username bug in place, clearing
 * too much resets the user's theme on every sign-out. Both directions are asserted here
 * because both have a plausible wrong-way edit.
 */
class UserScopedPrefsTest {

    /** Written by `ui.theme.ThemeMode` and the DEBUG-gated developer switches. */
    private val deviceScopedKeys = listOf(
        "theme_mode",
        "dark_mode_enabled",
        "debug_use_minutes_as_days",
        "debug_hard_mode_min_1",
        "permission_lost_at",
    )

    @Test
    fun `the cached username is cleared — this is the stale-picker bug`() {
        assertTrue(
            "username must be user-scoped: MainActivity routes on this cache",
            UserScopedPrefs.KEY_USERNAME in UserScopedPrefs.KEYS
        )
    }

    @Test
    fun `a pending deep link does not survive into the next session`() {
        assertTrue(UserScopedPrefs.KEY_PENDING_DEEP_LINK_TARGET in UserScopedPrefs.KEYS)
        assertTrue(UserScopedPrefs.KEY_PENDING_DEEP_LINK_ARG in UserScopedPrefs.KEYS)
    }

    @Test
    fun `device-scoped keys are never cleared`() {
        deviceScopedKeys.forEach {
            assertFalse(
                "'$it' is device-scoped and must survive a logout — the user did not change devices",
                it in UserScopedPrefs.KEYS
            )
        }
    }

    /**
     * A reminder, not a restriction: growing this set is fine, but each addition needs the
     * user/device call made deliberately rather than by reflex.
     */
    @Test
    fun `the cleared set is exactly what is documented`() {
        assertEquals(
            setOf("username", "pending_deep_link_target", "pending_deep_link_arg"),
            UserScopedPrefs.KEYS
        )
    }

    @Test
    fun `it targets the settings prefs file`() {
        assertEquals("detox_settings", UserScopedPrefs.PREFS_NAME)
    }
}
