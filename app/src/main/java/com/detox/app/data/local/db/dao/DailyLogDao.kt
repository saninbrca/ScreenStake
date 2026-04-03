package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.DailyLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyLog(log: DailyLogEntity)

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId ORDER BY date DESC")
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getLogForDate(challengeId: String, date: Long): DailyLogEntity?

    @Query("SELECT COALESCE(SUM(pointsEarned), 0) FROM daily_logs WHERE challengeId = :challengeId")
    suspend fun getTotalPointsForChallenge(challengeId: String): Int

    @Query("SELECT COALESCE(consciousOpens, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getConsciousOpens(challengeId: String, date: Long): Int

    @Query("SELECT COALESCE(overlayPausedMs, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getOverlayPausedMs(challengeId: String, date: Long): Long

    @Query("UPDATE daily_logs SET overlayPausedMs = overlayPausedMs + :additionalMs WHERE challengeId = :challengeId AND date = :date")
    suspend fun addOverlayPausedMs(challengeId: String, date: Long, additionalMs: Long)

    @Query("SELECT COALESCE(budgetRemainingMinutes, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getBudgetRemainingMinutes(challengeId: String, date: Long): Int

    @Query("UPDATE daily_logs SET budgetUsedMinutes = :used, budgetRemainingMinutes = :remaining WHERE challengeId = :challengeId AND date = :date")
    suspend fun updateBudgetState(challengeId: String, date: Long, used: Int, remaining: Int)
}
