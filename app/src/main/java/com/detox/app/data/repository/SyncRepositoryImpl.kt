package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.PointTransactionDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.local.db.entity.PointTransactionEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.PointTransaction
import com.detox.app.domain.repository.SyncRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    private val challengeDao: ChallengeDao,
    private val dailyLogDao: DailyLogDao,
    private val pointTransactionDao: PointTransactionDao
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

            // 3. Fetch and upsert point transactions
            val transactions = firestoreService.fetchPointTransactions(userId)
            Timber.d("SyncRepository: fetched ${transactions.size} point transactions")
            transactions.forEach { tx -> pointTransactionDao.insertTransaction(tx.toEntity()) }

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
        appPackageName = appPackageName,
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
        createdAt = createdAt
    )

    private fun DailyLog.toEntity() = DailyLogEntity(
        id = id,
        challengeId = challengeId,
        date = date,
        totalMinutes = totalMinutes,
        openCount = openCount,
        consciousOpens = consciousOpens,
        overlayPausedMs = overlayPausedMs,
        pointsEarned = pointsEarned,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents
    )

    private fun PointTransaction.toEntity() = PointTransactionEntity(
        id = id,
        type = type,
        amount = amount,
        reason = reason,
        challengeId = challengeId,
        timestamp = timestamp
    )
}
