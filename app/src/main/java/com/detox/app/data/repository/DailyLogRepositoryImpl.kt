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

    override suspend fun getTotalPointsForChallenge(challengeId: String): Result<Int> {
        return try {
            Result.success(dailyLogDao.getTotalPointsForChallenge(challengeId))
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
        pointsEarned = pointsEarned,
        limitExceeded = limitExceeded,
        moneyLostCents = moneyLostCents
    )
}
