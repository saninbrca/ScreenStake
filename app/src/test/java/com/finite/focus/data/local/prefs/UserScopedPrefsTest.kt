package com.finite.focus.data.local.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the user/device split.
 *
 * Both directions matter and both have a plausible wrong-way edit: clearing too little leaves
 * the stale-username bug in place, clearing too much resets the user's theme — or makes them
 * re-read the five-page intro — on every sign-out.
 *
 * The criterion each key was judged by: **what should the NEXT user on this device get?**
 */
class UserScopedPrefsTest {

    /** Written by `ui.theme.ThemeMode`, `WelcomeOnboardingScreen`, and DEBUG-gated switches. */
    private val deviceScopedSettingsKeys = listOf(
        "theme_mode",
        "dark_mode_enabled",
        "onboarding_completed",
        "debug_use_minutes_as_days",
        "debug_hard_mode_min_1",
        "permission_lost_at",
    )

    /** Forfeit-path, enforcement and infrastructure prefs files — off-limits to the cleaner. */
    private val offLimitsFiles = listOf(
        "detox_permission",
        "detox_accessibility",
        "detox_usage_violation",
        "detox_heartbeat",
        "detox_budget_session",
        "detox_session_timers",
        "detox_group_time_tracking",
        "detox_db_security",
        "detox_app_config",
        "detox_fgs_retry",
        "detox_update_banner",
    )

    // ── user-scoped: must be cleared ───────────────────────────────────────────

    @Test
    fun `the cached username is cleared — this is the stale-picker bug`() {
        assertTrue(
            "username must be user-scoped: MainActivity routes on this cache",
            UserScopedPrefs.KEY_USERNAME in UserScopedPrefs.SETTINGS_KEYS
        )
    }

    @Test
    fun `a pending deep link does not survive into the next session`() {
        assertTrue(UserScopedPrefs.KEY_PENDING_DEEP_LINK_TARGET in UserScopedPrefs.SETTINGS_KEYS)
        assertTrue(UserScopedPrefs.KEY_PENDING_DEEP_LINK_ARG in UserScopedPrefs.SETTINGS_KEYS)
    }

    @Test
    fun `notification opt-outs do not carry over to the next user`() {
        // One user silencing friend alerts must not silently silence them for someone else.
        assertTrue(UserScopedPrefs.KEY_CHALLENGE_UPDATES in UserScopedPrefs.SETTINGS_KEYS)
        assertTrue(UserScopedPrefs.KEY_FRIEND_ALERTS in UserScopedPrefs.SETTINGS_KEYS)
        assertTrue("detox_notifications" in UserScopedPrefs.USER_SCOPED_FILES)
    }

    @Test
    fun `one-shot shown-already flags are cleared so the next user is not skipped`() {
        // A stale flag means the new user never sees their first podium or win popup.
        assertTrue("detox_podium" in UserScopedPrefs.USER_SCOPED_FILES)
        assertTrue("detox_win_popup" in UserScopedPrefs.USER_SCOPED_FILES)
        assertTrue("detox_broadcast" in UserScopedPrefs.USER_SCOPED_FILES)
    }

    // ── device-scoped and off-limits: must NOT be cleared ──────────────────────

    @Test
    fun `device-scoped keys are never cleared`() {
        deviceScopedSettingsKeys.forEach {
            assertFalse(
                "'$it' is device-scoped and must survive a logout — the user did not change devices",
                it in UserScopedPrefs.SETTINGS_KEYS
            )
        }
    }

    @Test
    fun `the onboarding flag survives — the intro explains the app, not the account`() {
        assertFalse(
            "someone who deletes and re-registers should not re-read five pages",
            "onboarding_completed" in UserScopedPrefs.SETTINGS_KEYS
        )
    }

    @Test
    fun `the theme survives a logout`() {
        assertFalse("theme_mode" in UserScopedPrefs.SETTINGS_KEYS)
        assertFalse("dark_mode_enabled" in UserScopedPrefs.SETTINGS_KEYS)
    }

    @Test
    fun `forfeit-path, enforcement and infrastructure prefs files are never wiped`() {
        offLimitsFiles.forEach {
            assertFalse(
                "'$it' carries money-path, enforcement or infrastructure state — not the cleaner's business",
                it in UserScopedPrefs.USER_SCOPED_FILES
            )
        }
    }

    @Test
    fun `the mixed settings file is never wiped wholesale`() {
        // It holds the theme and the onboarding flag; clear() would take them too.
        assertFalse(
            "detox_settings must be cleared key-by-key, never as a whole file",
            UserScopedPrefs.PREFS_NAME in UserScopedPrefs.USER_SCOPED_FILES
        )
    }

    // ── exact pinning ──────────────────────────────────────────────────────────

    @Test
    fun `the cleared sets are exactly what is documented`() {
        assertEquals(
            setOf(
                "username",
                "pending_deep_link_target",
                "pending_deep_link_arg",
                "challenge_updates_enabled",
                "friend_alerts_enabled",
            ),
            UserScopedPrefs.SETTINGS_KEYS
        )
        assertEquals(
            setOf("detox_notifications", "detox_podium", "detox_win_popup", "detox_broadcast"),
            UserScopedPrefs.USER_SCOPED_FILES
        )
    }

    @Test
    fun `it targets the settings prefs file`() {
        assertEquals("detox_settings", UserScopedPrefs.PREFS_NAME)
    }
}
