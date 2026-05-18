package com.detox.app.data.repository

import android.content.Context
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.repository.SyncRepository
import com.detox.app.service.OverlayManager
import com.detox.app.service.TrackedAppEventBus
import com.detox.app.util.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    private val challengeDao: ChallengeDao,
    private val dailyLogDao: DailyLogDao,
    private val groupChallengeRepository: GroupChallengeRepository,
) : SyncRepository {

    override suspend fun syncUserData(): Result<Unit> {
        return try {
            val userId = firebaseAuthService.currentUserId()
                ?: return Result.failure(IllegalStateException("User not signed in"))

            Timber.d("SyncRepository: starting sync for uid=$userId")

            // 1. Fetch and upsert active challenges (challenges must be inserted before
            //    daily logs due to the Room foreign-key constraint on daily_logs.challengeId).
            // Guard: skip overwriting a locally-failed challenge with Firestore "active" data.
            // Firestore rules block client status updates, so a failed challenge may temporarily
            // still be "active" in Firestore if the async delete hasn't completed yet.
            val challenges = firestoreService.fetchActiveChallenges(userId)
            Timber.d("SyncRepository: fetched ${challenges.size} active challenges")
            challenges.forEach { challenge ->
                val existing = challengeDao.getChallengeById(challenge.id)
                if (existing != null && existing.status != "active") {
                    Timber.w(
                        "SyncRepository: skipping ghost challenge %s — local status=%s, Firestore=active",
                        challenge.id, existing.status
                    )
                    return@forEach
                }
                challengeDao.insertChallenge(challenge.toEntity())
            }

            // 2. Fetch and upsert daily logs for each challenge
            challenges.forEach { challenge ->
                val logs = firestoreService.fetchDailyLogs(userId, challenge.id)
                Timber.d(
                    "SyncRepository: fetched ${logs.size} daily logs for " +
                            "challenge=${challenge.id}"
                )
                logs.forEach { log -> dailyLogDao.insertDailyLog(log.toEntity()) }
            }

            // 3. Sync group challenge statuses from Firestore → Room so stale 'active' records
            //    (from cancelled/completed group challenges) don't block app selection.
            groupChallengeRepository.refreshFromFirestore(userId)
                .onFailure { e -> Timber.w(e, "SyncRepository: group challenge status sync failed") }

            // 3a. Restore Group Challenge DAILY_BUDGET budgets from Firestore → Room.
            //     Group challenges have no entries in the nested Solo dailyLogs sub-collection
            //     (step 2), so their budgetUsedMs must be restored explicitly here.
            //     Runs AFTER refreshFromFirestore so ChallengeEntity rows exist in Room.
            val todayKey = DateUtils.todayKey()
            val budgetPrefs = context.getSharedPreferences(
                OverlayManager.BUDGET_SESSION_PREFS_NAME, Context.MODE_PRIVATE
            )
            val groupChallenges = groupChallengeRepository.getGroupChallenges().first()
            for (gc in groupChallenges) {
                if (gc.status != GroupChallengeStatus.ACTIVE) continue
                if (gc.limitType != LimitType.TIME_BUDGET) continue
                val localChallengeId = "group_${gc.groupId}"
                Timber.d("Group restore: reading Firestore path = users/$userId/dailyLogs/${localChallengeId}_${todayKey}")
                val data = firestoreService.fetchDailyLogDocument(userId, localChallengeId, todayKey)
                val budgetUsedMs = data?.get("budgetUsedMs") as? Long ?: 0L
                val totalBudgetMs = gc.limitValueMinutes * 60_000L
                val budgetRemainingMs = (data?.get("budgetRemainingMs") as? Long)
                    ?: (totalBudgetMs - budgetUsedMs).coerceAtLeast(0L)
                Timber.d("Group restore: Firestore budgetUsedMs=$budgetUsedMs budgetRemainingMs=$budgetRemainingMs")
                val existing = dailyLogDao.getLogForDate(localChallengeId, todayKey)
                if (existing != null) {
                    dailyLogDao.insertDailyLog(
                        existing.copy(budgetUsedMs = budgetUsedMs, budgetRemainingMs = budgetRemainingMs)
                    )
                } else {
                    dailyLogDao.insertDailyLog(
                        DailyLogEntity(
                            id = "${localChallengeId}_${todayKey}",
                            challengeId = localChallengeId,
                            date = todayKey,
                            totalMinutes = 0,
                            openCount = 0,
                            pointsEarned = 0,
                            limitExceeded = false,
                            moneyLostCents = 0,
                            budgetUsedMs = budgetUsedMs,
                            budgetRemainingMs = budgetRemainingMs
                        )
                    )
                }
                val roomCheck = dailyLogDao.getLogForDate(localChallengeId, todayKey)
                Timber.d("Group restore: Room after write = ${roomCheck?.budgetUsedMs} used, ${roomCheck?.budgetRemainingMs} remaining")
                // Set per-challenge SharedPreferences key so UsageTrackingService.onStartCommand()
                // restart-recovery reads the correct committed value for THIS challenge specifically.
                // Guard: skip when a session is already active — COMMITTED is already correct then.
                val activeSessionEnd = budgetPrefs.getLong(OverlayManager.BUDGET_SESSION_END_TIME_KEY, 0L)
                if (activeSessionEnd <= 0L) {
                    val prefsKey = "budget_committed_ms_${localChallengeId}"
                    budgetPrefs.edit().putLong(prefsKey, budgetUsedMs).apply()
                    Timber.d("Group restore: SharedPreferences[$prefsKey] set to $budgetUsedMs")
                }
                Timber.d("Group budget restore: groupId=${gc.groupId} usedMs=$budgetUsedMs remainingMs=$budgetRemainingMs")
            }

            // 4. Fetch today's dailyLog entries from the flat Firestore collection and upsert
            //    consciousOpens + budgetUsedMs/budgetRemainingMs into Room — restores both
            //    session-limit and daily-budget state after reinstalls / service kills.
            val startOfToday = DateUtils.todayKey()
            val todayLogs = firestoreService.fetchTodayDailyLogs(userId, startOfToday)
            Timber.d("Sync: about to write ${todayLogs.size} DailyLogs to Room")
            Timber.d("Sync: DailyLog keys being written: ${todayLogs.mapNotNull { it["challengeId"] as? String }}")
            todayLogs.forEach { data ->
                val challengeId = data["challengeId"] as? String ?: return@forEach
                val date = data["date"] as? Long ?: return@forEach
                // consciousOpens is absent for TIME_BUDGET entries — use null, not return@forEach
                val opens = (data["consciousOpens"] as? Long)?.toInt()
                val budgetUsedMs = data["budgetUsedMs"] as? Long
                val budgetRemainingMs = data["budgetRemainingMs"] as? Long

                val existing = dailyLogDao.getLogForDate(challengeId, date)
                if (existing != null) {
                    dailyLogDao.insertDailyLog(
                        existing.copy(
                            consciousOpens = opens ?: existing.consciousOpens,
                            budgetUsedMs = budgetUsedMs ?: existing.budgetUsedMs,
                            budgetRemainingMs = budgetRemainingMs ?: existing.budgetRemainingMs
                        )
                    )
                } else {
                    dailyLogDao.insertDailyLog(
                        DailyLogEntity(
                            id = "${challengeId}_${date}",
                            challengeId = challengeId,
                            date = date,
                            totalMinutes = 0,
                            openCount = 0,
                            consciousOpens = opens ?: 0,
                            budgetUsedMs = budgetUsedMs ?: 0L,
                            budgetRemainingMs = budgetRemainingMs ?: 0L,
                            pointsEarned = 0,
                            limitExceeded = false,
                            moneyLostCents = 0
                        )
                    )
                }
                if (opens != null) Timber.d("DailyLog synced from Firestore: opens=$opens for $challengeId")
                if (budgetUsedMs != null) Timber.d("Budget restored from Firestore: ${budgetUsedMs}ms used for $challengeId")
            }

            // Part B: after Room is fully populated, push correct data to TrackedAppEventBus.
            // The UsageTrackingService Flow re-emission may arrive late (or be suppressed by
            // the race-condition guard) if Room was empty when the service first subscribed.
            // This explicit push guarantees the bus reflects current Room state immediately
            // after sync — critical for Huawei where service and sync race on every restart.
            try {
                val synced = challengeDao.getActiveChallengesList()
                if (synced.isNotEmpty()) {
                    val pkgs = synced
                        .filter { it.isPartialBlockOnly == 0 }
                        .flatMap { e ->
                            e.appPackageNames
                                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                                ?: if (e.appPackageName.isNotBlank()) listOf(e.appPackageName) else emptyList()
                        }
                        .toSet()
                    val doms = synced
                        .flatMap { e ->
                            e.blockedDomains
                                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                                ?: emptyList()
                        }
                        .toSet()
                    val paths = synced
                        .flatMap { e ->
                            e.partialBlockDomains
                                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                                ?: emptyList()
                        }
                        .toSet()
                    val sections = synced
                        .flatMap { e ->
                            e.partialBlockSections
                                .split(",").filter { it.isNotBlank() }
                                .mapNotNull { PartialBlockSection.fromId(it.trim()) }
                        }
                        .distinct()
                    TrackedAppEventBus.updateTrackedPackages(pkgs)
                    TrackedAppEventBus.updateBlockedDomains(doms)
                    TrackedAppEventBus.updatePartialBlockDomains(paths)
                    TrackedAppEventBus.updateActivePartialBlockSections(sections)
                    Timber.d(
                        "SyncRepository: post-sync bus update — " +
                            "packages=${pkgs.size} domains=${doms.size} from ${synced.size} challenges"
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "SyncRepository: post-sync bus update failed (non-fatal)")
            }

            Timber.d("SyncRepository: sync completed")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "SyncRepository: sync failed")
            Result.failure(e)
        }
    }

    // ── Entity mappers ─────────────────────────────────────────────────────────

    private fun Challenge.toEntity() = ChallengeEntity(
        id = id,
        appPackageName = appPackageNames.firstOrNull() ?: "",
        appDisplayName = appDisplayName,
        mode = mode.name.lowercase(),
        limitType = limitType.name.lowercase(),
        limitValueMinutes = limitValueMinutes,
        limitValueSessions = limitValueSessions,
        startDate = startDate,
        endDate = endDate,
        amountCents = amountCents,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = customMotivation,
        status = status.name.lowercase(),
        createdAt = createdAt,
        dailyBudgetMinutes = dailyBudgetMinutes,
        appPackageNames = appPackageNames.joinToString(",").ifEmpty { null },
        blockedDomains = blockedDomains.joinToString(",").ifEmpty { null },
        partialBlockDomains = partialBlockDomains.joinToString(",").ifEmpty { null },
        blockingType = blockingType.name.lowercase(),
        blockAdultContent = if (blockAdultContent) 1 else 0,
        scheduleStartTime = scheduleStartTime,
        scheduleEndTime = scheduleEndTime,
        activeDays = activeDays.joinToString(",").ifEmpty { null },
        partialBlockSections = partialBlockSections.joinToString(",") { it.id },
        isPartialBlockOnly = if (isPartialBlockOnly) 1 else 0,
    )

    private fun DailyLog.toEntity() = DailyLogEntity(
        id = id,
        challengeId = challengeId,
        date = date,
        totalMinutes = totalMinutes,
        openCount = openCount,
        consciousOpens = consciousOpens,
        overlayPausedMs = overlayPausedMs,
        budgetUsedMinutes = budgetUsedMinutes,
        budgetRemainingMinutes = budgetRemainingMinutes,
        pointsEarned = 0,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents
    )
}
