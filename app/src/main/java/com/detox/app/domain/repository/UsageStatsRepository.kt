package com.detox.app.domain.repository

import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.InstalledAppInfo

interface UsageStatsRepository {
    suspend fun getAppUsageStats(days: Int = 14): List<AppUsageInfo>
    fun hasUsageStatsPermission(): Boolean
    suspend fun getTodayUsageForApp(packageName: String): AppDailyUsage

    /**
     * Every user-launchable installed app (PackageManager MAIN/LAUNCHER query), used or not.
     * This is the source of truth for the app picker — independent of usage history and of
     * PACKAGE_USAGE_STATS, so the list is complete even before usage access is granted.
     */
    suspend fun getLaunchableApps(): List<InstalledAppInfo>

    /**
     * Packages that must NEVER be blockable, resolved dynamically at runtime so it's OEM-agnostic:
     * the home/launcher app(s), default dialer, default SMS app, active input method(s), the Settings
     * app, and this app itself. The picker excludes these so a user can't trap their own device.
     */
    suspend fun getNeverBlockablePackages(): Set<String>

    /**
     * Average daily foreground minutes + opens per package over the last [days]. Empty when usage
     * access is missing (queries return nothing) — callers treat absent packages as zero usage.
     */
    suspend fun getUsageByPackage(days: Int = 14): Map<String, Pair<Long, Int>>

    /**
     * The package of the app currently on top per UsageStats, or null if it can't be determined
     * (no permission / no recent data). Unlike the accessibility-cached foreground value, this
     * ignores IME/transient windows (keyboards, dialogs) because they don't register as foreground
     * activities in UsageStats.
     *
     * FOREGROUND CHECK ONLY (invariant #15): used solely to decide whether to show an overlay now
     * vs defer — NEVER to count conscious opens. Opens increment only on the user's "Ja, öffnen" tap.
     */
    suspend fun getCurrentForegroundPackage(): String?
}
