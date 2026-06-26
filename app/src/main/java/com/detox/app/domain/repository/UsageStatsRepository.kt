package com.detox.app.domain.repository

import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.model.AppUsageInfo

interface UsageStatsRepository {
    suspend fun getAppUsageStats(days: Int = 14): List<AppUsageInfo>
    fun hasUsageStatsPermission(): Boolean
    suspend fun getTodayUsageForApp(packageName: String): AppDailyUsage

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
