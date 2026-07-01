package com.detox.app.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.InstalledAppInfo
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

        /**
         * Look-back window for [getCurrentForegroundPackage]. Generous on purpose: since we take the
         * most-recently-used app (max lastTimeUsed), a longer window can NEVER return a stale result
         * (any app foregrounded later wins) — it only ensures the current top app, possibly idle
         * in-app for minutes before a session-timer expiry, still appears.
         */
        private const val FOREGROUND_LOOKBACK_MS = 10L * 60 * 1000 // 10 minutes
    }

    /**
     * FOREGROUND CHECK ONLY (invariant #15): reads the current top app to decide show-now vs defer;
     * never used to count conscious opens. Ignores IME/transient windows, which don't register as
     * foreground activities in UsageStats. Returns null when permission is missing or no recent data.
     */
    override suspend fun getCurrentForegroundPackage(): String? = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) return@withContext null
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, now - FOREGROUND_LOOKBACK_MS, now
        )
        stats?.filter { it.lastTimeUsed > 0L }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
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
                    avgDailyMinutes = avgDailyMinutes,
                    avgDailyOpens = avgDailyOpens,
                    isTrackable = isTrackable
                )
            }.sortedByDescending { it.avgDailyMinutes }
        }

    override suspend fun getLaunchableApps(): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    val label = runCatching { resolveInfo.loadLabel(packageManager).toString() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: pkg
                    InstalledAppInfo(packageName = pkg, appName = label)
                }
                // One entry per package even if it exposes multiple launcher activities.
                .distinctBy { it.packageName }
        }

    override suspend fun getNeverBlockablePackages(): Set<String> =
        withContext(Dispatchers.IO) {
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

            result
        }

    override suspend fun getUsageByPackage(days: Int): Map<String, Pair<Long, Int>> =
        withContext(Dispatchers.IO) {
            val endTime = System.currentTimeMillis()
            val startTime = Calendar.getInstance().apply {
                timeInMillis = endTime
                add(Calendar.DAY_OF_YEAR, -days)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val safeDays = days.coerceAtLeast(1)
            val timeByPackage = getUsageTimeByPackage(startTime, endTime)
            val opensByPackage = getOpenCountByPackage(startTime, endTime)

            (timeByPackage.keys + opensByPackage.keys).associateWith { pkg ->
                val avgMinutes = (timeByPackage[pkg] ?: 0L) / safeDays
                val avgOpens = (opensByPackage[pkg] ?: 0) / safeDays
                avgMinutes to avgOpens
            }
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
