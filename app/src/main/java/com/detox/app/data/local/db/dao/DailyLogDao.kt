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
}
