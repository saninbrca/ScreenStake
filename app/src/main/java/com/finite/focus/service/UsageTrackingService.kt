package com.finite.focus.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.finite.focus.R
import com.finite.focus.domain.model.GroupChallengeStatus
import com.finite.focus.domain.model.LimitType
import com.finite.focus.data.remote.firebase.FirebaseAuthService
import com.finite.focus.data.remote.firebase.FirestoreService
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.domain.repository.GroupChallengeRepository
import com.finite.focus.domain.repository.UsageStatsRepository
import com.finite.focus.domain.usecase.EndExpiredGroupChallengesUseCase
import com.finite.focus.domain.usecase.SettleEndedSoftChallengesUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.finite.focus.util.DateUtils
import com.finite.focus.util.resolveAppsDisplayName
import timber.log.Timber
import javax.inject.Inject

/**
 * Lets the context-only [UsageTrackingService.startIfActiveChallenge] resolve its own repository,
 * so a caller with no Hilt injection point of its own (notably [BootReceiver]) still goes through
 * the same gate instead of starting the service blind.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface StarterEntryPoint {
    fun challengeRepository(): ChallengeRepository
}

@AndroidEntryPoint
class UsageTrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "detox_tracking"
        private const val NOTIFICATION_ID = 1

        /**
         * Latch remembering that a foreground promotion was refused by the platform, so the next
         * moment a start is legal can retry it. Its own prefs file: the permission prefs are
         * `clear()`ed wholesale on restore, which would silently drop a pending retry.
         */
        private const val RETRY_PREFS = "detox_fgs_retry"
        private const val KEY_PENDING_START = "pendingForegroundStart"

        /**
         * Unconditional start. Only for call sites where an active challenge exists BY
         * CONSTRUCTION (the user just created one) — everywhere else use [startIfActiveChallenge].
         *
         * Since Android 12 the platform refuses a background foreground-service start unless an
         * exemption applies, and it can refuse on EITHER side: here at the caller, or inside the
         * service at `Service.startForeground()` (see [promoteToForeground]). Both are caught; a
         * refusal here means the service was never created, so the retry latch is all that is
         * needed.
         */
        fun start(context: Context) {
            val intent = Intent(context, UsageTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startGuarded(context, intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun startGuarded(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Timber.w(e, "UsageTrackingService: start refused while backgrounded — retry pending")
                markForegroundStartPending(context)
            }
        }

        /**
         * The guarded start: enforcement infrastructure only runs when there is something to
         * enforce. No active challenge ⇒ no tracked packages, no overlays, no group polling — the
         * service would idle in the foreground until the system killed it and then be recreated
         * (START_STICKY) into a background context where the promotion is illegal. That recreation
         * loop is the crash this gate removes at the source.
         *
         * Deliberately NOT gated on usage access: blocking is driven by the accessibility service
         * reading [TrackedAppEventBus], which only THIS service populates, and by [OverlayManager]
         * listening on this service's scope. `UsageStatsManager` feeds group leaderboard stats
         * alone. Refusing to start without `PACKAGE_USAGE_STATS` would leave a user with a live
         * challenge unblocked — enforcement must never get weaker.
         *
         * FAIL-OPEN on a Room read failure: an unreadable database means "unknown", never "nothing
         * to enforce". Only a definitively empty active list suppresses the start.
         */
        suspend fun startIfActiveChallenge(context: Context) {
            val repository = EntryPointAccessors
                .fromApplication(context.applicationContext, StarterEntryPoint::class.java)
                .challengeRepository()
            startIfActiveChallenge(context, repository)
        }

        suspend fun startIfActiveChallenge(
            context: Context,
            challengeRepository: ChallengeRepository,
        ) {
            val active = challengeRepository.getActiveChallengesList().getOrElse { e ->
                Timber.w(e, "UsageTrackingService: active-challenge read failed — starting anyway")
                start(context)
                return
            }
            if (active.isEmpty()) {
                Timber.d("UsageTrackingService: no active challenge — not starting")
                return
            }
            start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageTrackingService::class.java))
        }

        /** True when a promotion was refused and has not succeeded since. */
        fun isForegroundStartPending(context: Context): Boolean =
            retryPrefs(context).getBoolean(KEY_PENDING_START, false)

        private fun markForegroundStartPending(context: Context) {
            retryPrefs(context).edit().putBoolean(KEY_PENDING_START, true).apply()
        }

        private fun clearForegroundStartPending(context: Context) {
            if (isForegroundStartPending(context)) {
                retryPrefs(context).edit().putBoolean(KEY_PENDING_START, false).apply()
            }
        }

        private fun retryPrefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(RETRY_PREFS, Context.MODE_PRIVATE)
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

    @Inject
    lateinit var endExpiredGroupChallengesUseCase: EndExpiredGroupChallengesUseCase

    @Inject
    lateinit var settleEndedSoftChallengesUseCase: SettleEndedSoftChallengesUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Set once the foreground notification is actually held — see [promoteToForeground]. */
    private var isPromoted = false

    override fun onCreate() {
        super.onCreate()
        Timber.d("UsageTrackingService created")
        createNotificationChannel()
        // The foreground promotion deliberately does NOT live here: onCreate cannot tell an
        // explicit start from a system-initiated START_STICKY recreation, and the latter always
        // runs in a background context where the promotion is illegal on Android 12+. It moved to
        // onStartCommand, which knows the start reason. See [promoteToForeground].

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

                // Build per-package schedule map so AccessibilityService can gate overlays.
                // groupBy, NOT toMap(): two challenges can track the same app, and on a duplicate
                // key toMap() kept only the last one — silently dropping the other challenge's
                // window, and disabling the gate outright whenever the survivor had no schedule.
                val scheduleMap = challenges.flatMap { challenge ->
                    challenge.appPackageNames.map { pkg ->
                        pkg to TrackedAppEventBus.ScheduleInfo(
                            scheduleStartTime = challenge.scheduleStartTime,
                            scheduleEndTime = challenge.scheduleEndTime,
                            activeDays = challenge.activeDays,
                            // Carried so the gate knows which way the window points: TIME_WINDOW
                            // means "only ALLOW inside", every other type means "enforce inside".
                            limitType = challenge.limitType
                        )
                    }
                }.groupBy({ it.first }, { it.second })
                TrackedAppEventBus.updatePackageSchedules(scheduleMap)
            }
        }

        overlayManager.startListening(serviceScope)
        overlayManager.startTauntListening(serviceScope)
        startGroupChallengeStatsPolling()
        startGroupSessionLimitTracking()
        startGroupTimeLimitPolling()
        startOverlayPermissionMonitoring()
        startBudgetSessionPolling()
        startExpiredChallengeSweep()
    }

    /**
     * Ends enforcement for challenges past their end date — GROUP rows and SOFT SOLO rows —
     * on-device and offline.
     *
     * [OverlayManager.handleAppOpen] runs the same two sweeps, which covers the app-open case; this
     * loop covers everything that does NOT go through it — the tracked-package/domain sets pushed to
     * [TrackedAppEventBus] (website + adult blocking), the group stat pollers, and simply crossing
     * midnight while the phone sits idle. Cheap: one indexed Room read each, and a write only on the
     * one tick where a challenge actually rolls over.
     *
     * Neither call can reach Stripe. The group one settles nothing at all (see
     * [com.finite.focus.domain.usecase.EndExpiredGroupChallengesUseCase]); the Soft one settles a
     * money-free Soft SOLO challenge and filters Hard and group rows out itself. Hard solo is
     * deliberately NOT covered here — it needs a mechanism that leaves `status = 'active'` intact,
     * because [DailyEvaluationWorker] finds its capture/refund work by that very status.
     */
    private fun startExpiredChallengeSweep() {
        serviceScope.launch(Dispatchers.IO) {
            sweepExpiredChallenges()
            while (isActive) {
                delay(60_000L)
                sweepExpiredChallenges()
            }
        }
    }

    private suspend fun sweepExpiredChallenges() {
        endExpiredGroupChallengesUseCase()
        // ENFORCEMENT_STOP, never the default: the settlement predicate (`>=`) fires ON the final
        // day, which on a 60 s loop means freeing the app at 00:00 of a day the user still has to
        // get through.
        settleEndedSoftChallengesUseCase(
            SettleEndedSoftChallengesUseCase.Trigger.ENFORCEMENT_STOP
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the SYSTEM recreated us after the process was killed (START_STICKY),
        // not an explicit start. The app is backgrounded by definition on that path, so the
        // promotion below is the one the platform is most likely to refuse — it must never be able
        // to take the process down with it.
        val systemRestart = intent == null
        promoteToForeground(systemRestart)

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
        // START_STICKY stays. Enforcement lives entirely in this process: the tracked-package and
        // blocked-domain sets in [TrackedAppEventBus] have no other writer at rest, and
        // [OverlayManager] listens on this service's scope — so a killed, never-restarted service
        // means a blocked app opens freely until the user next opens Finite. START_NOT_STICKY would
        // trade this crash for silently absent enforcement, which is the one trade this fix may not
        // make. The recreation path is now safe rather than avoided: the promotion is guarded, and
        // the call-site gate means a user with nothing to enforce never starts the service in the
        // first place, so the system has nothing to recreate.
        return START_STICKY
    }

    /**
     * Promotes to the foreground, absorbing the platform's refusal.
     *
     * Since Android 12 a background foreground-service start throws unless an exemption applies.
     * Catching is safe: `ActiveServices.setServiceForegroundInnerLocked` clears `fgRequired` and
     * cancels the "must call startForeground within 5s" timer BEFORE every throw in that method
     * (`removeMessages(SERVICE_FOREGROUND_TIMEOUT_MSG)` on API 31-34, `mServiceFGAnrTimer.cancel`
     * on 35-36), so no `RemoteServiceException` can follow. Verified against AOSP for API 31-36.
     *
     * What survives a refusal is NARROW, and must not be mistaken for working enforcement: the
     * service continues as a plain BACKGROUND service, which `ActiveServices.stopInBackgroundLocked`
     * ("Stopping service due to app idle") reclaims once the uid goes background-idle — roughly
     * 60s, per `ActivityManagerConstants.DEFAULT_BACKGROUND_SETTLE_TIME` — and the process is an
     * LMK candidate before that. So this buys about a minute, not durable enforcement.
     *
     * It is still not stopped here, because that minute is free and the alternative was a dead
     * process. But the RETRY is what actually restores enforcement, not this survival window — see
     * [MainActivity.onResume] (always legal) and [PermissionCheckWorker] (API 31-34 only; see its
     * retry comment for why 35+ is refused).
     */
    private fun promoteToForeground(systemRestart: Boolean) {
        if (isPromoted) return
        isPromoted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startForegroundGuarded(systemRestart)
        } else {
            startForegroundNow()
            true
        }
        if (isPromoted) clearForegroundStartPending(applicationContext)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startForegroundGuarded(systemRestart: Boolean): Boolean = try {
        startForegroundNow()
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Timber.w(
            e,
            "UsageTrackingService: foreground promotion refused (systemRestart=%b) — " +
                "continuing unpromoted, retry latched",
            systemRestart
        )
        Sentry.addBreadcrumb(Breadcrumb().apply {
            category = "UsageTrackingService"
            message = "Foreground promotion refused"
            level = SentryLevel.WARNING
            setData("systemRestart", systemRestart)
        })
        markForegroundStartPending(applicationContext)
        false
    }

    private fun startForegroundNow() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
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

        // Day-key guard, deliberately ABOVE the no-active-session early return. This is the single
        // accumulation path — the committed read below adds today's elapsed on top of whatever is
        // stored — so the reset has to happen before it, on every tick, whether or not a session is
        // running. Running it here (not only when a session is active) also means the 10s loop
        // clears a stale accumulator within seconds of midnight, so the dashboard read in
        // GetDailyStatsUseCase never shows yesterday's spend either.
        OverlayManager.ensureCommittedBudgetFresh(prefs)

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

        // 80 % approach warning. This tick is the ONLY place holding a live TIME_BUDGET figure:
        // the overlay gate's CheckDailyLimitUseCase deliberately reports the FULL budget as
        // remaining for this limit type, so the warning could never cross 80 % over there.
        // Toggle gate, threshold and once-per-day dedup all live in the helper, so firing this on
        // every 10 s tick still yields at most one notification per challenge per day.
        NotificationHelper.maybeSendUsage80Percent(
            context = applicationContext,
            challengeId = challenge.id,
            appName = challenge.resolveAppsDisplayName(applicationContext),
            used = totalUsedMs,
            limit = totalBudgetMs
        )

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
                Timber.d("Accessibility lost — timer started")
            }
        } else {
            if (prefs.contains("accessibilityLostAt")) {
                prefs.edit().clear().apply()
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
