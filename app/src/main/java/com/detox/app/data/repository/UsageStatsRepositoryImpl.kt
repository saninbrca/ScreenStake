package com.detox.app.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.repository.UsageStatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageStatsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatsManager: UsageStatsManager,
    private val packageManager: PackageManager
) : UsageStatsRepository {

    companion object {
        private const val MIN_DAILY_MINUTES = 0L
        private const val MIN_DAILY_OPENS = 0
    }

    override suspend fun getAppUsageStats(days: Int): List<AppUsageInfo> =
        withContext(Dispatchers.IO) {
            val endTime = System.currentTimeMillis()
            val calendar = Calendar.getInstance().apply {
                timeInMillis = endTime
                add(Calendar.DAY_OF_YEAR, -days)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis

            val usageTimeByPackage = getUsageTimeByPackage(startTime, endTime)
            val openCountByPackage = getOpenCountByPackage(startTime, endTime)

            val allPackages = (usageTimeByPackage.keys + openCountByPackage.keys).distinct()

            allPackages.mapNotNull { packageName ->
                if (isSystemApp(packageName)) return@mapNotNull null

                val appInfo = try {
                    packageManager.getApplicationInfo(packageName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }

                val totalMinutes = usageTimeByPackage[packageName] ?: 0L
                val totalOpens = openCountByPackage[packageName] ?: 0
                val avgDailyMinutes = totalMinutes / days
                val avgDailyOpens = totalOpens / days

                val isTrackable = avgDailyMinutes >= MIN_DAILY_MINUTES || avgDailyOpens >= MIN_DAILY_OPENS

                AppUsageInfo(
                    packageName = packageName,
                    appName = appInfo.loadLabel(packageManager).toString(),
                    icon = try { appInfo.loadIcon(packageManager) } catch (e: Exception) { null },
                    avgDailyMinutes = avgDailyMinutes,
                    avgDailyOpens = avgDailyOpens,
                    isTrackable = isTrackable
                )
            }.sortedByDescending { it.avgDailyMinutes }
        }

    private fun getUsageTimeByPackage(startTime: Long, endTime: Long): Map<String, Long> {
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val timeByPackage = mutableMapOf<String, Long>()
        for (stat in stats) {
            val minutes = stat.totalTimeInForeground / 60_000
            if (minutes > 0) {
                timeByPackage[stat.packageName] =
                    (timeByPackage[stat.packageName] ?: 0L) + minutes
            }
        }
        return timeByPackage
    }

    private fun getOpenCountByPackage(startTime: Long, endTime: Long): Map<String, Int> {
        val opensByPackage = mutableMapOf<String, Int>()
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                opensByPackage[event.packageName] =
                    (opensByPackage[event.packageName] ?: 0) + 1
            }
        }
        return opensByPackage
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            true
        }
    }

    override suspend fun getTodayUsageForApp(packageName: String): AppDailyUsage =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val usageTime = getUsageTimeByPackage(startOfDay, now)
            val openCount = getOpenCountByPackage(startOfDay, now)

            AppDailyUsage(
                minutes = (usageTime[packageName] ?: 0L).toInt(),
                opens = openCount[packageName] ?: 0
            )
        }

    override fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
