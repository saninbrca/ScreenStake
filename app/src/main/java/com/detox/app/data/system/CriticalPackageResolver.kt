package com.detox.app.data.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.detox.app.BuildConfig
import com.detox.app.domain.model.NeverBlockablePackages
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "packages that must NEVER be blocked", used by BOTH the app picker
 * (pick time) and the accessibility/overlay layer (enforcement time).
 *
 * Sharing one resolver is the point: the picker filter alone is not protection, because the set is
 * a snapshot taken when the picker loaded. A user who changes their default dialer, SMS app or
 * keyboard AFTER creating a challenge would otherwise keep enforcing against a package that has
 * since become critical. Enforcement re-consults this resolver, so a package holding a critical
 * role right now is never blocked regardless of how it got into the tracked set.
 *
 * ## Fail-safe contract
 * A failed role lookup must never make a critical app blockable. Three independent layers:
 *  1. [NeverBlockablePackages] prefixes are checked FIRST and never depend on resolution.
 *  2. Each role lookup is individually wrapped — one failing role never loses the others.
 *  3. A failed or empty resolution NEVER overwrites a good cache, and is never cached as
 *     authoritative. With no usable set at all, [isNeverBlockable] answers `true`
 *     (treat as critical → do not block) rather than falling through to blocking.
 */
