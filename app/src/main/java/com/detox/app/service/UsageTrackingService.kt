package com.detox.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.R
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.ThresholdFlags
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

private data class ThresholdSpec(val percent: Int, val fraction: Float)

private val USAGE_THRESHOLDS = listOf(
    ThresholdSpec(50, 0.50f),
    ThresholdSpec(75, 0.75f),
    ThresholdSpec(90, 0.90f),
)

@AndroidEntryPoint
class UsageTrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "detox_tracking"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, UsageTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageTrackingService::class.java))
        }
    }

    @Inject
    lateinit var challengeRepository: ChallengeRepository

    @Inject
    lateinit var overlayManager: OverlayManager

    @Inject
    lateinit var usageStatsRepository: UsageStatsRepository

    @Inject
    lateinit var dailyLogRepository: DailyLogRepository

    @Inject
    lateinit var groupChallengeRepository: GroupChallengeRepository

    @Inject
    lateinit var firebaseAuthService: FirebaseAuthService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Timber.d("UsageTrackingService created")
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        serviceScope.launch {
            challengeRepository.getActiveChallenges().collect { challenges ->
                // Include every package from multi-app challenges
                val packageNames = challenges.flatMap { it.appPackageNames }.toSet()
                TrackedAppEventBus.updateTrackedPackages(packageNames)
                Timber.d("Updated tracked packages: $packageNames")

                // Aggregate blocked domains across all active challenges (for the event bus only)
                val blockedDomains = challenges.flatMap { it.blockedDomains }.toSet()
                TrackedAppEventBus.updateBlockedDomains(blockedDomains)
                Timber.d("Updated blocked domains: $blockedDomains")

                // Aggregate partial-block URL path prefixes for feature-level blocking
                val partialBlockDomains = challenges.flatMap { it.partialBlockDomains }.toSet()
                TrackedAppEventBus.updatePartialBlockDomains(partialBlockDomains)
                Timber.d("Updated partial block paths: $partialBlockDomains")

                // Notify AccessibilityService whether adult content blocking is needed.
                // It reads this in-memory flag on every browser URL event — no Room query.
                val adultBlockingActive = challenges.any { it.blockAdultContent }
                TrackedAppEventBus.updateAdultBlockingActive(adultBlockingActive)

                // Build per-package schedule map so AccessibilityService can gate overlays
                val scheduleMap = challenges.flatMap { challenge ->
                    challenge.appPackageNames.map { pkg ->
                        pkg to TrackedAppEventBus.ScheduleInfo(
                            scheduleStartTime = challenge.scheduleStartTime,
                            scheduleEndTime = challenge.scheduleEndTime,
                            activeDays = challenge.activeDays
                        )
                    }
                }.toMap()
                TrackedAppEventBus.updatePackageSchedules(scheduleMap)
            }
        }

        overlayManager.startListening(serviceScope)
        overlayManager.startTauntListening(serviceScope)
        startUsagePolling()
        startGroupChallengeStatsPolling()
        startGroupSessionLimitTracking()
        startOverlayPermissionMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.stopListening()
        serviceScope.cancel()
        Timber.d("UsageTrackingService destroyed")
    }

    // ── Overlay permission monitoring ─────────────────────────────────────────

    private fun startOverlayPermissionMonitoring() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000L)
                checkOverlayPermission()
                checkAccessibilityPermission()
            }
        }
    }

    private suspend fun checkAccessibilityPermission() {
        val accessibilityEnabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true

        Timber.d("Accessibility check: enabled=$accessibilityEnabled")

        val prefs = getSharedPreferences("detox_accessibility", MODE_PRIVATE)

        if (!accessibilityEnabled) {
            val hasActive = runCatching {
                challengeRepository.getActiveChallengesList().getOrThrow().isNotEmpty()
            }.getOrElse { false }
            if (hasActive && !prefs.contains("accessibilityLostAt")) {
                prefs.edit().putLong("accessibilityLostAt", System.currentTimeMillis()).apply()
                NotificationHelper.createChannels(applicationContext)
                NotificationHelper.sendAccessibilityLost(applicationContext)
                Timber.d("Accessibility lost — notification sent, timer started")
            }
        } else {
            if (prefs.contains("accessibilityLostAt")) {
                prefs.edit().clear().apply()
                NotificationHelper.createChannels(applicationContext)
                NotificationHelper.sendAccessibilityRestored(applicationContext)
                Timber.d("Accessibility restored — cleared timer")
            }
        }
    }

    private fun checkOverlayPermission() {
        val prefs = getSharedPreferences("detox_permission", MODE_PRIVATE)
        if (!Settings.canDrawOverlays(this)) {
            if (!prefs.contains("permissionLostAt")) {
                Timber.d("Permission lost at ${System.currentTimeMillis()}")
                prefs.edit()
                    .putLong("permissionLostAt", System.currentTimeMillis())
                    .putInt("userOpenedAndIgnored", 0)
                    .apply()
                schedulePermissionWarnings()
            }
        } else {
            if (prefs.contains("permissionLostAt")) {
                prefs.edit().clear().apply()
                cancelPermissionWarnings()
                NotificationHelper.sendPermissionRestored(applicationContext)
            }
        }
    }

    private fun schedulePermissionWarnings() {
        val wm = WorkManager.getInstance(applicationContext)
        val schedule = listOf(0L to 0, 2L to 1, 6L to 2, 12L to 3)
        for ((delayHours, level) in schedule) {
            val tag = "permission_warning_$delayHours"
            val request = OneTimeWorkRequestBuilder<PermissionWarningWorker>()
                .setInitialDelay(delayHours, java.util.concurrent.TimeUnit.HOURS)
                .setInputData(Data.Builder().putInt(PermissionWarningWorker.KEY_LEVEL, level).build())
                .addTag(tag)
                .build()
            wm.enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
            Timber.d("Warning scheduled: level=$level")
        }
    }

    private fun cancelPermissionWarnings() {
        val wm = WorkManager.getInstance(applicationContext)
        listOf("permission_warning_0", "permission_warning_2", "permission_warning_6", "permission_warning_12")
            .forEach { tag ->
                wm.cancelAllWorkByTag(tag)
            }
        Timber.d("Warnings cancelled: permission restored")
    }

    // ── Group challenge leaderboard polling (60-second) ───────────────────────

    private fun startGroupChallengeStatsPolling() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000L)
                updateGroupChallengeStats()
            }
        }
    }

    /**
     * Observes active group challenges and keeps [TrackedAppEventBus.groupSessionInfos] in sync
     * with each participant's current [Participant.opensToday] from Firestore.
     * This gives [AppDetectionAccessibilityService] a synchronous, DB-free path to check whether
     * the session limit is already reached before emitting the app-open event.
     */
    private fun startGroupSessionLimitTracking() {
        val uid = firebaseAuthService.currentUserId() ?: return
        serviceScope.launch {
            groupChallengeRepository.getGroupChallenges().collect { groupChallenges ->
                val infos = groupChallenges
                    .filter { gc ->
                        gc.status == GroupChallengeStatus.ACTIVE &&
                            gc.limitType == LimitType.SESSIONS &&
                            (gc.limitValueSessions ?: 0) > 0
                    }
                    .flatMap { gc ->
                        val opensToday = gc.participants.find { it.userId == uid }?.opensToday ?: 0
                        val limit = gc.limitValueSessions!!
                        gc.appPackageNames.map { pkg ->
                            pkg to TrackedAppEventBus.GroupSessionInfo(opensToday, limit)
                        }
                    }
                    .toMap()
                TrackedAppEventBus.updateGroupSessionInfos(infos)
                Timber.d("GroupSessionInfos updated: $infos")
            }
        }
    }

    private suspend fun updateGroupChallengeStats() {
        val uid = firebaseAuthService.currentUserId() ?: return
        val challenges = runCatching {
            challengeRepository.getActiveChallengesList().getOrThrow()
        }.getOrElse { return }

        for (challenge in challenges) {
            val groupId = challenge.groupChallengeId ?: continue
            val packageName = challenge.appPackageName ?: continue
            val usage = runCatching {
                usageStatsRepository.getTodayUsageForApp(packageName)
            }.getOrNull() ?: continue

            groupChallengeRepository.updateParticipantTimeUsed(
                groupId = groupId,
                userId = uid,
                timeUsedMinutes = usage.minutes
            )
            Timber.d("Leaderboard time updated: userId=$uid time=${usage.minutes}")
        }
    }

    // ── Threshold usage polling ────────────────────────────────────────────────

    /**
     * Polls every 15 minutes to check whether any active TIME / TIME_BUDGET /
     * SESSIONS challenge has crossed the 50 %, 75 %, or 90 % usage mark.
     *
     * "Fire once per day per threshold" is guaranteed by persisting the seen
     * flags in [daily_logs] (notified50 / notified75 / notified90).  Because
     * the flags are keyed by challengeId + calendar-day midnight timestamp they
     * reset automatically the next day — no midnight coroutine needed.
     *
     * 15-minute granularity: fine enough that the user gets the 90 % warning
     * before they hit 100 %, coarse enough to avoid excessive wakeups.
     */
    private fun startUsagePolling() {
        serviceScope.launch(Dispatchers.IO) {
            Timber.d("UsageTrackingService: threshold polling started (15-min interval, thresholds: 50/75/90%%)")
            while (isActive) {
                delay(15 * 60 * 1_000L) // 15 minutes
                checkUsageThresholds()
            }
        }
    }

    private suspend fun checkUsageThresholds() {
        val today = todayMidnightMs()

        val challenges = runCatching {
            challengeRepository.getActiveChallengesList().getOrThrow()
        }.getOrElse { e ->
            Timber.w(e, "UsageTrackingService: could not load challenges for threshold check")
            return
        }

        for (challenge in challenges) {
            val appPkg = challenge.appPackageName ?: continue  // WEBSITE-only challenges have no package

            val usage = runCatching {
                usageStatsRepository.getTodayUsageForApp(appPkg)
            }.onFailure { e ->
                Timber.w(e, "UsageTrackingService: could not get usage for $appPkg")
            }.getOrNull() ?: continue

            val usedFraction = when (challenge.limitType) {
                LimitType.TIME -> {
                    if (challenge.limitValueMinutes > 0)
                        usage.minutes.toFloat() / challenge.limitValueMinutes
                    else 0f
                }
                LimitType.SESSIONS -> {
                    val maxSessions = challenge.limitValueSessions ?: 0
                    if (maxSessions > 0) usage.opens.toFloat() / maxSessions else 0f
                }
                LimitType.TIME_BUDGET -> {
                    val budget = challenge.dailyBudgetMinutes ?: 0
                    if (budget > 0) usage.minutes.toFloat() / budget else 0f
                }
                // TIME_WINDOW has no usage cap — thresholds never apply
                LimitType.TIME_WINDOW -> 0f
            }

            if (usedFraction <= 0f) continue

            Timber.d(
                "UsageTrackingService: ${challenge.appDisplayName} " +
                        "usage = ${"%.0f".format(usedFraction * 100)}%%"
            )

            // Read today's notification flags from DailyLog (persisted across restarts)
            val flags = runCatching {
                dailyLogRepository.getThresholdFlags(challenge.id, today).getOrThrow()
            }.getOrElse { ThresholdFlags() }

            // Check each threshold in ascending order so we post at most one new
            // notification per poll cycle per challenge (the lowest unnotified one).
            for ((percent, triggerFraction) in USAGE_THRESHOLDS) {
                val alreadyNotified = when (percent) {
                    50 -> flags.notified50
                    75 -> flags.notified75
                    else -> flags.notified90
                }
                if (!alreadyNotified && usedFraction >= triggerFraction) {
                    Timber.d(
                        "UsageTrackingService: $percent%% threshold reached for " +
                                "${challenge.appDisplayName} — posting notification"
                    )
                    NotificationHelper.createChannels(applicationContext)
                    NotificationHelper.sendUsageThreshold(
                        applicationContext, challenge.appDisplayName, percent
                    )
                    dailyLogRepository.markThresholdNotified(challenge.id, today, percent)
                    // Update local copy of flags so subsequent thresholds in THIS loop
                    // iteration use the freshly updated state (avoids double-posting
                    // 75 % immediately after 50 % in the same poll cycle).
                    break
                }
            }
        }
    }

    /** Returns the Unix timestamp for today's local midnight (00:00:00.000). */
    private fun todayMidnightMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
