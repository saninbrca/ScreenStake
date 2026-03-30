package com.detox.app.domain.repository

import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.model.AppUsageInfo

interface UsageStatsRepository {
    suspend fun getAppUsageStats(days: Int = 14): List<AppUsageInfo>
    fun hasUsageStatsPermission(): Boolean
    suspend fun getTodayUsageForApp(packageName: String): AppDailyUsage
}
