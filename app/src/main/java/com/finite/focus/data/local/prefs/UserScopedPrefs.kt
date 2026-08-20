package com.finite.focus.data.local.prefs

import android.content.Context
import timber.log.Timber

/**
 * The keys in `detox_settings` that belong to the SIGNED-IN USER rather than the device.
 *
 * ## Why this exists
 * Room is cleared on both logout and account deletion; SharedPreferences never were. The
 * `username` key is the one that bites: [com.finite.focus.MainActivity] uses the cached
 * value to decide whether to route to the username picker, so a stale cache survives a
 * logout or an account deletion and makes the NEXT account skip the picker entirely —
 * leaving it with no username in Firestore while the app believes it has one.
 *
 * ## What is deliberately NOT in here
 * `detox_settings` is a MIXED file. Device-scoped keys must survive a logout — the user
 * did not change devices, and re-picking the theme after every sign-out would be wrong:
 *  - `theme_mode` / `dark_mode_enabled` — device display preference (see `ui.theme.ThemeMode`)
 *  - `debug_use_minutes_as_days`, `debug_hard_mode_min_1`, `permission_lost_at`,
 *    `budget_committed_ms_*` — `BuildConfig.DEBUG`-gated developer switches
 *
 * Separate prefs FILES are also left untouched on purpose. `detox_permission`,
 * `detox_accessibility`, `detox_usage_violation` and `detox_heartbeat` feed the
 * permission-loss and went-dark forfeit paths; `detox_budget_session`,
 * `detox_session_timers` and `detox_group_time_tracking` hold live enforcement state;
 * `detox_db_security` holds the SQLCipher passphrase wrapper and clearing it would orphan
 * the encrypted database. None of those are this function's business.
 *
 * ## Adding a key
 * Add it to [KEYS] and nowhere else. `UserScopedPrefsTest` asserts that no known
 * device-scoped key ever appears here, so a mistaken addition fails the build.
 */
object UserScopedPrefs {

    const val PREFS_NAME = "detox_settings"

    /** Cached unique handle. The stale-cache bug above is entirely this key. */
    const val KEY_USERNAME = "username"

    /** A deep link captured for the PREVIOUS session must not fire for the next user. */
    const val KEY_PENDING_DEEP_LINK_TARGET = "pending_deep_link_target"
    const val KEY_PENDING_DEEP_LINK_ARG = "pending_deep_link_arg"

    /** Every key removed on logout and on account deletion. */
    val KEYS: Set<String> = setOf(
        KEY_USERNAME,
        KEY_PENDING_DEEP_LINK_TARGET,
        KEY_PENDING_DEEP_LINK_ARG,
    )

    /**
     * Removes exactly [KEYS] from `detox_settings`, leaving every other key in place.
     *
     * Uses `remove()` per key rather than `clear()` — `clear()` would take the theme and the
     * debug switches with it. Committed synchronously (`commit()`, not `apply()`) because both
     * callers navigate away immediately afterwards: an async write racing a process death
     * during sign-out is exactly how the stale cache would survive anyway.
     */
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        KEYS.forEach { editor.remove(it) }
        val ok = editor.commit()
        Timber.d("UserScopedPrefs: cleared %d user-scoped keys (commit=%b)", KEYS.size, ok)
    }
}
