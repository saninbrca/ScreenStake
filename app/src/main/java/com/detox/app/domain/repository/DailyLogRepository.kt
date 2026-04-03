package com.detox.app.domain.repository

import com.detox.app.domain.model.DailyLog
import kotlinx.coroutines.flow.Flow

interface DailyLogRepository {
    suspend fun insertDailyLog(log: DailyLog): Result<Unit>
    suspend fun getLogForDate(challengeId: String, date: Long): Result<DailyLog?>
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLog>>
    suspend fun getTotalPointsForChallenge(challengeId: String): Result<Int>
    suspend fun getConsciousOpens(challengeId: String, date: Long): Result<Int>
    suspend fun upsertConsciousOpens(challengeId: String, date: Long, count: Int): Result<Unit>
    suspend fun getOverlayPausedMs(challengeId: String, date: Long): Result<Long>
    suspend fun addOverlayPausedMs(challengeId: String, date: Long, additionalMs: Long): Result<Unit>
}
