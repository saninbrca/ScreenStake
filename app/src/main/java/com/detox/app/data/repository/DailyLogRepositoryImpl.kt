package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.repository.DailyLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyLogRepositoryImpl @Inject constructor(
    private val dailyLogDao: DailyLogDao,
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    @ApplicationScope private val appScope: CoroutineScope
) : DailyLogRepository {

    override suspend fun insertDailyLog(log: DailyLog): Result<Unit> {
        return try {
            dailyLogDao.insertDailyLog(log.toEntity())
            // Fire-and-forget Firestore sync
            appScope.launch {
                firebaseAuthService.currentUserId()?.let { uid ->
                    firestoreService.saveDailyLog(uid, log)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getLogForDate(challengeId: String, date: Long): Result<DailyLog?> {
        return try {
            Result.success(dailyLogDao.getLogForDate(challengeId, date)?.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getLogsForChallenge(challengeId: String): Flow<List<DailyLog>> {
        return dailyLogDao.getLogsForChallenge(challengeId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getConsciousOpens(challengeId: String, date: Long): Result<Int> {
        return try {
            Result.success(dailyLogDao.getConsciousOpens(challengeId, date))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upsertConsciousOpens(challengeId: String, date: Long, count: Int): Result<Unit> {
        return try {
            val existing = dailyLogDao.getLogForDate(challengeId, date)
            val updated = if (existing != null) {
                existing.copy(consciousOpens = count)
            } else {
                DailyLogEntity(
                    id = UUID.randomUUID().toString(),
                    challengeId = challengeId,
                    date = date,
                    totalMinutes = 0,
                    openCount = 0,
                    consciousOpens = count,
                    pointsEarned = 0,
                    limitExceeded = false,
                    moneyLostCents = 0
                )
            }
            dailyLogDao.insertDailyLog(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOverlayPausedMs(challengeId: String, date: Long): Result<Long> {
        return try {
            Result.success(dailyLogDao.getOverlayPausedMs(challengeId, date))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Atomically adds [additionalMs] to the overlay-paused counter for today.
     * If no DailyLog exists yet for the given challenge+date, creates a minimal placeholder row
     * so the UPDATE has something to target.
     */
    override suspend fun addOverlayPausedMs(challengeId: String, date: Long, additionalMs: Long): Result<Unit> {
        return try {
            val existing = dailyLogDao.getLogForDate(challengeId, date)
            if (existing != null) {
                dailyLogDao.addOverlayPausedMs(challengeId, date, additionalMs)
            } else {
                // Row doesn't exist yet — insert a placeholder so the UPDATE has a target.
                dailyLogDao.insertDailyLog(
                    DailyLogEntity(
                        id = UUID.randomUUID().toString(),
                        challengeId = challengeId,
                        date = date,
                        totalMinutes = 0,
                        openCount = 0,
                        consciousOpens = 0,
                        overlayPausedMs = additionalMs,
                        pointsEarned = 0,
                        limitExceeded = false,
                        moneyLostCents = 0
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getBudgetRemainingMinutes(challengeId: String, date: Long): Result<Int> {
        return try {
            Result.success(dailyLogDao.getBudgetRemainingMinutes(challengeId, date))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateBudgetState(
        challengeId: String,
        date: Long,
        used: Int,
        remaining: Int
    ): Result<Unit> {
        return try {
            val existing = dailyLogDao.getLogForDate(challengeId, date)
            if (existing != null) {
                dailyLogDao.updateBudgetState(challengeId, date, used, remaining)
            } else {
                // No row yet — create a placeholder carrying only the budget state.
                dailyLogDao.insertDailyLog(
                    DailyLogEntity(
                        id = UUID.randomUUID().toString(),
                        challengeId = challengeId,
                        date = date,
                        totalMinutes = 0,
                        openCount = 0,
                        budgetUsedMinutes = used,
                        budgetRemainingMinutes = remaining,
                        pointsEarned = 0,
                        limitExceeded = false,
                        moneyLostCents = 0
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun DailyLogEntity.toDomain(): DailyLog = DailyLog(
        id = id,
        challengeId = challengeId,
        date = date,
        totalMinutes = totalMinutes,
        openCount = openCount,
        consciousOpens = consciousOpens,
        overlayPausedMs = overlayPausedMs,
        budgetUsedMinutes = budgetUsedMinutes,
        budgetRemainingMinutes = budgetRemainingMinutes,
        pointsEarned = pointsEarned,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents
    )

    private fun DailyLog.toEntity(): DailyLogEntity = DailyLogEntity(
        id = id,
        challengeId = challengeId,
        date = date,
        totalMinutes = totalMinutes,
        openCount = openCount,
        consciousOpens = consciousOpens,
        overlayPausedMs = overlayPausedMs,
        budgetUsedMinutes = budgetUsedMinutes,
        budgetRemainingMinutes = budgetRemainingMinutes,
        pointsEarned = pointsEarned,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents
    )
}
