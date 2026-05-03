package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.repository.SyncRepository
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
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
            //    daily logs due to the Room foreign-key constraint on daily_logs.challengeId)
            val challenges = firestoreService.fetchActiveChallenges(userId)
            Timber.d("SyncRepository: fetched ${challenges.size} active challenges")
            challenges.forEach { challenge ->
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

            // 4. Fetch today's dailyLog entries from the flat Firestore collection and upsert
            //    consciousOpens + budgetUsedMs/budgetRemainingMs into Room — restores both
            //    session-limit and daily-budget state after reinstalls / service kills.
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val todayLogs = firestoreService.fetchTodayDailyLogs(userId, startOfToday)
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
        blockingType = blockingType.name.lowercase(),
        blockAdultContent = if (blockAdultContent) 1 else 0,
        scheduleStartTime = scheduleStartTime,
        scheduleEndTime = scheduleEndTime,
        activeDays = activeDays.joinToString(",").ifEmpty { null },
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