@Singleton
class CriticalPackageResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
) {

    companion object {
        /**
         * How long a resolved set stays authoritative. Long enough that the steady-state cost of
         * [isNeverBlockable] is a HashSet lookup (it runs on every accessibility event), short
         * enough that a default-app change is picked up within minutes.
         */
        private const val CACHE_TTL_MS = 5L * 60 * 1000 // 5 minutes
    }

    /** Last successfully resolved set. Never replaced by a failed/empty resolution. */
    @Volatile
    private var cached: Set<String>? = null

    @Volatile
    private var cachedAtElapsedMs: Long = 0L

    private val lock = Any()

    /**
     * Resolves the critical set from scratch. Every lookup is individually guarded, so a role that
     * is unavailable on this OEM/device contributes nothing instead of aborting the whole pass.
     */
    private fun resolveBlocking(): Set<String> {
        val result = mutableSetOf<String>()

        // This app itself.
        result += context.packageName

        // Home / launcher app(s).
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0).forEach {
                it.activityInfo?.packageName?.let(result::add)
            }
        }

        // Default dialer (+ whatever resolves ACTION_DIAL).
        runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let(result::add)
        }
        runCatching {
            packageManager.resolveActivity(Intent(Intent.ACTION_DIAL), 0)
                ?.activityInfo?.packageName?.let(result::add)
        }

        // Default SMS app.
        runCatching {
            Telephony.Sms.getDefaultSmsPackage(context)?.let(result::add)
        }

        // Active input method(s) — blocking the keyboard would lock the user out of typing.
        runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.enabledInputMethodList
                ?.forEach { it.packageName?.let(result::add) }
        }
        runCatching {
            Settings.Secure.getString(
                context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let(result::add)
        }

        // Settings app.
        runCatching {
            packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                ?.activityInfo?.packageName?.let(result::add)
        }

        // Clock / alarm app(s) — blocking these means a ringing alarm can't be dismissed and no
        // new alarm can be set. Resolved dynamically so it covers OEM clocks we've never seen
        // (Huawei, Samsung, Oppo, vivo, …) instead of relying on a hardcoded package list.
        //
        // queryIntentActivities (all handlers) rather than resolveActivity: with several clocks
        // installed, resolveActivity returns the system ResolverActivity, not a clock.
        // Both actions are queried — the harm is asymmetric. Over-exclusion means a user can't
        // block an app they wanted to; under-exclusion means a user can block their alarm clock.
        result += resolveAlarmPackages()

        return result
    }

    /**
     * Packages handling [AlarmClock.ACTION_SHOW_ALARMS] or [AlarmClock.ACTION_SET_ALARM].
     * Both actions exist since API 19/9 respectively — always available at minSdk 26.
     */
    private fun resolveAlarmPackages(): Set<String> {
        val alarmPackages = mutableSetOf<String>()
        listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM).forEach { action ->
            runCatching {
                packageManager.queryIntentActivities(Intent(action), 0).forEach {
                    it.activityInfo?.packageName?.let(alarmPackages::add)
                }
            }.onFailure { Timber.w(it, "CriticalPackageResolver: alarm lookup failed for $action") }
        }

        // Debug-only visibility into what the alarm resolver actually catches on real hardware:
        // ACTION_SET_ALARM can be claimed by assistant/search apps, which would silently make a
        // genuine time-sink un-blockable. Logged, never acted on automatically.
        if (BuildConfig.DEBUG && alarmPackages.isNotEmpty()) {
            Timber.d(
                "CriticalPackageResolver: alarm role excluded ${alarmPackages.size} package(s): " +
                    alarmPackages.sorted().joinToString()
            )
        }
        return alarmPackages
    }

    /**
     * The critical set, refreshed if the cache is cold or stale. On failure the last good set is
     * kept (see the fail-safe contract) — a transient PackageManager problem can never widen what
     * is blockable.
     */
    private fun currentSet(): Set<String>? {
        val now = android.os.SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot != null && now - cachedAtElapsedMs < CACHE_TTL_MS) return snapshot

        synchronized(lock) {
            // Another thread may have refreshed while we waited.
            val current = cached
            val nowInner = android.os.SystemClock.elapsedRealtime()
            if (current != null && nowInner - cachedAtElapsedMs < CACHE_TTL_MS) return current

            val resolved = runCatching { resolveBlocking() }.getOrElse { e ->
                Timber.w(e, "CriticalPackageResolver: resolution failed — keeping last known set")
                Sentry.addBreadcrumb(Breadcrumb().apply {
                    category = "CriticalPackageResolver"
                    message = "Role resolution failed; falling back to last known set"
                    level = SentryLevel.WARNING
                })
                return current
            }

            // Never cache an empty result as authoritative: resolveBlocking() always contributes
            // at least our own package, so empty means something went badly wrong.
            if (resolved.isEmpty()) {
                Timber.w("CriticalPackageResolver: resolution returned empty — not caching")
                return current
            }

            cached = resolved
            cachedAtElapsedMs = nowInner
            return resolved
        }
    }

    /**
     * SYNCHRONOUS enforcement-time check: true if [packageName] must not be blocked right now.
     *
     * Steady state is a HashSet lookup against the cached set — safe to call on every accessibility
     * event. Only a cold or stale cache triggers resolution (a handful of PackageManager calls);
     * [warmCache] pre-warms it off the main thread when the service connects, so in practice the
     * enforcement path never resolves inline.
     *
     * Fails safe: with no usable set at all, returns true (treat as critical → do not block).
     */
    fun isNeverBlockable(packageName: String): Boolean {
        // Layer 1: static prefixes, independent of any runtime lookup.
        if (NeverBlockablePackages.matchesExcludedPrefix(packageName)) return true

        val set = currentSet()
        if (set == null) {
            // Layer 3: no usable set — never fall through to blocking.
            Timber.w(
                "CriticalPackageResolver: no critical-package set available — " +
                    "treating $packageName as critical (not blocking)"
            )
            return true
        }
        return packageName in set
    }

    /** Pre-warms the cache off the main thread so enforcement never resolves inline. */
    suspend fun warmCache() {
        withContext(Dispatchers.IO) { currentSet() }
    }

    /**
     * The full critical set for pick-time filtering. Returns an empty set only if resolution has
     * never succeeded — the picker's static prefix list still applies in that case.
     */
    suspend fun getNeverBlockablePackages(): Set<String> =
        withContext(Dispatchers.IO) { currentSet() ?: emptySet() }
}
