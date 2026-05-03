package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.ThresholdFlags
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

    override fun observeLogForDate(challengeId: String, date: Long): Flow<DailyLog?> =
        dailyLogDao.observeLogForDate(challengeId, date).map { it?.toDomain() }

    override fun getLogsForChallenge(challengeId: String): Flow<List<DailyLog>> {
        return dailyLogDao.getLogsForChallenge(challengeId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeLogsForDate(date: Long): Flow<List<DailyLog>> =
        dailyLogDao.observeLogsForDate(date).map { entities -> entities.map { it.toDomain() } }

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
            appScope.launch {
                firebaseAuthService.currentUserId()?.let { uid ->
                    firestoreService.updateDailyLogConsciousOpens(uid, challengeId, date, count)
                }
            }
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
            // INSERT OR IGNORE ensures the row exists without overwriting any column that another
            // concurrent write (e.g. upsertConsciousOpens) may have already set.
            // The subsequent SQL UPDATE then atomically adds only to overlayPausedMs.
            dailyLogDao.insertOrIgnore(
                DailyLogEntity(
                    id = UUID.randomUUID().toString(),
                    challengeId = challengeId,
                    date = date,
                    totalMinutes = 0,
                    openCount = 0,
                    consciousOpens = 0,
                    overlayPausedMs = 0,
                    pointsEarned = 0,
                    limitExceeded = false,
                    moneyLostCents = 0
                )
            )
            dailyLogDao.addOverlayPausedMs(challengeId, date, additionalMs)
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

    override suspend fun getBudgetRemainingMs(challengeId: String, date: Long): Result<Long> {
        return try {
            Result.success(dailyLogDao.getBudgetRemainingMs(challengeId, date))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateBudgetStateMs(
        challengeId: String,
        date: Long,
        usedMs: Long,
        remainingMs: Long
    ): Result<Unit> {
        return try {
            val existing = dailyLogDao.getLogForDate(challengeId, date)
            if (existing != null) {
                dailyLogDao.updateBudgetStateMs(challengeId, date, usedMs, remainingMs)
            } else {
                dailyLogDao.insertOrIgnore(
                    DailyLogEntity(
                        id = UUID.randomUUID().toString(),
                        challengeId = challengeId,
                        date = date,
                        totalMinutes = 0,
                        openCount = 0,
                        pointsEarned = 0,
                        limitExceeded = false,
                        moneyLostCents = 0
                    )
                )
                dailyLogDao.updateBudgetStateMs(challengeId, date, usedMs, remainingMs)
            }
            // Fire-and-forget Firestore sync — never blocks Room or the overlay path
            appScope.launch {
                firebaseAuthService.currentUserId()?.let { uid ->
                    firestoreService.updateDailyLogBudget(uid, challengeId, date, usedMs, remainingMs)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getThresholdFlags(challengeId: String, date: Long): Result<ThresholdFlags> {
        return try {
            Result.success(
                dailyLogDao.getThresholdFlags(challengeId, date) ?: ThresholdFlags()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markThresholdNotified(
        challengeId: String,
        date: Long,
        threshold: Int
    ): Result<Unit> {
        return try {
            // Ensure a row exists so the UPDATE below has a target.
            // INSERT OR IGNORE: a no-op if the row already exists.
            val existing = dailyLogDao.getLogForDate(challengeId, date)
            if (existing == null) {
                dailyLogDao.insertOrIgnore(
                    DailyLogEntity(
                        id = UUID.randomUUID().toString(),
                        challengeId = challengeId,
                        date = date,
                        totalMinutes = 0,
                        openCount = 0,
                        pointsEarned = 0,
                        limitExceeded = false,
                        moneyLostCents = 0
                    )
                )
            }
            when (threshold) {
                50 -> dailyLogDao.markNotified50(challengeId, date)
                75 -> dailyLogDao.markNotified75(challengeId, date)
                90 -> dailyLogDao.markNotified90(challengeId, date)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getStreakForChallenge(challengeId: String, beforeDate: Long): Result<Int> {
        return try {
            val flags = dailyLogDao.getRecentLimitExceededFlags(challengeId, beforeDate)
            var streak = 0
            for (exceeded in flags) {
                if (!exceeded) streak++ else break
            }
            Result.success(streak)
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
        budgetUsedMs = budgetUsedMs,
        budgetRemainingMs = budgetRemainingMs,
        pointsEarned = pointsEarned,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents,
        notified50 = notified50,
        notified75 = notified75,
        notified90 = notified90,
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
        budgetUsedMs = budgetUsedMs,
        budgetRemainingMs = budgetRemainingMs,
        pointsEarned = pointsEarned,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents,
        notified50 = notified50,
        notified75 = notified75,
        notified90 = notified90,
    )
}
