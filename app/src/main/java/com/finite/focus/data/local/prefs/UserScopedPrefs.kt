package com.finite.focus.data.local.prefs

import android.content.Context
import timber.log.Timber

/**
 * Everything in SharedPreferences that belongs to the SIGNED-IN USER rather than the device,
 * cleared on both logout and account deletion.
 *
 * ## Why this exists
 * Room is cleared on both paths; SharedPreferences never were. The `username` key is the one
 * that bites: [com.finite.focus.MainActivity] uses the cached value to decide whether to route
 * to the username picker, so a stale cache survives a logout or an account deletion and makes
 * the NEXT account skip the picker entirely — leaving it with no username in Firestore while
 * the app believes it has one.
 *
 * ## The criterion
 * **"What should the NEXT user on this device get?"** Defaults are the safe landing: one user
 * silencing friend alerts must not silently carry over to someone else, and a stale "podium
 * already shown" flag means the new user misses their first podium.
 *
 * ## Deliberately NOT cleared — device-scoped
 *  - `theme_mode` / `dark_mode_enabled` — display preference; the user did not change devices
 *  - `onboarding_completed` — the 5-page intro explains the APP, not the account. Someone who
 *    deletes and re-registers should not re-read five pages.
 *  - `debug_use_minutes_as_days`, `debug_hard_mode_min_1`, `permission_lost_at`,
 *    `budget_committed_ms_*` — `BuildConfig.DEBUG`-gated developer switches
 *
 * ## Deliberately NOT cleared — off-limits
 * `detox_permission`, `detox_accessibility`, `detox_usage_violation` and `detox_heartbeat` feed
 * the permission-loss and went-dark forfeit paths; `detox_budget_session`,
 * `detox_session_timers` and `detox_group_time_tracking` hold live enforcement state;
 * `detox_db_security` holds the SQLCipher passphrase wrapper and clearing it would orphan the
 * encrypted database; `detox_app_config` is the fail-open remote-config cache. None of those
 * are this function's business.
 *
 * ## Adding something
 * A key in the mixed `detox_settings` file goes in [SETTINGS_KEYS]; a prefs file that is
 * user-scoped in its entirety goes in [USER_SCOPED_FILES]. `UserScopedPrefsTest` pins both
 * lists in both directions, so a wrong-way edit fails the build.
 */
object UserScopedPrefs {

    /** The MIXED file — user and device keys live side by side, so it is never `clear()`ed. */
    const val PREFS_NAME = "detox_settings"

    /** Cached unique handle. The stale-picker bug above is entirely this key. */
    const val KEY_USERNAME = "username"

    /** A deep link captured for the PREVIOUS session must not fire for the next user. */
    const val KEY_PENDING_DEEP_LINK_TARGET = "pending_deep_link_target"
    const val KEY_PENDING_DEEP_LINK_ARG = "pending_deep_link_arg"

    /** Notification choices — one user's opt-out must not land on the next user. */
    const val KEY_CHALLENGE_UPDATES = "challenge_updates_enabled"
    const val KEY_FRIEND_ALERTS = "friend_alerts_enabled"

    /** Keys removed from [PREFS_NAME]. Everything else in that file survives. */
    val SETTINGS_KEYS: Set<String> = setOf(
        KEY_USERNAME,
        KEY_PENDING_DEEP_LINK_TARGET,
        KEY_PENDING_DEEP_LINK_ARG,
        KEY_CHALLENGE_UPDATES,
        KEY_FRIEND_ALERTS,
    )

    /**
     * Prefs files that are user-scoped in their entirety and are wiped wholesale.
     *
     *  - `detox_notifications` — notification toggles (`notif_group_participant_failed`)
     *  - `detox_podium` — "podium already shown" per group challenge
     *  - `detox_win_popup` — "win popup already shown" per challenge
     *  - `detox_broadcast` — "admin broadcast already seen"
     *
     * All four are one-shot flags tied to one user's challenges or announcements. A stale flag
     * silently suppresses something the next user has never seen.
     */
    val USER_SCOPED_FILES: Set<String> = setOf(
        "detox_notifications",
        "detox_podium",
        "detox_win_popup",
        "detox_broadcast",
    )

    /**
     * Removes [SETTINGS_KEYS] from [PREFS_NAME] and clears [USER_SCOPED_FILES] entirely.
     *
     * `detox_settings` uses per-key `remove()` rather than `clear()` — `clear()` would take the
     * theme, the onboarding flag and the debug switches with it. Committed synchronously
     * (`commit()`, not `apply()`) because both callers navigate away immediately afterwards: an
     * async write racing a process death during sign-out is exactly how the stale cache would
     * survive anyway.
     */
    fun clear(context: Context) {
        val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        SETTINGS_KEYS.forEach { settings.remove(it) }
        val settingsOk = settings.commit()

        var filesOk = true
        USER_SCOPED_FILES.forEach { name ->
            val ok = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
            if (!ok) filesOk = false
        }

        Timber.d(
            "UserScopedPrefs: removed %d keys from %s (ok=%b), cleared %d user-scoped files (ok=%b)",
            SETTINGS_KEYS.size, PREFS_NAME, settingsOk, USER_SCOPED_FILES.size, filesOk
        )
    }
}
