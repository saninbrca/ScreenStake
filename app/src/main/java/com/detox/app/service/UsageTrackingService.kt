package com.detox.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.detox.app.data.remote.firebase.FirestoreService
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
import com.detox.app.util.DateUtils
import timber.log.Timber
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

    @Inject
    lateinit var firestoreService: FirestoreService

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
                // Include every package from multi-app challenges (skip partial-only challenges).
                // Filter blank strings — website-only challenges have no app package.
                val packages = challenges
                    .filter { !it.isPartialBlockOnly }
                    .flatMap { it.appPackageNames }
                    .filter { it.isNotBlank() }
                    .toSet()
                val domains = challenges.flatMap { it.blockedDomains }.toSet()

                Timber.d(
                    "UsageTrackingService: updating packages=${packages.size} " +
                        "domains=${domains.size} from ${challenges.size} challenges"
                )

                // Race condition guard: Room may emit an empty list before sync populates it
                // (process restart after Recents kill — bus is always empty then, so the old
                // busHasData check was always false and never protected anything).
                // Fix: check Room directly. Only skip if ALL derived lists are empty AND Room
                // still has active challenges. If Room is genuinely empty, allow the update.
                val allNewDataIsEmpty = packages.isEmpty() && domains.isEmpty()
                if (allNewDataIsEmpty) {
                    val roomActive = challengeRepository.getActiveChallengesList()
                        .getOrElse { emptyList() }
                    if (roomActive.isNotEmpty()) {
                        Timber.w(
                            "UsageTrackingService: skipping empty update — " +
                                "Room has ${roomActive.size} active challenges (race condition guard)"
                        )
                        return@collect
                    }
                }

                TrackedAppEventBus.updateTrackedPackages(packages)
                Timber.d("Updated tracked packages: $packages")

                TrackedAppEventBus.updateBlockedDomains(domains)
                Timber.d("Updated blocked domains: $domains")

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
        startGroupTimeLimitPolling()
        startOverlayPermissionMonitoring()
        startBudgetSessionPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recovery: handle sessions that expired while the service was dead (Huawei kills onDestroy)
        serviceScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences(OverlayManager.BUDGET_SESSION_PREFS_NAME, MODE_PRIVATE)
            val sessionEndTime = prefs.getLong(OverlayManager.BUDGET_SESSION_END_TIME_KEY, 0L)
            val now = System.currentTimeMillis()
            if (sessionEndTime > 0L && now >= sessionEndTime) {
                // Session expired while service was dead — run final accounting tick
                checkBudgetSession()
            }
            // Restart recovery for DAILY_BUDGET challenges: sync budget_committed_ms from Room
            // so the 10-second polling loop starts from the correct accumulated value.
            // Without this, a stale COMMITTED from a previous day (when the midnight reset never
            // fired because the service was killed) causes the next session to over-count usage.
            // Guard: only when no session is active. During an active session COMMITTED is already
            // correct relative to sessionStartTime; overwriting it here would double-count elapsed.
            if (sessionEndTime <= 0L) {
                val challenges = runCatching {
                    challengeRepository.getActiveChallengesList().getOrThrow()
                }.getOrElse { emptyList() }
                for (challenge in challenges) {
                    if (challenge.limitType != LimitType.TIME_BUDGET) continue
                    val key = todayKey()
                    Timber.d("restart recovery: reading Room with todayKey=$key for ${challenge.id}")
                    val log = dailyLogRepository.getLogForDate(challenge.id, key).getOrNull()
                    Timber.d("restart recovery: got budgetUsedMs=${log?.budgetUsedMs} from Room for ${challenge.id}")
                    val usedMs = log?.budgetUsedMs ?: 0L
                    val committedKey = "budget_committed_ms_${challenge.id}"
                    prefs.edit().putLong(committedKey, usedMs).apply()
                    Timber.d(
                        "UsageTrackingService: restart recovery — COMMITTED[$committedKey] " +
                            "synced to ${usedMs}ms from Room"
                    )
                }
            }
            // If sessionEndTime > now → session still active → polling loop handles periodic writes
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.stopListening()
        serviceScope.cancel()
        Timber.d("UsageTrackingService destroyed")
    }

    // ── DAILY_BUDGET session tracking ─────────────────────────────────────────

    private fun startBudgetSessionPolling() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000L)
                checkBudgetSession()
            }
        }
    }

    private suspend fun checkBudgetSession() {
        val prefs = getSharedPreferences(OverlayManager.BUDGET_SESSION_PREFS_NAME, MODE_PRIVATE)
        val sessionEndTime = prefs.getLong(OverlayManager.BUDGET_SESSION_END_TIME_KEY, 0L)
        if (sessionEndTime <= 0L) return  // no active session

        val sessionStartTime = prefs.getLong(OverlayManager.BUDGET_SESSION_START_TIME_KEY, 0L)
        val challengeId = prefs.getString(OverlayManager.BUDGET_SESSION_CHALLENGE_KEY, null)
        val packageName  = prefs.getString(OverlayManager.BUDGET_SESSION_PACKAGE_KEY, null)
        if (challengeId == null || packageName == null) {
            clearBudgetSession(prefs)
            return
        }

        val challenges = runCatching {
            challengeRepository.getActiveChallengesList().getOrThrow()
        }.getOrElse { emptyList() }
        val challenge = challenges.firstOrNull { it.id == challengeId }
        if (challenge == null) {
            Timber.w("UsageTrackingService: budget session challenge $challengeId not found — clearing prefs")
            clearBudgetSession(prefs)
            return
        }

        val now = System.currentTimeMillis()
        val committedMs = prefs.getLong("budget_committed_ms_${challengeId}", 0L)
        val totalBudgetMs = (challenge.dailyBudgetMinutes ?: 0) * 60_000L
        val today = todayKey()

        // Cap elapsed to session end — prevents over-counting on post-expiry ticks
        val effectiveNow = minOf(now, sessionEndTime)
        val elapsedMs = (effectiveNow - sessionStartTime).coerceAtLeast(0L)
        val totalUsedMs = committedMs + elapsedMs
        val newRemainingMs = (totalBudgetMs - totalUsedMs).coerceAtLeast(0L)

        // Write to Room every tick — idempotent, survives Huawei kill
        dailyLogRepository.updateBudgetStateMs(challengeId, today, totalUsedMs, newRemainingMs)
            .onSuccess {
                Timber.d(
                    "UsageTrackingService: budget tick — challengeId=$challengeId " +
                        "totalUsedMs=$totalUsedMs remaining=$newRemainingMs"
                )
            }
            .onFailure { e -> Timber.e(e, "UsageTrackingService: failed to write budget tick for $challengeId") }

        // Mirror budgetUsedMs → participants array + Firestore dailyLogs for Group Challenge DAILY_BUDGET (fire-and-forget)
        challenge.groupChallengeId?.let { groupId ->
            val uid = firebaseAuthService.currentUserId()
            if (uid != null) {
                val usedMinutes = (totalUsedMs / 60_000L).toInt()
                serviceScope.launch {
                    groupChallengeRepository.updateParticipantTimeUsed(groupId, uid, usedMinutes)
                    Timber.d("UsageTrackingService: budget mirror — groupId=$groupId usedMinutes=$usedMinutes")
                }
                serviceScope.launch {
                    firestoreService.updateDailyLogBudget(uid, "group_$groupId", today, totalUsedMs, newRemainingMs)
                    Timber.d("UsageTrackingService: Group budget synced to Firestore — group_${groupId}_${today} usedMs=$totalUsedMs")
                }
            }
        }

        if (now >= sessionEndTime) {
            // Session done — persist committed total, then clear active session prefs
            prefs.edit()
                .putLong("budget_committed_ms_${challengeId}", totalUsedMs)
                .apply()
            clearBudgetSession(prefs)
            overlayManager.onBudgetSessionExpired(packageName)
        }
    }

    private fun clearBudgetSession(prefs: SharedPreferences) {
        prefs.edit()
            .putLong(OverlayManager.BUDGET_SESSION_END_TIME_KEY, 0L)
            .putLong(OverlayManager.BUDGET_SESSION_START_TIME_KEY, 0L)
            .remove(OverlayManager.BUDGET_SESSION_CHALLENGE_KEY)
            .remove(OverlayManager.BUDGET_SESSION_PACKAGE_KEY)
            .apply()
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
        val today = todayKey()

        for (challenge in challenges) {
            val groupId = challenge.groupChallengeId ?: continue

            // TIME_LIMIT is handled exclusively by startGroupTimeLimitPolling (10-second loop).
            // UsageStatsManager must NOT be used for TIME_LIMIT — it counts ALL app time
            // including overlay display time and time when the user is not in the app.
            if (challenge.limitType == LimitType.TIME) continue

            val timeMinutes: Int = if (challenge.limitType == LimitType.TIME_BUDGET) {
                val log = dailyLogRepository.getLogForDate(challenge.id, today).getOrNull()
                ((log?.budgetUsedMs ?: 0L) / 60_000L).toInt()
            } else {
                // SESSION_LIMIT: timeUsedMinutes is a secondary leaderboard sort key only.
                val packageName = challenge.appPackageName ?: continue
                runCatching { usageStatsRepository.getTodayUsageForApp(packageName) }
                    .getOrNull()?.minutes ?: continue
            }
            groupChallengeRepository.updateParticipantTimeUsed(
                groupId = groupId,
                userId = uid,
                timeUsedMinutes = timeMinutes
            )
            Timber.d("Leaderboard time updated: userId=$uid groupId=$groupId time=$timeMinutes")
        }
    }

    // ── GROUP TIME_LIMIT active-session polling (10-second) ───────────────────

    /**
     * Every 10 seconds: reads the time accumulated in [OverlayManager]'s SharedPreferences
     * for GROUP TIME_LIMIT challenges and mirrors the absolute total to the Firestore
     * participants array (arrayRemove+arrayUnion via [GroupChallengeFirestoreService]).
     *
     * This is the ONLY path that writes [Participant.timeUsedMinutes] for TIME_LIMIT group
     * challenges. [updateGroupChallengeStats] explicitly skips [LimitType.TIME].
     *
     * The timer in SharedPreferences only advances while:
     *   - the user is inside the blocked app (foreground), AND
     *   - no overlay is currently visible.
     * It is paused by [OverlayManager.pauseGroupTimeLimitTracking] and resumed by
     * [OverlayManager.startGroupTimeLimitTracking].
     */
    private fun startGroupTimeLimitPolling() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000L)
                updateGroupTimeLimitStats()
            }
        }
    }

    private suspend fun updateGroupTimeLimitStats() {
        val uid = firebaseAuthService.currentUserId() ?: return
        val prefs = getSharedPreferences(OverlayManager.GROUP_TIME_TRACKING_PREFS_NAME, MODE_PRIVATE)
        val challenges = runCatching {
            challengeRepository.getActiveChallengesList().getOrThrow()
        }.getOrElse { return }
        val today = todayKey()

        for (challenge in challenges) {
            if (challenge.limitType != LimitType.TIME) continue
            val groupId = challenge.groupChallengeId ?: continue

            // Stale-data guard: if the stored date doesn't match today (service survived midnight
            // without a reset, typical on Huawei), treat accumulated time as 0.
            val storedDate = prefs.getLong(
                "${OverlayManager.GROUP_TIME_DATE_KEY_PREFIX}${challenge.id}", 0L
            )
            val accumMs = if (storedDate == today) {
                prefs.getLong("${OverlayManager.GROUP_TIME_ACCUM_KEY_PREFIX}${challenge.id}", 0L)
            } else {
                0L
            }
            val startMs = if (storedDate == today) {
                prefs.getLong("${OverlayManager.GROUP_TIME_START_KEY_PREFIX}${challenge.id}", 0L)
            } else {
                0L
            }

            // Current session elapsed — only count if timer is actively running (startMs > 0)
            val currentMs = if (startMs > 0L) {
                (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val totalMs = accumMs + currentMs

            // Skip Firestore write if no data has been tracked yet
            if (totalMs == 0L) continue

            val totalMinutes = (totalMs / 60_000L).toInt()
            val limitMinutes = challenge.limitValueMinutes

            serviceScope.launch {
                groupChallengeRepository.updateParticipantTimeUsed(groupId, uid, totalMinutes)
                Timber.d(
                    "GroupTimer: WRITE timeUsedMinutes=$totalMinutes limit=$limitMinutes " +
                        "to Firestore (groupId=$groupId challengeId=${challenge.id})"
                )
            }
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
        val today = todayKey()

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

    private fun todayKey(): Long = DateUtils.todayKey()

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
