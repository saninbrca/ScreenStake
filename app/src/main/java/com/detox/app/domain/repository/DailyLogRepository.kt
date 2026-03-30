package com.detox.app.domain.repository

import com.detox.app.domain.model.DailyLog
import kotlinx.coroutines.flow.Flow

interface DailyLogRepository {
    suspend fun insertDailyLog(log: DailyLog): Result<Unit>
    suspend fun getLogForDate(challengeId: String, date: Long): Result<DailyLog?>
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLog>>
    suspend fun getTotalPointsForChallenge(challengeId: String): Result<Int>
}
